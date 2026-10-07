package com.aleksalfi.curvegen.plan;

import com.aleksalfi.curvegen.geom.Vec2;

/** Direction of travel used where a segment needs a start tangent that no previous segment supplies. */
public enum Heading {
    AUTO, PREVIOUS, NORTH, EAST, SOUTH, WEST;

    public Heading next() { return values()[(ordinal() + 1) % values().length]; }

    public Vec2 vector() {
        return switch (this) {
            case NORTH -> new Vec2(0, -1);
            case EAST -> new Vec2(1, 0);
            case SOUTH -> new Vec2(0, 1);
            case WEST -> new Vec2(-1, 0);
            default -> null;
        };
    }

    /** Snaps a direction to the nearest cardinal axis. */
    public static Vec2 snapCardinal(Vec2 d) {
        if (Math.abs(d.x()) >= Math.abs(d.z())) return new Vec2(d.x() >= 0 ? 1 : -1, 0);
        return new Vec2(0, d.z() >= 0 ? 1 : -1);
    }
}
