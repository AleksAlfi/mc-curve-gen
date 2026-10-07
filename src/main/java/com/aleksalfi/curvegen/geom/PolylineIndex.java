package com.aleksalfi.curvegen.geom;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Uniform-grid spatial index over the segments of a polyline for nearest-segment queries. */
public final class PolylineIndex {
    /** Result of a nearest-segment query. */
    public static final class Hit {
        public int segment = -1;
        /** Unclamped projection parameter along the segment. */
        public double u;
        /** Signed lateral offset (positive = left of the direction of travel). */
        public double lateral;
        public double distance = Double.POSITIVE_INFINITY;
        public double height;
        /** Unit direction of travel of the hit segment. */
        public double tx, tz;
        public boolean beyondStart, beyondEnd;

        public boolean found() { return segment >= 0; }
    }

    /** How far (in blocks) the road extends past the first/last path point so clicked blocks are fully covered. */
    public static final double END_EXTENSION = 0.5;

    private final Polyline line;
    private final double cell;
    private final Map<Long, int[]> grid = new HashMap<>();

    public PolylineIndex(Polyline line, double cellSize) {
        this.line = line;
        this.cell = Math.max(1.0, cellSize);
        Map<Long, List<Integer>> tmp = new HashMap<>();
        for (int i = 0; i < line.size - 1; i++) {
            int cx0 = (int) Math.floor(Math.min(line.x[i], line.x[i + 1]) / cell);
            int cx1 = (int) Math.floor(Math.max(line.x[i], line.x[i + 1]) / cell);
            int cz0 = (int) Math.floor(Math.min(line.z[i], line.z[i + 1]) / cell);
            int cz1 = (int) Math.floor(Math.max(line.z[i], line.z[i + 1]) / cell);
            for (int cx = cx0; cx <= cx1; cx++)
                for (int cz = cz0; cz <= cz1; cz++)
                    tmp.computeIfAbsent(key(cx, cz), k -> new ArrayList<>()).add(i);
        }
        tmp.forEach((k, v) -> grid.put(k, v.stream().mapToInt(Integer::intValue).toArray()));
    }

    private static long key(int cx, int cz) { return ((long) cx << 32) ^ (cz & 0xffffffffL); }

    /**
     * Finds the nearest polyline segment to (px, pz) within {@code maxDist}. The search is exact
     * as long as maxDist <= cell size.
     */
    public Hit nearest(double px, double pz, double maxDist) {
        Hit hit = new Hit();
        int cx = (int) Math.floor(px / cell), cz = (int) Math.floor(pz / cell);
        int reach = (int) Math.ceil(maxDist / cell);
        double bestD2 = maxDist * maxDist;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                int[] segs = grid.get(key(cx + dx, cz + dz));
                if (segs == null) continue;
                for (int i : segs) {
                    double ax = line.x[i], az = line.z[i];
                    double bx = line.x[i + 1], bz = line.z[i + 1];
                    double ex = bx - ax, ez = bz - az;
                    double len2 = ex * ex + ez * ez;
                    if (len2 < 1e-18) continue;
                    double u = ((px - ax) * ex + (pz - az) * ez) / len2;
                    double uc = Math.max(0, Math.min(1, u));
                    double qx = ax + ex * uc, qz = az + ez * uc;
                    double d2 = (px - qx) * (px - qx) + (pz - qz) * (pz - qz);
                    // Prefer the segment whose clamped projection is interior on ties (shared vertices).
                    if (d2 < bestD2 - 1e-12 || (Math.abs(d2 - bestD2) <= 1e-12 && hit.found() && u > 0 && u < 1)) {
                        bestD2 = d2;
                        double len = Math.sqrt(len2);
                        hit.segment = i;
                        hit.u = u;
                        hit.tx = ex / len;
                        hit.tz = ez / len;
                        // left = (tz, -tx)
                        hit.lateral = (px - qx) * hit.tz - (pz - qz) * hit.tx;
                        hit.distance = Math.sqrt(d2);
                        hit.height = line.y[i] + (line.y[i + 1] - line.y[i]) * uc;
                    }
                }
            }
        }
        if (hit.found()) {
            int i = hit.segment;
            double ex = line.x[i + 1] - line.x[i], ez = line.z[i + 1] - line.z[i];
            double len = Math.sqrt(ex * ex + ez * ez);
            hit.beyondStart = i == 0 && hit.u * len < -END_EXTENSION;
            hit.beyondEnd = i == line.size - 2 && (hit.u - 1) * len > END_EXTENSION;
            // Past an interior vertex the projection onto one segment's normal is not the real distance;
            // use the distance to the vertex so sharp corners get a round join instead of a bump.
            boolean pastInterior = (hit.u < 0 && i > 0) || (hit.u > 1 && i < line.size - 2);
            if (pastInterior) hit.lateral = Math.copySign(hit.distance, hit.lateral);
        }
        return hit;
    }
}
