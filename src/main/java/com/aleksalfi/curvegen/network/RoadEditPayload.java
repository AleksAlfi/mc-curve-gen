package com.aleksalfi.curvegen.network;

import com.aleksalfi.curvegen.CurveGen;
import com.aleksalfi.curvegen.road.RoadEdit;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client → server: edit the road network the held Road Planner is bound to. */
public record RoadEditPayload(RoadEdit edit) implements CustomPacketPayload {
    public static final Type<RoadEditPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(CurveGen.MOD_ID, "road_edit"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RoadEditPayload> STREAM_CODEC =
            ByteBufCodecs.fromCodec(RoadEdit.CODEC, () -> net.minecraft.nbt.NbtAccounter.create(65536L)).map(RoadEditPayload::new, RoadEditPayload::edit).cast();

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
