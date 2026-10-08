package com.aleksalfi.curvegen.client;

import com.aleksalfi.curvegen.client.gui.RoadLinkScreen;
import com.aleksalfi.curvegen.client.gui.RoadNodeScreen;
import com.aleksalfi.curvegen.item.RoadPlannerItem;
import com.aleksalfi.curvegen.network.RoadActionPayload;
import com.aleksalfi.curvegen.road.RoadEdit;
import com.aleksalfi.curvegen.road.RoadNetwork;
import com.aleksalfi.curvegen.road.RoadPlannerState;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * Mouse handling for the Road Planner, driven from the client tick (vanilla never sees the buttons).
 * <p>Right-click: on the ground places a node, on a road inserts one, on a node a tap selects or connects
 * it while a hold ({@link #HOLD_MS}) picks the node up to drag it; releasing drops it where you aim.
 * Sneak + right-click opens the network screen.
 * <p>Left-click: opens the settings of the node or road under the crosshair, or finishes the chain.
 * Sneak + left-click undoes on the ground; on a node or road it deletes after the same hold, with a
 * square filling around the crosshair.
 */
public final class RoadInput {
    private RoadInput() {}

    public static final long HOLD_MS = 250;

    // left button
    private static long holdStart = -1;
    private static RoadAim.Target holdTarget = RoadAim.Target.NONE;
    /** The current press already did its action (or was cancelled); ignore it until released. */
    private static boolean consumed;

    // right button
    private static long useStart = -1;
    private static RoadAim.Target useTarget = RoadAim.Target.NONE;
    private static boolean useConsumed;
    /** A node picked up with a right-click hold is dropped when the button is released. */
    private static boolean dragging;

    // Button state from the mouse events themselves, so a click shorter than a tick is not lost.
    private static boolean attackDown, useDown, attackLatch, useLatch;
    private static boolean attackWasDown, useWasDown;

    /** Called from the client's mouse button event, before the game handles it. */
    public static void onMouseButton(Minecraft mc, int button, int action) {
        boolean press = action == org.lwjgl.glfw.GLFW.GLFW_PRESS, release = action == org.lwjgl.glfw.GLFW.GLFW_RELEASE;
        if (!press && !release) return;
        if (mc.options.keyAttack.matchesMouse(button)) { attackDown = press; if (press) attackLatch = true; }
        if (mc.options.keyUse.matchesMouse(button)) { useDown = press; if (press) useLatch = true; }
    }

    /** 0..1 while a hold (delete or pick-up) is in progress, otherwise -1. */
    public static float holdProgress() {
        long start = holdStart >= 0 ? holdStart : useStart;
        if (start < 0) return -1;
        return Math.min(1f, (System.currentTimeMillis() - start) / (float) HOLD_MS);
    }

    /** True while the hold in progress is a delete (red square), false for a pick-up (white square). */
    public static boolean holdIsDelete() { return holdStart >= 0; }

    public static boolean dragging() { return dragging; }

    public static void tick(Minecraft mc, boolean active) {
        tickAttack(mc, active);
        tickUse(mc, active);
    }

    private static void tickAttack(Minecraft mc, boolean active) {
        boolean pressed = attackLatch && !attackWasDown;
        attackLatch = false;
        boolean down = active && mc.screen == null && (attackDown || pressed);
        attackWasDown = attackDown;
        if (!down) { holdStart = -1; consumed = false; return; }
        if (pressed && !consumed) { press(mc); if (!attackDown) { holdStart = -1; consumed = false; } return; }
        if (holdStart < 0) return;
        if (!RoadAim.target.same(holdTarget)) { holdStart = -1; consumed = true; return; }
        if (System.currentTimeMillis() - holdStart >= HOLD_MS) {
            holdStart = -1;
            consumed = true;
            delete(mc, holdTarget);
        }
    }

    private static void tickUse(Minecraft mc, boolean active) {
        boolean pressed = useLatch && !useWasDown;
        useLatch = false;
        boolean down = active && mc.screen == null && (useDown || pressed);
        useWasDown = useDown;
        if (!down) {
            if (useStart >= 0 && !useConsumed) {
                // Released before the hold: a plain tap. On a node: select / connect; on a road: insert a node.
                if (useTarget.kind() == RoadAim.Kind.ROAD) ClientActions.sendRoadEdit(RoadEdit.at(RoadEdit.Op.NODE_INSERT, useTarget.id(), useTarget.x(), useTarget.y(), useTarget.z()));
                else ClientActions.sendRoadEdit(RoadEdit.of(RoadEdit.Op.NODE_CLICK, useTarget.id()));
            } else if (dragging) {
                drop(mc);
            }
            useStart = -1;
            useConsumed = false;
            dragging = false;
            return;
        }
        if (pressed && !useConsumed && useStart < 0 && !dragging) {
            rightPress(mc);
            if (!useDown && useStart >= 0 && !useConsumed) {
                // Pressed and released within one tick: a tap.
                if (useTarget.kind() == RoadAim.Kind.ROAD) ClientActions.sendRoadEdit(RoadEdit.at(RoadEdit.Op.NODE_INSERT, useTarget.id(), useTarget.x(), useTarget.y(), useTarget.z()));
                else ClientActions.sendRoadEdit(RoadEdit.of(RoadEdit.Op.NODE_CLICK, useTarget.id()));
                useStart = -1; useConsumed = false;
            }
            return;
        }
        if (useStart < 0) return;
        boolean movedOff = !RoadAim.target.same(useTarget);
        if (movedOff || System.currentTimeMillis() - useStart >= HOLD_MS) {
            ItemStack stack = RoadPlannerItem.activeStack(mc.player);
            RoadNetwork net = stack == null ? null : RoadClientCache.named(RoadPlannerItem.getState(stack).network());
            useStart = -1;
            useConsumed = true;
            if (net == null || !RoadAim.canEdit(mc, net)) { if (net != null) overlay("curvegen.road.read_only"); return; }
            if (useTarget.kind() == RoadAim.Kind.ROAD && net.links().containsKey(useTarget.id())) {
                // Held on a road: insert a node there and pick it up right away. Its id is the network's next id.
                ClientActions.sendRoadEdit(RoadEdit.at(RoadEdit.Op.NODE_INSERT, useTarget.id(), useTarget.x(), useTarget.y(), useTarget.z()));
                RoadAim.movingNode = net.nextId();
                RoadAim.movingPending = true;
                dragging = true;
                overlay("curvegen.road.moving", net.nextId());
            } else if (useTarget.kind() == RoadAim.Kind.NODE && net.nodes().containsKey(useTarget.id())) {
                RoadAim.movingNode = useTarget.id();
                dragging = true;
                overlay("curvegen.road.moving", useTarget.id());
            }
        }
    }

    private static void rightPress(Minecraft mc) {
        useConsumed = true;
        if (mc.player.isShiftKeyDown()) { mc.setScreen(new com.aleksalfi.curvegen.client.gui.RoadNetworkScreen()); return; }
        ItemStack stack = RoadPlannerItem.activeStack(mc.player);
        if (stack == null) return;
        RoadNetwork net = RoadClientCache.named(RoadPlannerItem.getState(stack).network());
        RoadAim.Target t = RoadAim.target;
        if (net == null) { mc.setScreen(new com.aleksalfi.curvegen.client.gui.RoadNetworkScreen()); return; }
        switch (RoadAim.action) {
            case NO_NETWORK -> mc.setScreen(new com.aleksalfi.curvegen.client.gui.RoadNetworkScreen());
            case NOTHING -> overlay(t.kind() == RoadAim.Kind.NONE ? "curvegen.msg.no_target" : "curvegen.road.move_aim");
            case READ_ONLY -> {
                if (t.kind() == RoadAim.Kind.NODE) { useStart = System.currentTimeMillis(); useTarget = t; useConsumed = false; } // may still select
                else overlay("curvegen.road.read_only");
            }
            case PLACE -> ClientActions.sendRoadEdit(RoadEdit.at(RoadEdit.Op.NODE_ADD, -1, t.x(), t.y(), t.z()));
            case CONNECT, SELECT, DESELECT -> { useStart = System.currentTimeMillis(); useTarget = t; useConsumed = false; }
            case INSERT -> { useStart = System.currentTimeMillis(); useTarget = t; useConsumed = false; }
            case MOVE -> drop(mc);
        }
    }

    /** Drops the node being moved where the crosshair points, or leaves it where it was. */
    private static void drop(Minecraft mc) {
        int id = RoadAim.movingNode;
        RoadAim.Target t = RoadAim.target;
        if (id >= 0 && RoadAim.action == RoadAim.Action.MOVE) {
            ClientActions.sendRoadEdit(RoadEdit.at(RoadEdit.Op.NODE_MOVE, id, t.x(), t.y(), t.z()));
        } else if (id >= 0) {
            overlay("curvegen.road.move_cancelled");
        }
        RoadAim.movingNode = -1;
    }

    private static void press(Minecraft mc) {
        consumed = true;
        ItemStack stack = RoadPlannerItem.held(mc.player);
        if (stack == null) return;
        RoadPlannerState state = RoadPlannerItem.getState(stack);
        RoadNetwork net = RoadClientCache.named(state.network());
        RoadAim.Target t = RoadAim.target;
        if (RoadAim.movingNode >= 0) {
            RoadAim.movingNode = -1;
            overlay("curvegen.road.move_cancelled");
            return;
        }
        if (net == null) return;
        boolean sneak = mc.player.isShiftKeyDown();
        if (sneak) {
            switch (t.kind()) {
                case NODE, ROAD -> {
                    if (!RoadAim.canEdit(mc, net)) { overlay("curvegen.road.read_only"); return; }
                    holdStart = System.currentTimeMillis();
                    holdTarget = t;
                }
                default -> ClientActions.sendRoadEdit(RoadEdit.of(RoadEdit.Op.UNDO));
            }
            return;
        }
        switch (t.kind()) {
            case NODE -> mc.setScreen(new RoadNodeScreen(t.id()));
            case ROAD -> mc.setScreen(new RoadLinkScreen(t.id()));
            default -> {
                if (net.nodes().containsKey(state.selectedNode())) {
                    ClientActions.sendRoad(RoadActionPayload.Action.DESELECT);
                    overlay("curvegen.road.deselected");
                }
            }
        }
    }

    private static void delete(Minecraft mc, RoadAim.Target t) {
        switch (t.kind()) {
            case NODE -> { ClientActions.sendRoadEdit(RoadEdit.of(RoadEdit.Op.NODE_DELETE, t.id())); overlay("curvegen.road.node_deleted", t.id()); }
            case ROAD -> { ClientActions.sendRoadEdit(RoadEdit.of(RoadEdit.Op.LINK_DELETE, t.id())); overlay("curvegen.road.link_deleted", t.id()); }
            default -> {}
        }
    }

    static void overlay(String key, Object... args) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.translatable(key, args), true);
    }
}
