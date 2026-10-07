package com.aleksalfi.curvegen.network;

import com.aleksalfi.curvegen.CurveGen;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client → server: a one-shot action on the held planner. */
public record PlannerActionPayload(Action action) implements CustomPacketPayload {
    public enum Action { UNDO_POINT, REMOVE_SEGMENT, CLEAR, PLACE, UNDO_PLACE, DEPLOY_SCHEMATIC, FINISH_SEGMENT, NONE }

    public static final Type<PlannerActionPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(CurveGen.MOD_ID, "planner_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PlannerActionPayload> STREAM_CODEC =
            ByteBufCodecs.VAR_INT.map(i -> new PlannerActionPayload(i >= 0 && i < Action.values().length ? Action.values()[i] : Action.NONE), p -> p.action().ordinal()).cast();

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
