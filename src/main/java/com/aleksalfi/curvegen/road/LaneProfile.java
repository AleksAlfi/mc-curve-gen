package com.aleksalfi.curvegen.road;

import java.util.ArrayList;
import java.util.List;

/** Cross-section of a road class as rasterizer lanes, left to right in the direction of travel of the chain. */
public record LaneProfile(double[] widths, LaneKind[] kinds) {

    public static LaneProfile of(RoadClass c) {
        List<Double> w = new ArrayList<>();
        List<LaneKind> k = new ArrayList<>();
        if (c.hasSidewalk()) { w.add((double) c.sidewalkWidth()); k.add(LaneKind.SIDEWALK); w.add(1.0); k.add(LaneKind.CURB); }
        if (c.edgeLines()) { w.add(1.0); k.add(LaneKind.EDGE); }
        for (int i = 0; i < c.lanesPerDirection(); i++) {
            w.add((double) c.laneWidth()); k.add(LaneKind.LANE);
            if (i < c.lanesPerDirection() - 1) { w.add(1.0); k.add(LaneKind.LANE_LINE); }
        }
        w.add(1.0); k.add(LaneKind.CENTRE);
        for (int i = 0; i < c.lanesPerDirection(); i++) {
            if (i > 0) { w.add(1.0); k.add(LaneKind.LANE_LINE); }
            w.add((double) c.laneWidth()); k.add(LaneKind.LANE);
        }
        if (c.edgeLines()) { w.add(1.0); k.add(LaneKind.EDGE); }
        if (c.hasSidewalk()) { w.add(1.0); k.add(LaneKind.CURB); w.add((double) c.sidewalkWidth()); k.add(LaneKind.SIDEWALK); }
        double[] widths = new double[w.size()];
        for (int i = 0; i < widths.length; i++) widths[i] = w.get(i);
        return new LaneProfile(widths, k.toArray(new LaneKind[0]));
    }

    /** The lane at a signed lateral offset from the road centre (negative = left), or null outside the road. */
    public LaneKind kindAt(double lateral) {
        double x = lateral + totalWidth() / 2;
        if (x < 0) return null;
        for (int i = 0; i < widths.length; i++) {
            if (x < widths[i]) return kinds[i];
            x -= widths[i];
        }
        return null;
    }

    public double totalWidth() {
        double t = 0;
        for (double v : widths) t += v;
        return t;
    }
}
