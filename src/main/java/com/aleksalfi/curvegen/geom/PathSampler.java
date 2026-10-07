package com.aleksalfi.curvegen.geom;

import java.util.ArrayList;
import java.util.List;

public final class PathSampler {
    private PathSampler() {}

    public enum Elevation { FLAT, LINEAR, SMOOTH }

    /** Samples the segments into one polyline with roughly {@code step} spacing. */
    public static Polyline sample(List<PathSegment> segments, double step, Elevation elevation) {
        List<Double> xs = new ArrayList<>(), zs = new ArrayList<>(), ys = new ArrayList<>();
        double flatY = segments.isEmpty() ? 0 : segments.get(0).y0();
        for (PathSegment seg : segments) {
            double total = seg.length();
            double travelled = 0;
            for (int k = 0; k < seg.curves().size(); k++) {
                Curve2 c = seg.curves().get(k);
                double len = c.length();
                // Béziers are not arc-length parameterised, so sample densely and measure distance along the way.
                int n = Math.max(2, (int) Math.ceil(2 * len / step) + 1);
                double along = 0;
                Vec2 prev = c.point(0);
                for (int i = 0; i < n; i++) {
                    double t = i / (double) (n - 1);
                    Vec2 p = c.point(t);
                    along += p.distanceTo(prev);
                    prev = p;
                    double f, ya, yb;
                    boolean smooth = elevation == Elevation.SMOOTH;
                    if (seg.uniform()) {
                        f = total < 1e-9 ? 0 : Math.min(1, (travelled + along) / total);
                        ya = seg.y0();
                        yb = seg.y1();
                    } else {
                        // Per-span heights (splines): smoothing each span would make terraces, so stay linear.
                        f = len < 1e-9 ? 0 : Math.min(1, along / len);
                        ya = seg.ys()[k];
                        yb = seg.ys()[k + 1];
                        smooth = false;
                    }
                    double y = switch (elevation) {
                        case FLAT -> flatY;
                        default -> smooth ? ya + (yb - ya) * (f * f * (3 - 2 * f)) : ya + (yb - ya) * f;
                    };
                    if (!xs.isEmpty()) {
                        double lx = xs.get(xs.size() - 1), lz = zs.get(zs.size() - 1);
                        if (Math.abs(lx - p.x()) < 1e-6 && Math.abs(lz - p.z()) < 1e-6) {
                            ys.set(ys.size() - 1, y);
                            continue;
                        }
                    }
                    xs.add(p.x());
                    zs.add(p.z());
                    ys.add(y);
                }
                travelled += len;
            }
        }
        double[] x = new double[xs.size()], z = new double[xs.size()], y = new double[xs.size()];
        for (int i = 0; i < x.length; i++) { x[i] = xs.get(i); z[i] = zs.get(i); y[i] = ys.get(i); }
        return new Polyline(x, z, y);
    }
}
