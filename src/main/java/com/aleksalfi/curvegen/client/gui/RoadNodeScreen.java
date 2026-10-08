package com.aleksalfi.curvegen.client.gui;

import com.aleksalfi.curvegen.client.ClientActions;
import com.aleksalfi.curvegen.client.ClientEvents;
import com.aleksalfi.curvegen.client.RoadClientCache;
import com.aleksalfi.curvegen.network.RoadActionPayload;
import com.aleksalfi.curvegen.road.ArmSettings;
import com.aleksalfi.curvegen.road.NodeKind;
import com.aleksalfi.curvegen.road.RoadClass;
import com.aleksalfi.curvegen.road.RoadEdit;
import com.aleksalfi.curvegen.road.RoadLink;
import com.aleksalfi.curvegen.road.RoadNetwork;
import com.aleksalfi.curvegen.road.RoadNode;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

/** Settings of one road node: kind, corner, radii, zebra, and every arm (link) that meets it. */
public class RoadNodeScreen extends Screen implements RoadScreen {
    private static final int W = 400;
    private final int nodeId;
    private int left, top, panelH;
    private boolean needsRebuild;
    private final List<Object[]> labels = new ArrayList<>();

    public RoadNodeScreen(int nodeId) {
        super(Component.translatable("curvegen.road.node.title"));
        this.nodeId = nodeId;
    }

    private RoadNetwork net() { return RoadClientCache.current(); }
    private RoadNode node() { RoadNetwork n = net(); return n == null ? null : n.nodes().get(nodeId); }

    private void edit(UnaryOperator<RoadNode> change) {
        RoadNode n = node();
        if (n != null) ClientActions.sendRoadEdit(RoadEdit.node(change.apply(n)));
    }

    private Button button(int x, int y, int w, Component text, Button.OnPress press) {
        return addRenderableWidget(Button.builder(text, press).bounds(left + x, top + y, w, 18).build());
    }

    private void label(int x, int y, int color, Component text) { labels.add(new Object[]{x, y, color, text}); }

    private EditBox numberBox(int x, int y, int w, double value, double min, double max, java.util.function.DoubleConsumer onChange) {
        EditBox box = new EditBox(font, left + x, top + y, w, 18, Component.empty());
        box.setMaxLength(8);
        box.setValue(value == Math.rint(value) ? Integer.toString((int) value) : String.format(Locale.ROOT, "%.1f", value));
        box.setResponder(s -> {
            try {
                double v = Double.parseDouble(s.trim());
                boolean ok = Double.isFinite(v) && v >= min && v <= max;
                box.setTextColor(ok ? 0xE0E0E0 : 0xFF5555);
                if (ok) onChange.accept(v);
            } catch (NumberFormatException e) { box.setTextColor(0xFF5555); }
        });
        return addRenderableWidget(box);
    }

    @Override
    protected void init() {
        labels.clear();
        RoadNetwork net = net();
        RoadNode n = node();
        List<RoadLink> links = net == null || n == null ? List.of() : net.linksOf(nodeId);
        panelH = Math.min(height, 150 + 22 * Math.max(1, links.size()));
        left = Math.max(0, (width - W) / 2);
        top = Math.max(0, (height - panelH) / 2);
        if (net == null || n == null) {
            label(8, 8, 0xFF5555, Component.translatable("curvegen.road.node.gone"));
            button(8, 24, 100, Component.translatable("curvegen.gui.picker.cancel"), b -> onClose());
            return;
        }
        boolean canEdit = true; // the server refuses edits of read-only networks; the UI stays usable for viewing
        int y = 6;
        label(8, y, 0x55FFFF, Component.translatable("curvegen.road.node.header", nodeId, ClientEvents.describeNode(net, n), (int) n.x(), (int) n.y(), (int) n.z()));
        y += 14;
        button(8, y, 120, Component.translatable("curvegen.road.node.kind", Component.translatable("curvegen.enum.nodekind." + n.kind().name().toLowerCase(Locale.ROOT))),
                b -> edit(x -> x.withKind(x.kind().next())));
        if (n.kind() == NodeKind.ROUNDABOUT) {
            label(136, y + 5, 0xAAAAAA, Component.translatable("curvegen.road.node.ra_radius"));
            numberBox(196, y, 40, n.roundaboutRadius(), 2, 128, v -> edit(x -> x.withRoundaboutRadius(v)));
            button(244, y, 70, Component.translatable("curvegen.road.node.ra_lanes", n.roundaboutLanes()), b -> edit(x -> x.withRoundaboutLanes(x.roundaboutLanes() == 1 ? 2 : 1)));
        } else {
            button(136, y, 110, Component.translatable("curvegen.road.node.corner", Component.translatable("curvegen.enum.corner." + n.corner().name().toLowerCase(Locale.ROOT))),
                    b -> edit(x -> x.withCorner(x.corner().next())));
            label(252, y + 5, 0xAAAAAA, Component.translatable("curvegen.road.node.fillet"));
            numberBox(300, y, 44, n.filletRadius(), 0, 256, v -> edit(x -> x.withFilletRadius(v)));
        }
        y += 22;
        addRenderableWidget(Checkbox.builder(Component.translatable("curvegen.road.node.zebra"), font).pos(left + 8, top + y).selected(n.zebra())
                .onValueChange((cb, v) -> edit(x -> x.withZebra(v))).build());
        y += 22;
        label(8, y, 0x55FFFF, Component.translatable("curvegen.road.node.arms"));
        y += 12;
        for (RoadLink link : links) {
            RoadNode other = net.nodes().get(link.other(nodeId));
            ArmSettings arm = n.arm(link.id());
            RoadClass cls = net.classOf(link);
            label(8, y + 5, 0xCCCCCC, Component.translatable("curvegen.road.node.arm", link.id(), other == null ? "?" : other.id()));
            button(84, y, 96, Component.literal(cls.name()), b -> {
                List<String> ids = new ArrayList<>(net.classes().keySet());
                int idx = ids.indexOf(link.classId());
                String next = ids.get((idx + 1) % ids.size());
                ClientActions.sendRoadEdit(RoadEdit.of(RoadEdit.Op.LINK_CLASS, link.id(), next));
            });
            button(184, y, 90, Component.translatable("curvegen.enum.priority." + arm.priority().name().toLowerCase(Locale.ROOT)),
                    b -> edit(x -> x.withArm(link.id(), x.arm(link.id()).withPriority(x.arm(link.id()).priority().next()))));
            addRenderableWidget(Checkbox.builder(Component.translatable("curvegen.road.node.arm_zebra"), font).pos(left + 280, top + y).selected(arm.zebra())
                    .onValueChange((cb, v) -> edit(x -> x.withArm(link.id(), x.arm(link.id()).withZebra(v)))).build());
            button(W - 8 - 36, y, 36, Component.translatable("curvegen.road.node.unlink"), b -> ClientActions.sendRoadEdit(RoadEdit.of(RoadEdit.Op.LINK_DELETE, link.id())));
            y += 22;
        }
        if (links.isEmpty()) { label(8, y + 5, 0x888888, Component.translatable("curvegen.road.node.no_arms")); y += 22; }
        y = panelH - 26;
        button(8, y, 100, Component.translatable("curvegen.road.node.delete"), b -> { ClientActions.sendRoadEdit(RoadEdit.of(RoadEdit.Op.NODE_DELETE, nodeId)); onClose(); });
        button(112, y, 100, Component.translatable("curvegen.road.node.deselect"), b -> { ClientActions.sendRoad(RoadActionPayload.Action.DESELECT); onClose(); });
        button(W - 8 - 120, y, 120, Component.translatable("curvegen.road.node.network_screen"), b -> Minecraft.getInstance().setScreen(new RoadNetworkScreen()));
    }

    @Override
    public void onNetworkChanged() { needsRebuild = true; }

    @Override
    public void tick() {
        super.tick();
        if (needsRebuild) { needsRebuild = false; rebuildWidgets(); clearFocus(); }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(left, top, left + W, top + panelH, 0xB0101010);
        g.renderOutline(left, top, W, panelH, 0xFF555555);
        for (Object[] l : labels) g.drawString(font, (Component) l[3], left + (int) l[0], top + (int) l[1], (int) l[2], false);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
