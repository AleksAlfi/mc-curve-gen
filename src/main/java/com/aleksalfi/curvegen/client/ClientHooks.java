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

    /** Sneak + right-click with the road planner: node screen when looking at a node, else the network screen. */
    public static void openRoadPlanner(ItemStack stack) {
        Minecraft mc = Minecraft.getInstance();
        com.aleksalfi.curvegen.road.RoadPlannerState state = com.aleksalfi.curvegen.item.RoadPlannerItem.getState(stack);
        com.aleksalfi.curvegen.road.RoadNetwork network = RoadClientCache.named(state.network());
        if (network != null) {
            com.aleksalfi.curvegen.road.RoadNode near = null;
            if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult bhr && mc.hitResult.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                net.minecraft.core.BlockPos t = bhr.getBlockPos().relative(bhr.getDirection());
                near = network.nearestNode(t.getX() + 0.5, t.getZ() + 0.5, 1.5);
            }
            if (near == null && state.selectedNode() >= 0) near = network.nodes().get(state.selectedNode());
            if (near != null) {
                mc.setScreen(new com.aleksalfi.curvegen.client.gui.RoadNodeScreen(near.id()));
                return;
            }
        }
        mc.setScreen(new com.aleksalfi.curvegen.client.gui.RoadNetworkScreen());
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
