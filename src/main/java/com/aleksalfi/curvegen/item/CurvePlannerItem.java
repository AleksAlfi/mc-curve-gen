package com.aleksalfi.curvegen.item;

import com.aleksalfi.curvegen.ModRegistry;
import com.aleksalfi.curvegen.plan.CurvePlan;
import com.aleksalfi.curvegen.plan.PlanPoint;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult;

import java.util.List;

public class CurvePlannerItem extends Item {
    /** How far the planner can pick points when right-clicking at a distance. */
    public static final double LONG_RANGE = 256;

    public CurvePlannerItem(Properties properties) {
        super(properties);
    }

    public static CurvePlan getPlan(ItemStack stack) {
        CurvePlan plan = stack.get(ModRegistry.PLAN.get());
        return plan == null ? CurvePlan.DEFAULT : plan;
    }

    public static void setPlan(ItemStack stack, CurvePlan plan) {
        stack.set(ModRegistry.PLAN.get(), plan);
    }

    /** The planner stack the player is holding, main hand preferred; null if none. */
    public static ItemStack held(Player player) {
        ItemStack main = player.getMainHandItem();
        if (main.getItem() instanceof CurvePlannerItem) return main;
        ItemStack off = player.getOffhandItem();
        if (off.getItem() instanceof CurvePlannerItem) return off;
        return null;
    }

    /** World position of the road surface for a click on {@code pos}/{@code face}: the block that would be placed there. */
    public static PlanPoint pointFor(BlockPos pos, Direction face) {
        BlockPos target = pos.relative(face);
        return new PlanPoint(target.getX() + 0.5, target.getY() + 1, target.getZ() + 0.5);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;
        if (player.isShiftKeyDown()) return InteractionResult.PASS; // handled by use(): opens the screen
        if (!context.getLevel().isClientSide()) {
            addPoint(player, context.getItemInHand(), pointFor(context.getClickedPos(), context.getClickedFace()));
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide());
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isShiftKeyDown()) {
            if (level.isClientSide()) com.aleksalfi.curvegen.client.ClientHooks.openPlanner(stack);
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }
        if (!level.isClientSide()) {
            HitResult hit = pickLoaded(player, LONG_RANGE);
            if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult bhr) {
                addPoint(player, stack, pointFor(bhr.getBlockPos(), bhr.getDirection()));
                return InteractionResultHolder.success(stack);
            }
            player.displayClientMessage(Component.translatable("curvegen.msg.no_target"), true);
            return InteractionResultHolder.pass(stack);
        }
        return InteractionResultHolder.sidedSuccess(stack, true);
    }

    /**
     * Ray cast that never forces chunks to load: the range is shortened to the last loaded chunk along the
     * ray (and to the server view distance) before the vanilla clip runs.
     */
    public static HitResult pickLoaded(Player player, double range) {
        Level level = player.level();
        if (level instanceof net.minecraft.server.level.ServerLevel server) {
            range = Math.min(range, server.getServer().getPlayerList().getViewDistance() * 16.0);
        }
        Vec3 eye = player.getEyePosition(1.0F);
        Vec3 dir = player.getViewVector(1.0F);
        double usable = 0;
        for (double d = 0; d <= range; d += 4) {
            Vec3 p = eye.add(dir.scale(d));
            if (!level.hasChunkAt(BlockPos.containing(p))) break;
            usable = d;
        }
        if (usable <= 0) return BlockHitResult.miss(eye, net.minecraft.core.Direction.UP, BlockPos.containing(eye));
        return level.clip(new ClipContext(eye, eye.add(dir.scale(usable)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
    }

    private static void addPoint(Player player, ItemStack stack, PlanPoint point) {
        CurvePlan plan = getPlan(stack);
        CurvePlan next = plan.addPoint(point);
        if (next == plan) {
            player.displayClientMessage(Component.translatable("curvegen.msg.point_ignored"), true);
            return;
        }
        setPlan(stack, next);
        String label = next.draft().nextClickLabel(next.draftIsFirst(), next.hasPreviousTangent());
        Component msg = label == null
                ? Component.translatable("curvegen.msg.segment_done", next.segments().size())
                : Component.translatable("curvegen.msg.next_click", label);
        player.displayClientMessage(msg, true);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        CurvePlan plan = getPlan(stack);
        tooltip.add(Component.translatable("curvegen.tooltip.segments", plan.segments().size(),
                plan.profile().lanes().size(), fmt(plan.profile().totalWidth())).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("curvegen.tooltip.help1").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("curvegen.tooltip.help2").withStyle(ChatFormatting.DARK_GRAY));
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? Integer.toString((int) d) : String.format(java.util.Locale.ROOT, "%.2f", d);
    }
}
