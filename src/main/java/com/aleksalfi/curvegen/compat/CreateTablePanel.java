package com.aleksalfi.curvegen.compat;

import com.aleksalfi.curvegen.CurveGen;
import com.aleksalfi.curvegen.client.ExportTracker;
import com.simibubi.create.CreateClient;
import com.simibubi.create.content.schematics.table.SchematicTableScreen;
import com.simibubi.create.foundation.gui.widget.ScrollInput;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.lang.reflect.Field;
import java.util.List;

/**
 * A panel beside Create's Schematic Table listing the parts of the last export: one click selects a part
 * in the table (upload it with the table's own button), done parts are ticked, Dismiss stops tracking.
 * Only loaded when Create is present.
 */
public final class CreateTablePanel {
    private CreateTablePanel() {}

    private static final int W = 112, ROW = 20, PER_PAGE = 8;
    private static int page;
    private static Field areaField;

    public static void refreshSender() {
        try { CreateClient.SCHEMATIC_SENDER.refresh(); } catch (RuntimeException e) { CurveGen.LOGGER.debug("schematic list refresh failed", e); }
    }

    public static void onInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof SchematicTableScreen screen) || !ExportTracker.active()) return;
        List<String> files = ExportTracker.files();
        int pages = Math.max(1, (files.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));
        int x = Math.max(2, screen.getGuiLeft() - W - 6), y = screen.getGuiTop();
        int rows = Math.min(PER_PAGE, files.size() - page * PER_PAGE);
        int h = 16 + rows * ROW + (pages > 1 ? ROW : 0) + ROW + 6;
        event.addListener(new Background(x - 4, y - 4, W + 8, h));
        int ry = y + 12;
        for (int i = 0; i < rows; i++) {
            String file = files.get(page * PER_PAGE + i);
            boolean done = ExportTracker.isPicked(file);
            String label = (done ? "✔ " : "") + shortName(file, files.size());
            Button b = Button.builder(Component.literal(label).withStyle(done ? ChatFormatting.GRAY : ChatFormatting.WHITE), bt -> pick(screen, file))
                    .bounds(x, ry, W, 18).build();
            b.setTooltip(Tooltip.create(Component.translatable("curvegen.table.pick_tip", file)));
            event.addListener(b);
            ry += ROW;
        }
        if (pages > 1) {
            event.addListener(Button.builder(Component.literal("▲"), bt -> { page--; reinitKeepingSelection(screen); }).bounds(x, ry, 24, 18).build());
            event.addListener(Button.builder(Component.literal("▼"), bt -> { page++; reinitKeepingSelection(screen); }).bounds(x + W - 24, ry, 24, 18).build());
            ry += ROW;
        }
        Button dismiss = Button.builder(Component.translatable("curvegen.table.dismiss"), bt -> {
            ExportTracker.dismiss();
            reinitKeepingSelection(screen);
        }).bounds(x, ry, W, 18).build();
        dismiss.setTooltip(Tooltip.create(Component.translatable("curvegen.table.dismiss_tip")));
        event.addListener(dismiss);
    }

    /** Rebuilds the screen (to redraw the panel) without losing the table's current selection. */
    private static void reinitKeepingSelection(SchematicTableScreen screen) {
        ScrollInput area = area(screen);
        int state = area == null ? -1 : area.getState();
        screen.init(Minecraft.getInstance(), screen.width, screen.height);
        ScrollInput again = area(screen);
        if (again != null && state >= 0) { again.setState(state); again.onChanged(); }
    }

    private static String shortName(String file, int total) {
        String n = file.endsWith(".nbt") ? file.substring(0, file.length() - 4) : file;
        int p = n.lastIndexOf("_p");
        if (total > 1 && p >= 0) return Component.translatable("curvegen.table.part", n.substring(p + 2), total).getString();
        Minecraft mc = Minecraft.getInstance();
        return mc.font.plainSubstrByWidth(n, W - 16);
    }

    /** Selects the file in the table's schematic list, rebuilding the screen first if the list is stale. */
    private static void pick(SchematicTableScreen screen, String file) {
        Minecraft mc = Minecraft.getInstance();
        refreshSender();
        int idx = indexOf(file);
        if (idx < 0) {
            if (mc.player != null) mc.player.displayClientMessage(Component.translatable("curvegen.table.missing", file).withStyle(ChatFormatting.RED), true);
            return;
        }
        ScrollInput area = area(screen);
        if (area == null || area.getState() >= 0 && optionCount(area) != CreateClient.SCHEMATIC_SENDER.getAvailableSchematics().size()) {
            screen.init(mc, screen.width, screen.height); // rebuild with the refreshed list
            area = area(screen);
        }
        if (area == null) return;
        area.setState(idx);
        area.onChanged();
        ExportTracker.markPicked(file);
        screen.init(mc, screen.width, screen.height);
        ScrollInput again = area(screen);
        if (again != null) { again.setState(idx); again.onChanged(); }
    }

    private static int indexOf(String file) {
        List<Component> names = CreateClient.SCHEMATIC_SENDER.getAvailableSchematics();
        String bare = file.endsWith(".nbt") ? file.substring(0, file.length() - 4) : file;
        for (int i = 0; i < names.size(); i++) {
            String s = names.get(i).getString();
            if (s.equals(file) || s.equals(bare)) return i;
        }
        return -1;
    }

    private static int optionCount(ScrollInput area) {
        try {
            Field f = com.simibubi.create.foundation.gui.widget.SelectionScrollInput.class.getDeclaredField("options");
            f.setAccessible(true);
            Object o = f.get(area);
            return o instanceof List<?> l ? l.size() : -1;
        } catch (ReflectiveOperationException | ClassCastException e) { return -1; }
    }

    private static ScrollInput area(SchematicTableScreen screen) {
        try {
            if (areaField == null) {
                areaField = SchematicTableScreen.class.getDeclaredField("schematicsArea");
                areaField.setAccessible(true);
            }
            return (ScrollInput) areaField.get(screen);
        } catch (ReflectiveOperationException e) {
            CurveGen.LOGGER.warn("Cannot reach the schematic table's selector", e);
            return null;
        }
    }

    /** The panel's backdrop and title; never takes clicks. */
    private static final class Background extends AbstractWidget {
        Background(int x, int y, int w, int h) { super(x, y, w, h, Component.empty()); active = false; }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            g.fill(getX(), getY(), getX() + width, getY() + height, 0xC0101010);
            g.renderOutline(getX(), getY(), width, height, 0xFF555555);
            g.drawString(Minecraft.getInstance().font, Component.translatable("curvegen.table.title"), getX() + 4, getY() + 4, 0x55FFFF, false);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {}

        @Override
        public boolean isMouseOver(double x, double y) { return false; }
    }
}
