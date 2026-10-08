package com.aleksalfi.curvegen.client;

import com.aleksalfi.curvegen.CurveGen;
import com.aleksalfi.curvegen.client.gui.PlannerScreen;
import com.aleksalfi.curvegen.client.render.CurvePreview;
import com.aleksalfi.curvegen.client.render.PreviewManager;
import com.aleksalfi.curvegen.client.render.RoadPreview;
import com.aleksalfi.curvegen.item.RoadPlannerItem;
import com.aleksalfi.curvegen.road.RoadNetwork;
import com.aleksalfi.curvegen.road.RoadNode;
import com.aleksalfi.curvegen.road.RoadPlannerState;
import net.minecraft.world.level.EmptyBlockGetter;
import com.aleksalfi.curvegen.client.render.PreviewRenderer;
import com.aleksalfi.curvegen.compat.CreateClientHooks;
import com.aleksalfi.curvegen.compat.CreateCompat;
import com.aleksalfi.curvegen.item.CurvePlannerItem;
import com.aleksalfi.curvegen.plan.CurvePlan;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.ArrayList;
import java.util.List;

@EventBusSubscriber(modid = CurveGen.MOD_ID, value = Dist.CLIENT)
public final class ClientEvents {
    private ClientEvents() {}

    public static boolean previewEnabled = true;
    /** Whether the attack key was down at the end of the previous tick (edge detection for left clicks). */
    public static boolean attackHeldLastTick;
    /** The road planner is the active tool: in the main hand, or in the off hand with an empty main hand. */
    public static boolean roadActive(Minecraft mc) {
        return mc.player != null && RoadPlannerItem.activeStack(mc.player) != null;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            PreviewManager.clear();
            return;
        }
        while (CurveGenClient.OPEN_PLANNER.consumeClick()) {
            if (mc.screen != null) continue;
            if (RoadPlannerItem.activeStack(mc.player) != null) {
                ClientHooks.openRoadPlanner(RoadPlannerItem.activeStack(mc.player));
            } else if (CurvePlannerItem.held(mc.player) != null) {
                mc.setScreen(new PlannerScreen());
            }
        }
        while (CurveGenClient.TOGGLE_PREVIEW.consumeClick()) previewEnabled = !previewEnabled;
        updatePreview(mc);
        boolean road = roadActive(mc);
        if (road) {
            ItemStack stack = RoadPlannerItem.activeStack(mc.player);
            RoadPlannerState state = RoadPlannerItem.getState(stack);
            RoadAim.tick(mc, RoadClientCache.named(state.network()), state);
        } else {
            RoadAim.clear();
        }
        RoadInput.tick(mc, road);
        if (CreateCompat.isLoaded()) CreateClientHooks.refresh();
        attackHeldLastTick = mc.options.keyAttack.isDown();
        useHeldLastTick = mc.options.keyUse.isDown();
    }

    /** Whether the use key was down at the end of the previous tick (edge detection for right clicks). */
    public static boolean useHeldLastTick;

    @SubscribeEvent
    public static void onMouseButton(net.neoforged.neoforge.client.event.InputEvent.MouseButton.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || !roadActive(mc)) return;
        RoadInput.onMouseButton(mc, event.getButton(), event.getAction());
    }

    /** With the road planner, neither mouse button reaches vanilla (no block breaking, placing or using); {@link RoadInput} handles both. */
    @SubscribeEvent
    public static void onClickInput(net.neoforged.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isAttack() && !event.isUseItem()) return;
        Minecraft mc = Minecraft.getInstance();
        if (!roadActive(mc)) return;
        event.setCanceled(true);
        event.setSwingHand(false);
    }

    /** The main hand decides which planner is previewed; the off hand only counts when the main hand holds none. */
    private static void updatePreview(Minecraft mc) {
        ItemStack main = mc.player.getMainHandItem(), off = mc.player.getOffhandItem();
        ItemStack road = main.getItem() instanceof RoadPlannerItem ? main : off.getItem() instanceof RoadPlannerItem ? off : null;
        ItemStack curve = main.getItem() instanceof CurvePlannerItem ? main : off.getItem() instanceof CurvePlannerItem ? off : null;
        if (road != null && road == RoadPlannerItem.activeStack(mc.player)) {
            RoadPlannerState state = RoadPlannerItem.getState(road);
            RoadNetwork net = RoadClientCache.named(state.network());
            if (net == null) { PreviewManager.clear(); return; }
            RoadPreview.Key key = new RoadPreview.Key(net, state.selectedNode());
            PreviewManager.update(key, () -> RoadPreview.compile(net, state.selectedNode(), EmptyBlockGetter.INSTANCE));
        } else if (curve != null) {
            CurvePlan plan = CurvePlannerItem.getPlan(curve).geometryKey();
            PreviewManager.update(plan, () -> CurvePreview.compile(plan, EmptyBlockGetter.INSTANCE));
        } else {
            PreviewManager.clear();
        }
    }

    @SubscribeEvent
    public static void onLogin(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingIn event) {
        PreviewManager.clear();
        RoadClientCache.clear();
        com.aleksalfi.curvegen.client.gui.BlockPickerScreen.resetCache();
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (previewEnabled) PreviewRenderer.render(event);
        if (roadActive(Minecraft.getInstance())) com.aleksalfi.curvegen.client.render.AimRenderer.render(event);
    }

    @SubscribeEvent
    public static void onLeftClickEmpty(PlayerInteractEvent.LeftClickEmpty event) {
        if (event.getEntity().getMainHandItem().getItem() instanceof CurvePlannerItem) ClientHooks.sendUndoPoint();
    }

    private static void roadHud(Minecraft mc, GuiGraphics g, ItemStack stack) {
        RoadPlannerState state = RoadPlannerItem.getState(stack);
        RoadNetwork net = RoadClientCache.named(state.network());
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("curvegen.road.hud.title").withStyle(ChatFormatting.AQUA));
        if (net == null) {
            lines.add(Component.translatable("curvegen.road.hud.no_network").withStyle(ChatFormatting.YELLOW));
        } else {
            lines.add(Component.translatable("curvegen.road.hud.network", net.name(), net.ownerName(), net.nodes().size(), net.links().size()));
            RoadNode sel = net.nodes().get(state.selectedNode());
            lines.add(Component.translatable("curvegen.road.hud.selected", sel == null ? "-" : sel.id() + " (" + describeNode(net, sel) + ")").withStyle(ChatFormatting.YELLOW));
            PreviewManager.Stats stats = PreviewManager.stats();
            lines.add(Component.translatable("curvegen.road.hud.stats", stats.blocks(), stats.layers()));
            for (String w : stats.warnings()) lines.add(Component.literal(w).withStyle(ChatFormatting.GOLD));
            if (!RoadAim.canEdit(mc, net)) lines.add(Component.translatable("curvegen.road.hud.view_only").withStyle(ChatFormatting.RED));
            lines.addAll(aimLines(net, state));
        }
        if (!previewEnabled) lines.add(Component.translatable("curvegen.hud.preview_off").withStyle(ChatFormatting.RED));
        lines.add(Component.translatable("curvegen.road.hud.help").withStyle(ChatFormatting.DARK_GRAY));
        int y = 4;
        for (Component c : lines) { g.drawString(mc.font, c, 4, y, 0xFFFFFF, true); y += 10; }
    }

    /** What the crosshair points at and what each button does there. */
    private static List<Component> aimLines(RoadNetwork net, RoadPlannerState state) {
        List<Component> out = new ArrayList<>();
        RoadAim.Target t = RoadAim.target;
        RoadAim.Action a = RoadAim.action;
        int sel = state.selectedNode();
        if (RoadAim.movingNode >= 0) {
            out.add(Component.translatable("curvegen.road.hud.moving", RoadAim.movingNode).withStyle(ChatFormatting.AQUA));
            out.add(Component.translatable(a == RoadAim.Action.MOVE ? "curvegen.road.hud.moving_help" : "curvegen.road.hud.moving_aim").withStyle(ChatFormatting.GRAY));
            return out;
        }
        switch (t.kind()) {
            case NODE -> {
                RoadNode n = net.nodes().get(t.id());
                if (n != null) out.add(Component.translatable("curvegen.road.hud.at_node", n.id(), describeNode(net, n)).withStyle(ChatFormatting.WHITE));
                String rmb = switch (a) {
                    case CONNECT -> Component.translatable("curvegen.road.hud.rmb_connect", sel, t.id()).getString();
                    case DESELECT -> Component.translatable("curvegen.road.hud.rmb_finish").getString();
                    case READ_ONLY -> Component.translatable("curvegen.road.hud.rmb_select", t.id()).getString();
                    default -> Component.translatable("curvegen.road.hud.rmb_select", t.id()).getString();
                };
                out.add(Component.translatable("curvegen.road.hud.node_help", rmb, t.id()).withStyle(ChatFormatting.GRAY));
            }
            case ROAD -> {
                com.aleksalfi.curvegen.road.RoadLink l = net.links().get(t.id());
                if (l != null) out.add(Component.translatable("curvegen.road.hud.at_road", l.id(), net.classOf(l).name() + (l.oneWay() ? " one-way " + l.from() + "→" + l.to() : ""), l.a(), l.b()).withStyle(ChatFormatting.WHITE));
                out.add(Component.translatable("curvegen.road.hud.road_help", t.id()).withStyle(ChatFormatting.GRAY));
            }
            case GROUND -> {
                String rmb = net.nodes().containsKey(sel)
                        ? Component.translatable("curvegen.road.hud.rmb_place_linked", sel).getString()
                        : Component.translatable("curvegen.road.hud.rmb_place").getString();
                if (t.snapped()) rmb += Component.translatable("curvegen.road.hud.snapped").getString();
                out.add(Component.translatable("curvegen.road.hud.ground_help", rmb,
                        Component.translatable(net.nodes().containsKey(sel) ? "curvegen.road.hud.lmb_finish" : "curvegen.road.hud.lmb_nothing").getString()).withStyle(ChatFormatting.GRAY));
            }
            default -> out.add(Component.translatable("curvegen.road.hud.aim_ground").withStyle(ChatFormatting.GRAY));
        }
        return out;
    }

    /** The square that fills clockwise around the crosshair while a hold is in progress: red for delete, white for pick-up. */
    private static void holdSquare(Minecraft mc, GuiGraphics g, float progress, int color) {
        int cx = g.guiWidth() / 2, cy = g.guiHeight() / 2;
        int h = 9, t = 2; // half side, thickness
        int side = 2 * h;
        // top: left → right, right: top → bottom, bottom: right → left, left: bottom → top
        int[] len = new int[4];
        for (int i = 0; i < 4; i++) len[i] = Math.round(side * Math.max(0f, Math.min(1f, progress * 4 - i)));
        if (len[0] > 0) g.fill(cx - h, cy - h, cx - h + len[0], cy - h + t, color);
        if (len[1] > 0) g.fill(cx + h - t, cy - h, cx + h, cy - h + len[1], color);
        if (len[2] > 0) g.fill(cx + h - len[2], cy + h - t, cx + h, cy + h, color);
        if (len[3] > 0) g.fill(cx - h, cy + h - len[3], cx - h + t, cy + h, color);
    }

    public static String describeNode(RoadNetwork net, RoadNode n) {
        if (n.kind() == com.aleksalfi.curvegen.road.NodeKind.ROUNDABOUT) return "roundabout r" + (int) n.roundaboutRadius();
        com.aleksalfi.curvegen.road.Merge merge = com.aleksalfi.curvegen.road.Merge.at(net, n);
        if (merge != null) return (merge.entry() ? "entry ramp onto " : "exit ramp from ") + merge.highway().name();
        if (com.aleksalfi.curvegen.road.Split.at(net, n) != null) return "split into one-way roads";
        int deg = net.degree(n.id());
        if (deg >= 3) return "junction, " + deg + " arms";
        if (deg == 2) return n.corner() == com.aleksalfi.curvegen.road.CornerStyle.SMOOTH ? "smooth" : "fillet r" + (int) n.filletRadius();
        return deg == 1 ? "end" : "unlinked";
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.screen != null || mc.getDebugOverlay().showDebugScreen()) return;
        ItemStack held = CurvePlannerItem.held(mc.player);
        ItemStack roadHeld = RoadPlannerItem.activeStack(mc.player);
        if (roadHeld != null) {
            roadHud(mc, event.getGuiGraphics(), roadHeld);
            float hold = RoadInput.holdProgress();
            if (hold >= 0) holdSquare(mc, event.getGuiGraphics(), hold, RoadInput.holdIsDelete() ? 0xFFFF4040 : 0xFFFFFFFF);
            return;
        }
        if (held == null) return;
        CurvePlan plan = CurvePlannerItem.getPlan(held);
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("curvegen.hud.title").withStyle(ChatFormatting.AQUA));
        lines.add(Component.translatable("curvegen.hud.segment", PlannerScreen.describeSegment(plan.draft())));
        String next = plan.draft().nextClickLabel(plan.draftIsFirst(), plan.hasPreviousTangent());
        lines.add(Component.translatable("curvegen.hud.next", next == null ? "-" : next).withStyle(ChatFormatting.YELLOW));
        PreviewManager.Stats stats = PreviewManager.stats();
        lines.add(Component.translatable("curvegen.hud.stats", plan.segments().size(), stats.blocks(), stats.layers()));
        if (!previewEnabled) lines.add(Component.translatable("curvegen.hud.preview_off").withStyle(ChatFormatting.RED));
        for (String w : stats.warnings()) lines.add(Component.literal(w).withStyle(ChatFormatting.GOLD));
        lines.add(Component.translatable("curvegen.hud.help").withStyle(ChatFormatting.GRAY));
        GuiGraphics g = event.getGuiGraphics();
        int y = 4;
        for (Component c : lines) {
            g.drawString(mc.font, c, 4, y, 0xFFFFFF, true);
            y += 10;
        }
    }
}
