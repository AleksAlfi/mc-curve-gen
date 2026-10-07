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

    /** One undo per press: holding the attack button re-fires the click event every few ticks. */
    public static void sendUndoPoint() {
        if (ClientEvents.attackHeldLastTick) return;
        ClientEvents.attackHeldLastTick = true;
        PacketDistributor.sendToServer(new PlannerActionPayload(PlannerActionPayload.Action.UNDO_POINT));
    }
}
