package com.aleksalfi.curvegen.build;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.fml.ModList;

/**
 * Builds Create: Copycats+ "Copycat Layers" states and block entity data without a compile-time
 * dependency on Copycats+. The layer block uses the vanilla {@code facing} (exposed face) and
 * {@code layers} (1-8 eighths) properties; the material lives in the block entity.
 */
public final class CopycatSupport {
    private CopycatSupport() {}

    public static final ResourceLocation LAYER_BLOCK = ResourceLocation.fromNamespaceAndPath("copycats", "copycat_layer");
    public static final String BLOCK_ENTITY_ID = "copycats:copycat";
    private static final TagKey<Block> COPYCAT_ALLOW = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("create", "copycat_allow"));
    private static final TagKey<Block> COPYCAT_DENY = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("create", "copycat_deny"));

    public static boolean available() {
        return ModList.get().isLoaded("copycats") && BuiltInRegistries.BLOCK.containsKey(LAYER_BLOCK);
    }

    public static Block layerBlock() {
        return BuiltInRegistries.BLOCK.get(LAYER_BLOCK);
    }

    public static boolean isLayer(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).equals(LAYER_BLOCK);
    }

    /** @param facing the exposed face; layers are attached to the opposite face. */
    public static BlockState layer(Direction facing, int layers) {
        BlockState s = layerBlock().defaultBlockState();
        int l = Math.max(1, Math.min(8, layers));
        if (s.hasProperty(BlockStateProperties.FACING)) s = s.setValue(BlockStateProperties.FACING, facing);
        if (s.hasProperty(BlockStateProperties.LAYERS)) s = s.setValue(BlockStateProperties.LAYERS, l);
        return s;
    }

    public static CompoundTag materialNbt(BlockState material) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", BLOCK_ENTITY_ID);
        tag.put("Material", NbtUtils.writeBlockState(material));
        Item item = material.getBlock().asItem();
        if (item instanceof BlockItem) {
            CompoundTag stack = new CompoundTag();
            stack.putString("id", BuiltInRegistries.ITEM.getKey(item).toString());
            stack.putInt("count", 1);
            tag.put("Item", stack);
        }
        return tag;
    }

    /**
     * Mirrors Create's rules for which blocks a copycat accepts as material. Like Create, the shape checks
     * run on the block's default state (the one its item places), not on the requested state.
     */
    public static boolean isValidMaterial(BlockGetter level, BlockState state) {
        Block block = state.getBlock();
        if (!(block.asItem() instanceof BlockItem bi) || bi.getBlock() != block) return false;
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
        if (key.getNamespace().equals("copycats")) return false;
        if (key.getNamespace().equals("create") && key.getPath().startsWith("copycat")) return false;
        BlockState def = block.defaultBlockState();
        if (def.is(COPYCAT_ALLOW)) return true;
        if (def.is(COPYCAT_DENY)) return false;
        if (block instanceof EntityBlock || block instanceof StairBlock) return false;
        try {
            VoxelShape shape = def.getShape(level, BlockPos.ZERO);
            if (shape.isEmpty() || !shape.bounds().equals(Shapes.block().bounds())) return false;
            return !def.getCollisionShape(level, BlockPos.ZERO).isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
