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
 * when the server deploys the item while it is already in hand we tell the handler about it.
 * Only loaded when Create is present.
 */
public final class CreateClientHooks {
    private CreateClientHooks() {}

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
            BlockPos anchor = stack.get(AllDataComponents.SCHEMATIC_ANCHOR);
            boolean stale = !handler.isDeployed()
                    || (anchor != null && handler.getTransformation() != null && !anchor.equals(handler.getTransformation().getAnchor()));
            if (!stale) return;
            handler.loadSettings(stack);
            handler.deploy();
            return;
        }
    }
}
