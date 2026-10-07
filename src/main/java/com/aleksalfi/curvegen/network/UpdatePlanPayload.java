package com.aleksalfi.curvegen.network;

import com.aleksalfi.curvegen.CurveGen;
import com.aleksalfi.curvegen.plan.CurvePlan;
import com.aleksalfi.curvegen.plan.PlanCodecs;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client → server: replace the plan stored on the planner the player is holding. */
public record UpdatePlanPayload(CurvePlan plan) implements CustomPacketPayload {
    public static final Type<UpdatePlanPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(CurveGen.MOD_ID, "update_plan"));
    public static final StreamCodec<RegistryFriendlyByteBuf, UpdatePlanPayload> STREAM_CODEC =
            PlanCodecs.PLAN_STREAM.map(UpdatePlanPayload::new, UpdatePlanPayload::plan).cast();

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
