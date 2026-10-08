package com.aleksalfi.curvegen.road;

public enum NodeKind {
    /** Plain pass-through with 2 links, dead end with 1, junction with 3 or more; a ramp merge when the geometry says so. */
    AUTO,
    /** Roundabout regardless of the number of links. */
    ROUNDABOUT,
    /** Always a plain junction, never a ramp merge. */
    JUNCTION,
    /** Ramp merge: the lower-class arm is an entry (acceleration lane after it). */
    ENTRY,
    /** Ramp merge: the lower-class arm is an exit (deceleration lane before it). */
    EXIT,
    /** A two-way road splitting into two one-way roads (also detected automatically at shallow angles). */
    FORK;

    public NodeKind next() { return values()[(ordinal() + 1) % values().length]; }
}
