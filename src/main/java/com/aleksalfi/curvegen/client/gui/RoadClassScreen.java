package com.aleksalfi.curvegen.client.gui;

import com.aleksalfi.curvegen.client.ClientActions;
import com.aleksalfi.curvegen.client.RoadClientCache;
import com.aleksalfi.curvegen.road.RoadClass;
import com.aleksalfi.curvegen.road.RoadEdit;
import com.aleksalfi.curvegen.road.RoadNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/** Editor for one road class. Edits are sent as a whole class record. */
public class RoadClassScreen extends Screen implements RoadScreen {
    private static final int W = 380, H = 254;
    private final Screen parent;
    private final String classId;
    private int left, top;
    private boolean needsRebuild;
    private final List<Object[]> labels = new ArrayList<>();

    public RoadClassScreen(Screen parent, String classId) {
        super(Component.translatable("curvegen.road.class.title"));
        this.parent = parent;
        this.classId = classId;
    }

    private RoadClass cls() { RoadNetwork n = RoadClientCache.current(); return n == null ? null : n.classes().get(classId); }

    private void edit(UnaryOperator<RoadClass> change) {
        RoadClass c = cls();
        if (c != null) ClientActions.sendRoadEdit(RoadEdit.roadClass(change.apply(c)));
    }

    private void label(int x, int y, int color, Component text) { labels.add(new Object[]{x, y, color, text}); }

    private EditBox intBox(int x, int y, int w, int value, int min, int max, java.util.function.IntConsumer onChange) {
        EditBox box = new EditBox(font, left + x, top + y, w, 18, Component.empty());
        box.setMaxLength(4);
        box.setValue(Integer.toString(value));
        box.setResponder(s -> {
            try {
                int v = Integer.parseInt(s.trim());
                boolean ok = v >= min && v <= max;
                box.setTextColor(ok ? 0xE0E0E0 : 0xFF5555);
                if (ok && v != value) onChange.accept(v);
            } catch (NumberFormatException e) { box.setTextColor(0xFF5555); }
        });
        return addRenderableWidget(box);
    }

    @Override
    protected void init() {
        labels.clear();
        left = Math.max(0, (width - W) / 2);
        top = Math.max(0, (height - H) / 2);
        RoadClass c = cls();
        if (c == null) {
            label(8, 8, 0xFF5555, Component.translatable("curvegen.road.class.gone"));
            addRenderableWidget(Button.builder(Component.translatable("curvegen.gui.picker.cancel"), b -> onClose()).bounds(left + 8, top + 24, 100, 18).build());
            return;
        }
        int y = 6;
        label(8, y, 0x55FFFF, Component.translatable("curvegen.road.class.header", c.id()));
        y += 14;
        label(8, y + 5, 0xAAAAAA, Component.translatable("curvegen.road.class.name"));
        EditBox name = new EditBox(font, left + 110, top + y, W - 118, 18, Component.empty());
        name.setMaxLength(32);
        name.setValue(c.name());
        name.setResponder(s -> { if (!s.isBlank() && !s.equals(c.name())) edit(x -> x.withName(s)); });
        addRenderableWidget(name);
        y += 22;
        label(8, y + 5, 0xAAAAAA, Component.translatable("curvegen.road.class.lane_width"));
        intBox(110, y, 40, c.laneWidth(), 2, 32, v -> edit(x -> x.withLaneWidth(v)));
        label(160, y + 5, 0xAAAAAA, Component.translatable("curvegen.road.class.lanes"));
        intBox(250, y, 40, c.lanesPerDirection(), 1, 4, v -> edit(x -> x.withLanesPerDirection(v)));
        y += 22;
        label(8, y + 5, 0xAAAAAA, Component.translatable("curvegen.road.class.sidewalk"));
        intBox(110, y, 40, c.sidewalkWidth(), 0, 16, v -> edit(x -> x.withSidewalkWidth(v)));
        label(160, y + 5, 0xAAAAAA, Component.translatable("curvegen.road.class.curb"));
        intBox(250, y, 40, c.curbLayers(), 0, 7, v -> edit(x -> x.withCurbLayers(v)));
        y += 22;
        addRenderableWidget(Checkbox.builder(Component.translatable("curvegen.road.class.edge_lines"), font).pos(left + 8, top + y).selected(c.edgeLines())
                .onValueChange((cb, v) -> edit(x -> x.withEdgeLines(v))).build());
        Checkbox smooth = addRenderableWidget(Checkbox.builder(Component.translatable("curvegen.road.class.smooth_edges"), font).pos(left + 160, top + y).selected(c.smoothEdges())
                .onValueChange((cb, v) -> edit(x -> x.withSmoothEdges(v))).build());
        smooth.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("curvegen.road.class.smooth_edges_tip")));
        y += 22;
        label(8, y + 5, 0xAAAAAA, Component.translatable("curvegen.road.class.shoulder"));
        intBox(110, y, 40, c.shoulderWidth(), 0, 8, v -> edit(x -> x.withShoulderWidth(v)));
        label(160, y + 5, 0xAAAAAA, Component.translatable("curvegen.road.class.merge_length"));
        intBox(250, y, 40, c.mergeLength(), 20, 200, v -> edit(x -> x.withMergeLength(v)));
        Checkbox arrows = addRenderableWidget(Checkbox.builder(Component.translatable("curvegen.road.class.arrows"), font).pos(left + 296, top + y).selected(c.paintArrows())
                .onValueChange((cb, v) -> edit(x -> x.withPaintArrows(v))).build());
        arrows.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("curvegen.road.class.arrows_tip")));
        y += 22;
        blockRowDirect(y, Component.translatable("curvegen.road.class.asphalt"), c.asphalt(), s -> edit(x -> x.withAsphalt(s)));
        y += 22;
        blockRowDirect(y, Component.translatable("curvegen.road.class.line"), c.line(), s -> edit(x -> x.withLine(s)));
        y += 22;
        blockRowDirect(y, Component.translatable("curvegen.road.class.curb_block"), c.curb(), s -> edit(x -> x.withCurb(s)));
        y += 22;
        blockRowDirect(y, Component.translatable("curvegen.road.class.sidewalk_block"), c.sidewalk(), s -> edit(x -> x.withSidewalk(s)));
        y = H - 26;
        addRenderableWidget(Button.builder(Component.translatable("curvegen.road.class.remove"), b -> { ClientActions.sendRoadEdit(RoadEdit.of(RoadEdit.Op.CLASS_REMOVE, classId)); onClose(); })
                .bounds(left + 8, top + y, 100, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("curvegen.road.class.back"), b -> onClose()).bounds(left + W - 108, top + y, 100, 18).build());
    }

    private void blockRowDirect(int y, Component name, String current, java.util.function.Consumer<String> apply) {
        label(8, y + 5, 0xAAAAAA, name);
        addRenderableWidget(new BlockButton(left + 110, top + y, W - 118, 18, current, Component.translatable("curvegen.gui.none"),
                b -> Minecraft.getInstance().setScreen(new BlockPickerScreen(this, current, false, false, apply))));
    }

    @Override
    public void onNetworkChanged() { needsRebuild = true; }

    @Override
    public void tick() {
        super.tick();
        if (needsRebuild) { needsRebuild = false; RoadScreen.rebuildKeepingFocus(this, this::rebuildWidgets); }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(left, top, left + W, top + H, 0xB0101010);
        g.renderOutline(left, top, W, H, 0xFF555555);
        for (Object[] l : labels) g.drawString(font, (Component) l[3], left + (int) l[0], top + (int) l[1], (int) l[2], false);
    }

    @Override
    public void onClose() { Minecraft.getInstance().setScreen(parent); }

    @Override
    public boolean isPauseScreen() { return false; }
}
