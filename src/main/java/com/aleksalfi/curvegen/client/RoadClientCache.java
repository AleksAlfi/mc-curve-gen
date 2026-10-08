package com.aleksalfi.curvegen.client;

import com.aleksalfi.curvegen.road.RoadNetwork;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Client copy of what the server sent: visible network names and the current network. */
public final class RoadClientCache {
    private RoadClientCache() {}

    private static List<String> names = List.of();
    @Nullable private static RoadNetwork current;
    private static int revision;

    public static void accept(List<String> newNames, @Nullable RoadNetwork network) {
        names = List.copyOf(newNames);
        current = network;
        revision++;
    }

    public static List<String> names() { return names; }

    @Nullable
    public static RoadNetwork current() { return current; }

    /** The current network if it carries this name, else null. */
    @Nullable
    public static RoadNetwork named(String name) {
        return current != null && current.name().equals(name) ? current : null;
    }

    public static int revision() { return revision; }

    public static void clear() { names = List.of(); current = null; revision++; }
}
