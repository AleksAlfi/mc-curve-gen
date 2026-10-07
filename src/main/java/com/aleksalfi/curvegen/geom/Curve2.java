package com.aleksalfi.curvegen.geom;

/** A parametric planar curve on t in [0,1]. */
public interface Curve2 {
    Vec2 point(double t);

    /** Unit tangent (direction of travel) at t. */
    Vec2 tangent(double t);

    /** Approximate arc length. */
    double length();

    default Vec2 start() { return point(0); }
    default Vec2 end() { return point(1); }
    default Vec2 startTangent() { return tangent(0); }
    default Vec2 endTangent() { return tangent(1); }
}
