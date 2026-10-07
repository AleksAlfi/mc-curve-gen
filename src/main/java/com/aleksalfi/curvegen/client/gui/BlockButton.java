package com.aleksalfi.curvegen.client.gui;

import com.aleksalfi.curvegen.build.BlockStates;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** A button showing a block's icon and name; clicking opens the block picker. */
public class BlockButton extends Button {
    private ItemStack icon = ItemStack.EMPTY;
    private Component label;

    public BlockButton(int x, int y, int w, int h, String blockState, Component emptyLabel, OnPress onPress) {
        super(x, y, w, h, Component.empty(), onPress, DEFAULT_NARRATION);
        setBlock(blockState, emptyLabel);
    }

    public void setBlock(String blockState, Component emptyLabel) {
        BlockState state = BlockStates.parse(blockState);
        if (state == null) {
            icon = ItemStack.EMPTY;
            label = blockState == null || blockState.isBlank() ? emptyLabel : Component.literal("? " + blockState);
        } else {
            icon = new ItemStack(state.getBlock());
            label = state.getBlock().getName();
        }
        setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(blockState == null || blockState.isBlank() ? emptyLabel.getString() : blockState)));
    }

    @Override
    public void renderString(GuiGraphics g, Font font, int color) {
        int textX = getX() + 4;
        if (!icon.isEmpty()) {
            g.renderItem(icon, getX() + 3, getY() + (getHeight() - 16) / 2);
            textX += 18;
        }
        int maxW = getX() + getWidth() - 3 - textX;
        String text = font.plainSubstrByWidth(label.getString(), maxW);
        g.drawString(font, text, textX, getY() + (getHeight() - 8) / 2, color, true);
    }
}
