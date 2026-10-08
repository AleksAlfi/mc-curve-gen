package com.aleksalfi.curvegen.road;

/** Per-arm settings of a node: the link's end at this node. */
public record ArmSettings(ArmPriority priority, boolean zebra) {
    public static final ArmSettings DEFAULT = new ArmSettings(ArmPriority.GIVE_WAY, false);

    public ArmSettings withPriority(ArmPriority p) { return new ArmSettings(p, zebra); }
    public ArmSettings withZebra(boolean z) { return new ArmSettings(priority, z); }
}
