package com.aleksalfi.curvegen.road;

/** How an arm meets a junction: who has the right of way, which decides the line painted across the entry. */
public enum ArmPriority {
    /** Main road: no line across the entry. */
    PRIORITY,
    /** Dashed give-way line across the entry lanes. */
    GIVE_WAY,
    /** Solid stop line across the entry lanes. */
    STOP;

    public ArmPriority next() { return values()[(ordinal() + 1) % values().length]; }
}
