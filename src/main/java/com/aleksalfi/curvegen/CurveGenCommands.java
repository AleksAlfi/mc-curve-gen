package com.aleksalfi.curvegen;

import com.aleksalfi.curvegen.build.BlockPlan;
import com.aleksalfi.curvegen.build.PlanCompiler;
import com.aleksalfi.curvegen.build.WorldPlacer;
import com.aleksalfi.curvegen.compat.CreateCompat;
import com.aleksalfi.curvegen.item.CurvePlannerItem;
import com.aleksalfi.curvegen.network.Networking;
import com.aleksalfi.curvegen.plan.CurvePlan;
import com.aleksalfi.curvegen.plan.ArcMode;
import com.aleksalfi.curvegen.plan.BezierKind;
import com.aleksalfi.curvegen.plan.Heading;
import com.aleksalfi.curvegen.plan.PlanPoint;
import com.aleksalfi.curvegen.plan.SBendStyle;
import com.aleksalfi.curvegen.plan.SegmentSpec;
import com.aleksalfi.curvegen.plan.SegmentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /curvegen} commands for precise point entry and scripting. All of them act on the planner the
 * player is holding, exactly like clicking with it.
 */
public final class CurveGenCommands {
    private CurveGenCommands() {}

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("curvegen")
                .then(Commands.literal("point").then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(ctx -> point(ctx, BlockPosArgument.getBlockPos(ctx, "pos")))))
                .then(Commands.literal("undo").executes(ctx -> edit(ctx, CurvePlan::undoPoint, "curvegen.cmd.undone")))
                .then(Commands.literal("removesegment").executes(ctx -> edit(ctx, CurvePlan::removeLastSegment, "curvegen.cmd.segment_removed")))
                .then(Commands.literal("clear").executes(ctx -> edit(ctx, CurvePlan::clearPath, "curvegen.cmd.cleared")))
                .then(Commands.literal("finish").executes(ctx -> edit(ctx, CurvePlan::finishDraft, "curvegen.cmd.finished")))
                .then(enumCommand("type", SegmentType.values(), (ctx, v) -> option(ctx, seg -> seg.withType((SegmentType) v))))
                .then(enumCommand("arcmode", ArcMode.values(), (ctx, v) -> option(ctx, seg -> seg.withArcMode((ArcMode) v))))
                .then(enumCommand("bezier", BezierKind.values(), (ctx, v) -> option(ctx, seg -> seg.withBezierKind((BezierKind) v))))
                .then(enumCommand("sbend", SBendStyle.values(), (ctx, v) -> option(ctx, seg -> seg.withSBendStyle((SBendStyle) v))))
                .then(enumCommand("heading", Heading.values(), (ctx, v) -> option(ctx, seg -> seg.withHeading((Heading) v))))
                .then(Commands.literal("radius").then(Commands.argument("value", DoubleArgumentType.doubleArg(0.5))
                        .executes(ctx -> option(ctx, seg -> seg.withRadius(DoubleArgumentType.getDouble(ctx, "value"))))))
                .then(Commands.literal("turnleft").then(Commands.argument("value", BoolArgumentType.bool())
                        .executes(ctx -> option(ctx, seg -> seg.withTurnLeft(BoolArgumentType.getBool(ctx, "value"))))))
                .then(Commands.literal("smoothjoin").then(Commands.argument("value", BoolArgumentType.bool())
                        .executes(ctx -> option(ctx, seg -> seg.withSmoothJoin(BoolArgumentType.getBool(ctx, "value"))))))
                .then(Commands.literal("align")
                        .then(Commands.literal("start").then(Commands.argument("value", BoolArgumentType.bool())
                                .executes(ctx -> option(ctx, seg -> seg.withAlignStart(BoolArgumentType.getBool(ctx, "value"))))))
                        .then(Commands.literal("end").then(Commands.argument("value", BoolArgumentType.bool())
                                .executes(ctx -> option(ctx, seg -> seg.withAlignEnd(BoolArgumentType.getBool(ctx, "value")))))))
                .then(Commands.literal("place").executes(CurveGenCommands::place))
                .then(Commands.literal("undoplace").executes(CurveGenCommands::undoPlace))
                .then(Commands.literal("deploy").executes(ctx -> {
                    CreateCompat.deployHeldSchematic(ctx.getSource().getPlayerOrException(), true);
                    return 1;
                })));
    }

    private interface EnumAction { int run(CommandContext<CommandSourceStack> ctx, Enum<?> value) throws com.mojang.brigadier.exceptions.CommandSyntaxException; }

    /** {@code /curvegen <name> <constant>} with one lower-case literal per enum constant, e.g. {@code /curvegen type spline}. */
    private static <E extends Enum<E>> com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> enumCommand(String name, E[] values, EnumAction action) {
        com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(name);
        for (E v : values) root.then(Commands.literal(v.name().toLowerCase(java.util.Locale.ROOT)).executes(ctx -> action.run(ctx, v)));
        return root;
    }

    /** Applies an option change to the draft segment (same as the options screen). */
    private static int option(CommandContext<CommandSourceStack> ctx, java.util.function.UnaryOperator<SegmentSpec> op) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ItemStack stack = planner(ctx);
        if (stack == null) return 0;
        CurvePlan plan = CurvePlannerItem.getPlan(stack);
        CurvePlan next = plan.withDraftOptions(op.apply(plan.draft()));
        CurvePlannerItem.setPlan(stack, next);
        ctx.getSource().sendSuccess(() -> Component.literal(com.aleksalfi.curvegen.plan.SegmentDescriber.describe(next.draft())), false);
        return 1;
    }

    private static ItemStack planner(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ItemStack stack = CurvePlannerItem.held(player);
        if (stack == null) {
            ctx.getSource().sendFailure(Component.translatable("curvegen.cmd.hold_planner"));
            return null;
        }
        return stack;
    }

    /** Adds a point whose road surface block is {@code pos} itself (a click on the top face of the block below). */
    private static int point(CommandContext<CommandSourceStack> ctx, BlockPos pos) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ItemStack stack = planner(ctx);
        if (stack == null) return 0;
        CurvePlan plan = CurvePlannerItem.getPlan(stack).addPoint(new PlanPoint(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5));
        CurvePlannerItem.setPlan(stack, plan);
        String label = plan.draft().nextClickLabel(plan.draftIsFirst(), plan.hasPreviousTangent());
        ctx.getSource().sendSuccess(() -> label == null
                ? Component.translatable("curvegen.msg.segment_done", plan.segments().size())
                : Component.translatable("curvegen.msg.next_click", label), false);
        return 1;
    }

    private static int edit(CommandContext<CommandSourceStack> ctx, java.util.function.UnaryOperator<CurvePlan> op, String key) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ItemStack stack = planner(ctx);
        if (stack == null) return 0;
        CurvePlannerItem.setPlan(stack, op.apply(CurvePlannerItem.getPlan(stack)));
        ctx.getSource().sendSuccess(() -> Component.translatable(key), false);
        return 1;
    }

    private static int place(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ItemStack stack = planner(ctx);
        if (stack == null) return 0;
        if (!Networking.mayPlace(player)) {
            ctx.getSource().sendFailure(Component.translatable("curvegen.msg.no_permission"));
            return 0;
        }
        BlockPlan blocks = PlanCompiler.compile(CurvePlannerItem.getPlan(stack), player.serverLevel()).blocks();
        if (blocks.isEmpty()) {
            ctx.getSource().sendFailure(Component.translatable("curvegen.msg.nothing_to_place"));
            return 0;
        }
        if (blocks.size() > WorldPlacer.MAX_BLOCKS) {
            ctx.getSource().sendFailure(Component.translatable("curvegen.msg.too_many", blocks.size(), WorldPlacer.MAX_BLOCKS));
            return 0;
        }
        WorldPlacer.Report report = WorldPlacer.place(player.serverLevel(), blocks, player, player.getUUID());
        ctx.getSource().sendSuccess(() -> Component.translatable("curvegen.msg.placed", report.placed(), report.skippedUnloaded()), false);
        if (report.itemsReturned() > 0) ctx.getSource().sendSuccess(() -> Networking.returnedMessage(player, report.itemsReturned()), false);
        for (String w : blocks.warnings()) ctx.getSource().sendSuccess(() -> Component.literal("§e" + w), false);
        return report.placed();
    }

    private static int undoPlace(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!Networking.mayPlace(player)) {
            ctx.getSource().sendFailure(Component.translatable("curvegen.msg.no_permission"));
            return 0;
        }
        WorldPlacer.Report report = WorldPlacer.undo(player.server, player, player.getUUID());
        if (report == null) {
            ctx.getSource().sendFailure(Component.translatable("curvegen.msg.nothing_to_undo"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("curvegen.msg.undone", report.placed()), false);
        if (report.itemsReturned() > 0) ctx.getSource().sendSuccess(() -> Networking.returnedMessage(player, report.itemsReturned()), false);
        return report.placed();
    }
}
