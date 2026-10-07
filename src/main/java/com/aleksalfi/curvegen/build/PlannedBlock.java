package com.aleksalfi.curvegen.build;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** A block to place: its state and, for copycat layers, the block entity data holding the material. */
public record PlannedBlock(BlockState state, @Nullable CompoundTag blockEntity, @Nullable BlockState material) {
    public static PlannedBlock of(BlockState state) { return new PlannedBlock(state, null, null); }

    /** The state whose colour/texture best represents this block in previews. */
    public BlockState displayState() { return material != null ? material : state; }
}
