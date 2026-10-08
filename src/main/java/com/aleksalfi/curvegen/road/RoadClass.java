package com.aleksalfi.curvegen.road;

/**
 * A road class: everything that decides the cross-section of a link.
 *
 * @param laneWidth        width of one traffic lane in blocks
 * @param lanesPerDirection 1 or 2 (two gives a dashed lane line per direction and a solid centre)
 * @param sidewalkWidth    0 for none, otherwise the sidewalk strip beside the 1-block curb
 * @param curbLayers       height of curb and sidewalk in copycat layers (eighths), 0 = flat
 * @param edgeLines        solid white line along both carriageway edges
 * @param smoothEdges      sideways copycat layers on partially covered edge columns (off: full blocks, 50% rule)
 * @param shoulderWidth    hard shoulder (asphalt) outside the edge line, 0 for none
 * @param mergeLength      length of acceleration / deceleration lanes where a lower class joins this one
 * @param paintArrows      paint direction arrows in the lanes of one-way roads
 */
public record RoadClass(String id, String name, int laneWidth, int lanesPerDirection, int sidewalkWidth, int curbLayers,
                        boolean edgeLines, boolean smoothEdges, String asphalt, String line, String curb, String sidewalk,
                        int shoulderWidth, int mergeLength, boolean paintArrows) {

    public RoadClass {
        laneWidth = Math.max(2, Math.min(32, laneWidth));
        lanesPerDirection = Math.max(1, Math.min(4, lanesPerDirection));
        sidewalkWidth = Math.max(0, Math.min(16, sidewalkWidth));
        curbLayers = Math.max(0, Math.min(7, curbLayers));
        shoulderWidth = Math.max(0, Math.min(8, shoulderWidth));
        mergeLength = Math.max(20, Math.min(200, mergeLength));
    }

    public static RoadClass street() {
        return new RoadClass("street", "Street", 6, 1, 3, 3, false, false,
                "minecraft:gray_concrete", "minecraft:white_concrete", "minecraft:stone_bricks", "minecraft:smooth_stone", 0, 60, false);
    }

    public static RoadClass mainRoad() {
        return new RoadClass("main", "Main road", 7, 1, 3, 3, true, false,
                "minecraft:gray_concrete", "minecraft:white_concrete", "minecraft:stone_bricks", "minecraft:smooth_stone", 0, 60, false);
    }

    public static RoadClass highway() {
        return new RoadClass("highway", "Highway", 8, 2, 0, 0, true, false,
                "minecraft:gray_concrete", "minecraft:white_concrete", "minecraft:stone_bricks", "minecraft:smooth_stone", 2, 60, false);
    }

    /** Carriageway half width: lanes, lane lines and the centre line, no edge lines. */
    public double halfCarriageway() {
        return lanesPerDirection * laneWidth + (lanesPerDirection - 1) + 0.5; // + half of the centre line
    }

    /** Half width of a one-way carriageway of this class: all lanes in one direction with lane lines between. */
    public double oneWayHalf() { return (lanesPerDirection * laneWidth + (lanesPerDirection - 1)) / 2.0; }

    /** Half width of everything: carriageway, edge lines, shoulder, curbs, sidewalks. */
    public double halfTotal() {
        return halfCarriageway() + (edgeLines ? 1 : 0) + shoulderWidth + (sidewalkWidth > 0 ? 1 + sidewalkWidth : 0);
    }

    public boolean hasSidewalk() { return sidewalkWidth > 0; }

    /** Default curb radius at junction corners. */
    public double cornerRadius() { return laneWidth; }

    public RoadClass withId(String v) { return new RoadClass(v, name, laneWidth, lanesPerDirection, sidewalkWidth, curbLayers, edgeLines, smoothEdges, asphalt, line, curb, sidewalk, shoulderWidth, mergeLength, paintArrows); }
    public RoadClass withName(String v) { return new RoadClass(id, v, laneWidth, lanesPerDirection, sidewalkWidth, curbLayers, edgeLines, smoothEdges, asphalt, line, curb, sidewalk, shoulderWidth, mergeLength, paintArrows); }
    public RoadClass withLaneWidth(int v) { return new RoadClass(id, name, v, lanesPerDirection, sidewalkWidth, curbLayers, edgeLines, smoothEdges, asphalt, line, curb, sidewalk, shoulderWidth, mergeLength, paintArrows); }
    public RoadClass withLanesPerDirection(int v) { return new RoadClass(id, name, laneWidth, v, sidewalkWidth, curbLayers, edgeLines, smoothEdges, asphalt, line, curb, sidewalk, shoulderWidth, mergeLength, paintArrows); }
    public RoadClass withSidewalkWidth(int v) { return new RoadClass(id, name, laneWidth, lanesPerDirection, v, curbLayers, edgeLines, smoothEdges, asphalt, line, curb, sidewalk, shoulderWidth, mergeLength, paintArrows); }
    public RoadClass withCurbLayers(int v) { return new RoadClass(id, name, laneWidth, lanesPerDirection, sidewalkWidth, v, edgeLines, smoothEdges, asphalt, line, curb, sidewalk, shoulderWidth, mergeLength, paintArrows); }
    public RoadClass withEdgeLines(boolean v) { return new RoadClass(id, name, laneWidth, lanesPerDirection, sidewalkWidth, curbLayers, v, smoothEdges, asphalt, line, curb, sidewalk, shoulderWidth, mergeLength, paintArrows); }
    public RoadClass withSmoothEdges(boolean v) { return new RoadClass(id, name, laneWidth, lanesPerDirection, sidewalkWidth, curbLayers, edgeLines, v, asphalt, line, curb, sidewalk, shoulderWidth, mergeLength, paintArrows); }
    public RoadClass withShoulderWidth(int v) { return new RoadClass(id, name, laneWidth, lanesPerDirection, sidewalkWidth, curbLayers, edgeLines, smoothEdges, asphalt, line, curb, sidewalk, v, mergeLength, paintArrows); }
    public RoadClass withMergeLength(int v) { return new RoadClass(id, name, laneWidth, lanesPerDirection, sidewalkWidth, curbLayers, edgeLines, smoothEdges, asphalt, line, curb, sidewalk, shoulderWidth, v, paintArrows); }
    public RoadClass withPaintArrows(boolean v) { return new RoadClass(id, name, laneWidth, lanesPerDirection, sidewalkWidth, curbLayers, edgeLines, smoothEdges, asphalt, line, curb, sidewalk, shoulderWidth, mergeLength, v); }
    public RoadClass withAsphalt(String v) { return new RoadClass(id, name, laneWidth, lanesPerDirection, sidewalkWidth, curbLayers, edgeLines, smoothEdges, v, line, curb, sidewalk, shoulderWidth, mergeLength, paintArrows); }
    public RoadClass withLine(String v) { return new RoadClass(id, name, laneWidth, lanesPerDirection, sidewalkWidth, curbLayers, edgeLines, smoothEdges, asphalt, v, curb, sidewalk, shoulderWidth, mergeLength, paintArrows); }
    public RoadClass withCurb(String v) { return new RoadClass(id, name, laneWidth, lanesPerDirection, sidewalkWidth, curbLayers, edgeLines, smoothEdges, asphalt, line, v, sidewalk, shoulderWidth, mergeLength, paintArrows); }
    public RoadClass withSidewalk(String v) { return new RoadClass(id, name, laneWidth, lanesPerDirection, sidewalkWidth, curbLayers, edgeLines, smoothEdges, asphalt, line, curb, v, shoulderWidth, mergeLength, paintArrows); }
}
