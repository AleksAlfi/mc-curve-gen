package com.aleksalfi.curvegen.build;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Places a block plan directly into a server level (creative / operator use) with per-player undo. */
public final class WorldPlacer {
    private WorldPlacer() {}

    public static final int MAX_BLOCKS = 250_000;
    private static final int MAX_UNDO = 8;

    public record Snapshot(BlockPos pos, BlockState state, @Nullable CompoundTag blockEntity) {}
    public record UndoEntry(ResourceKey<Level> dimension, List<Snapshot> previous) {}
    /**
     * @param itemsReturned number of items that blocks dropped while being replaced and that went to the player
     * @param containersReplaced non-empty containers that were paved over (their contents live in the undo entry)
     */
    public record Report(int placed, int skippedUnloaded, int itemsReturned, int containersReplaced) {}

    private static final Map<UUID, Deque<UndoEntry>> UNDO = new ConcurrentHashMap<>();
    /** Item stacks spawned by blocks being replaced in the current operation on this thread (see {@link #onEntityJoin}). */
    private static final ThreadLocal<List<ItemStack>> CAPTURE = new ThreadLocal<>();

    /**
     * Blocks such as Create's copycats pop their stored material when replaced. While a placement or undo
     * runs, every item entity that would spawn is intercepted here and handed to the player instead.
     */
    private static Level captureLevel;

    public static void onEntityJoin(EntityJoinLevelEvent event) {
        List<ItemStack> capture = CAPTURE.get();
        if (capture == null || event.getLevel() != captureLevel || event.loadedFromDisk()) return;
        if (event.getEntity() instanceof ItemEntity item) {
            capture.add(item.getItem().copy());
            event.setCanceled(true);
        }
    }

    /** Clears the undo history, e.g. when the server stops (entries reference dimensions of that server). */
    public static void clearAll() { UNDO.clear(); }

    public static Report place(ServerLevel level, BlockPlan plan, @Nullable ServerPlayer player, UUID owner) {
        List<Snapshot> previous = new ArrayList<>(plan.size());
        int placed = 0, skipped = 0, containers = 0;
        List<ItemStack> captured = new ArrayList<>();
        // Snapshot every position before anything changes, so multi-block structures (doors, double chests)
        // are recorded intact even when replacing one half alters the other.
        List<Map.Entry<BlockPos, PlannedBlock>> todo = new ArrayList<>(plan.size());
        for (Map.Entry<BlockPos, PlannedBlock> e : plan.blocks().entrySet()) {
            BlockPos pos = e.getKey();
            if (!level.isLoaded(pos) || level.isOutsideBuildHeight(pos)) { skipped++; continue; }
            previous.add(snapshot(level, pos));
            todo.add(e);
        }
        CAPTURE.set(captured);
        captureLevel = level;
        try {
            for (Map.Entry<BlockPos, PlannedBlock> e : todo) {
                BlockPos pos = e.getKey();
                // The snapshot keeps the old block entity's contents, so empty it before replacing to avoid
                // spilling (and later duplicating) them.
                if (neutralize(level, pos)) containers++;
                apply(level, pos, e.getValue().state(), e.getValue().blockEntity());
                placed++;
            }
        } finally {
            CAPTURE.remove();
            captureLevel = null;
            if (!previous.isEmpty()) {
                Deque<UndoEntry> stack = UNDO.computeIfAbsent(owner, k -> new ArrayDeque<>());
                stack.push(new UndoEntry(level.dimension(), previous));
                while (stack.size() > MAX_UNDO) stack.removeLast();
            }
        }
        return new Report(placed, skipped, deliver(player, merge(captured)), containers);
    }

    /** Restores the blocks replaced by the player's last placement, or returns null when there is nothing to undo. */
    @Nullable
    public static Report undo(MinecraftServer server, @Nullable ServerPlayer player, UUID owner) {
        Deque<UndoEntry> stack = UNDO.get(owner);
        if (stack == null || stack.isEmpty()) return null;
        UndoEntry entry = stack.pop();
        ServerLevel level = server.getLevel(entry.dimension());
        if (level == null) return new Report(0, entry.previous().size(), 0, 0);
        int n = 0, skipped = 0;
        List<ItemStack> captured = new ArrayList<>();
        CAPTURE.set(captured);
        captureLevel = level;
        try {
            for (Snapshot s : entry.previous()) {
                if (!level.isLoaded(s.pos())) { skipped++; continue; }
                apply(level, s.pos(), s.state(), s.blockEntity());
                n++;
            }
        } finally {
            CAPTURE.remove();
            captureLevel = null;
        }
        return new Report(n, skipped, deliver(player, merge(captured)), 0);
    }

    /**
     * Detaches the block entity that is about to be replaced so the block's onRemove finds nothing to drop
     * (no spilled containers, no copycat material pop, no furnace experience). The snapshot already holds
     * the full block entity for the undo. Inventories are never touched, so multi-block ones such as double
     * chests or item vaults keep the half that is not paved over. Returns true when the entity held items.
     */
    private static boolean neutralize(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return false;
        boolean hadItems = false;
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler != null) {
            for (int i = 0; i < handler.getSlots() && !hadItems; i++) hadItems = !handler.getStackInSlot(i).isEmpty();
        }
        level.removeBlockEntity(pos);
        return hadItems;
    }

    private static List<ItemStack> merge(@Nullable List<ItemStack> items) {
        List<ItemStack> out = new ArrayList<>();
        if (items == null) return out;
        for (ItemStack s : items) {
            boolean merged = false;
            for (ItemStack o : out) {
                if (ItemStack.isSameItemSameComponents(o, s) && o.getCount() < o.getMaxStackSize()) {
                    int move = Math.min(s.getCount(), o.getMaxStackSize() - o.getCount());
                    o.grow(move);
                    s.shrink(move);
                    if (s.isEmpty()) { merged = true; break; }
                }
            }
            if (!merged && !s.isEmpty()) out.add(s.copy());
        }
        return out;
    }

    /**
     * Creative players simply discard the items; survival players get them in their inventory and whatever
     * does not fit is dropped at their feet, exactly like {@code /give}. Returns the number of items handled.
     */
    private static int deliver(@Nullable ServerPlayer player, List<ItemStack> items) {
        int total = 0;
        for (ItemStack s : items) total += s.getCount();
        if (player == null || player.isCreative() || items.isEmpty()) return total;
        for (ItemStack stack : items) {
            ItemStack remaining = stack.copy();
            player.getInventory().add(remaining);
            if (!remaining.isEmpty()) {
                ItemEntity dropped = player.drop(remaining, false);
                if (dropped != null) {
                    dropped.setNoPickUpDelay();
                    dropped.setTarget(player.getUUID());
                }
            }
        }
        player.containerMenu.broadcastChanges();
        return total;
    }

    private static Snapshot snapshot(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        return new Snapshot(pos.immutable(), level.getBlockState(pos), be == null ? null : be.saveWithFullMetadata(level.registryAccess()));
    }

    private static void apply(ServerLevel level, BlockPos pos, BlockState state, @Nullable CompoundTag nbt) {
        level.setBlock(pos, state, Block.UPDATE_ALL);
        if (nbt != null) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null) {
                CompoundTag tag = nbt.copy();
                tag.putInt("x", pos.getX());
                tag.putInt("y", pos.getY());
                tag.putInt("z", pos.getZ());
                be.loadWithComponents(tag, level.registryAccess());
                be.setChanged();
                level.sendBlockUpdated(pos, state, state, Block.UPDATE_ALL);
            }
        }
    }
}
