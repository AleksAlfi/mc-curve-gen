package com.aleksalfi.curvegen.network;

import com.aleksalfi.curvegen.CurveGen;
import com.aleksalfi.curvegen.road.RoadCodecs;
import com.aleksalfi.curvegen.road.RoadNetwork;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Server → client: the networks the player may see (full copies). {@code names} lists every network the
 * player can view so the client can offer them; {@code network} is the one bound to the held planner.
 */
public record RoadSyncPayload(List<String> names, java.util.Optional<RoadNetwork> network) implements CustomPacketPayload {
    public static final Type<RoadSyncPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(CurveGen.MOD_ID, "road_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RoadSyncPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(256)), RoadSyncPayload::names,
            ByteBufCodecs.optional(RoadCodecs.NETWORK_STREAM), RoadSyncPayload::network,
            RoadSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
