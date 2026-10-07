package com.aleksalfi.curvegen.plan;

import com.aleksalfi.curvegen.geom.Vec2;

/** A clicked position: block center horizontally, road surface height vertically. */
public record PlanPoint(double x, double y, double z) {
    public Vec2 xz() { return new Vec2(x, z); }
}
