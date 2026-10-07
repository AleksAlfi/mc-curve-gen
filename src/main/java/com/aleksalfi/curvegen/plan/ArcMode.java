package com.aleksalfi.curvegen.plan;

/** How an arc segment is defined. */
public enum ArcMode {
    /** Tangent-continuous with the previous segment (or the chosen heading); click only the end point. */
    TANGENT,
    /** Click the end point, then a point the arc should pass through. */
    THROUGH_POINT,
    /** Click the end point; the radius and turn side come from the options. */
    RADIUS;

    public ArcMode next() { return values()[(ordinal() + 1) % values().length]; }
}
