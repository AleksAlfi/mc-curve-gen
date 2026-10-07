package com.aleksalfi.curvegen.geom;

import java.util.Arrays;

/** Minimal open-addressing set of longs (keeps the geometry package free of library dependencies). */
final class LongSet {
    private static final long EMPTY = Long.MIN_VALUE;
    private long[] keys = new long[1024];
    private int size;

    LongSet() { Arrays.fill(keys, EMPTY); }

    int size() { return size; }

    boolean add(long key) {
        if (key == EMPTY) key = Long.MIN_VALUE + 1; // never produced by packed block coordinates anyway
        if (size * 2 >= keys.length) grow();
        int mask = keys.length - 1;
        int i = mix(key) & mask;
        while (true) {
            long k = keys[i];
            if (k == EMPTY) { keys[i] = key; size++; return true; }
            if (k == key) return false;
            i = (i + 1) & mask;
        }
    }

    long[] toSortedArray() {
        long[] out = new long[size];
        int n = 0;
        for (long k : keys) if (k != EMPTY) out[n++] = k;
        Arrays.sort(out);
        return out;
    }

    private void grow() {
        long[] old = keys;
        keys = new long[old.length * 2];
        Arrays.fill(keys, EMPTY);
        size = 0;
        for (long k : old) if (k != EMPTY) add(k);
    }

    private static int mix(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        return (int) k;
    }
}
