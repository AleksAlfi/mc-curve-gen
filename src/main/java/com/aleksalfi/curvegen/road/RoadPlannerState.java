package com.aleksalfi.curvegen.road;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** What a Road Planner item remembers: the network it edits, the selected node and the export name. */
public record RoadPlannerState(String network, int selectedNode, String schematicName) {
    public static final RoadPlannerState DEFAULT = new RoadPlannerState("", -1, "road");

    public static final Codec<RoadPlannerState> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.optionalFieldOf("network", "").forGetter(RoadPlannerState::network),
            Codec.INT.optionalFieldOf("selected", -1).forGetter(RoadPlannerState::selectedNode),
            Codec.STRING.optionalFieldOf("schematic_name", "road").forGetter(RoadPlannerState::schematicName)
    ).apply(i, RoadPlannerState::new));
    public static final StreamCodec<ByteBuf, RoadPlannerState> STREAM = ByteBufCodecs.fromCodec(CODEC);

    public RoadPlannerState withNetwork(String n) { return new RoadPlannerState(n, -1, schematicName); }
    public RoadPlannerState withSelected(int id) { return new RoadPlannerState(network, id, schematicName); }
    public RoadPlannerState withSchematicName(String s) { return new RoadPlannerState(network, selectedNode, s); }
}
