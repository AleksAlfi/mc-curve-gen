package com.aleksalfi.curvegen.build;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The set of blocks a plan produces, keyed by world position. */
public final class BlockPlan {
    private final Map<BlockPos, PlannedBlock> blocks = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
    private int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

    public void put(BlockPos pos, PlannedBlock block) {
        blocks.put(pos.immutable(), block);
        minX = Math.min(minX, pos.getX()); minY = Math.min(minY, pos.getY()); minZ = Math.min(minZ, pos.getZ());
        maxX = Math.max(maxX, pos.getX()); maxY = Math.max(maxY, pos.getY()); maxZ = Math.max(maxZ, pos.getZ());
    }

    public void warn(String message) {
        if (!warnings.contains(message)) warnings.add(message);
    }

    public Map<BlockPos, PlannedBlock> blocks() { return Collections.unmodifiableMap(blocks); }
    public List<String> warnings() { return Collections.unmodifiableList(warnings); }
    public boolean isEmpty() { return blocks.isEmpty(); }
    public int size() { return blocks.size(); }
    public BlockPos min() { return isEmpty() ? BlockPos.ZERO : new BlockPos(minX, minY, minZ); }
    public BlockPos max() { return isEmpty() ? BlockPos.ZERO : new BlockPos(maxX, maxY, maxZ); }
}
