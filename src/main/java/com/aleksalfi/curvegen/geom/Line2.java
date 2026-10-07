package com.aleksalfi.curvegen.geom;

public record Line2(Vec2 a, Vec2 b) implements Curve2 {
    @Override public Vec2 point(double t) { return a.lerp(b, t); }
    @Override public Vec2 tangent(double t) { return b.sub(a).normalize(); }
    @Override public double length() { return a.distanceTo(b); }
}
