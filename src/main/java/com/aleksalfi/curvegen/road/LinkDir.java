package com.aleksalfi.curvegen.road;

/** Travel direction of a link: both ways, from a to b, or from b to a. */
public enum LinkDir {
    TWO_WAY, FORWARD, REVERSE;

    public LinkDir next() { return values()[(ordinal() + 1) % values().length]; }
}
