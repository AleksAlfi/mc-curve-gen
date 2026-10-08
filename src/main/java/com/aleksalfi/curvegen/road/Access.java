package com.aleksalfi.curvegen.road;

/** What a player may do with a network. */
public enum Access {
    NONE, VIEW, EDIT;

    public boolean atLeast(Access other) { return ordinal() >= other.ordinal(); }

    public Access next() { return values()[(ordinal() + 1) % values().length]; }
}
