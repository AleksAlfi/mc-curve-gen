package com.aleksalfi.curvegen.compat;

import com.aleksalfi.curvegen.CurveGen;
import com.aleksalfi.curvegen.ModRegistry;
import com.aleksalfi.curvegen.build.SchematicWriter;
import com.simibubi.create.AllDataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Touches Create classes directly; only loaded when Create is present (see {@link CreateCompat}).
 */
final class CreateSchematicHooks {
    private CreateSchematicHooks() {}

    private record CacheEntry(long modified, Optional<BlockPos> anchor, long checkedAt) {}
    /** How long a cached answer is trusted before the file is looked at again. */
    private static final long RECHECK_MS = 5000;
    private static final Map<Path, CacheEntry> CACHE = new ConcurrentHashMap<>();

    static boolean isSchematic(ItemStack stack) {
        return stack.has(AllDataComponents.SCHEMATIC_FILE) && stack.has(AllDataComponents.SCHEMATIC_OWNER);
    }

    static void deployHeld(ServerPlayer player, boolean verbose) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (!isSchematic(stack)) continue;
            Optional<BlockPos> anchor = anchorFor(stack);
            if (anchor.isEmpty()) {
                if (verbose) player.displayClientMessage(Component.translatable("curvegen.msg.not_curve_schematic"), false);
                return;
            }
            deploy(stack, anchor.get());
            player.displayClientMessage(Component.translatable("curvegen.msg.deployed",
                    stack.get(AllDataComponents.SCHEMATIC_FILE), anchor.get().toShortString()), false);
            return;
        }
        if (verbose) player.displayClientMessage(Component.translatable("curvegen.msg.no_schematic_held"), false);
    }

    /** Deploys an exported schematic the first time it is seen; later re-positioning by the player is left alone. */
    static void tryAutoDeploy(ServerPlayer player, ItemStack stack) {
        if (!isSchematic(stack)) return;
        if (Boolean.TRUE.equals(stack.get(ModRegistry.AUTO_DEPLOYED.get()))) return;
        if (Boolean.TRUE.equals(stack.get(AllDataComponents.SCHEMATIC_DEPLOYED))) return;
        Optional<BlockPos> anchor = anchorFor(stack);
        if (anchor.isEmpty()) return;
        deploy(stack, anchor.get());
        player.displayClientMessage(Component.translatable("curvegen.msg.deployed",
                stack.get(AllDataComponents.SCHEMATIC_FILE), anchor.get().toShortString()), false);
    }

    /** Checks every inventory slot, so schematics are deployed before they even reach the player's hand. */
    static void scanInventory(ServerPlayer player) {
        for (ItemStack stack : player.getInventory().items) if (!stack.isEmpty()) tryAutoDeploy(player, stack);
        for (ItemStack stack : player.getInventory().offhand) if (!stack.isEmpty()) tryAutoDeploy(player, stack);
    }

    private static void deploy(ItemStack stack, BlockPos anchor) {
        stack.set(ModRegistry.AUTO_DEPLOYED.get(), true);
        stack.set(AllDataComponents.SCHEMATIC_DEPLOYED, true);
        stack.set(AllDataComponents.SCHEMATIC_ANCHOR, anchor);
        stack.set(AllDataComponents.SCHEMATIC_ROTATION, Rotation.NONE);
        stack.set(AllDataComponents.SCHEMATIC_MIRROR, Mirror.NONE);
        com.simibubi.create.content.schematics.SchematicInstances.clearHash(stack);
    }

    /** Reads the anchor stored by {@link SchematicWriter} from the uploaded copy of the schematic file. */
    private static Optional<BlockPos> anchorFor(ItemStack stack) {
        String owner = stack.get(AllDataComponents.SCHEMATIC_OWNER);
        String file = stack.get(AllDataComponents.SCHEMATIC_FILE);
        if (owner == null || file == null || !file.endsWith(".nbt")) return Optional.empty();
        Path dir = CreateCompat.uploadedSchematicsDir();
        Path path = dir.resolve(owner).resolve(file).normalize();
        if (!path.startsWith(dir)) return Optional.empty();
        long now = System.currentTimeMillis();
        CacheEntry cached = CACHE.get(path);
        if (cached != null && now - cached.checkedAt < RECHECK_MS) return cached.anchor;
        long modified;
        try {
            modified = Files.isRegularFile(path) ? Files.getLastModifiedTime(path).toMillis() : -1;
        } catch (IOException e) {
            modified = -1;
        }
        if (modified < 0) {
            CACHE.put(path, new CacheEntry(-1, Optional.empty(), now));
            return Optional.empty();
        }
        if (cached != null && cached.modified == modified) {
            CACHE.put(path, new CacheEntry(modified, cached.anchor, now));
            return cached.anchor;
        }
        Optional<BlockPos> anchor = readAnchor(path);
        CACHE.put(path, new CacheEntry(modified, anchor, now));
        return anchor;
    }

    private static Optional<BlockPos> readAnchor(Path path) {
        try {
            CompoundTag root = NbtIo.readCompressed(path, NbtAccounter.create(536870912L));
            if (!root.contains(SchematicWriter.ROOT_TAG)) return Optional.empty();
            ListTag a = root.getCompound(SchematicWriter.ROOT_TAG).getList("anchor", 3);
            if (a.size() != 3) return Optional.empty();
            return Optional.of(new BlockPos(a.getInt(0), a.getInt(1), a.getInt(2)));
        } catch (IOException | RuntimeException e) {
            CurveGen.LOGGER.warn("Could not read schematic {}", path, e);
            return Optional.empty();
        }
    }
}
