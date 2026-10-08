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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** All road networks of a server, stored with the overworld. */
public final class RoadNetworks extends SavedData {
    private static final String ID = "curvegen_roads";
    private static final Codec<List<RoadNetwork>> LIST = RoadCodecs.NETWORK.listOf();

    private final Map<String, RoadNetwork> networks = new LinkedHashMap<>();

    public static RoadNetworks get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(RoadNetworks::new, RoadNetworks::load), ID);
    }

    private static RoadNetworks load(CompoundTag tag, HolderLookup.Provider registries) {
        RoadNetworks data = new RoadNetworks();
        DataResult<List<RoadNetwork>> result = LIST.parse(NbtOps.INSTANCE, tag.get("networks"));
        result.resultOrPartial(err -> CurveGen.LOGGER.error("Failed to read road networks: {}", err))
                .ifPresent(list -> list.forEach(n -> data.networks.put(n.name(), n)));
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        Tag list = LIST.encodeStart(NbtOps.INSTANCE, new ArrayList<>(networks.values()))
                .resultOrPartial(err -> CurveGen.LOGGER.error("Failed to write road networks: {}", err)).orElse(null);
        if (list != null) tag.put("networks", list);
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

    public boolean remove(String name) {
        boolean removed = networks.remove(normalize(name)) != null;
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
