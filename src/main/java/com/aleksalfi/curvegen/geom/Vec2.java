package com.aleksalfi.curvegen.geom;

/** Immutable 2D vector in the horizontal plane (x = east, z = south). */
public record Vec2(double x, double z) {
    public static final Vec2 ZERO = new Vec2(0, 0);

    public Vec2 add(Vec2 o) { return new Vec2(x + o.x, z + o.z); }
    public Vec2 sub(Vec2 o) { return new Vec2(x - o.x, z - o.z); }
    public Vec2 scale(double s) { return new Vec2(x * s, z * s); }
    public double dot(Vec2 o) { return x * o.x + z * o.z; }
    public double length() { return Math.sqrt(x * x + z * z); }
    public double lengthSq() { return x * x + z * z; }
    public double distanceTo(Vec2 o) { return sub(o).length(); }

    public Vec2 normalize() {
        double l = length();
        return l < 1e-12 ? new Vec2(1, 0) : new Vec2(x / l, z / l);
    }

    /** Unit vector pointing to the left of this direction when viewed from above (y up). */
    public Vec2 left() { return new Vec2(z, -x); }

    public double angle() { return Math.atan2(z, x); }

    public static Vec2 fromAngle(double a) { return new Vec2(Math.cos(a), Math.sin(a)); }

    public Vec2 lerp(Vec2 o, double t) { return new Vec2(x + (o.x - x) * t, z + (o.z - z) * t); }
}
