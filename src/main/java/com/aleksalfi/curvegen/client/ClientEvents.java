package com.aleksalfi.curvegen.client;

import com.aleksalfi.curvegen.CurveGen;
import com.aleksalfi.curvegen.client.gui.PlannerScreen;
import com.aleksalfi.curvegen.client.render.PreviewManager;
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
    /** Whether the attack key was down at the end of the previous tick (edge detection for left-click undo). */
    public static boolean attackHeldLastTick;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            PreviewManager.clear();
            return;
        }
        while (CurveGenClient.OPEN_PLANNER.consumeClick()) {
            if (CurvePlannerItem.held(mc.player) != null && mc.screen == null) mc.setScreen(new PlannerScreen());
        }
        while (CurveGenClient.TOGGLE_PREVIEW.consumeClick()) previewEnabled = !previewEnabled;
        ItemStack held = CurvePlannerItem.held(mc.player);
        PreviewManager.update(held == null ? null : CurvePlannerItem.getPlan(held), mc.level);
        if (CreateCompat.isLoaded()) CreateClientHooks.refresh();
        attackHeldLastTick = mc.options.keyAttack.isDown();
    }

    @SubscribeEvent
    public static void onLogin(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingIn event) {
        PreviewManager.clear();
        com.aleksalfi.curvegen.client.gui.BlockPickerScreen.resetCache();
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (!previewEnabled) return;
        PreviewRenderer.render(event);
    }

    @SubscribeEvent
    public static void onLeftClickEmpty(PlayerInteractEvent.LeftClickEmpty event) {
        if (event.getEntity().getMainHandItem().getItem() instanceof CurvePlannerItem) ClientHooks.sendUndoPoint();
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.screen != null || mc.getDebugOverlay().showDebugScreen()) return;
        ItemStack held = CurvePlannerItem.held(mc.player);
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
