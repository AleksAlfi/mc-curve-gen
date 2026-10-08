package com.aleksalfi.curvegen.build;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes a {@link BlockPlan} as a vanilla structure (.nbt) file, the format Create's schematic
 * table and schematicannon read. The file also carries a {@code curvegen} tag with the world anchor
 * so the schematic item can be deployed at the right place automatically.
 */
public final class SchematicWriter {
    private SchematicWriter() {}

    public static final String ROOT_TAG = "curvegen";

    public static CompoundTag toNbt(BlockPlan plan) {
        BlockPos min = plan.min();
        BlockPos max = plan.max();
        CompoundTag root = new CompoundTag();
        root.put("size", ints(max.getX() - min.getX() + 1, max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1));

        List<BlockState> palette = new ArrayList<>();
        Map<BlockState, Integer> paletteIndex = new HashMap<>();
        ListTag blocks = new ListTag();
        plan.blocks().forEach((pos, block) -> {
            Integer idx = paletteIndex.get(block.state());
            if (idx == null) {
                idx = palette.size();
                palette.add(block.state());
                paletteIndex.put(block.state(), idx);
            }
            CompoundTag b = new CompoundTag();
            b.put("pos", ints(pos.getX() - min.getX(), pos.getY() - min.getY(), pos.getZ() - min.getZ()));
            b.putInt("state", idx);
            if (block.blockEntity() != null) b.put("nbt", block.blockEntity().copy());
            blocks.add(b);
        });
        ListTag paletteTag = new ListTag();
        for (BlockState s : palette) paletteTag.add(NbtUtils.writeBlockState(s));
        root.put("palette", paletteTag);
        root.put("blocks", blocks);
        root.put("entities", new ListTag());
        root.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());

        CompoundTag meta = new CompoundTag();
        meta.put("anchor", ints(min.getX(), min.getY(), min.getZ()));
        meta.putInt("blocks", plan.size());
        root.put(ROOT_TAG, meta);
        return root;
    }

    /** Largest side of one schematic part; a Schematicannon only prints near its own position. */
    public static final int REGION = 128;

    /**
     * Splits a plan into parts of at most {@link #REGION} x {@link #REGION} columns on a grid aligned to the
     * plan's corner, in row-major order (north to south, west to east). Empty cells are skipped. A plan that
     * already fits comes back as a single part.
     */
    public static java.util.List<BlockPlan> split(BlockPlan plan) {
        BlockPos min = plan.min(), max = plan.max();
        if (max.getX() - min.getX() < REGION && max.getZ() - min.getZ() < REGION) return java.util.List.of(plan);
        java.util.TreeMap<Long, BlockPlan> cells = new java.util.TreeMap<>();
        for (java.util.Map.Entry<BlockPos, com.aleksalfi.curvegen.build.PlannedBlock> e : plan.blocks().entrySet()) {
            BlockPos p = e.getKey();
            long row = (p.getZ() - min.getZ()) / REGION, col = (p.getX() - min.getX()) / REGION;
            cells.computeIfAbsent(row * 100_000L + col, k -> new BlockPlan()).put(p, e.getValue());
        }
        java.util.List<BlockPlan> out = new java.util.ArrayList<>(cells.values());
        for (String w : plan.warnings()) out.get(0).warn(w);
        return out;
    }

    /** Writes the schematic and returns the file written. Picks a free name unless {@code overwrite}. */
    public static Path write(BlockPlan plan, Path dir, String name, boolean overwrite) throws IOException {
        Files.createDirectories(dir);
        String safe = sanitize(name);
        Path file = dir.resolve(safe + ".nbt");
        if (!overwrite) {
            int n = 1;
            while (Files.exists(file)) file = dir.resolve(safe + "_" + (n++) + ".nbt");
        }
        NbtIo.writeCompressed(toNbt(plan), file);
        return file;
    }

    private static final java.util.Set<String> RESERVED = java.util.Set.of("con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    public static String sanitize(String name) {
        String s = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        if (s.endsWith(".nbt")) s = s.substring(0, s.length() - 4);
        s = s.replaceAll("[^a-z0-9_\\-]+", "_");
        if (s.isBlank() || s.chars().allMatch(c -> c == '_')) return "curve";
        if (RESERVED.contains(s)) s = s + "_curve"; // Windows device names
        return s;
    }

    private static ListTag ints(int... v) {
        ListTag l = new ListTag();
        for (int i : v) l.add(IntTag.valueOf(i));
        return l;
    }
}
