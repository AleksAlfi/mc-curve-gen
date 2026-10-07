package com.aleksalfi.curvegen.geom;

public record CubicBezier2(Vec2 p0, Vec2 p1, Vec2 p2, Vec2 p3) implements Curve2 {

    /** Exact cubic representation of the quadratic Bézier p0, c, p2. */
    public static CubicBezier2 fromQuadratic(Vec2 p0, Vec2 c, Vec2 p2) {
        return new CubicBezier2(p0, p0.add(c.sub(p0).scale(2.0 / 3)), p2.add(c.sub(p2).scale(2.0 / 3)), p2);
    }

    @Override
    public Vec2 point(double t) {
        double u = 1 - t;
        double b0 = u * u * u, b1 = 3 * u * u * t, b2 = 3 * u * t * t, b3 = t * t * t;
        return new Vec2(
                b0 * p0.x() + b1 * p1.x() + b2 * p2.x() + b3 * p3.x(),
                b0 * p0.z() + b1 * p1.z() + b2 * p2.z() + b3 * p3.z());
    }

    @Override
    public Vec2 tangent(double t) {
        double u = 1 - t;
        Vec2 d = p1.sub(p0).scale(3 * u * u).add(p2.sub(p1).scale(6 * u * t)).add(p3.sub(p2).scale(3 * t * t));
        if (d.lengthSq() < 1e-12) {
            // Degenerate (coincident control points): fall back to a finite difference.
            double t2 = Math.min(1, t + 1e-3), t1 = Math.max(0, t - 1e-3);
            d = point(t2).sub(point(t1));
            if (d.lengthSq() < 1e-12) d = p3.sub(p0);
        }
        return d.normalize();
    }

    @Override
    public double length() {
        double len = 0;
        Vec2 prev = p0;
        for (int i = 1; i <= 32; i++) {
            Vec2 p = point(i / 32.0);
            len += prev.distanceTo(p);
            prev = p;
        }
        return len;
    }
}
