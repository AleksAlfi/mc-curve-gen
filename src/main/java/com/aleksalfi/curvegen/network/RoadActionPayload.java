package com.aleksalfi.curvegen.network;

import com.aleksalfi.curvegen.CurveGen;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client → server: one-shot action for the held Road Planner. */
public record RoadActionPayload(Action action) implements CustomPacketPayload {
    public enum Action { PLACE, UNDO_PLACE, DEPLOY_SCHEMATIC, DESELECT, REQUEST_SYNC, NONE }

    public static final Type<RoadActionPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(CurveGen.MOD_ID, "road_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RoadActionPayload> STREAM_CODEC =
            ByteBufCodecs.VAR_INT.map(i -> new RoadActionPayload(i >= 0 && i < Action.values().length ? Action.values()[i] : Action.NONE), p -> p.action().ordinal()).cast();

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
