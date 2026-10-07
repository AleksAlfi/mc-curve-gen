package com.aleksalfi.curvegen.network;

import com.aleksalfi.curvegen.build.BlockPlan;
import com.aleksalfi.curvegen.build.PlanCompiler;
import com.aleksalfi.curvegen.build.WorldPlacer;
import com.aleksalfi.curvegen.compat.CreateCompat;
import com.aleksalfi.curvegen.item.CurvePlannerItem;
import com.aleksalfi.curvegen.plan.CurvePlan;
import com.aleksalfi.curvegen.plan.PlanLimits;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class Networking {
    private Networking() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(UpdatePlanPayload.TYPE, UpdatePlanPayload.STREAM_CODEC, Networking::handleUpdate);
        registrar.playToServer(PlannerActionPayload.TYPE, PlannerActionPayload.STREAM_CODEC, Networking::handleAction);
    }

    private static void handleUpdate(UpdatePlanPayload payload, IPayloadContext ctx) {
        Player player = ctx.player();
        ItemStack stack = CurvePlannerItem.held(player);
        if (stack == null) return;
        // The screen only edits options: keep the server's segments and draft points so a click that is still
        // in flight is never overwritten by the screen's older copy of the plan.
        CurvePlan incoming = PlanLimits.sanitize(payload.plan());
        CurvePlan current = CurvePlannerItem.getPlan(stack);
        CurvePlan merged = current.withProfile(incoming.profile()).withSchematicName(incoming.schematicName())
                .withDraftOptions(incoming.draft());
        CurvePlannerItem.setPlan(stack, PlanLimits.sanitize(merged));
    }

    private static void handleAction(PlannerActionPayload payload, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) return;
        ItemStack stack = CurvePlannerItem.held(player);
        switch (payload.action()) {
            case UNDO_POINT -> { if (stack != null) CurvePlannerItem.setPlan(stack, CurvePlannerItem.getPlan(stack).undoPoint()); }
            case REMOVE_SEGMENT -> { if (stack != null) CurvePlannerItem.setPlan(stack, CurvePlannerItem.getPlan(stack).removeLastSegment()); }
            case CLEAR -> { if (stack != null) CurvePlannerItem.setPlan(stack, CurvePlannerItem.getPlan(stack).clearPath()); }
            case PLACE -> { if (stack != null) place(player, stack); }
            case UNDO_PLACE -> undoPlace(player);
            case DEPLOY_SCHEMATIC -> CreateCompat.deployHeldSchematic(player, true);
            case FINISH_SEGMENT -> { if (stack != null) CurvePlannerItem.setPlan(stack, CurvePlannerItem.getPlan(stack).finishDraft()); }
            case NONE -> {}
        }
    }

    public static boolean mayPlace(ServerPlayer player) {
        return player.isCreative() || player.hasPermissions(2);
    }

    private static void place(ServerPlayer player, ItemStack stack) {
        if (!mayPlace(player)) {
            player.displayClientMessage(Component.translatable("curvegen.msg.no_permission"), false);
            return;
        }
        CurvePlan plan = CurvePlannerItem.getPlan(stack);
        BlockPlan blocks = PlanCompiler.compile(plan, player.serverLevel()).blocks();
        if (blocks.isEmpty()) {
            player.displayClientMessage(Component.translatable("curvegen.msg.nothing_to_place"), false);
            return;
        }
        if (blocks.size() > WorldPlacer.MAX_BLOCKS) {
            player.displayClientMessage(Component.translatable("curvegen.msg.too_many", blocks.size(), WorldPlacer.MAX_BLOCKS), false);
            return;
        }
        WorldPlacer.Report report = WorldPlacer.place(player.serverLevel(), blocks, player, player.getUUID());
        player.displayClientMessage(Component.translatable("curvegen.msg.placed", report.placed(), report.skippedUnloaded()), false);
        if (report.itemsReturned() > 0) player.displayClientMessage(returnedMessage(player, report.itemsReturned()), false);
        if (report.containersReplaced() > 0) player.displayClientMessage(Component.translatable("curvegen.msg.containers", report.containersReplaced()), false);
        for (String w : blocks.warnings()) player.displayClientMessage(Component.literal("§e" + w), false);
    }

    private static void undoPlace(ServerPlayer player) {
        if (!mayPlace(player)) {
            player.displayClientMessage(Component.translatable("curvegen.msg.no_permission"), false);
            return;
        }
        WorldPlacer.Report report = WorldPlacer.undo(player.server, player, player.getUUID());
        if (report == null) {
            player.displayClientMessage(Component.translatable("curvegen.msg.nothing_to_undo"), false);
            return;
        }
        player.displayClientMessage(Component.translatable("curvegen.msg.undone", report.placed()), false);
        if (report.itemsReturned() > 0) player.displayClientMessage(returnedMessage(player, report.itemsReturned()), false);
    }

    public static Component returnedMessage(ServerPlayer player, int items) {
        return player.isCreative() ? Component.translatable("curvegen.msg.items_discarded", items)
                : Component.translatable("curvegen.msg.items_returned", items);
    }
}
