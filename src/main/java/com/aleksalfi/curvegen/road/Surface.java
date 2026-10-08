package com.aleksalfi.curvegen.road;

/** What the top of a road column is. */
public enum Surface {
    NONE, ASPHALT, LINE, CURB, SIDEWALK, ISLAND;

    public boolean raised() { return this == CURB || this == SIDEWALK || this == ISLAND; }
}
