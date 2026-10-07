package com.aleksalfi.curvegen.build;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** Parsing and formatting of block state strings such as {@code minecraft:oak_stairs[facing=north]}. */
public final class BlockStates {
    private BlockStates() {}

    @Nullable
    public static BlockState parse(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), text.trim(), false).blockState();
        } catch (CommandSyntaxException | IllegalArgumentException e) {
            return null;
        }
    }

    public static String serialize(BlockState state) {
        return BlockStateParser.serialize(state);
    }

    public static String id(Block block) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
        return key.toString();
    }
}
