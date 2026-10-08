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
    /** Per-column result. {@code along} is the distance along the path of the column centre's projection. */
    public record Column(int x, int z, double coverage, int lane, double height, double outwardX, double outwardZ,
                         double[] laneCoverage, double along, double lateral) {}

    /** Thrown when the path's bounding box has more columns than the caller allows. */
    public static final class PlanTooLargeException extends RuntimeException {
        public final long columns, limit;
        public PlanTooLargeException(long columns, long limit) {
            super("Plan bounding box has " + columns + " columns, limit " + limit);
            this.columns = columns;
            this.limit = limit;
        }
    }

    /** Lane widths that may change along the path (tapers, merge lanes). Lane count and order are fixed. */
    public interface WidthProfile {
        int lanes();
        /** Widths of every lane at this along-position; zero-width lanes are simply absent there. */
        double[] widthsAt(double along);
        /** Largest half width anywhere on the path (either side). */
        double maxHalf();
        /**
         * Index of the lane the path's centre line runs through, or -1 when the cross-section is simply centred
         * on the path. With a centre lane the layout is anchored on it, so lanes added on one side (an
         * acceleration lane) do not shift the rest of the road.
         */
        default int centreLane() { return -1; }
    }

    /** The same widths everywhere, centred on the path. */
    public record FixedWidths(double[] widths) implements WidthProfile {
        public int lanes() { return widths.length; }
        public double[] widthsAt(double along) { return widths; }
        public double maxHalf() { return half(widths); }
    }

    public static double half(double[] widths) {
        double t = 0;
        for (double w : widths) t += w;
        return t / 2;
    }

    /** Extent of the cross-section to the left of the centre line (positive lateral). */
    public static double leftHalf(double[] widths, int centreLane) {
        if (centreLane < 0) return half(widths);
        double t = 0;
        for (int i = 0; i < centreLane; i++) t += widths[i];
        return t + widths[centreLane] / 2;
    }

    /** Extent of the cross-section to the right of the centre line (negative lateral). */
    public static double rightHalf(double[] widths, int centreLane) {
        if (centreLane < 0) return half(widths);
        double t = 0;
        for (int i = centreLane + 1; i < widths.length; i++) t += widths[i];
        return t + widths[centreLane] / 2;
    }

    public static List<Column> rasterize(Polyline line, double[] laneWidths, int supersample) {
        return rasterize(line, new FixedWidths(laneWidths), supersample, Long.MAX_VALUE);
    }

    public static List<Column> rasterize(Polyline line, double[] laneWidths, int supersample, long maxColumns) {
        return rasterize(line, new FixedWidths(laneWidths), supersample, maxColumns);
    }

    public static List<Column> rasterize(Polyline line, WidthProfile profile, int supersample, long maxColumns) {
        List<Column> out = new ArrayList<>();
        int lanes = profile.lanes();
        if (line.size < 2 || lanes == 0) return out;
        double half = profile.maxHalf();
        if (half <= 0) return out;
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
                double[] laneWidths = profile.widthsAt(center.along);
                int cl = profile.centreLane();
                double lh = leftHalf(laneWidths, cl), rh = rightHalf(laneWidths, cl);
                if (lh + rh <= 0) continue;

                // Fast path: clearly inside one lane, away from the ends and from corner joins -> no supersampling.
                if (!center.pastInterior && center.lateral + inset < lh && -center.lateral + inset < rh
                        && center.along > PolylineIndex.END_EXTENSION + inset && center.toEnd > PolylineIndex.END_EXTENSION + inset
                        && laneBoundaryDistance(center.lateral, laneWidths, lh) > inset) {
                    int lane = laneFor(center.lateral, laneWidths, lh);
                    if (lane >= 0) {
                        double[] laneCov = new double[lanes];
                        laneCov[lane] = 1;
                        double sign = center.lateral >= 0 ? 1 : -1;
                        out.add(new Column(bx, bz, 1, lane, center.height, sign * center.tz, sign * -center.tx, laneCov, center.along, center.lateral));
                        continue;
                    }
                }

                int inside = 0;
                double[] laneCount = new double[lanes];
                double heightSum = 0;
                for (int i = 0; i < n; i++) {
                    for (int j = 0; j < n; j++) {
                        double sx = bx + (i + 0.5) * inv, sz = bz + (j + 0.5) * inv;
                        PolylineIndex.Hit h = index.nearest(sx, sz, margin);
                        if (!h.found() || h.beyondStart || h.beyondEnd) continue;
                        double d = h.lateral;
                        double[] w = profile.widthsAt(h.along);
                        double wl = leftHalf(w, cl), wr = rightHalf(w, cl);
                        if (d > wl || d < -wr) continue;
                        int lane = laneFor(d, w, wl);
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
                out.add(new Column(bx, bz, inside / (double) total, bestLane, heightSum / inside, ox, oz, laneCov, center.along, center.lateral));
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
