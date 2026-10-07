package com.aleksalfi.curvegen.plan;

public enum SegmentType {
    STRAIGHT, ARC, BEZIER, S_BEND, SPLINE;

    public SegmentType next() { return values()[(ordinal() + 1) % values().length]; }
}
