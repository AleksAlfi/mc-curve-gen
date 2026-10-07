package com.aleksalfi.curvegen.geom;

/** Densely sampled path with per-vertex horizontal position and surface height. */
public final class Polyline {
    public final double[] x, z, y, s;
    public final int size;

    public Polyline(double[] x, double[] z, double[] y) {
        this.x = x;
        this.z = z;
        this.y = y;
        this.size = x.length;
        this.s = new double[size];
        for (int i = 1; i < size; i++) {
            double dx = x[i] - x[i - 1], dz = z[i] - z[i - 1];
            s[i] = s[i - 1] + Math.sqrt(dx * dx + dz * dz);
        }
    }

    public double totalLength() { return size == 0 ? 0 : s[size - 1]; }

    public double minX() { return min(x); }
    public double maxX() { return max(x); }
    public double minZ() { return min(z); }
    public double maxZ() { return max(z); }

    private static double min(double[] a) { double m = Double.POSITIVE_INFINITY; for (double v : a) m = Math.min(m, v); return m; }
    private static double max(double[] a) { double m = Double.NEGATIVE_INFINITY; for (double v : a) m = Math.max(m, v); return m; }
}
