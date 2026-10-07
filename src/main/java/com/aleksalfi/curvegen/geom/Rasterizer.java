package com.aleksalfi.curvegen.geom;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts a sampled path plus lane widths into per-column coverage information. Everything here is
 * independent of Minecraft so it can be unit tested.
 */
public final class Rasterizer {
    private Rasterizer() {}

    /** Per-column result. Coordinates are block coordinates (floor of world x/z). */
    public record Column(int x, int z, double coverage, int lane, double height, double outwardX, double outwardZ,
                         double[] laneCoverage) {}

    /** Thrown when the path's bounding box has more columns than the caller allows. */
    public static final class PlanTooLargeException extends RuntimeException {
        public final long columns, limit;
        public PlanTooLargeException(long columns, long limit) {
            super("Plan bounding box has " + columns + " columns, limit " + limit);
            this.columns = columns;
            this.limit = limit;
        }
    }

    public static List<Column> rasterize(Polyline line, double[] laneWidths, int supersample) {
        return rasterize(line, laneWidths, supersample, Long.MAX_VALUE);
    }

    public static List<Column> rasterize(Polyline line, double[] laneWidths, int supersample, long maxColumns) {
        List<Column> out = new ArrayList<>();
        if (line.size < 2 || laneWidths.length == 0) return out;
        double width = 0;
        for (double w : laneWidths) width += w;
        if (width <= 0) return out;
        double half = width / 2;
        double margin = half + 1.0;
        PolylineIndex index = new PolylineIndex(line, Math.max(2.0, margin));

        // Candidate columns: squares around every polyline vertex (vertices are at most ~0.5 apart), so a long
        // diagonal road does not cost its whole bounding box.
        int reach = (int) Math.ceil(margin) + 1;
        LongSet candidates = new LongSet();
        for (int i = 0; i < line.size; i++) {
            int vx = (int) Math.floor(line.x[i]), vz = (int) Math.floor(line.z[i]);
            for (int dx = -reach; dx <= reach; dx++)
                for (int dz = -reach; dz <= reach; dz++)
                    candidates.add(((long) (vx + dx) << 32) ^ ((vz + dz) & 0xffffffffL));
            if (candidates.size() > maxColumns) throw new PlanTooLargeException(candidates.size(), maxColumns);
        }
        long[] keys = candidates.toSortedArray();
        int n = Math.max(1, supersample);
        double inv = 1.0 / n;
        // Columns whose centre is further than this from the path cannot be touched (end caps included).
        double centerReach = Math.sqrt(half * half + PolylineIndex.END_EXTENSION * PolylineIndex.END_EXTENSION) + Math.sqrt(0.5) + 1e-6;
        // Half the diagonal of a column: a column centre at least this far inside a region is fully inside it.
        double inset = Math.sqrt(0.5) + 1e-6;

        for (long key : keys) {
            int bx = (int) (key >> 32), bz = (int) key;
            {
                double cx = bx + 0.5, cz = bz + 0.5;
                PolylineIndex.Hit center = index.nearest(cx, cz, margin);
                if (!center.found() || center.distance > centerReach) continue;

                // Fast path: clearly inside one lane, away from the ends and from corner joins -> no supersampling.
                if (!center.pastInterior && Math.abs(center.lateral) + inset < half
                        && center.along > PolylineIndex.END_EXTENSION + inset && center.toEnd > PolylineIndex.END_EXTENSION + inset
                        && laneBoundaryDistance(center.lateral, laneWidths, half) > inset) {
                    int lane = laneFor(center.lateral, laneWidths, half);
                    if (lane >= 0) {
                        double[] laneCov = new double[laneWidths.length];
                        laneCov[lane] = 1;
                        double sign = center.lateral >= 0 ? 1 : -1;
                        out.add(new Column(bx, bz, 1, lane, center.height, sign * center.tz, sign * -center.tx, laneCov));
                        continue;
                    }
                }

                int inside = 0;
                double[] laneCount = new double[laneWidths.length];
                double heightSum = 0;
                for (int i = 0; i < n; i++) {
                    for (int j = 0; j < n; j++) {
                        double sx = bx + (i + 0.5) * inv, sz = bz + (j + 0.5) * inv;
                        PolylineIndex.Hit h = index.nearest(sx, sz, margin);
                        if (!h.found() || h.beyondStart || h.beyondEnd) continue;
                        double d = h.lateral;
                        if (d > half || d < -half) continue;
                        int lane = laneFor(d, laneWidths, half);
                        if (lane < 0) continue;
                        inside++;
                        laneCount[lane]++;
                        heightSum += h.height;
                    }
                }
                if (inside == 0) continue;
                int total = n * n;
                int bestLane = 0;
                for (int l = 1; l < laneCount.length; l++) if (laneCount[l] > laneCount[bestLane]) bestLane = l;
                double[] laneCov = new double[laneCount.length];
                for (int l = 0; l < laneCount.length; l++) laneCov[l] = laneCount[l] / total;
                double sign = center.lateral >= 0 ? 1 : -1;
                // outward = away from the centerline = sign(lateral) * left
                double ox = sign * center.tz, oz = sign * -center.tx;
                out.add(new Column(bx, bz, inside / (double) total, bestLane, heightSum / inside, ox, oz, laneCov));
            }
        }
        return out;
    }

    /** Distance from a lateral offset to the nearest lane boundary (including the road edges). */
    static double laneBoundaryDistance(double d, double[] widths, double half) {
        double best = Math.min(Math.abs(half - d), Math.abs(-half - d));
        double upper = half;
        for (double w : widths) {
            upper -= w;
            best = Math.min(best, Math.abs(upper - d));
        }
        return best;
    }

    static long boundingColumns(Polyline line, double margin) {
        long w = (long) Math.floor(line.maxX() + margin) - (long) Math.floor(line.minX() - margin) + 1;
        long h = (long) Math.floor(line.maxZ() + margin) - (long) Math.floor(line.minZ() - margin) + 1;
        return w * h;
    }

    /** Lane index for a lateral offset; lane 0 is the leftmost lane. */
    static int laneFor(double d, double[] widths, double half) {
        double upper = half;
        for (int i = 0; i < widths.length; i++) {
            double lower = upper - widths[i];
            if (d <= upper + 1e-9 && d > lower - (i == widths.length - 1 ? 1e-9 : 0)) return i;
            upper = lower;
        }
        return -1;
    }
}
