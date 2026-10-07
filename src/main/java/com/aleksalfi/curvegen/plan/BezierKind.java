package com.aleksalfi.curvegen.plan;

public enum BezierKind {
    QUADRATIC, CUBIC;

    public BezierKind next() { return values()[(ordinal() + 1) % values().length]; }
}
