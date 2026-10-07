package com.aleksalfi.curvegen.geom;

/**
 * Circular arc. Angles are measured with {@link Vec2#angle()} (atan2(z, x)); a positive sweep
 * rotates from +x toward +z, which is a right turn when viewed from above in Minecraft coordinates.
 */
public record Arc2(Vec2 center, double radius, double startAngle, double sweep) implements Curve2 {
    private static final double TWO_PI = Math.PI * 2;

    @Override
    public Vec2 point(double t) {
        return center.add(Vec2.fromAngle(startAngle + sweep * t).scale(radius));
    }

    @Override
    public Vec2 tangent(double t) {
        double a = startAngle + sweep * t;
        Vec2 d = new Vec2(-Math.sin(a), Math.cos(a));
        return sweep >= 0 ? d : d.scale(-1);
    }

    @Override
    public double length() { return radius * Math.abs(sweep); }

    /** Normalizes an angle difference to (−π, π]. */
    public static double wrap(double a) {
        a = a % TWO_PI;
        if (a <= -Math.PI) a += TWO_PI;
        if (a > Math.PI) a -= TWO_PI;
        return a;
    }

    /** Positive (counter-rotation-free) angular distance from a to b in [0, 2π). */
    public static double positiveDelta(double a, double b) {
        double d = (b - a) % TWO_PI;
        if (d < 0) d += TWO_PI;
        return d;
    }

    /**
     * Arc from p to q whose tangent at p equals the unit vector t. Returns null when p, t, q are
     * collinear (the caller should use a straight line).
     */
    public static Arc2 fromTangent(Vec2 p, Vec2 t, Vec2 q) {
        Vec2 n = t.left();
        Vec2 d = q.sub(p);
        double denom = 2 * d.dot(n);
        if (Math.abs(denom) < 1e-9 || d.lengthSq() < 1e-12) return null;
        double rs = d.lengthSq() / denom; // signed: positive = center on the left = left turn
        Vec2 c = p.add(n.scale(rs));
        double r = Math.abs(rs);
        double a0 = p.sub(c).angle();
        double a1 = q.sub(c).angle();
        return withStartTangent(c, r, a0, a1, t);
    }

    /** Arc through three points p (start), m (middle, on the arc) and q (end). Null if collinear. */
    public static Arc2 throughPoints(Vec2 p, Vec2 m, Vec2 q) {
        double ax = p.x(), az = p.z(), bx = m.x(), bz = m.z(), cx = q.x(), cz = q.z();
        double d = 2 * (ax * (bz - cz) + bx * (cz - az) + cx * (az - bz));
        if (Math.abs(d) < 1e-9) return null;
        double a2 = ax * ax + az * az, b2 = bx * bx + bz * bz, c2 = cx * cx + cz * cz;
        double ux = (a2 * (bz - cz) + b2 * (cz - az) + c2 * (az - bz)) / d;
        double uz = (a2 * (cx - bx) + b2 * (ax - cx) + c2 * (bx - ax)) / d;
        Vec2 c = new Vec2(ux, uz);
        double r = p.distanceTo(c);
        double a0 = p.sub(c).angle();
        double a1 = q.sub(c).angle();
        double am = m.sub(c).angle();
        double pos = positiveDelta(a0, a1);
        double posM = positiveDelta(a0, am);
        double sweep = posM < pos ? pos : pos - Math.PI * 2;
        return new Arc2(c, r, a0, sweep);
    }

    /**
     * Minor arc from p to q with the given radius. {@code left} selects a left turn (center on the
     * left of p→q). The radius is clamped to at least half the chord.
     */
    public static Arc2 fromRadius(Vec2 p, Vec2 q, double radius, boolean left) {
        Vec2 d = q.sub(p);
        double chord = d.length();
        if (chord < 1e-9) return null;
        double r = Math.max(radius, chord / 2 + 1e-9);
        double h = Math.sqrt(Math.max(0, r * r - chord * chord / 4));
        Vec2 mid = p.add(d.scale(0.5));
        Vec2 n = d.normalize().left();
        Vec2 c = mid.add(n.scale(left ? h : -h));
        double a0 = p.sub(c).angle();
        double a1 = q.sub(c).angle();
        double sweep = wrap(a1 - a0); // minor arc
        return new Arc2(c, r, a0, sweep);
    }

    private static Arc2 withStartTangent(Vec2 c, double r, double a0, double a1, Vec2 t) {
        Vec2 dPos = new Vec2(-Math.sin(a0), Math.cos(a0));
        double pos = positiveDelta(a0, a1);
        if (pos < 1e-9) pos = Math.PI * 2;
        double sweep = dPos.dot(t) >= 0 ? pos : pos - Math.PI * 2;
        return new Arc2(c, r, a0, sweep);
    }
}
