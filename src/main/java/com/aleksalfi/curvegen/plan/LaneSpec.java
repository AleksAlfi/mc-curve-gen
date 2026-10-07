package com.aleksalfi.curvegen.plan;

/**
 * One lane of the road profile. {@code block} is a block state string ("minecraft:gray_concrete" or
 * "mod:block[prop=value]"). {@code material} is the copycat material used for smoothing layers; empty
 * means "same as block".
 */
public record LaneSpec(double width, String block, String material) {
    public LaneSpec withWidth(double w) { return new LaneSpec(w, block, material); }
    public LaneSpec withBlock(String b) { return new LaneSpec(width, b, material); }
    public LaneSpec withMaterial(String m) { return new LaneSpec(width, block, m); }
}
