package com.aleksalfi.curvegen.compat;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

/** Entry points that are safe to call whether or not Create is installed. */
public final class CreateCompat {
    private CreateCompat() {}

    public static boolean isLoaded() {
        return ModList.get().isLoaded("create");
    }

    /** Client-side folder Create reads schematics from (the Schematic Table lists files in here). */
    public static Path schematicsDir() {
        return FMLPaths.GAMEDIR.get().resolve("schematics");
    }

    /** Server-side folder Create stores uploaded schematics in, per player name. */
    public static Path uploadedSchematicsDir() {
        return schematicsDir().resolve("uploaded");
    }

    /** Deploys the Create schematic the player holds if it was exported by this mod. */
    public static void deployHeldSchematic(ServerPlayer player, boolean verbose) {
        if (!isLoaded()) {
            if (verbose) player.displayClientMessage(net.minecraft.network.chat.Component.translatable("curvegen.msg.create_missing"), false);
            return;
        }
        CreateSchematicHooks.deployHeld(player, verbose);
    }

    /** Called when a player's hand item changes; auto-deploys freshly written schematics. */
    public static void onHandItemChanged(ServerPlayer player, ItemStack stack) {
        if (!isLoaded() || stack.isEmpty()) return;
        CreateSchematicHooks.tryAutoDeploy(player, stack);
    }

    /** Periodic server-side check of the whole inventory (every few ticks). */
    public static void onPlayerTick(ServerPlayer player) {
        if (!isLoaded()) return;
        CreateSchematicHooks.scanInventory(player);
    }
}
