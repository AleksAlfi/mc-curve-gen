package com.aleksalfi.curvegen.plan;

public enum ElevationMode {
    FLAT, LINEAR, SMOOTH;

    public ElevationMode next() { return values()[(ordinal() + 1) % values().length]; }
}
