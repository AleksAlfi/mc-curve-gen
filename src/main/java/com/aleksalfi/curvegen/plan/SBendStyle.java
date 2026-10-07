package com.aleksalfi.curvegen.plan;

public enum SBendStyle {
    /** One cubic Bézier with parallel end tangents. */
    SMOOTH,
    /** Two mirrored circular arcs of equal radius (a classic reverse curve). */
    TWO_ARCS;

    public SBendStyle next() { return values()[(ordinal() + 1) % values().length]; }
}
