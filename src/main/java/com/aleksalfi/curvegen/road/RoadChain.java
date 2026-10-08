package com.aleksalfi.curvegen.road;

import com.aleksalfi.curvegen.geom.Polyline;

import java.util.List;

/**
 * A run of links through pass-through nodes, sampled to one polyline.
 *
 * @param startBox   radius of the junction/roundabout core at the start node (0 for a plain end)
 * @param endBox     same for the end node
 * @param solidRanges along-ranges [from, to] where the centre line must be solid (tight bends)
 * @param zebras     along-positions of zebra crossings on plain nodes
 * @param corners    filleted corners (for tidying tight inner corners when painting)
 */
public record RoadChain(int id, RoadClass roadClass, List<Integer> nodeIds, List<Integer> linkIds, Polyline line,
                        double startBox, double endBox, List<double[]> solidRanges, List<Double> zebras, List<Corner> corners,
                        CrossSlope startCross, CrossSlope endCross) {
    public double length() { return line.totalLength(); }

    /**
     * Cross slope (rise per block of lateral offset, positive to the left of travel) that the road has at
     * a junction or roundabout on a hill, so its cross-section sits on the tilted core plane. It holds for
     * {@code run} blocks from the node and then tapers to level over about four road widths.
     */
    public record CrossSlope(double slope, double run) {
        public static final CrossSlope NONE = new CrossSlope(0, 0);

        public CrossSlope {
            slope = Math.max(-Junction.MAX_TILT, Math.min(Junction.MAX_TILT, slope));
        }
    }

    /** Rise per block of lateral offset at along-position {@code s}. */
    public double crossSlopeAt(double s) {
        double taper = Math.max(20, 4 * roadClass.halfTotal());
        double out = 0;
        if (startCross.slope() != 0) out += startCross.slope() * Math.max(0, Math.min(1, 1 - (s - startCross.run()) / taper));
        if (endCross.slope() != 0) out += endCross.slope() * Math.max(0, Math.min(1, 1 - (length() - s - endCross.run()) / taper));
        return out;
    }

    /**
     * A filleted corner: the node, the incoming and outgoing directions, the arc centre and radius.
     * The inner side of the corner is the side of {@code centre}.
     */
    public record Corner(com.aleksalfi.curvegen.geom.Vec2 node, com.aleksalfi.curvegen.geom.Vec2 dIn, com.aleksalfi.curvegen.geom.Vec2 dOut,
                         com.aleksalfi.curvegen.geom.Vec2 centre, double radius) {}
}
