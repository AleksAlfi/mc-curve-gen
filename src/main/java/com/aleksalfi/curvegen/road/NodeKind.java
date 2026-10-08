package com.aleksalfi.curvegen.road;

public enum NodeKind {
    /** Plain pass-through with 2 links, dead end with 1, junction with 3 or more. */
    AUTO,
    /** Roundabout regardless of the number of links. */
    ROUNDABOUT;

    public NodeKind next() { return values()[(ordinal() + 1) % values().length]; }
}
