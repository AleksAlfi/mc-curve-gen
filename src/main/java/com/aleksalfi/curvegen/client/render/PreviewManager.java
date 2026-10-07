package com.aleksalfi.curvegen.client.render;

import com.aleksalfi.curvegen.CurveGen;
import com.aleksalfi.curvegen.build.CopycatSupport;
import com.aleksalfi.curvegen.build.PlanCompiler;
import com.aleksalfi.curvegen.plan.CurvePlan;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Keeps the compiled preview of the held planner up to date, compiling off-thread. */
public final class PreviewManager {
    private PreviewManager() {}

    public record Stats(int blocks, int layers, List<String> warnings) {}

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "curvegen-preview");
        t.setDaemon(true);
        return t;
    });

    private static CompletableFuture<PlanCompiler.Result> pending;
    private static CurvePlan pendingPlan;
    @Nullable private static PlanCompiler.Result result;
    @Nullable private static CurvePlan resultPlan;
    @Nullable private static PreviewMesh mesh;
    private static Stats stats = new Stats(0, 0, List.of());

    public static void update(@Nullable CurvePlan plan, Level level) {
        if (plan == null) {
            clear();
            return;
        }
        CurvePlan key = plan.geometryKey();
        if (pending != null && pending.isDone()) {
            // Whatever happens below, this plan counts as handled so a failure is not retried every tick.
            resultPlan = pendingPlan;
            try {
                PlanCompiler.Result r = pending.join();
                PreviewMesh built = PreviewMesh.build(r, pendingPlan);
                if (mesh != null) mesh.close();
                mesh = built;
                result = r;
                int layers = 0;
                boolean copycats = CopycatSupport.available();
                for (var b : r.blocks().blocks().values()) if (copycats && CopycatSupport.isLayer(b.state())) layers++;
                List<String> warnings = new java.util.ArrayList<>(r.blocks().warnings());
                if (r.blocks().size() > PreviewMesh.MAX_BLOCKS) warnings.add("Preview shows only the first " + PreviewMesh.MAX_BLOCKS + " blocks.");
                stats = new Stats(r.blocks().size(), layers, List.copyOf(warnings));
            } catch (RuntimeException e) {
                CurveGen.LOGGER.error("Preview compile failed", e);
                if (mesh != null) { mesh.close(); mesh = null; }
                result = null;
                stats = new Stats(0, 0, List.of("Preview failed: " + e.getMessage()));
            }
            pending = null;
        }
        if (pending == null && !key.equals(resultPlan)) {
            pendingPlan = key;
            pending = CompletableFuture.supplyAsync(() -> PlanCompiler.compile(key, EmptyBlockGetter.INSTANCE), EXECUTOR);
        }
    }

    public static void clear() {
        if (pending != null) pending.cancel(true);
        pending = null;
        pendingPlan = null;
        resultPlan = null;
        result = null;
        if (mesh != null) { mesh.close(); mesh = null; }
        stats = new Stats(0, 0, List.of());
    }

    @Nullable public static PreviewMesh mesh() { return mesh; }
    @Nullable public static PlanCompiler.Result result() { return result; }
    public static Stats stats() { return stats; }

    /** Blocking compile for the plan, reusing the cached result when it matches. */
    public static PlanCompiler.Result compileNow(CurvePlan plan, Level level) {
        if (result != null && plan.geometryKey().equals(resultPlan)) return result;
        return PlanCompiler.compile(plan, level);
    }
}
