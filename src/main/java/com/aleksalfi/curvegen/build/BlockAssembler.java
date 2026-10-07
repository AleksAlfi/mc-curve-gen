package com.aleksalfi.curvegen.build;

import com.aleksalfi.curvegen.geom.Rasterizer;
import com.aleksalfi.curvegen.plan.LaneSpec;
import com.aleksalfi.curvegen.plan.PlanLimits;
import com.aleksalfi.curvegen.plan.ProfileSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Turns rasterized columns into concrete blocks, using copycat layers for smoothing where possible. */
public final class BlockAssembler {
    private BlockAssembler() {}

    /** Columns covered less than this are dropped entirely. */
    private static final double MIN_COVERAGE = 1.0 / 16;
    /** Columns covered at least this much count as full blocks. */
    private static final double FULL_COVERAGE = 15.0 / 16;

    private record Lane(BlockState block, @Nullable BlockState material) {}

    public static BlockPlan assemble(List<Rasterizer.Column> columns, ProfileSpec profile, BlockGetter level) {
        BlockPlan out = new BlockPlan();
        boolean layersAvailable = CopycatSupport.available();
        if ((profile.edgeSmoothing() || profile.slopeSmoothing()) && !layersAvailable) {
            out.warn("Create: Copycats+ is not loaded; smoothing with copycat layers is disabled.");
        }

        Lane[] lanes = new Lane[profile.lanes().size()];
        for (int i = 0; i < lanes.length; i++) {
            LaneSpec spec = profile.lanes().get(i);
            BlockState block = BlockStates.parse(spec.block());
            if (block == null) {
                out.warn("Lane " + (i + 1) + ": unknown block '" + spec.block() + "', using stone.");
                block = Blocks.STONE.defaultBlockState();
            }
            BlockState material = null;
            if (layersAvailable && (profile.edgeSmoothing() || profile.slopeSmoothing())) {
                BlockState wanted = spec.material().isBlank() ? block : BlockStates.parse(spec.material());
                if (wanted == null) {
                    out.warn("Lane " + (i + 1) + ": unknown material '" + spec.material() + "'.");
                    wanted = block;
                }
                if (CopycatSupport.isValidMaterial(level, wanted)) material = wanted;
                else out.warn("Lane " + (i + 1) + ": '" + BlockStates.serialize(wanted) + "' cannot be a copycat material; no smoothing for this lane.");
            }
            lanes[i] = new Lane(block, material);
        }
        BlockState base = profile.baseBlock().isBlank() ? null : BlockStates.parse(profile.baseBlock());
        if (base == null && !profile.baseBlock().isBlank()) out.warn("Unknown base block '" + profile.baseBlock() + "'.");
        BlockState baseMaterial = base != null && layersAvailable && CopycatSupport.isValidMaterial(level, base) ? base : null;

        int thickness = Math.max(1, profile.thickness());
        for (Rasterizer.Column c : columns) {
            if (out.size() >= PlanLimits.MAX_PLAN_BLOCKS) {
                out.warn("Plan has more than " + PlanLimits.MAX_PLAN_BLOCKS + " blocks; the rest was skipped. Split the road into smaller parts.");
                break;
            }
            if (c.coverage() < MIN_COVERAGE || c.lane() < 0 || c.lane() >= lanes.length) continue;
            Lane lane = lanes[c.lane()];
            boolean edgeLayers = profile.edgeSmoothing() && lane.material != null;
            boolean slopeLayers = profile.slopeSmoothing() && lane.material != null;

            int top;
            int layersUp = 0;
            if (slopeLayers) {
                top = (int) Math.floor(c.height() + 1e-6);
                layersUp = (int) Math.round((c.height() - top) * 8);
                if (layersUp >= 8) { top++; layersUp = 0; }
            } else {
                top = (int) Math.round(c.height());
            }

            boolean full;
            int sideLayers = 0;
            if (c.coverage() >= FULL_COVERAGE) {
                full = true;
            } else if (edgeLayers) {
                sideLayers = (int) Math.round(c.coverage() * 8);
                if (sideLayers >= 8) full = true;
                else if (sideLayers <= 0) continue;
                else full = false;
            } else {
                if (c.coverage() < 0.5) continue;
                full = true;
            }
            Direction facing = outwardFacing(c.outwardX(), c.outwardZ());

            for (int k = 1; k <= thickness; k++) {
                BlockPos pos = new BlockPos(c.x(), top - k, c.z());
                boolean surface = k == 1 || base == null;
                BlockState block = surface ? lane.block : base;
                // Base rows only get layers when the base block itself is a valid copycat material.
                BlockState material = surface ? lane.material : baseMaterial;
                if (full || material == null) {
                    out.put(pos, PlannedBlock.of(block));
                } else {
                    out.put(pos, new PlannedBlock(CopycatSupport.layer(facing, sideLayers), CopycatSupport.materialNbt(material), material));
                }
            }
            // A fractional top layer has a full footprint, so it only goes on fully covered columns (no overhang).
            if (layersUp > 0 && full) {
                BlockPos pos = new BlockPos(c.x(), top, c.z());
                out.put(pos, new PlannedBlock(CopycatSupport.layer(Direction.UP, layersUp), CopycatSupport.materialNbt(lane.material), lane.material));
            }
        }
        return out;
    }

    /** Cardinal direction closest to the outward (away from the road centre) normal: the layer's exposed face. */
    static Direction outwardFacing(double ox, double oz) {
        if (Math.abs(ox) >= Math.abs(oz)) return ox >= 0 ? Direction.EAST : Direction.WEST;
        return oz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
