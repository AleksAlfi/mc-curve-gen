package com.aleksalfi.curvegen.road;

import com.aleksalfi.curvegen.CurveGen;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** All road networks of a server, stored with the overworld. */
public final class RoadNetworks extends SavedData {
    private static final String ID = "curvegen_roads";
    private static final Codec<List<RoadNetwork>> LIST = RoadCodecs.NETWORK.listOf();

    /** Undo depth per network; the versions are saved with the world. */
    public static final int HISTORY = 50;

    private final Map<String, RoadNetwork> networks = new LinkedHashMap<>();
    /** Previous versions per network for undo, newest first. */
    private final Map<String, Deque<RoadNetwork>> history = new HashMap<>();

    public static RoadNetworks get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(RoadNetworks::new, RoadNetworks::load), ID);
    }

    private static RoadNetworks load(CompoundTag tag, HolderLookup.Provider registries) {
        RoadNetworks data = new RoadNetworks();
        DataResult<List<RoadNetwork>> result = LIST.parse(NbtOps.INSTANCE, tag.get("networks"));
        result.resultOrPartial(err -> CurveGen.LOGGER.error("Failed to read road networks: {}", err))
                .ifPresent(list -> list.forEach(n -> data.networks.put(n.name(), n)));
        CompoundTag hist = tag.getCompound("history");
        for (String name : hist.getAllKeys()) {
            if (!data.networks.containsKey(name)) continue;
            LIST.parse(NbtOps.INSTANCE, hist.get(name))
                    .resultOrPartial(err -> CurveGen.LOGGER.error("Failed to read road history of {}: {}", name, err))
                    .ifPresent(list -> {
                        Deque<RoadNetwork> h = new ArrayDeque<>();
                        for (RoadNetwork v : list) if (h.size() < HISTORY) h.addLast(v);
                        if (!h.isEmpty()) data.history.put(name, h);
                    });
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        Tag list = LIST.encodeStart(NbtOps.INSTANCE, new ArrayList<>(networks.values()))
                .resultOrPartial(err -> CurveGen.LOGGER.error("Failed to write road networks: {}", err)).orElse(null);
        if (list != null) tag.put("networks", list);
        CompoundTag hist = new CompoundTag();
        for (Map.Entry<String, Deque<RoadNetwork>> e : history.entrySet()) {
            if (e.getValue().isEmpty() || !networks.containsKey(e.getKey())) continue;
            LIST.encodeStart(NbtOps.INSTANCE, new ArrayList<>(e.getValue()))
                    .resultOrPartial(err -> CurveGen.LOGGER.error("Failed to write road history of {}: {}", e.getKey(), err))
                    .ifPresent(t -> hist.put(e.getKey(), t));
        }
        tag.put("history", hist);
        return tag;
    }

    public static String normalize(String name) {
        String n = name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]+", "_");
        return n.length() > 32 ? n.substring(0, 32) : n;
    }

    public RoadNetwork network(String name) { return networks.get(normalize(name)); }

    public void put(RoadNetwork network) {
        networks.put(network.name(), network);
        setDirty();
    }

    /** Edits with the same merge key arriving within this window share one undo step (typing in a field). */
    public static final long MERGE_MS = 4000;
    private final Map<String, String> lastMergeKey = new HashMap<>();
    private final Map<String, Long> lastMergeTime = new HashMap<>();

    /**
     * Stores {@code next} and remembers {@code previous} so the change can be undone. With a non-null
     * {@code mergeKey}, a run of edits with the same key within {@link #MERGE_MS} of each other becomes a
     * single undo step (the version before the first of them).
     */
    public void putRemembering(RoadNetwork previous, RoadNetwork next, String mergeKey) {
        if (previous != next) {
            long now = System.currentTimeMillis();
            boolean merge = mergeKey != null && mergeKey.equals(lastMergeKey.get(next.name()))
                    && now - lastMergeTime.getOrDefault(next.name(), 0L) <= MERGE_MS && canUndo(next.name());
            if (!merge) {
                Deque<RoadNetwork> h = history.computeIfAbsent(next.name(), k -> new ArrayDeque<>());
                h.push(previous);
                while (h.size() > HISTORY) h.removeLast();
            }
            lastMergeKey.put(next.name(), mergeKey);
            lastMergeTime.put(next.name(), now);
        }
        put(next);
    }

    public void putRemembering(RoadNetwork previous, RoadNetwork next) { putRemembering(previous, next, null); }

    public boolean canUndo(String name) {
        Deque<RoadNetwork> h = history.get(normalize(name));
        return h != null && !h.isEmpty();
    }

    /** Restores the previous version of a network; null when there is nothing to undo. */
    public RoadNetwork undo(String name) {
        Deque<RoadNetwork> h = history.get(normalize(name));
        if (h == null || h.isEmpty()) return null;
        RoadNetwork previous = h.pop();
        lastMergeKey.remove(normalize(name));
        put(previous);
        return previous;
    }

    public boolean remove(String name) {
        boolean removed = networks.remove(normalize(name)) != null;
        history.remove(normalize(name));
        if (removed) setDirty();
        return removed;
    }

    /** Networks the player may at least view. */
    public List<RoadNetwork> visibleTo(UUID player, boolean operator) {
        List<RoadNetwork> out = new ArrayList<>();
        for (RoadNetwork n : networks.values()) if (n.canView(player, operator)) out.add(n);
        return out;
    }

    public int size() { return networks.size(); }
}
