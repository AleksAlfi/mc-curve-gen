package com.aleksalfi.curvegen.client.render;

import com.aleksalfi.curvegen.CurveGen;
import com.aleksalfi.curvegen.build.CopycatSupport;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/** Keeps the compiled preview of whatever planner is held up to date, compiling off-thread. */
public final class PreviewManager {
    private PreviewManager() {}

    public record Stats(int blocks, int layers, List<String> warnings) {}

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "curvegen-preview");
        t.setDaemon(true);
        return t;
    });

    private static CompletableFuture<Compiled> pending;
    private static Object pendingKey;
    @Nullable private static Compiled result;
    @Nullable private static Object resultKey;
    @Nullable private static PreviewMesh mesh;
    private static Stats stats = new Stats(0, 0, List.of());

    /**
     * @param key     identifies the input; a compile is started when it differs from the last one handled
     * @param compile runs on a worker thread
     */
    public static void update(@Nullable Object key, Supplier<Compiled> compile) {
        if (key == null) {
            clear();
            return;
        }
        if (pending != null && pending.isDone()) {
            // Whatever happens below, this key counts as handled so a failure is not retried every tick.
            resultKey = pendingKey;
            try {
                Compiled c = pending.join();
                PreviewMesh built = PreviewMesh.build(c);
                if (mesh != null) mesh.close();
                mesh = built;
                result = c;
                int layers = 0;
                boolean copycats = CopycatSupport.available();
                for (var b : c.blocks().blocks().values()) if (copycats && CopycatSupport.isLayer(b.state())) layers++;
                List<String> warnings = new ArrayList<>(c.warnings());
                if (c.blocks().size() > PreviewMesh.MAX_BLOCKS) warnings.add("Preview shows only the first " + PreviewMesh.MAX_BLOCKS + " blocks.");
                stats = new Stats(c.blocks().size(), layers, List.copyOf(warnings));
            } catch (RuntimeException e) {
                CurveGen.LOGGER.error("Preview compile failed", e);
                if (mesh != null) { mesh.close(); mesh = null; }
                result = null;
                stats = new Stats(0, 0, List.of("Preview failed: " + e.getMessage()));
            }
            pending = null;
        }
        if (pending == null && !key.equals(resultKey)) {
            pendingKey = key;
            pending = CompletableFuture.supplyAsync(compile, EXECUTOR);
        }
    }

    public static void clear() {
        if (pending != null) pending.cancel(true);
        pending = null;
        pendingKey = null;
        resultKey = null;
        result = null;
        if (mesh != null) { mesh.close(); mesh = null; }
        stats = new Stats(0, 0, List.of());
    }

    @Nullable public static PreviewMesh mesh() { return mesh; }
    public static Stats stats() { return stats; }

    /** The cached result for this key, or null when it is not the one currently shown. */
    @Nullable
    public static Compiled cached(Object key) {
        return result != null && key.equals(resultKey) ? result : null;
    }
}
