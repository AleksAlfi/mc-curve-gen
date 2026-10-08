package com.aleksalfi.curvegen.road;

/** A link between two nodes, built with one road class, two-way or one-way. */
public record RoadLink(int id, int a, int b, String classId, LinkDir dir) {
    public RoadLink(int id, int a, int b, String classId) { this(id, a, b, classId, LinkDir.TWO_WAY); }

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

    public RoadLink withClassId(String c) { return new RoadLink(id, a, b, c, dir); }
    public RoadLink withDir(LinkDir d) { return new RoadLink(id, a, b, classId, d); }
}
