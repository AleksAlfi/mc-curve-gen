package com.aleksalfi.curvegen.road;

import java.util.Optional;

/**
 * A link between two nodes, built with one road class, two-way or one-way. The sidewalk width, edge lines
 * and shoulder of its class can be overridden per road (empty = inherit from the class).
 */
public record RoadLink(int id, int a, int b, String classId, LinkDir dir,
                       Optional<Integer> sidewalk, Optional<Boolean> edgeLines, Optional<Integer> shoulder, Optional<Integer> laneWidth) {
    public RoadLink(int id, int a, int b, String classId) { this(id, a, b, classId, LinkDir.TWO_WAY); }
    public RoadLink(int id, int a, int b, String classId, LinkDir dir) { this(id, a, b, classId, dir, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()); }

    public boolean touches(int node) { return a == node || b == node; }

    public int other(int node) { return node == a ? b : a; }

    public boolean oneWay() { return dir != LinkDir.TWO_WAY; }

    /** Node traffic enters from on a one-way link ({@code a} for a two-way link). */
    public int from() { return dir == LinkDir.REVERSE ? b : a; }

    /** Node traffic leaves towards on a one-way link ({@code b} for a two-way link). */
    public int to() { return dir == LinkDir.REVERSE ? a : b; }

    /** Whether traffic may leave {@code node} along this link. */
    public boolean leaves(int node) { return !oneWay() || from() == node; }

    /** Whether traffic may arrive at {@code node} along this link. */
    public boolean arrives(int node) { return !oneWay() || to() == node; }

    public boolean hasOverrides() { return sidewalk.isPresent() || edgeLines.isPresent() || shoulder.isPresent() || laneWidth.isPresent(); }

    /** The class with this road's overrides applied. */
    public RoadClass apply(RoadClass c) {
        if (sidewalk.isPresent()) c = c.withSidewalkWidth(sidewalk.get());
        if (edgeLines.isPresent()) c = c.withEdgeLines(edgeLines.get());
        if (shoulder.isPresent()) c = c.withShoulderWidth(shoulder.get());
        if (laneWidth.isPresent()) c = c.withLaneWidth(laneWidth.get());
        return c;
    }

    public RoadLink withClassId(String c) { return new RoadLink(id, a, b, c, dir, sidewalk, edgeLines, shoulder, laneWidth); }
    public RoadLink withDir(LinkDir d) { return new RoadLink(id, a, b, classId, d, sidewalk, edgeLines, shoulder, laneWidth); }
    public RoadLink withSidewalk(Optional<Integer> v) { return new RoadLink(id, a, b, classId, dir, v, edgeLines, shoulder, laneWidth); }
    public RoadLink withEdgeLines(Optional<Boolean> v) { return new RoadLink(id, a, b, classId, dir, sidewalk, v, shoulder, laneWidth); }
    public RoadLink withShoulder(Optional<Integer> v) { return new RoadLink(id, a, b, classId, dir, sidewalk, edgeLines, v, laneWidth); }
    public RoadLink withLaneWidth(Optional<Integer> v) { return new RoadLink(id, a, b, classId, dir, sidewalk, edgeLines, shoulder, v); }
}
