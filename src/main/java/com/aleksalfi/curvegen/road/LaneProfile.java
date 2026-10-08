package com.aleksalfi.curvegen.road;

/**
 * Cross-section of a road as rasterizer lanes, left to right in the direction of travel of the chain.
 * Every road uses the same canonical layout ({@link #KINDS}); features a class lacks simply have zero
 * width, which is what lets widths blend from one class into another along a road.
 */
public record LaneProfile(double[] widths, LaneKind[] kinds) {

    /** Lane slots per direction (the class limit); the slots nearest the centre are used first. */
    public static final int MAX_LANES = 4;

    /** The canonical layout: sidewalk, curb, shoulder, edge line, auxiliary lane, lanes 4..1, centre, mirrored. */
    public static final LaneKind[] KINDS = build();
    public static final int CENTRE_INDEX = KINDS.length / 2;

    private static LaneKind[] build() {
        java.util.List<LaneKind> k = new java.util.ArrayList<>();
        k.add(LaneKind.SIDEWALK); k.add(LaneKind.CURB); k.add(LaneKind.SHOULDER); k.add(LaneKind.EDGE);
        k.add(LaneKind.AUX); k.add(LaneKind.AUX_LINE);
        for (int i = MAX_LANES; i >= 1; i--) { k.add(LaneKind.LANE); if (i > 1) k.add(LaneKind.LANE_LINE); }
        int half = k.size();
        k.add(LaneKind.CENTRE);
        for (int i = half - 1; i >= 0; i--) k.add(k.get(i));
        return k.toArray(new LaneKind[0]);
    }

    /** Canonical widths for a two-way road of a class. */
    public static double[] widthsOf(RoadClass c) { return widthsOf(c, false); }

    /**
     * Canonical widths. A one-way road has all its lanes in one direction, centred on the path: an odd lane
     * count puts the middle lane in the centre slot (painted as asphalt), an even count puts a lane line
     * there; the remaining lanes fill the outer slots symmetrically.
     */
    public static double[] widthsOf(RoadClass c, boolean oneWay) {
        double[] w = new double[KINDS.length];
        double[] side = sideWidths(c, oneWay);
        int half = CENTRE_INDEX;
        for (int i = 0; i < half; i++) { w[i] = side[i]; w[KINDS.length - 1 - i] = side[i]; }
        w[CENTRE_INDEX] = oneWay ? (c.lanesPerDirection() % 2 == 1 ? c.laneWidth() : 1) : 1;
        return w;
    }

    /** Widths of one side, outside in, matching the first half of {@link #KINDS}. */
    private static double[] sideWidths(RoadClass c, boolean oneWay) {
        double[] s = new double[CENTRE_INDEX];
        int i = 0;
        s[i++] = c.sidewalkWidth();                 // SIDEWALK
        s[i++] = c.hasSidewalk() ? 1 : 0;           // CURB
        s[i++] = c.shoulderWidth();                 // SHOULDER
        s[i++] = c.edgeLines() ? 1 : 0;             // EDGE
        s[i++] = 0;                                 // AUX
        s[i++] = 0;                                 // AUX_LINE
        int n = c.lanesPerDirection();
        // Lanes per side: all n for a two-way road; for a one-way road the lanes beside the centre slot.
        int perSide = oneWay ? n / 2 : n;
        // One-way with an odd count: the centre slot is a lane, so slot LANE(1) stays empty and the outer
        // lanes shift out by one slot (the line between LANE(2) and LANE(1) then borders the centre lane).
        int firstSlot = oneWay && n % 2 == 1 ? 2 : 1;
        for (int lane = MAX_LANES; lane >= 1; lane--) {
            boolean used = lane >= firstSlot && lane < firstSlot + perSide;
            s[i++] = used ? c.laneWidth() : 0;      // LANE
            if (lane > 1) {
                boolean innerUsed = lane - 1 >= firstSlot && lane - 1 < firstSlot + perSide;
                boolean bordersCentreLane = oneWay && n % 2 == 1 && lane == 2 && perSide > 0;
                s[i++] = (used && innerUsed) || bordersCentreLane ? 1 : 0; // LANE_LINE
            }
        }
        return s;
    }

    public static LaneProfile of(RoadClass c) { return new LaneProfile(widthsOf(c), KINDS); }
    public static LaneProfile of(RoadClass c, boolean oneWay) { return new LaneProfile(widthsOf(c, oneWay), KINDS); }

    /** The lane at a signed lateral offset from the road centre (negative = left), or null outside the road. */
    public static LaneKind kindAt(double[] widths, double lateral) {
        double total = 0;
        for (double v : widths) total += v;
        double x = lateral + total / 2;
        if (x < 0) return null;
        for (int i = 0; i < widths.length; i++) {
            if (widths[i] > 0 && x < widths[i]) return KINDS[i];
            x -= widths[i];
        }
        return null;
    }

    public LaneKind kindAt(double lateral) { return kindAt(widths, lateral); }

    public double totalWidth() {
        double t = 0;
        for (double v : widths) t += v;
        return t;
    }
}
