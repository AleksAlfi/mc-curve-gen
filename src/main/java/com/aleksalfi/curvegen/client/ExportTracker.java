package com.aleksalfi.curvegen.client;

import com.aleksalfi.curvegen.CurveGen;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The schematic files of the last export, shown beside Create's Schematic Table so each part can be picked
 * with one click. Kept until dismissed (saved in the game folder, so it survives a restart).
 */
public final class ExportTracker {
    private ExportTracker() {}

    private static final List<String> files = new ArrayList<>();
    private static final Set<String> picked = new LinkedHashSet<>();
    private static boolean loaded;

    private static Path store() { return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("curvegen-exports.txt"); }

    private static void load() {
        if (loaded) return;
        loaded = true;
        try {
            Path f = store();
            if (!Files.exists(f)) return;
            for (String line : Files.readAllLines(f)) {
                if (line.isBlank()) continue;
                if (line.startsWith("*")) { files.add(line.substring(1)); picked.add(line.substring(1)); }
                else files.add(line);
            }
        } catch (IOException e) { CurveGen.LOGGER.warn("Could not read export list", e); }
    }

    private static void save() {
        try {
            Path f = store();
            if (files.isEmpty()) { Files.deleteIfExists(f); return; }
            Files.createDirectories(f.getParent());
            List<String> lines = new ArrayList<>();
            for (String n : files) lines.add((picked.contains(n) ? "*" : "") + n);
            Files.write(f, lines);
        } catch (IOException e) { CurveGen.LOGGER.warn("Could not save export list", e); }
    }

    public static void track(List<String> fileNames) {
        load();
        files.clear();
        picked.clear();
        files.addAll(fileNames);
        save();
    }

    public static List<String> files() { load(); return List.copyOf(files); }

    public static boolean isPicked(String file) { load(); return picked.contains(file); }

    public static void markPicked(String file) { load(); picked.add(file); save(); }

    public static void dismiss() { load(); files.clear(); picked.clear(); save(); }

    public static boolean active() { load(); return !files.isEmpty(); }
}
