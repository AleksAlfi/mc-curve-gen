package com.aleksalfi.curvegen.item;

import com.aleksalfi.curvegen.ModRegistry;
import com.aleksalfi.curvegen.road.RoadPlannerState;
import com.aleksalfi.curvegen.road.RoadService;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.List;

/** Places and links road nodes; sneak + right-click opens the road screens. */
public class RoadPlannerItem extends Item {
    public RoadPlannerItem(Properties properties) { super(properties); }

    public static RoadPlannerState getState(ItemStack stack) {
        RoadPlannerState s = stack.get(ModRegistry.ROAD_STATE.get());
        return s == null ? RoadPlannerState.DEFAULT : s;
    }

    public static void setState(ItemStack stack, RoadPlannerState state) { stack.set(ModRegistry.ROAD_STATE.get(), state); }

    public static ItemStack held(Player player) {
        ItemStack main = player.getMainHandItem();
        if (main.getItem() instanceof RoadPlannerItem) return main;
        ItemStack off = player.getOffhandItem();
        if (off.getItem() instanceof RoadPlannerItem) return off;
        return null;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || player.isShiftKeyDown()) return InteractionResult.PASS;
        if (player instanceof ServerPlayer sp) {
            BlockPos target = context.getClickedPos().relative(context.getClickedFace());
            RoadService.click(sp, context.getItemInHand(), target.getX() + 0.5, target.getY() + 1, target.getZ() + 0.5);
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide());
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isShiftKeyDown()) {
            if (level.isClientSide()) com.aleksalfi.curvegen.client.ClientHooks.openRoadPlanner(stack);
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }
        if (player instanceof ServerPlayer sp) {
            HitResult hit = CurvePlannerItem.pickLoaded(player, CurvePlannerItem.LONG_RANGE);
            if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult bhr) {
                BlockPos target = bhr.getBlockPos().relative(bhr.getDirection());
                RoadService.click(sp, stack, target.getX() + 0.5, target.getY() + 1, target.getZ() + 0.5);
                return InteractionResultHolder.success(stack);
            }
            player.displayClientMessage(Component.translatable("curvegen.msg.no_target"), true);
            return InteractionResultHolder.pass(stack);
        }
        return InteractionResultHolder.sidedSuccess(stack, true);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        RoadPlannerState s = getState(stack);
        tooltip.add(Component.translatable("curvegen.road.tooltip.network", s.network().isEmpty() ? "-" : s.network()).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("curvegen.road.tooltip.help1").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("curvegen.road.tooltip.help2").withStyle(ChatFormatting.DARK_GRAY));
    }
}
