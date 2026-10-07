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
 * rows with a running y cursor; on small screens (below 440x268 GUI pixels) a compact variant is used.
 * Widgets are never rebuilt from inside their own click handler: changes set a flag and the rebuild
 * happens on the next tick.
 */
public class PlannerScreen extends Screen {
    private static final int FULL_W = 440, FULL_H = 268;

    private CurvePlan plan;
    private int panelW, panelH, left, top;
    private int laneScroll;
    private boolean overwrite;
    private boolean needsRebuild;
    private Component status = Component.empty();
    /** Labels drawn in renderBackground at panel-relative positions: {x, y, color, component}. */
    private final List<Object[]> labels = new ArrayList<>();
    private int statsY = -1, statusY;

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

    /** Stores the plan locally and on the server (the server keeps its own points; only options travel). */
    private void update(CurvePlan next, boolean rebuild) {
        plan = next;
        ClientActions.syncPlan(plan);
        if (rebuild) needsRebuild = true;
    }

    /** Point/segment edits are applied on the server through an action (never by sending points). */
    private void action(CurvePlan next, PlannerActionPayload.Action action) {
        plan = next;
        ClientActions.send(action);
        needsRebuild = true;
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

    /** Numeric input; only finite values inside [min, max] are applied, out-of-range text turns red. */
    private EditBox numberBox(int x, int y, int w, double value, double min, double max, Consumer<Double> onChange) {
        EditBox box = new EditBox(font, left + x, top + y, w, 18, Component.empty());
        box.setMaxLength(12);
        box.setValue(fmt(value));
        box.setResponder(s -> {
            try {
                double v = Double.parseDouble(s.trim());
                boolean ok = Double.isFinite(v) && v >= min && v <= max;
                box.setTextColor(ok ? 0xE0E0E0 : 0xFF5555);
                if (ok) onChange.accept(v);
            } catch (NumberFormatException e) {
                box.setTextColor(0xFF5555);
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
        statsY = -1;
        boolean compact = height < FULL_H || width < FULL_W;
        panelW = Math.min(FULL_W, width);
        panelH = Math.min(FULL_H, height);
        left = Math.max(0, (width - panelW) / 2);
        top = Math.max(0, (height - panelH) / 2);
        int row = compact ? 20 : 22, cb = compact ? 18 : 20, laneRows = compact ? 3 : 4;
        int lx = 8, lw = compact ? 186 : 196;
        int rx = lw + 18, rw = panelW - rx - 8;
        SegmentSpec d = plan.draft();
        ProfileSpec p = plan.profile();
        boolean first = plan.draftIsFirst();

        // ---------------- Segment column ----------------
        int y = 6;
        label(lx, y, 0x55FFFF, Component.translatable("curvegen.gui.segment"));
        y += 12;
        button(lx, y, lw, Component.translatable("curvegen.gui.type", enumName("segment", d.type())),
                b -> draft(plan.draft().withType(plan.draft().type().next()), true));
        y += row;
        switch (d.type()) {
            case ARC -> {
                boolean radiusRow = d.arcMode() == ArcMode.RADIUS;
                int modeW = radiusRow && compact ? 96 : lw;
                button(lx, y, modeW, Component.translatable("curvegen.gui.arc_mode", enumName("arc", d.arcMode())),
                        b -> draft(plan.draft().withArcMode(plan.draft().arcMode().next()), true));
                if (radiusRow) {
                    int ry = compact ? y : y + row;
                    int bx = compact ? lx + 100 : lx + 44;
                    if (!compact) label(lx, ry + 5, 0xAAAAAA, Component.translatable("curvegen.gui.radius"));
                    numberBox(bx, ry, 40, d.radius(), 0.5, PlanLimits.MAX_RADIUS, r -> draft(plan.draft().withRadius(r), false));
                    Component side = Component.translatable(d.turnLeft() ? "curvegen.gui.turn.left" : "curvegen.gui.turn.right");
                    button(bx + 44, ry, lx + lw - bx - 44, compact ? side : Component.translatable("curvegen.gui.turn", side),
                            b -> draft(plan.draft().withTurnLeft(!plan.draft().turnLeft()), true));
                    if (!compact) y += row;
                }
                y += row;
            }
            case BEZIER -> {
                button(lx, y, lw, Component.translatable("curvegen.gui.bezier", enumName("bezier", d.bezierKind())),
                        b -> draft(plan.draft().withBezierKind(plan.draft().bezierKind().next()), true));
                y += row;
            }
            case S_BEND -> {
                button(lx, y, lw, Component.translatable("curvegen.gui.sbend", enumName("sbend", d.sBendStyle())),
                        b -> draft(plan.draft().withSBendStyle(plan.draft().sBendStyle().next()), true));
                y += row;
            }
            case SPLINE -> {
                Button finish = button(lx, y, lw, Component.translatable("curvegen.gui.finish_segment"),
                        b -> action(plan.finishDraft(), PlannerActionPayload.Action.FINISH_SEGMENT));
                finish.setTooltip(Tooltip.create(Component.translatable("curvegen.gui.finish_tip")));
                finish.active = d.points().size() >= d.minimumPoints(first);
                y += row;
            }
            default -> {}
        }
        button(lx, y, lw, Component.translatable("curvegen.gui.heading", enumName("heading", d.heading())),
                b -> draft(plan.draft().withHeading(plan.draft().heading().next()), true));
        y += row;
        // These three change how many clicks the segment needs, so the labels are rebuilt.
        checkbox(lx, y, Component.translatable("curvegen.gui.smooth_join"), d.smoothJoin(), v -> draft(plan.draft().withSmoothJoin(v), true));
        y += cb;
        Checkbox as = checkbox(lx, y, Component.translatable("curvegen.gui.align_start"), d.alignStart(), v -> draft(plan.draft().withAlignStart(v), true));
        as.setTooltip(Tooltip.create(Component.translatable("curvegen.gui.align_tip")));
        y += cb;
        Checkbox ae = checkbox(lx, y, Component.translatable("curvegen.gui.align_end"), d.alignEnd(), v -> draft(plan.draft().withAlignEnd(v), true));
        ae.setTooltip(Tooltip.create(Component.translatable("curvegen.gui.align_tip")));
        y += cb;
        button(lx, y, 104, Component.translatable("curvegen.gui.elevation", enumName("elevation", p.elevationMode())),
                b -> profile(plan.profile().withElevationMode(plan.profile().elevationMode().next()), true));
        label(lx + 110, y + 5, 0xAAAAAA, Component.translatable("curvegen.gui.y_offset"));
        numberBox(lx + lw - 36, y, 36, p.yOffset(), -PlanLimits.MAX_Y_OFFSET, PlanLimits.MAX_Y_OFFSET, v -> profile(plan.profile().withYOffset(v), false));
        y += row;
        button(lx, y, 58, Component.translatable("curvegen.gui.undo_point"), b -> action(plan.undoPoint(), PlannerActionPayload.Action.UNDO_POINT));
        button(lx + 62, y, 72, Component.translatable("curvegen.gui.remove_segment"), b -> action(plan.removeLastSegment(), PlannerActionPayload.Action.REMOVE_SEGMENT));
        button(lx + 138, y, lw - 138, Component.translatable(compact ? "curvegen.gui.clear_short" : "curvegen.gui.clear"), b -> action(plan.clearPath(), PlannerActionPayload.Action.CLEAR));
        y += row;
        if (!compact) {
            String next = d.nextClickLabel(first, plan.hasPreviousTangent());
            label(lx, y, 0xFFFF55, Component.translatable("curvegen.hud.next", next == null ? "-" : next));
            statsY = y + 11;
        }

        // ---------------- Profile column ----------------
        int ry = 6;
        label(rx, ry, 0x55FFFF, Component.translatable("curvegen.gui.profile"));
        ry += 12;
        label(rx, ry, 0xAAAAAA, Component.translatable("curvegen.gui.width"));
        label(rx + 40, ry, 0xAAAAAA, Component.translatable("curvegen.gui.block"));
        label(rx + 142, ry, 0xAAAAAA, Component.translatable("curvegen.gui.material"));
        ry += 11;
        int laneCount = p.lanes().size();
        laneScroll = Math.max(0, Math.min(laneScroll, laneCount - laneRows));
        int visible = Math.max(0, Math.min(laneRows, laneCount - laneScroll));
        int blockW = rw - 40 - 4 - 56 - 4 - 16;
        for (int i = 0; i < visible; i++) {
            int idx = laneScroll + i;
            LaneSpec lane = p.lanes().get(idx);
            numberBox(rx, ry, 36, lane.width(), 0, PlanLimits.MAX_LANE_WIDTH, w -> updateLane(idx, l -> l.withWidth(w), false));
            addRenderableWidget(new BlockButton(left + rx + 40, top + ry, blockW, 18, lane.block(), Component.translatable("curvegen.gui.none"),
                    b -> minecraft.setScreen(new BlockPickerScreen(this, lane.block(), false, false,
                            s -> updateLane(idx, l -> l.withBlock(s), true)))));
            addRenderableWidget(new BlockButton(left + rx + 44 + blockW, top + ry, 56, 18, lane.material(), Component.translatable("curvegen.gui.same_as_block"),
                    b -> minecraft.setScreen(new BlockPickerScreen(this, lane.material().isBlank() ? lane.block() : lane.material(), true, true,
                            s -> updateLane(idx, l -> l.withMaterial(s), true)))));
            button(rx + rw - 16, ry, 16, Component.literal("x"), b -> {
                List<LaneSpec> lanes = new ArrayList<>(plan.profile().lanes());
                if (lanes.size() > 1 && idx < lanes.size()) lanes.remove(idx);
                profile(plan.profile().withLanes(lanes), true);
            });
            ry += 20;
        }
        ry += (laneRows - visible) * 20 + 2;
        button(rx, ry, 18, Component.literal("▲"), b -> { laneScroll = Math.max(0, laneScroll - 1); needsRebuild = true; });
        button(rx + 20, ry, 18, Component.literal("▼"), b -> { laneScroll = laneScroll + 1; needsRebuild = true; });
        Button add = button(rx + 42, ry, rw - 42, Component.translatable("curvegen.gui.add_lane"), b -> {
            List<LaneSpec> lanes = new ArrayList<>(plan.profile().lanes());
            if (lanes.size() >= PlanLimits.MAX_LANES) return;
            lanes.add(lanes.isEmpty() ? new LaneSpec(1, "minecraft:stone", "") : lanes.get(lanes.size() - 1));
            laneScroll = lanes.size();
            profile(plan.profile().withLanes(lanes), true);
        });
        add.setTooltip(Tooltip.create(Component.translatable("curvegen.gui.lane_hint")));
        ry += row;
        label(rx, ry + 5, 0xAAAAAA, Component.translatable("curvegen.gui.thickness"));
        numberBox(rx + 40, ry, 36, p.thickness(), 1, PlanLimits.MAX_THICKNESS, t -> profile(plan.profile().withThickness((int) Math.round(t)), false));
        addRenderableWidget(new BlockButton(left + rx + 80, top + ry, rw - 80, 18, p.baseBlock(), Component.translatable("curvegen.gui.base_block"),
                b -> minecraft.setScreen(new BlockPickerScreen(this, p.baseBlock(), false, true, s -> profile(plan.profile().withBaseBlock(s), true)))));
        ry += row;
        checkbox(rx, ry, Component.translatable("curvegen.gui.edge_smoothing"), p.edgeSmoothing(), v -> profile(plan.profile().withEdgeSmoothing(v), false));
        ry += cb;
        checkbox(rx, ry, Component.translatable("curvegen.gui.slope_smoothing"), p.slopeSmoothing(), v -> profile(plan.profile().withSlopeSmoothing(v), false));
        ry += cb;
        button(rx, ry, 110, Component.translatable("curvegen.gui.quality", Component.translatable("curvegen.gui.quality." + p.quality())),
                b -> profile(plan.profile().withQuality(plan.profile().quality() % 3 + 1), true));
        if (!CopycatSupport.available() && !compact) label(rx, ry + row, 0xFF5555, Component.translatable("curvegen.gui.copycats_missing"));

        // ---------------- Bottom rows ----------------
        int by = panelH - 2 * row - 4;
        EditBox name = new EditBox(font, left + lx, top + by, 90, 18, Component.translatable("curvegen.gui.schematic_name"));
        name.setMaxLength(PlanLimits.MAX_NAME_LENGTH);
        name.setValue(plan.schematicName());
        name.setTooltip(Tooltip.create(Component.translatable("curvegen.gui.schematic_name")));
        name.setResponder(s -> update(plan.withSchematicName(s), false));
        addRenderableWidget(name);
        checkbox(lx + 94, by, Component.translatable("curvegen.gui.overwrite"), overwrite, v -> overwrite = v);
        int bx = lx + 176;
        button(bx, by, 100, Component.translatable("curvegen.gui.export"), b -> status = ClientActions.exportSchematic(plan, overwrite));
        button(bx + 104, by, 52, Component.translatable("curvegen.gui.place"), b -> { ClientActions.send(PlannerActionPayload.Action.PLACE); onClose(); });
        button(bx + 160, by, Math.max(60, panelW - 8 - (bx + 160)), Component.translatable("curvegen.gui.undo_place"), b -> ClientActions.send(PlannerActionPayload.Action.UNDO_PLACE));
        by += row;
        button(lx, by, 136, Component.translatable("curvegen.gui.deploy"), b -> ClientActions.send(PlannerActionPayload.Action.DEPLOY_SCHEMATIC));
        statusY = by + 5;
    }

    private void updateLane(int idx, java.util.function.UnaryOperator<LaneSpec> change, boolean rebuild) {
        List<LaneSpec> lanes = plan.profile().lanes();
        if (idx < 0 || idx >= lanes.size()) return;
        profile(plan.profile().withLane(idx, change.apply(lanes.get(idx))), rebuild);
    }

    @Override
    public void tick() {
        super.tick();
        if (needsRebuild) {
            needsRebuild = false;
            rebuildWidgets();
            clearFocus();
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(left, top, left + panelW, top + panelH, 0xB0101010);
        g.renderOutline(left, top, panelW, panelH, 0xFF555555);
        for (Object[] l : labels) {
            g.drawString(font, (Component) l[3], left + (int) l[0], top + (int) l[1], (int) l[2], false);
        }
        PreviewManager.Stats stats = PreviewManager.stats();
        if (statsY >= 0) {
            g.drawString(font, Component.translatable("curvegen.gui.stats", stats.blocks(), stats.layers(), plan.segments().size()), left + 8, top + statsY, 0xCCCCCC, false);
        }
        Component line = status;
        if (line.getString().isEmpty() && !stats.warnings().isEmpty()) {
            line = Component.literal(font.plainSubstrByWidth(stats.warnings().get(0), panelW - 160)).withStyle(ChatFormatting.GOLD);
        }
        g.drawString(font, line, left + 8 + 142, top + statusY, 0xFFFFFF, true);
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
