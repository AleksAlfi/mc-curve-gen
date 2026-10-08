package com.aleksalfi.curvegen.road;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Painted columns keyed by (x, z), with one cell per <em>level</em>: roads whose surfaces are more than
 * {@link #LEVEL_GAP} apart in height (a bridge over a road) keep separate cells in the same column, roads
 * at the same level compete for it.
 */
public final class CellMap {
    public static final double LEVEL_GAP = 2.5;

    private final Map<Long, Object> columns = new HashMap<>(); // Cell for single-level columns, List<Cell> otherwise
    private int size;

    /** The cell of this column at the level of {@code height}, or null. */
    public RoadPainter.Cell at(long key, double height) {
        Object o = columns.get(key);
        if (o == null) return null;
        if (o instanceof RoadPainter.Cell c) return Math.abs(c.height - height) < LEVEL_GAP ? c : null;
        RoadPainter.Cell best = null;
        for (RoadPainter.Cell c : asList(o)) {
            if (Math.abs(c.height - height) < LEVEL_GAP && (best == null || Math.abs(c.height - height) < Math.abs(best.height - height))) best = c;
        }
        return best;
    }

    /** The highest cell of this column, or null. */
    public RoadPainter.Cell top(long key) {
        Object o = columns.get(key);
        if (o == null) return null;
        if (o instanceof RoadPainter.Cell c) return c;
        RoadPainter.Cell best = null;
        for (RoadPainter.Cell c : asList(o)) if (best == null || c.height > best.height) best = c;
        return best;
    }

    /** Stores the cell, replacing the column's cell at the same level if there is one. */
    public void put(long key, RoadPainter.Cell cell) {
        Object o = columns.get(key);
        if (o == null) { columns.put(key, cell); size++; return; }
        if (o instanceof RoadPainter.Cell c) {
            if (Math.abs(c.height - cell.height) < LEVEL_GAP) { columns.put(key, cell); return; }
            List<RoadPainter.Cell> list = new ArrayList<>(2);
            list.add(c);
            list.add(cell);
            columns.put(key, list);
            size++;
            return;
        }
        List<RoadPainter.Cell> list = asList(o);
        for (int i = 0; i < list.size(); i++) {
            if (Math.abs(list.get(i).height - cell.height) < LEVEL_GAP) { list.set(i, cell); return; }
        }
        list.add(cell);
        size++;
    }

    public void remove(long key, RoadPainter.Cell cell) {
        Object o = columns.get(key);
        if (o == null) return;
        if (o == cell) { columns.remove(key); size--; return; }
        if (o instanceof List<?>) {
            List<RoadPainter.Cell> list = asList(o);
            if (list.remove(cell)) {
                size--;
                if (list.size() == 1) columns.put(key, list.get(0));
                else if (list.isEmpty()) columns.remove(key);
            }
        }
    }

    public int size() { return size; }

    public void forEach(BiConsumer<Long, RoadPainter.Cell> action) {
        for (Map.Entry<Long, Object> e : columns.entrySet()) {
            if (e.getValue() instanceof RoadPainter.Cell c) action.accept(e.getKey(), c);
            else for (RoadPainter.Cell c : asList(e.getValue())) action.accept(e.getKey(), c);
        }
    }

    /** Every (key, cell) pair; a column with several levels appears once per level. */
    public List<Map.Entry<Long, RoadPainter.Cell>> entries() {
        List<Map.Entry<Long, RoadPainter.Cell>> out = new ArrayList<>(size);
        forEach((k, c) -> out.add(new java.util.AbstractMap.SimpleEntry<>(k, c)));
        return out;
    }

    public List<RoadPainter.Cell> all() {
        List<RoadPainter.Cell> out = new ArrayList<>(size);
        forEach((k, c) -> out.add(c));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<RoadPainter.Cell> asList(Object o) { return (List<RoadPainter.Cell>) o; }
}
