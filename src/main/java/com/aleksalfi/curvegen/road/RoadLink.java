package com.aleksalfi.curvegen.road;

/** An undirected link between two nodes, built with one road class. */
public record RoadLink(int id, int a, int b, String classId) {
    public boolean touches(int node) { return a == node || b == node; }

    public int other(int node) { return node == a ? b : a; }

    public RoadLink withClassId(String c) { return new RoadLink(id, a, b, c); }
}
