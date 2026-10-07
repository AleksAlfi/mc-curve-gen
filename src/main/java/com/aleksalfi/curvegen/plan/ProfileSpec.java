package com.aleksalfi.curvegen.plan;

import java.util.ArrayList;
import java.util.List;

public record ProfileSpec(List<LaneSpec> lanes, int thickness, String baseBlock, double yOffset,
                          boolean edgeSmoothing, boolean slopeSmoothing, ElevationMode elevationMode, int quality) {

    public ProfileSpec {
        thickness = Math.max(1, Math.min(32, thickness));
        quality = Math.max(1, Math.min(3, quality));
    }

    public static ProfileSpec defaults() {
        return new ProfileSpec(List.of(
                new LaneSpec(1, "minecraft:stone_bricks", ""),
                new LaneSpec(3, "minecraft:gray_concrete", ""),
                new LaneSpec(1, "minecraft:white_concrete", ""),
                new LaneSpec(3, "minecraft:gray_concrete", ""),
                new LaneSpec(1, "minecraft:stone_bricks", "")),
                1, "", 0, true, true, ElevationMode.LINEAR, 1);
    }

    public double totalWidth() {
        double w = 0;
        for (LaneSpec l : lanes) w += Math.max(0, l.width());
        return w;
    }

    public double[] laneWidths() {
        double[] w = new double[lanes.size()];
        for (int i = 0; i < w.length; i++) w[i] = Math.max(0, lanes.get(i).width());
        return w;
    }

    public ProfileSpec withLanes(List<LaneSpec> l) { return new ProfileSpec(List.copyOf(l), thickness, baseBlock, yOffset, edgeSmoothing, slopeSmoothing, elevationMode, quality); }
    public ProfileSpec withThickness(int t) { return new ProfileSpec(lanes, Math.max(1, Math.min(32, t)), baseBlock, yOffset, edgeSmoothing, slopeSmoothing, elevationMode, quality); }
    public ProfileSpec withBaseBlock(String b) { return new ProfileSpec(lanes, thickness, b, yOffset, edgeSmoothing, slopeSmoothing, elevationMode, quality); }
    public ProfileSpec withYOffset(double y) { return new ProfileSpec(lanes, thickness, baseBlock, y, edgeSmoothing, slopeSmoothing, elevationMode, quality); }
    public ProfileSpec withEdgeSmoothing(boolean e) { return new ProfileSpec(lanes, thickness, baseBlock, yOffset, e, slopeSmoothing, elevationMode, quality); }
    public ProfileSpec withSlopeSmoothing(boolean s) { return new ProfileSpec(lanes, thickness, baseBlock, yOffset, edgeSmoothing, s, elevationMode, quality); }
    public ProfileSpec withElevationMode(ElevationMode m) { return new ProfileSpec(lanes, thickness, baseBlock, yOffset, edgeSmoothing, slopeSmoothing, m, quality); }
    public ProfileSpec withQuality(int q) { return new ProfileSpec(lanes, thickness, baseBlock, yOffset, edgeSmoothing, slopeSmoothing, elevationMode, Math.max(1, Math.min(3, q))); }

    public ProfileSpec withLane(int i, LaneSpec lane) {
        List<LaneSpec> l = new ArrayList<>(lanes);
        l.set(i, lane);
        return withLanes(l);
    }

    /** Samples per block edge. Powers of two keep sample points off the 1/8-block boundaries copycat layers use. */
    public int supersample() {
        return switch (quality) { case 1 -> 4; case 2 -> 8; default -> 16; };
    }
}
