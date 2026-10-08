package com.aleksalfi.curvegen.item;

import com.aleksalfi.curvegen.ModRegistry;
import com.aleksalfi.curvegen.road.RoadPlannerState;
import net.minecraft.ChatFormatting;
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

import java.util.List;

/** Places, connects and edits road nodes; sneak + right-click opens the network screen. */
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

    /**
     * The planner that right-clicks act on: the main hand one, or the off-hand one when the main hand is empty.
     * Clicks themselves are handled on the client (see {@code RoadInput}); the item never reacts to use().
     */
    public static ItemStack activeStack(Player player) {
        ItemStack main = player.getMainHandItem();
        if (main.getItem() instanceof RoadPlannerItem) return main;
        if (main.isEmpty() && player.getOffhandItem().getItem() instanceof RoadPlannerItem) return player.getOffhandItem();
        return null;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) { return InteractionResult.PASS; }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        return InteractionResultHolder.pass(player.getItemInHand(hand));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        RoadPlannerState s = getState(stack);
        tooltip.add(Component.translatable("curvegen.road.tooltip.network", s.network().isEmpty() ? "-" : s.network()).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("curvegen.road.tooltip.help1").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("curvegen.road.tooltip.help2").withStyle(ChatFormatting.DARK_GRAY));
    }
}
