package com.aleksalfi.curvegen.road;

/** How a road passes through a plain node that joins exactly two links. */
public enum CornerStyle {
    /** Straight links joined by a circular arc of the node's fillet radius. */
    FILLET,
    /** The road curves smoothly through the node (spline-like tangent). */
    SMOOTH;

    public CornerStyle next() { return values()[(ordinal() + 1) % values().length]; }
}
