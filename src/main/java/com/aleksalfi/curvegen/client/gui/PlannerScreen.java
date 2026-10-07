package com.aleksalfi.curvegen.client.gui;

import com.aleksalfi.curvegen.build.CopycatSupport;
import com.aleksalfi.curvegen.client.ClientActions;
import com.aleksalfi.curvegen.client.render.PreviewManager;
import com.aleksalfi.curvegen.item.CurvePlannerItem;
import com.aleksalfi.curvegen.network.PlannerActionPayload;
import com.aleksalfi.curvegen.plan.ArcMode;
import com.aleksalfi.curvegen.plan.CurvePlan;
import com.aleksalfi.curvegen.plan.LaneSpec;
import com.aleksalfi.curvegen.plan.PlanLimits;
import com.aleksalfi.curvegen.plan.ProfileSpec;
import com.aleksalfi.curvegen.plan.SegmentSpec;
import com.aleksalfi.curvegen.plan.SegmentType;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Options screen for the Curve Planner (sneak + right-click or the keybind). The layout is built from
 * rows with a running y cursor so nothing depends on fixed offsets that could overlap.
 */
public class PlannerScreen extends Screen {
    private static final int PANEL_W = 440, PANEL_H = 268;
    private static final int LEFT_X = 8, LEFT_W = 196;
    private static final int RIGHT_X = 214, RIGHT_W = 218;
    private static final int ROW = 22, LANE_ROWS = 4, LINE = 11;

    private CurvePlan plan;
    private int left, top;
    private int laneScroll;
    private boolean overwrite;
    private Component status = Component.empty();
    /** Labels drawn in renderBackground at panel-relative positions: {x, y, color, component}. */
    private final List<Object[]> labels = new ArrayList<>();

    public PlannerScreen() {
        super(Component.translatable("curvegen.gui.title"));
        Minecraft mc = Minecraft.getInstance();
        ItemStack held = mc.player == null ? null : CurvePlannerItem.held(mc.player);
        plan = held == null ? CurvePlan.DEFAULT : CurvePlannerItem.getPlan(held);
    }

    // ---- helpers -------------------------------------------------------------------------------------

    public static Component enumName(String group, Enum<?> value) {
        return Component.translatable("curvegen.enum." + group + "." + value.name().toLowerCase(Locale.ROOT));
    }

    public static String describeSegment(SegmentSpec s) {
        String base = enumName("segment", s.type()).getString();
        return switch (s.type()) {
            case ARC -> base + " (" + enumName("arc", s.arcMode()).getString() + (s.arcMode() == ArcMode.RADIUS ? ", r=" + fmt(s.radius()) : "") + ")";
            case BEZIER -> base + " (" + enumName("bezier", s.bezierKind()).getString() + ")";
            case S_BEND -> base + " (" + enumName("sbend", s.sBendStyle()).getString() + ")";
            case SPLINE -> base + " (" + s.points().size() + " pts)";
            default -> base;
        };
    }

    static String fmt(double d) {
        return d == Math.rint(d) ? Integer.toString((int) d) : String.format(Locale.ROOT, "%.2f", d);
    }

    private void update(CurvePlan next, boolean rebuild) {
        plan = next;
        ClientActions.syncPlan(plan);
        if (rebuild) rebuildWidgets();
    }

    private void draft(SegmentSpec options, boolean rebuild) {
        update(plan.withDraftOptions(options), rebuild);
    }

    private void profile(ProfileSpec p, boolean rebuild) {
        update(plan.withProfile(p), rebuild);
    }

    private Button button(int x, int y, int w, Component text, Button.OnPress press) {
        return addRenderableWidget(Button.builder(text, press).bounds(left + x, top + y, w, 18).build());
    }

    /** Numeric input; only finite values inside [min, max] are applied, anything else is ignored. */
    private EditBox numberBox(int x, int y, int w, double value, double min, double max, Consumer<Double> onChange) {
        EditBox box = new EditBox(font, left + x, top + y, w, 18, Component.empty());
        box.setMaxLength(12);
        box.setValue(fmt(value));
        box.setResponder(s -> {
            try {
                double v = Double.parseDouble(s.trim());
                if (Double.isFinite(v)) onChange.accept(Math.max(min, Math.min(max, v)));
            } catch (NumberFormatException ignored) {
            }
        });
        return addRenderableWidget(box);
    }

    private Checkbox checkbox(int x, int y, Component text, boolean selected, Consumer<Boolean> onChange) {
        return addRenderableWidget(Checkbox.builder(text, font).pos(left + x, top + y).selected(selected)
                .onValueChange((cb, v) -> onChange.accept(v)).build());
    }

    private void label(int x, int y, int color, Component text) {
        labels.add(new Object[]{x, y, color, text});
    }

    // ---- layout --------------------------------------------------------------------------------------

    @Override
    protected void init() {
        labels.clear();
        left = Math.max(0, (width - PANEL_W) / 2);
        top = Math.max(0, (height - PANEL_H) / 2);
        SegmentSpec d = plan.draft();
        ProfileSpec p = plan.profile();

        // ---------------- Segment column ----------------
        int y = 6;
        label(LEFT_X, y, 0x55FFFF, Component.translatable("curvegen.gui.segment"));
        y += LINE + 1;
        button(LEFT_X, y, LEFT_W, Component.translatable("curvegen.gui.type", enumName("segment", d.type())),
                b -> draft(plan.draft().withType(plan.draft().type().next()), true));
        y += ROW;
        switch (d.type()) {
            case ARC -> {
                button(LEFT_X, y, LEFT_W, Component.translatable("curvegen.gui.arc_mode", enumName("arc", d.arcMode())),
                        b -> draft(plan.draft().withArcMode(plan.draft().arcMode().next()), true));
                y += ROW;
                if (d.arcMode() == ArcMode.RADIUS) {
                    label(LEFT_X, y + 5, 0xAAAAAA, Component.translatable("curvegen.gui.radius"));
                    numberBox(LEFT_X + 44, y, 50, d.radius(), 0.5, 1024, r -> draft(plan.draft().withRadius(r), false));
                    button(LEFT_X + 100, y, LEFT_W - 100, Component.translatable("curvegen.gui.turn",
                                    Component.translatable(d.turnLeft() ? "curvegen.gui.turn.left" : "curvegen.gui.turn.right")),
                            b -> draft(plan.draft().withTurnLeft(!plan.draft().turnLeft()), true));
                    y += ROW;
                }
            }
            case BEZIER -> {
                button(LEFT_X, y, LEFT_W, Component.translatable("curvegen.gui.bezier", enumName("bezier", d.bezierKind())),
                        b -> draft(plan.draft().withBezierKind(plan.draft().bezierKind().next()), true));
                y += ROW;
            }
            case S_BEND -> {
                button(LEFT_X, y, LEFT_W, Component.translatable("curvegen.gui.sbend", enumName("sbend", d.sBendStyle())),
                        b -> draft(plan.draft().withSBendStyle(plan.draft().sBendStyle().next()), true));
                y += ROW;
            }
            case SPLINE -> {
                Button finish = button(LEFT_X, y, LEFT_W, Component.translatable("curvegen.gui.finish_segment"),
                        b -> update(plan.finishDraft(), true));
                finish.setTooltip(Tooltip.create(Component.translatable("curvegen.gui.finish_tip")));
                finish.active = d.points().size() >= d.minimumPoints(plan.draftIsFirst());
                y += ROW;
            }
            default -> {}
        }
        button(LEFT_X, y, LEFT_W, Component.translatable("curvegen.gui.heading", enumName("heading", d.heading())),
                b -> draft(plan.draft().withHeading(plan.draft().heading().next()), true));
        y += ROW;
        // These three change how many clicks the segment needs, so the labels are rebuilt.
        checkbox(LEFT_X, y, Component.translatable("curvegen.gui.smooth_join"), d.smoothJoin(), v -> draft(plan.draft().withSmoothJoin(v), true));
        y += 20;
        Checkbox as = checkbox(LEFT_X, y, Component.translatable("curvegen.gui.align_start"), d.alignStart(), v -> draft(plan.draft().withAlignStart(v), true));
        as.setTooltip(Tooltip.create(Component.translatable("curvegen.gui.align_tip")));
        y += 20;
        Checkbox ae = checkbox(LEFT_X, y, Component.translatable("curvegen.gui.align_end"), d.alignEnd(), v -> draft(plan.draft().withAlignEnd(v), true));
        ae.setTooltip(Tooltip.create(Component.translatable("curvegen.gui.align_tip")));
        y += 20;
        button(LEFT_X, y, 112, Component.translatable("curvegen.gui.elevation", enumName("elevation", p.elevationMode())),
                b -> profile(plan.profile().withElevationMode(plan.profile().elevationMode().next()), true));
        label(LEFT_X + 118, y + 5, 0xAAAAAA, Component.translatable("curvegen.gui.y_offset"));
        numberBox(LEFT_X + 160, y, 36, p.yOffset(), -PlanLimits.MAX_Y_OFFSET, PlanLimits.MAX_Y_OFFSET, v -> profile(plan.profile().withYOffset(v), false));
        y += ROW;
        // update() already sends the whole plan, so no separate action packet (it would be applied twice).
        button(LEFT_X, y, 60, Component.translatable("curvegen.gui.undo_point"), b -> update(plan.undoPoint(), true));
        button(LEFT_X + 64, y, 74, Component.translatable("curvegen.gui.remove_segment"), b -> update(plan.removeLastSegment(), true));
        button(LEFT_X + 142, y, 54, Component.translatable("curvegen.gui.clear"), b -> update(plan.clearPath(), true));
        y += ROW;
        String next = plan.draft().nextClickLabel(plan.draftIsFirst(), plan.hasPreviousTangent());
        label(LEFT_X, y, 0xFFFF55, Component.translatable("curvegen.hud.next", next == null ? "-" : next));
        // stats are drawn live in renderBackground below this label
        statsY = y + LINE;

        // ---------------- Profile column ----------------
        int ry = 6;
        label(RIGHT_X, ry, 0x55FFFF, Component.translatable("curvegen.gui.profile"));
        ry += LINE + 1;
        label(RIGHT_X, ry, 0xAAAAAA, Component.translatable("curvegen.gui.width"));
        label(RIGHT_X + 40, ry, 0xAAAAAA, Component.translatable("curvegen.gui.block"));
        label(RIGHT_X + 142, ry, 0xAAAAAA, Component.translatable("curvegen.gui.material"));
        ry += LINE;
        int visible = Math.min(LANE_ROWS, p.lanes().size() - laneScroll);
        for (int i = 0; i < visible; i++) {
            int idx = laneScroll + i;
            LaneSpec lane = p.lanes().get(idx);
            numberBox(RIGHT_X, ry, 36, lane.width(), 0, PlanLimits.MAX_LANE_WIDTH, w -> profile(plan.profile().withLane(idx, plan.profile().lanes().get(idx).withWidth(w)), false));
            addRenderableWidget(new BlockButton(left + RIGHT_X + 40, top + ry, 98, 18, lane.block(), Component.translatable("curvegen.gui.none"),
                    b -> minecraft.setScreen(new BlockPickerScreen(this, lane.block(), false,
                            s -> profile(plan.profile().withLane(idx, plan.profile().lanes().get(idx).withBlock(s)), true)))));
            addRenderableWidget(new BlockButton(left + RIGHT_X + 142, top + ry, 56, 18, lane.material(), Component.translatable("curvegen.gui.same_as_block"),
                    b -> minecraft.setScreen(new BlockPickerScreen(this, lane.material().isBlank() ? lane.block() : lane.material(), true,
                            s -> profile(plan.profile().withLane(idx, plan.profile().lanes().get(idx).withMaterial(s)), true)))));
            button(RIGHT_X + 202, ry, 16, Component.literal("x"), b -> {
                List<LaneSpec> lanes = new ArrayList<>(plan.profile().lanes());
                if (lanes.size() > 1) lanes.remove(idx);
                laneScroll = Math.max(0, Math.min(laneScroll, lanes.size() - LANE_ROWS));
                profile(plan.profile().withLanes(lanes), true);
            });
            ry += 20;
        }
        ry += (LANE_ROWS - visible) * 20 + 2;
        button(RIGHT_X, ry, 18, Component.literal("▲"), b -> { laneScroll = Math.max(0, laneScroll - 1); rebuildWidgets(); });
        button(RIGHT_X + 20, ry, 18, Component.literal("▼"), b -> { laneScroll = Math.max(0, Math.min(laneScroll + 1, plan.profile().lanes().size() - LANE_ROWS)); rebuildWidgets(); });
        Button add = button(RIGHT_X + 42, ry, RIGHT_W - 42, Component.translatable("curvegen.gui.add_lane"), b -> {
            List<LaneSpec> lanes = new ArrayList<>(plan.profile().lanes());
            if (lanes.size() >= 32) return;
            lanes.add(lanes.isEmpty() ? new LaneSpec(1, "minecraft:stone", "") : lanes.get(lanes.size() - 1));
            laneScroll = Math.max(0, lanes.size() - LANE_ROWS);
            profile(plan.profile().withLanes(lanes), true);
        });
        add.setTooltip(Tooltip.create(Component.translatable("curvegen.gui.lane_hint")));
        ry += ROW;
        label(RIGHT_X, ry + 5, 0xAAAAAA, Component.translatable("curvegen.gui.thickness"));
        numberBox(RIGHT_X + 40, ry, 36, p.thickness(), 1, PlanLimits.MAX_THICKNESS, t -> profile(plan.profile().withThickness((int) Math.round(t)), false));
        addRenderableWidget(new BlockButton(left + RIGHT_X + 80, top + ry, RIGHT_W - 80, 18, p.baseBlock(), Component.translatable("curvegen.gui.base_block"),
                b -> minecraft.setScreen(new BlockPickerScreen(this, p.baseBlock(), false, s -> profile(plan.profile().withBaseBlock(s), true)))));
        ry += ROW;
        checkbox(RIGHT_X, ry, Component.translatable("curvegen.gui.edge_smoothing"), p.edgeSmoothing(), v -> profile(plan.profile().withEdgeSmoothing(v), false));
        ry += 20;
        checkbox(RIGHT_X, ry, Component.translatable("curvegen.gui.slope_smoothing"), p.slopeSmoothing(), v -> profile(plan.profile().withSlopeSmoothing(v), false));
        ry += 20;
        button(RIGHT_X, ry, 110, Component.translatable("curvegen.gui.quality", Component.translatable("curvegen.gui.quality." + p.quality())),
                b -> profile(plan.profile().withQuality(plan.profile().quality() % 3 + 1), true));
        if (!CopycatSupport.available()) label(RIGHT_X, ry + ROW, 0xFF5555, Component.translatable("curvegen.gui.copycats_missing"));

        // ---------------- Bottom rows ----------------
        int by = PANEL_H - 2 * ROW - 4;
        EditBox name = new EditBox(font, left + LEFT_X, top + by, 96, 18, Component.translatable("curvegen.gui.schematic_name"));
        name.setMaxLength(64);
        name.setValue(plan.schematicName());
        name.setTooltip(Tooltip.create(Component.translatable("curvegen.gui.schematic_name")));
        name.setResponder(s -> update(plan.withSchematicName(s), false));
        addRenderableWidget(name);
        checkbox(LEFT_X + 100, by, Component.translatable("curvegen.gui.overwrite"), overwrite, v -> overwrite = v);
        button(LEFT_X + 184, by, 104, Component.translatable("curvegen.gui.export"), b -> status = ClientActions.exportSchematic(plan, overwrite));
        button(LEFT_X + 292, by, 56, Component.translatable("curvegen.gui.place"), b -> { ClientActions.send(PlannerActionPayload.Action.PLACE); onClose(); });
        button(LEFT_X + 352, by, 72, Component.translatable("curvegen.gui.undo_place"), b -> ClientActions.send(PlannerActionPayload.Action.UNDO_PLACE));
        by += ROW;
        button(LEFT_X, by, 140, Component.translatable("curvegen.gui.deploy"), b -> ClientActions.send(PlannerActionPayload.Action.DEPLOY_SCHEMATIC));
        statusY = by + 5;
    }

    private int statsY, statusY;

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(left, top, left + PANEL_W, top + PANEL_H, 0xB0101010);
        g.renderOutline(left, top, PANEL_W, PANEL_H, 0xFF555555);
        for (Object[] l : labels) {
            g.drawString(font, (Component) l[3], left + (int) l[0], top + (int) l[1], (int) l[2], false);
        }
        PreviewManager.Stats stats = PreviewManager.stats();
        g.drawString(font, Component.translatable("curvegen.gui.stats", stats.blocks(), stats.layers(), plan.segments().size()), left + LEFT_X, top + statsY, 0xCCCCCC, false);
        g.drawString(font, status, left + LEFT_X + 146, top + statusY, 0xFFFFFF, true);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (super.keyPressed(keyCode, scanCode, modifiers)) return true;
        if (com.aleksalfi.curvegen.client.CurveGenClient.OPEN_PLANNER.matches(keyCode, scanCode) && !(getFocused() instanceof EditBox)) {
            onClose();
            return true;
        }
        return false;
    }
}
