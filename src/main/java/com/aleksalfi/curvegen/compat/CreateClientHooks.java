package com.aleksalfi.curvegen.compat;

import com.aleksalfi.curvegen.ModRegistry;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.CreateClient;
import com.simibubi.create.content.schematics.client.SchematicHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * Client side: Create's schematic handler only re-reads a held schematic when the file name changes, so
 * when the server deploys the item while it is already in hand we tell the handler about it. The hook acts
 * only when the item's own components change (never because Create's live transformation differs from the
 * item, which happens legitimately while the player moves the schematic with Create's tools).
 * Only loaded when Create is present.
 */
public final class CreateClientHooks {
    private CreateClientHooks() {}

    private static String appliedFile;
    private static BlockPos appliedAnchor;

    public static void refresh() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        SchematicHandler handler = CreateClient.SCHEMATIC_HANDLER;
        if (!handler.isActive()) return;
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = mc.player.getItemInHand(hand);
            if (!stack.has(AllDataComponents.SCHEMATIC_FILE)) continue;
            if (!Boolean.TRUE.equals(stack.get(ModRegistry.AUTO_DEPLOYED.get()))) return;
            if (!Boolean.TRUE.equals(stack.get(AllDataComponents.SCHEMATIC_DEPLOYED))) return;
            String file = stack.get(AllDataComponents.SCHEMATIC_FILE);
            BlockPos anchor = stack.get(AllDataComponents.SCHEMATIC_ANCHOR);
            if (anchor == null) return;
            boolean itemChanged = !file.equals(appliedFile) || !anchor.equals(appliedAnchor);
            if (!itemChanged && handler.isDeployed()) return;
            appliedFile = file;
            appliedAnchor = anchor;
            // deploy() builds the full tool list when the handler still thinks the item is undeployed;
            // loadSettings() then takes the anchor from the item and deploy() sets up the renderer for it.
            if (!handler.isDeployed()) handler.deploy();
            handler.loadSettings(stack);
            handler.deploy();
            return;
        }
    }
}
