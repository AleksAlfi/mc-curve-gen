package com.aleksalfi.curvegen.geom;

import java.util.List;

/**
 * One logical segment of a path: one or more curves travelled in order. {@code ys} holds the surface
 * height at every curve junction (length = curves + 1). When {@code uniform} is set the height is
 * interpolated from the first to the last value over the whole segment length; otherwise each curve
 * interpolates between its own two junction heights (used by splines through many points).
 */
public record PathSegment(List<Curve2> curves, double[] ys, boolean uniform, boolean draft) {

    public static PathSegment uniform(List<Curve2> curves, double y0, double y1, boolean draft) {
        double[] ys = new double[curves.size() + 1];
        double total = 0;
        for (Curve2 c : curves) total += c.length();
        double run = 0;
        for (int i = 0; i < curves.size(); i++) {
            ys[i] = total < 1e-9 ? y0 : y0 + (y1 - y0) * (run / total);
            run += curves.get(i).length();
        }
        ys[curves.size()] = y1;
        return new PathSegment(curves, ys, true, draft);
    }

    public double y0() { return ys[0]; }
    public double y1() { return ys[ys.length - 1]; }

    public double length() {
        double l = 0;
        for (Curve2 c : curves) l += c.length();
        return l;
    }

    public Vec2 end() { return curves.get(curves.size() - 1).end(); }
    public Vec2 endTangent() { return curves.get(curves.size() - 1).endTangent(); }
    public Vec2 start() { return curves.get(0).start(); }
    public Vec2 startTangent() { return curves.get(0).startTangent(); }
}
