package com.aleksalfi.curvegen.client;

import com.aleksalfi.curvegen.client.gui.PlannerScreen;
import com.aleksalfi.curvegen.network.PlannerActionPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/** Client-only entry points reachable from common code (never loaded on a dedicated server). */
public final class ClientHooks {
    private ClientHooks() {}


    public static void openPlanner(ItemStack stack) {
        Minecraft.getInstance().setScreen(new PlannerScreen());
    }

    /** The V key with the road planner: the network screen. */
    public static void openRoadPlanner(ItemStack stack) {
        Minecraft.getInstance().setScreen(new com.aleksalfi.curvegen.client.gui.RoadNetworkScreen());
    }

    public static void onRoadSync(com.aleksalfi.curvegen.network.RoadSyncPayload payload) {
        RoadClientCache.accept(payload.names(), payload.network().orElse(null));
        if (Minecraft.getInstance().screen instanceof com.aleksalfi.curvegen.client.gui.RoadScreen rs) rs.onNetworkChanged();
    }

    /** One undo per press: holding the attack button re-fires the click event every few ticks. */
    public static void sendUndoPoint() {
        if (ClientEvents.attackHeldLastTick) return;
        ClientEvents.attackHeldLastTick = true;
        PacketDistributor.sendToServer(new PlannerActionPayload(PlannerActionPayload.Action.UNDO_POINT));
    }
}
