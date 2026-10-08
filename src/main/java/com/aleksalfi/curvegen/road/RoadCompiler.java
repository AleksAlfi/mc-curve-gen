package com.aleksalfi.curvegen.road;

import com.aleksalfi.curvegen.build.BlockPlan;
import com.aleksalfi.curvegen.geom.Polyline;
import com.aleksalfi.curvegen.geom.Rasterizer;
import net.minecraft.world.level.BlockGetter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Network → chains → painted cells → blocks. Safe to call on any thread. */
public final class RoadCompiler {
    private RoadCompiler() {}

    public record Result(BlockPlan blocks, List<Polyline> centerlines, RoadNetwork network) {}

    public static Result compile(RoadNetwork network, BlockGetter level) {
        List<RoadChain> chains = RoadGeometry.chains(network);
        List<Polyline> lines = new ArrayList<>();
        double total = 0;
        for (RoadChain c : chains) { lines.add(c.line()); total += c.length(); }
        BlockPlan blocks;
        if (total > com.aleksalfi.curvegen.plan.PlanLimits.MAX_PATH_LENGTH) {
            blocks = new BlockPlan();
            blocks.warn("Road network is too long (" + (long) total + " blocks of road). Split it into several networks.");
        } else {
            try {
                Map<Long, RoadPainter.Cell> cells = RoadPainter.paint(network, chains);
                blocks = RoadAssembler.assemble(cells, level);
            } catch (Rasterizer.PlanTooLargeException e) {
                blocks = new BlockPlan();
                blocks.warn("Road network covers too many columns (" + e.columns + "). Split it into several networks.");
            }
        }
        return new Result(blocks, lines, network);
    }
}
