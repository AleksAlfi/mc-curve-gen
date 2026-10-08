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
 */
public record RoadChain(int id, RoadClass roadClass, List<Integer> nodeIds, List<Integer> linkIds, Polyline line,
                        double startBox, double endBox, List<double[]> solidRanges, List<Double> zebras) {
    public double length() { return line.totalLength(); }
}
