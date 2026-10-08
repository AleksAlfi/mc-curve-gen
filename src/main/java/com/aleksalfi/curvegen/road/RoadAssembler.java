package com.aleksalfi.curvegen.road;

import com.aleksalfi.curvegen.build.BlockPlan;
import com.aleksalfi.curvegen.build.BlockStates;
import com.aleksalfi.curvegen.build.CopycatSupport;
import com.aleksalfi.curvegen.build.PlannedBlock;
import com.aleksalfi.curvegen.plan.PlanLimits;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/** Turns painted road cells into blocks (asphalt, lines, raised curbs and sidewalks, copycat edges). */
public final class RoadAssembler {
    private RoadAssembler() {}

    private record Palette(BlockState asphalt, BlockState line, BlockState curb, BlockState sidewalk,
                           BlockState curbMat, BlockState sidewalkMat, BlockState asphaltMat, BlockState lineMat) {}

    public static BlockPlan assemble(CellMap cells, BlockGetter level) {
        BlockPlan out = new BlockPlan();
        boolean layers = CopycatSupport.available();
        Map<String, Palette> palettes = new HashMap<>();
        for (Map.Entry<Long, RoadPainter.Cell> e : cells.entries()) {
            if (out.size() >= PlanLimits.MAX_PLAN_BLOCKS) {
                out.warn("Road network has more than " + PlanLimits.MAX_PLAN_BLOCKS + " blocks; the rest was skipped.");
                break;
            }
            RoadPainter.Cell c = e.getValue();
            if (c.surface == Surface.NONE) continue;
            int x = RoadPainter.keyX(e.getKey()), z = RoadPainter.keyZ(e.getKey());
            Palette p = palettes.computeIfAbsent(c.cls.id(), id -> palette(c.cls, level, layers, out));
            BlockState block = switch (c.surface) {
                case LINE -> p.line();
                case CURB -> p.curb();
                case SIDEWALK, ISLAND -> p.sidewalk();
                default -> p.asphalt();
            };
            BlockState material = switch (c.surface) {
                case LINE -> p.lineMat();
                case CURB -> p.curbMat();
                case SIDEWALK, ISLAND -> p.sidewalkMat();
                default -> p.asphaltMat();
            };
            int top = (int) Math.floor(c.height + 1e-6);
            int up = layers && material != null ? (int) Math.round((c.height - top) * 8) : 0;
            if (up == 0 && !(layers && material != null)) top = (int) Math.round(c.height);
            if (c.surface.raised() && layers && material != null) up += c.cls.curbLayers();
            while (up >= 8) { top++; up -= 8; }

            boolean full = c.coverage >= 15.0 / 16;
            if (!full) {
                int side = (int) Math.round(c.coverage * 8);
                if (c.cls.smoothEdges() && layers && material != null && side > 0 && side < 8) {
                    Direction facing = facing(c.ox, c.oz);
                    out.put(new BlockPos(x, top - 1, z), new PlannedBlock(CopycatSupport.layer(facing, side), CopycatSupport.materialNbt(material), material));
                    continue; // no raised layer on a partial edge column
                }
                if (c.coverage < 0.5) continue;
            }
            out.put(new BlockPos(x, top - 1, z), PlannedBlock.of(block));
            if (up > 0) {
                out.put(new BlockPos(x, top, z), new PlannedBlock(CopycatSupport.layer(Direction.UP, up), CopycatSupport.materialNbt(material), material));
            }
        }
        return out;
    }

    private static Palette palette(RoadClass c, BlockGetter level, boolean layers, BlockPlan out) {
        BlockState asphalt = parse(c.asphalt(), "asphalt", c, out);
        BlockState line = parse(c.line(), "line", c, out);
        BlockState curb = parse(c.curb(), "curb", c, out);
        BlockState sidewalk = parse(c.sidewalk(), "sidewalk", c, out);
        return new Palette(asphalt, line, curb, sidewalk,
                material(curb, level, layers), material(sidewalk, level, layers), material(asphalt, level, layers), material(line, level, layers));
    }

    private static BlockState parse(String s, String what, RoadClass c, BlockPlan out) {
        BlockState state = BlockStates.parse(s);
        if (state == null) {
            out.warn("Road class '" + c.name() + "': unknown " + what + " block '" + s + "', using stone.");
            return Blocks.STONE.defaultBlockState();
        }
        return state;
    }

    private static BlockState material(BlockState state, BlockGetter level, boolean layers) {
        return layers && CopycatSupport.isValidMaterial(level, state) ? state : null;
    }

    private static Direction facing(double ox, double oz) {
        if (Math.abs(ox) >= Math.abs(oz)) return ox >= 0 ? Direction.EAST : Direction.WEST;
        return oz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
