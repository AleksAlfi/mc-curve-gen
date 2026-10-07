package com.aleksalfi.curvegen.build;

import com.aleksalfi.curvegen.geom.PathSampler;
import com.aleksalfi.curvegen.geom.PathSegment;
import com.aleksalfi.curvegen.geom.Polyline;
import com.aleksalfi.curvegen.geom.Rasterizer;
import com.aleksalfi.curvegen.plan.CurvePlan;
import com.aleksalfi.curvegen.plan.PathBuilder;
import com.aleksalfi.curvegen.plan.PlanLimits;
import net.minecraft.world.level.BlockGetter;

import java.util.List;

/** Runs the whole pipeline: plan → curves → polyline → columns → blocks. Safe to call on any thread. */
public final class PlanCompiler {
    private PlanCompiler() {}

    public record Result(BlockPlan blocks, Polyline centerline, List<PathSegment> segments) {}

    public static Result compile(CurvePlan plan, BlockGetter level) {
        plan = PlanLimits.sanitize(plan);
        List<PathSegment> segments = PathBuilder.build(plan);
        double length = 0;
        for (PathSegment seg : segments) length += seg.length();
        if (!(length <= PlanLimits.MAX_PATH_LENGTH)) {
            BlockPlan empty = new BlockPlan();
            empty.warn("Path is too long (" + (long) length + " blocks, limit " + (long) PlanLimits.MAX_PATH_LENGTH + "). Split the road into smaller parts.");
            return new Result(empty, new Polyline(new double[0], new double[0], new double[0]), segments);
        }
        PathSampler.Elevation elevation = switch (plan.profile().elevationMode()) {
            case FLAT -> PathSampler.Elevation.FLAT;
            case LINEAR -> PathSampler.Elevation.LINEAR;
            case SMOOTH -> PathSampler.Elevation.SMOOTH;
        };
        Polyline line = PathSampler.sample(segments, 0.5, elevation);
        double yOffset = plan.profile().yOffset();
        if (yOffset != 0) for (int i = 0; i < line.size; i++) line.y[i] += yOffset;
        List<Rasterizer.Column> columns;
        try {
            columns = Rasterizer.rasterize(line, plan.profile().laneWidths(), plan.profile().supersample(), PlanLimits.MAX_COLUMNS);
        } catch (Rasterizer.PlanTooLargeException e) {
            BlockPlan empty = new BlockPlan();
            empty.warn("Plan is too large: its bounding box covers " + e.columns + " columns (limit " + e.limit + "). Split the road into smaller parts.");
            return new Result(empty, line, segments);
        }
        BlockPlan blocks = BlockAssembler.assemble(columns, plan.profile(), level);
        return new Result(blocks, line, segments);
    }
}
