package com.aleksalfi.curvegen.client.render;

import com.aleksalfi.curvegen.build.BlockPlan;
import com.aleksalfi.curvegen.geom.Polyline;

import java.util.List;

/** Everything the preview renders: blocks, centre lines, point markers and extra straight lines. */
public record Compiled(BlockPlan blocks, List<Polyline> lines, List<Marker> markers, List<Segment3> segments, List<String> warnings) {
    /** A small wire box at a world position. */
    public record Marker(double x, double y, double z, float r, float g, float b, float size) {}

    /** A straight line between two world positions. */
    public record Segment3(double x0, double y0, double z0, double x1, double y1, double z1, float r, float g, float b) {}
}
