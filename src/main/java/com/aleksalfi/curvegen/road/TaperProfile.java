package com.aleksalfi.curvegen.road;

import com.aleksalfi.curvegen.geom.Rasterizer;

import java.util.ArrayList;
import java.util.List;

/**
 * Lane widths along a chain whose links may have different classes. At a class change the wider road
 * tapers into the narrower one over {@link #taperLength}, entirely on the wider road's side, so the node
 * marks exactly where the narrower standard begins.
 */
public final class TaperProfile implements Rasterizer.WidthProfile {
    /** Taper length: 10 blocks per block of width difference, at least 20. */
    public static double taperLength(double widthA, double widthB) { return Math.max(20, 10 * Math.abs(widthA - widthB)); }

    /**
     * An auxiliary (acceleration / deceleration / weaving) lane on one side of the chain: full width between
     * {@code start} and {@code end}, opened or closed by a taper where the flag says so.
     */
    public record AuxLane(boolean rightSide, double start, double end, boolean taperIn, boolean taperOut, double laneWidth) {}

    private final List<AuxLane> auxLanes = new ArrayList<>();
    /** Along-ranges with a fixed cross-section (the merge zone of a ramp): {from, to} and the widths. */
    private final List<double[]> zoneRanges = new ArrayList<>();
    private final List<double[]> zoneWidths = new ArrayList<>();

    /**
     * A local widening (or narrowing) to {@code target}: full target width between {@code edge} and {@code full},
     * easing back to the normal cross-section between {@code full} and {@code taper}. Used for the trunk of a fork.
     */
    public record Blend(double edge, double full, double taper, double[] target) {}

    private final List<Blend> blends = new ArrayList<>();

    public void addBlend(Blend b) { blends.add(b); }

    public void addZone(double from, double to, double[] widths) {
        zoneRanges.add(new double[]{from, to});
        zoneWidths.add(widths);
    }
    private final double[] nodeAlong;      // along-position of every node of the chain
    private final double[][] linkWidths;   // canonical widths per link
    private final double[] taperFrom, taperTo; // per node: along-range of its taper (NaN when none)
    private final int[] taperLink;         // per node: the link the taper lies on
    private final double maxHalf;

    public TaperProfile(double[] nodeAlong, List<RoadClass> linkClasses) { this(nodeAlong, linkClasses, false); }

    public TaperProfile(double[] nodeAlong, List<RoadClass> linkClasses, boolean oneWay) {
        this.nodeAlong = nodeAlong;
        int links = linkClasses.size();
        linkWidths = new double[links][];
        double mh = 0;
        for (int i = 0; i < links; i++) {
            linkWidths[i] = LaneProfile.widthsOf(linkClasses.get(i), oneWay);
            mh = Math.max(mh, Rasterizer.half(linkWidths[i]));
        }
        maxHalf = mh;
        int nodes = nodeAlong.length;
        taperFrom = new double[nodes];
        taperTo = new double[nodes];
        taperLink = new int[nodes];
        java.util.Arrays.fill(taperFrom, Double.NaN);
        java.util.Arrays.fill(taperTo, Double.NaN);
        for (int k = 1; k < nodes - 1 && k < links; k++) {
            double[] a = linkWidths[k - 1], b = linkWidths[k];
            if (java.util.Arrays.equals(a, b)) continue;
            double wa = 2 * Rasterizer.half(a), wb = 2 * Rasterizer.half(b);
            int link = wa > wb ? k - 1 : k; // the wider link carries the taper (equal totals: the later one)
            double len = nodeAlong[link + 1] - nodeAlong[link];
            double l = Math.min(taperLength(wa, wb), 0.45 * len);
            if (l < 2) continue;
            taperLink[k] = link;
            if (link == k - 1) { taperFrom[k] = nodeAlong[k] - l; taperTo[k] = nodeAlong[k]; }
            else { taperFrom[k] = nodeAlong[k]; taperTo[k] = nodeAlong[k] + l; }
        }
    }

    /** Index of the link containing along-position {@code s}. */
    public int linkAt(double s) {
        int links = linkWidths.length;
        if (s < nodeAlong[0]) return links - 1; // closed loop: before the first node is the last link
        int i = 0;
        while (i + 1 < links && s >= nodeAlong[i + 1]) i++;
        return i;
    }

    public void addAuxLane(AuxLane lane) { auxLanes.add(lane); }

    public List<AuxLane> auxLanes() { return auxLanes; }

    @Override public int lanes() { return LaneProfile.KINDS.length; }
    @Override public int centreLane() { return LaneProfile.CENTRE_INDEX; }

    @Override
    public double maxHalf() {
        double extra = 0;
        for (AuxLane a : auxLanes) extra = Math.max(extra, a.laneWidth() + 1);
        double mh = maxHalf + extra;
        for (double[] w : zoneWidths) mh = Math.max(mh, Rasterizer.half(w));
        for (Blend b : blends) mh = Math.max(mh, Rasterizer.half(b.target()));
        return mh;
    }

    /** Extent to the left (positive lateral) and right of the centre line at along-position {@code s}. */
    public double leftHalfAt(double s) { return Rasterizer.leftHalf(widthsAt(s), LaneProfile.CENTRE_INDEX); }
    public double rightHalfAt(double s) { return Rasterizer.rightHalf(widthsAt(s), LaneProfile.CENTRE_INDEX); }

    @Override
    public double[] widthsAt(double s) {
        for (int i = 0; i < zoneRanges.size(); i++) if (s >= zoneRanges.get(i)[0] && s <= zoneRanges.get(i)[1]) return zoneWidths.get(i);
        double[] base = baseWidthsAt(s);
        for (Blend b : blends) {
            double lo = Math.min(b.edge(), b.taper()), hi = Math.max(b.edge(), b.taper());
            if (s < lo || s > hi) continue;
            boolean inFull = s >= Math.min(b.edge(), b.full()) && s <= Math.max(b.edge(), b.full());
            double f = inFull ? 1 : 1 - Math.abs(s - b.full()) / Math.max(1e-9, Math.abs(b.taper() - b.full()));
            base = lerp(base, b.target(), f, base);
        }
        if (auxLanes.isEmpty()) return base;
        double[] out = base.clone();
        int n = out.length;
        for (AuxLane a : auxLanes) {
            double w = auxWidthAt(a, s);
            if (w <= 0) continue;
            int aux = a.rightSide() ? n - 5 : 4, line = a.rightSide() ? n - 6 : 5;
            out[aux] = Math.max(out[aux], w);
            out[line] = w >= a.laneWidth() / 2 ? 1 : 0;
        }
        return out;
    }

    private static double auxWidthAt(AuxLane a, double s) {
        double t = Merge.LANE_TAPER;
        if (s >= a.start() && s <= a.end()) return a.laneWidth();
        if (a.taperIn() && s >= a.start() - t && s < a.start()) { double f = (s - (a.start() - t)) / t; return a.laneWidth() * f * f * (3 - 2 * f); }
        if (a.taperOut() && s > a.end() && s <= a.end() + t) { double f = 1 - (s - a.end()) / t; return a.laneWidth() * f * f * (3 - 2 * f); }
        return 0;
    }

    private double[] baseWidthsAt(double s) {
        int i = linkAt(s);
        // Taper at the node that starts this link (the taper lies on this link when it is the wider one).
        int k = i;
        if (k >= 1 && k < taperFrom.length && !Double.isNaN(taperFrom[k]) && taperLink[k] == i && s <= taperTo[k]) {
            return lerp(linkWidths[k - 1], linkWidths[k], (s - taperFrom[k]) / (taperTo[k] - taperFrom[k]), linkWidths[i]);
        }
        k = i + 1;
        if (k < taperFrom.length - 1 && k < linkWidths.length && !Double.isNaN(taperFrom[k]) && taperLink[k] == i && s >= taperFrom[k]) {
            return lerp(linkWidths[k - 1], linkWidths[k], (s - taperFrom[k]) / (taperTo[k] - taperFrom[k]), linkWidths[i]);
        }
        return linkWidths[i];
    }

    /**
     * Blends two cross-sections. Lanes and the shoulder ease from one width to the other (an S-curve, no
     * kink at either end). Markings are all or nothing: an edge line stays as long as either class has one,
     * a lane line appears once its lane is at least half open. Curb and sidewalk are never blended: they are
     * exactly those of {@code own}, the class of the link the position lies on, so a sidewalk begins or ends
     * square at the node.
     */
    private static double[] lerp(double[] a, double[] b, double f, double[] own) {
        f = Math.max(0, Math.min(1, f));
        double e = f * f * (3 - 2 * f);
        LaneKind[] kinds = LaneProfile.KINDS;
        int n = a.length;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            switch (kinds[i]) {
                case SIDEWALK, CURB -> out[i] = own[i];
                case EDGE, AUX_LINE -> out[i] = Math.max(a[i], b[i]);
                case LANE_LINE -> out[i] = 0; // decided below
                default -> out[i] = a[i] + (b[i] - a[i]) * e;
            }
        }
        for (int i = 0; i < n; i++) {
            if (kinds[i] != LaneKind.LANE_LINE) continue;
            double full = Math.max(Math.max(a[i - 1], b[i - 1]), Math.max(a[i + 1], b[i + 1]));
            boolean present = (a[i] > 0 || b[i] > 0) && out[i - 1] >= 0.5 * full && out[i + 1] >= 0.5 * full;
            out[i] = present ? 1 : 0;
        }
        return out;
    }

    /** Along-ranges of every taper (for a solid centre line through the narrowing). */
    public List<double[]> taperRanges() {
        List<double[]> out = new ArrayList<>();
        for (int k = 0; k < taperFrom.length; k++) if (!Double.isNaN(taperFrom[k])) out.add(new double[]{taperFrom[k], taperTo[k]});
        return out;
    }
}
