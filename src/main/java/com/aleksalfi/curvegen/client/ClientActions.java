package com.aleksalfi.curvegen.client;

import com.aleksalfi.curvegen.CurveGen;
import com.aleksalfi.curvegen.build.BlockPlan;
import com.aleksalfi.curvegen.build.PlanCompiler;
import com.aleksalfi.curvegen.build.SchematicWriter;
import com.aleksalfi.curvegen.client.render.PreviewManager;
import com.aleksalfi.curvegen.compat.CreateCompat;
import com.aleksalfi.curvegen.item.CurvePlannerItem;
import com.aleksalfi.curvegen.network.PlannerActionPayload;
import com.aleksalfi.curvegen.network.UpdatePlanPayload;
import com.aleksalfi.curvegen.plan.CurvePlan;
import com.aleksalfi.curvegen.plan.PlanLimits;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.IOException;
import java.nio.file.Path;

/** Client-side actions triggered from the planner screen. */
public final class ClientActions {
    private ClientActions() {}

    /** Stores the plan on the held planner locally (instant preview) and on the server. */
    public static void syncPlan(CurvePlan plan) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        CurvePlan clean = PlanLimits.sanitize(plan);
        ItemStack stack = CurvePlannerItem.held(mc.player);
        if (stack != null) CurvePlannerItem.setPlan(stack, clean);
        PacketDistributor.sendToServer(new UpdatePlanPayload(clean));
    }

    public static void send(PlannerActionPayload.Action action) {
        PacketDistributor.sendToServer(new PlannerActionPayload(action));
    }

    public static void sendRoad(com.aleksalfi.curvegen.network.RoadActionPayload.Action action) {
        PacketDistributor.sendToServer(new com.aleksalfi.curvegen.network.RoadActionPayload(action));
    }

    public static void sendRoadEdit(com.aleksalfi.curvegen.road.RoadEdit edit) {
        PacketDistributor.sendToServer(new com.aleksalfi.curvegen.network.RoadEditPayload(edit));
    }

    /** Writes the current road network as a Create schematic. */
    public static Component exportRoad(com.aleksalfi.curvegen.road.RoadNetwork net, int selected, String name, boolean overwrite) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return Component.literal("No world");
        com.aleksalfi.curvegen.client.render.Compiled cached = PreviewManager.cached(new com.aleksalfi.curvegen.client.render.RoadPreview.Key(net, selected));
        BlockPlan blocks = cached != null ? cached.blocks() : com.aleksalfi.curvegen.road.RoadCompiler.compile(net, mc.level).blocks();
        return writeSchematic(blocks, name, overwrite);
    }

    /** Writes the plan as a Create schematic into the game's schematics folder. Returns a status line. */
    public static Component exportSchematic(CurvePlan plan, boolean overwrite) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return Component.literal("No world");
        com.aleksalfi.curvegen.client.render.Compiled cached = PreviewManager.cached(plan.geometryKey());
        BlockPlan blocks = cached != null ? cached.blocks() : PlanCompiler.compile(plan, mc.level).blocks();
        return writeSchematic(blocks, plan.schematicName(), overwrite);
    }

    private static Component writeSchematic(BlockPlan blocks, String name, boolean overwrite) {
        Minecraft mc = Minecraft.getInstance();
        if (blocks.isEmpty()) return Component.translatable("curvegen.msg.nothing_to_export").withStyle(ChatFormatting.RED);
        Path dir = CreateCompat.schematicsDir();
        try {
            java.util.List<BlockPlan> parts = SchematicWriter.split(blocks);
            java.util.List<String> written = new java.util.ArrayList<>();
            String base = SchematicWriter.sanitize(name);
            if (parts.size() == 1) {
                written.add(SchematicWriter.write(blocks, dir, base, overwrite).getFileName().toString());
            } else {
                if (!overwrite) { // a free base name for the whole set
                    String b = base; int n = 1;
                    while (java.nio.file.Files.exists(dir.resolve(b + "_p1.nbt"))) b = base + "_" + (n++);
                    base = b;
                } else { // remove parts left over from a larger earlier export
                    try (var stream = java.nio.file.Files.list(dir)) {
                        String prefix = base + "_p";
                        for (Path f : stream.filter(f -> f.getFileName().toString().matches(java.util.regex.Pattern.quote(prefix) + "\\d+\\.nbt")).toList()) java.nio.file.Files.deleteIfExists(f);
                    } catch (IOException ignored) {}
                }
                for (int i = 0; i < parts.size(); i++) written.add(SchematicWriter.write(parts.get(i), dir, base + "_p" + (i + 1), true).getFileName().toString());
            }
            long biggest = 0;
            for (String f : written) biggest = Math.max(biggest, java.nio.file.Files.size(dir.resolve(f)));
            if (biggest > 256 * 1024 && mc.player != null) {
                mc.player.displayClientMessage(Component.translatable("curvegen.msg.export_large", biggest / 1024).withStyle(ChatFormatting.GOLD), false);
            }
            ExportTracker.track(written);
            if (CreateCompat.isLoaded()) com.aleksalfi.curvegen.compat.CreateTablePanel.refreshSender();
            Component link = Component.literal(written.size() == 1 ? written.get(0) : written.get(0) + " … " + written.get(written.size() - 1)).withStyle(st -> st.withUnderlined(true)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, dir.toAbsolutePath().toString())));
            if (mc.player != null) {
                if (written.size() == 1) mc.player.displayClientMessage(Component.translatable("curvegen.msg.exported", link, blocks.size(), blocks.min().toShortString()), false);
                else mc.player.displayClientMessage(Component.translatable("curvegen.msg.exported_parts", link, blocks.size(), written.size(), SchematicWriter.REGION, SchematicWriter.REGION), false);
                mc.player.displayClientMessage(Component.translatable(written.size() == 1 ? "curvegen.msg.exported_howto" : "curvegen.msg.exported_howto_parts").withStyle(ChatFormatting.GRAY), false);
                for (String w : blocks.warnings()) mc.player.displayClientMessage(Component.literal(w).withStyle(ChatFormatting.GOLD), false);
            }
            return written.size() == 1
                    ? Component.translatable("curvegen.msg.exported_short", written.get(0)).withStyle(ChatFormatting.GREEN)
                    : Component.translatable("curvegen.msg.exported_short_parts", written.size()).withStyle(ChatFormatting.GREEN);
        } catch (IOException e) {
            CurveGen.LOGGER.error("Failed to write schematic", e);
            return Component.literal("Export failed: " + e.getMessage()).withStyle(ChatFormatting.RED);
        }
    }
}
