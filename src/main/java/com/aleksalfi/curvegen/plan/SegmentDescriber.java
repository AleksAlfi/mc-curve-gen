package com.aleksalfi.curvegen.plan;

import java.util.Locale;

/** Plain-text description of a segment's options (no translation, usable on the server). */
public final class SegmentDescriber {
    private SegmentDescriber() {}

    public static String describe(SegmentSpec s) {
        StringBuilder b = new StringBuilder("Next segment: ").append(s.type().name().toLowerCase(Locale.ROOT));
        switch (s.type()) {
            case ARC -> {
                b.append(" / ").append(s.arcMode().name().toLowerCase(Locale.ROOT));
                if (s.arcMode() == ArcMode.RADIUS) b.append(" r=").append(s.radius()).append(s.turnLeft() ? " left" : " right");
            }
            case BEZIER -> b.append(" / ").append(s.bezierKind().name().toLowerCase(Locale.ROOT));
            case S_BEND -> b.append(" / ").append(s.sBendStyle().name().toLowerCase(Locale.ROOT));
            default -> {}
        }
        b.append(", heading ").append(s.heading().name().toLowerCase(Locale.ROOT));
        if (s.smoothJoin()) b.append(", smooth join");
        if (s.alignStart()) b.append(", align start");
        if (s.alignEnd()) b.append(", align end");
        return b.toString();
    }
}
