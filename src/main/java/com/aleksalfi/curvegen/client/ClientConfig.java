package com.aleksalfi.curvegen.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Client-only preferences (config/curvegen-client.toml). */
public final class ClientConfig {
    private ClientConfig() {}

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue ROAD_AXIS_SNAP;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        ROAD_AXIS_SNAP = b.comment("Road Planner: snap a new node onto the north/south or east/west axis of the node it connects to when it is nearly aligned.")
                .define("roadAxisSnap", true);
        SPEC = b.build();
    }

    public static boolean axisSnap() {
        try { return ROAD_AXIS_SNAP.get(); } catch (IllegalStateException notLoaded) { return true; }
    }

    public static void setAxisSnap(boolean value) {
        try { ROAD_AXIS_SNAP.set(value); SPEC.save(); } catch (IllegalStateException ignored) {}
    }
}
