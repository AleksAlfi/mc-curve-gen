package com.aleksalfi.curvegen.client.gui;

import com.aleksalfi.curvegen.client.ClientActions;
import com.aleksalfi.curvegen.client.RoadClientCache;
import com.aleksalfi.curvegen.client.render.PreviewManager;
import com.aleksalfi.curvegen.item.RoadPlannerItem;
import com.aleksalfi.curvegen.network.RoadActionPayload;
import com.aleksalfi.curvegen.road.Access;
import com.aleksalfi.curvegen.road.RoadClass;
import com.aleksalfi.curvegen.road.RoadEdit;
import com.aleksalfi.curvegen.road.RoadNetwork;
import com.aleksalfi.curvegen.road.RoadPlannerState;
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
import java.util.Map;

/**
 * Road planner main screen: pick or create a network, manage its classes and sharing, and export or
 * place it. Everything is sent to the server as edits; the screen refreshes when the sync arrives.
 */
public class RoadNetworkScreen extends Screen implements RoadScreen {
    private static final int W = 440, H = 268;
    private int left, top, panelW, panelH;
    private boolean needsRebuild;
    private boolean overwrite;
    private String newName = "", shareName = "", schematicName = null;
    private int networkScroll, shareScroll;
    private Component status = Component.empty();
    private final List<Object[]> labels = new ArrayList<>();

    public RoadNetworkScreen() {
        super(Component.translatable("curvegen.road.net.title"));
    }

    private RoadPlannerState state() {
        Minecraft mc = Minecraft.getInstance();
        ItemStack held = mc.player == null ? null : RoadPlannerItem.held(mc.player);
        return held == null ? RoadPlannerState.DEFAULT : RoadPlannerItem.getState(held);
    }

    private Button button(int x, int y, int w, Component text, Button.OnPress press) {
        return addRenderableWidget(Button.builder(text, press).bounds(left + x, top + y, w, 18).build());
    }

    private void label(int x, int y, int color, Component text) { labels.add(new Object[]{x, y, color, text}); }

    @Override
    protected void init() {
        labels.clear();
        panelW = Math.min(W, width);
        panelH = Math.min(H, height);
        left = Math.max(0, (width - panelW) / 2);
        top = Math.max(0, (height - panelH) / 2);
        boolean compact = panelH < H || panelW < W;
        int row = compact ? 20 : 22;
        RoadNetwork net = RoadClientCache.current();
        RoadPlannerState state = state();
        if (schematicName == null) schematicName = state.schematicName();

        // ---------------- left: networks ----------------
        int lx = 8, lw = 150;
        int y = 6;
        label(lx, y, 0x55FFFF, Component.translatable("curvegen.road.net.networks"));
        y += 12;
        List<String> names = RoadClientCache.names();
        int visible = compact ? 4 : 5;
        networkScroll = Math.max(0, Math.min(networkScroll, names.size() - visible));
        for (int i = 0; i < Math.min(visible, names.size() - networkScroll); i++) {
            String name = names.get(networkScroll + i);
            boolean current = net != null && net.name().equals(name);
            Button b = button(lx, y, lw, Component.literal((current ? "▶ " : "") + name), bt -> ClientActions.sendRoadEdit(RoadEdit.of(RoadEdit.Op.NETWORK_SELECT, name)));
            b.active = !current;
            y += 20;
        }
        y += (visible - Math.min(visible, names.size() - networkScroll)) * 20;
        button(lx, y, 18, Component.literal("▲"), b -> { networkScroll = Math.max(0, networkScroll - 1); needsRebuild = true; });
        button(lx + 20, y, 18, Component.literal("▼"), b -> { networkScroll++; needsRebuild = true; });
        y += row;
        EditBox name = new EditBox(font, left + lx, top + y, 90, 18, Component.translatable("curvegen.road.net.new_name"));
        name.setHint(Component.translatable("curvegen.road.net.new_name"));
        name.setMaxLength(32);
        name.setValue(newName);
        name.setResponder(s -> newName = s);
        addRenderableWidget(name);
        button(lx + 94, y, 56, Component.translatable("curvegen.road.net.create"), b -> {
            if (!newName.isBlank()) { ClientActions.sendRoadEdit(RoadEdit.of(RoadEdit.Op.NETWORK_CREATE, newName)); newName = ""; needsRebuild = true; }
        });
        y += row;
        if (net != null) {
            Button del = button(lx, y, lw, Component.translatable("curvegen.road.net.delete"), b -> ClientActions.sendRoadEdit(RoadEdit.of(RoadEdit.Op.NETWORK_DELETE, net.name())));
            del.setTooltip(Tooltip.create(Component.translatable("curvegen.road.net.delete_tip")));
        }

        // ---------------- right: current network ----------------
        int rx = 170, rw = panelW - rx - 8;
        int ry = 6;
        if (net == null) {
            label(rx, ry, 0xFFFF55, Component.translatable("curvegen.road.net.none"));
        } else {
            label(rx, ry, 0x55FFFF, Component.translatable("curvegen.road.net.current", net.name(), net.ownerName()));
            ry += 12;
            label(rx, ry, 0xCCCCCC, Component.translatable("curvegen.road.net.counts", net.nodes().size(), net.links().size()));
            ry += 12;
            // Classes
            label(rx, ry, 0x55FFFF, Component.translatable("curvegen.road.net.classes"));
            ry += 12;
            int cx = rx;
            for (Map.Entry<String, RoadClass> e : net.classes().entrySet()) {
                boolean def = e.getKey().equals(net.defaultClass());
                int bw = Math.min(86, rw / 3 - 2);
                Button b = button(cx, ry, bw, Component.literal((def ? "★ " : "") + e.getValue().name()), bt -> Minecraft.getInstance().setScreen(new RoadClassScreen(this, e.getKey())));
                b.setTooltip(Tooltip.create(Component.translatable("curvegen.road.net.class_tip", e.getValue().laneWidth(), e.getValue().lanesPerDirection(), e.getValue().sidewalkWidth())));
                cx += bw + 2;
                if (cx + bw > rx + rw) { cx = rx; ry += 20; }
            }
            if (cx != rx) ry += 20;
            button(rx, ry, 80, Component.translatable("curvegen.road.net.add_class"), b -> {
                String id = "class" + (net.classes().size() + 1);
                ClientActions.sendRoadEdit(RoadEdit.roadClass(RoadClass.street().withId(id).withName("Class " + (net.classes().size() + 1))));
            });
            List<String> ids = new ArrayList<>(net.classes().keySet());
            button(rx + 84, ry, rw - 84, Component.translatable("curvegen.road.net.default_class", net.classOrDefault(net.defaultClass()).name()), b -> {
                int idx = ids.indexOf(net.defaultClass());
                ClientActions.sendRoadEdit(RoadEdit.of(RoadEdit.Op.CLASS_DEFAULT, ids.get((idx + 1) % ids.size())));
            });
            ry += row;
            // Sharing
            label(rx, ry, 0x55FFFF, Component.translatable("curvegen.road.net.sharing"));
            ry += 12;
            button(rx, ry, rw, Component.translatable("curvegen.road.net.public", Component.translatable("curvegen.enum.access." + net.publicAccess().name().toLowerCase(Locale.ROOT))),
                    b -> ClientActions.sendRoadEdit(RoadEdit.publicAccess(net.publicAccess().next())));
            ry += row;
            List<Map.Entry<String, Access>> shares = new ArrayList<>(net.shares().entrySet());
            int shareRows = compact ? 1 : 2;
            shareScroll = Math.max(0, Math.min(shareScroll, shares.size() - shareRows));
            for (int i = 0; i < Math.min(shareRows, shares.size() - shareScroll); i++) {
                Map.Entry<String, Access> sh = shares.get(shareScroll + i);
                String pname = net.playerNames().getOrDefault(sh.getKey(), sh.getKey());
                label(rx, ry + 5, 0xCCCCCC, Component.literal(font.plainSubstrByWidth(pname, rw - 130)));
                button(rx + rw - 126, ry, 70, Component.translatable("curvegen.enum.access." + sh.getValue().name().toLowerCase(Locale.ROOT)),
                        b -> ClientActions.sendRoadEdit(RoadEdit.share(pname, sh.getValue() == Access.VIEW ? Access.EDIT : Access.VIEW)));
                button(rx + rw - 52, ry, 52, Component.translatable("curvegen.road.net.unshare"), b -> ClientActions.sendRoadEdit(RoadEdit.share(pname, Access.NONE)));
                ry += 20;
            }
            EditBox share = new EditBox(font, left + rx, top + ry, rw - 130, 18, Component.translatable("curvegen.road.net.share_name"));
            share.setHint(Component.translatable("curvegen.road.net.share_name"));
            share.setMaxLength(16);
            share.setValue(shareName);
            share.setResponder(s -> shareName = s);
            addRenderableWidget(share);
            button(rx + rw - 126, ry, 60, Component.translatable("curvegen.road.net.share_view"), b -> { if (!shareName.isBlank()) ClientActions.sendRoadEdit(RoadEdit.share(shareName.trim(), Access.VIEW)); });
            button(rx + rw - 62, ry, 62, Component.translatable("curvegen.road.net.share_edit"), b -> { if (!shareName.isBlank()) ClientActions.sendRoadEdit(RoadEdit.share(shareName.trim(), Access.EDIT)); });
            ry += row;
            if (state.selectedNode() >= 0 && net.nodes().containsKey(state.selectedNode())) {
                button(rx, ry, rw, Component.translatable("curvegen.road.net.edit_node", state.selectedNode()), b -> Minecraft.getInstance().setScreen(new RoadNodeScreen(state.selectedNode())));
            }
        }

        // ---------------- bottom: export / place ----------------
        int by = panelH - row - 4;
        EditBox sname = new EditBox(font, left + 8, top + by, 90, 18, Component.translatable("curvegen.gui.schematic_name"));
        sname.setMaxLength(64);
        sname.setValue(schematicName);
        sname.setTooltip(Tooltip.create(Component.translatable("curvegen.gui.schematic_name")));
        sname.setResponder(s -> { schematicName = s; ClientActions.sendRoadEdit(RoadEdit.of(RoadEdit.Op.SCHEMATIC_NAME, s)); });
        addRenderableWidget(sname);
        addRenderableWidget(Checkbox.builder(Component.translatable("curvegen.gui.overwrite"), font).pos(left + 102, top + by).selected(overwrite)
                .onValueChange((cb, v) -> overwrite = v).build());
        int bx = 184;
        Button export = button(bx, by, 100, Component.translatable("curvegen.gui.export"), b -> {
            RoadNetwork n = RoadClientCache.current();
            if (n != null) status = ClientActions.exportRoad(n, state().selectedNode(), schematicName, overwrite);
        });
        export.active = net != null;
        button(bx + 104, by, 52, Component.translatable("curvegen.gui.place"), b -> { ClientActions.sendRoad(RoadActionPayload.Action.PLACE); onClose(); });
        button(bx + 160, by, Math.max(60, panelW - 8 - (bx + 160)), Component.translatable("curvegen.gui.undo_place"), b -> ClientActions.sendRoad(RoadActionPayload.Action.UNDO_PLACE));
        button(8, by - row, 136, Component.translatable("curvegen.gui.deploy"), b -> ClientActions.sendRoad(RoadActionPayload.Action.DEPLOY_SCHEMATIC));
        statusY = by - row + 5;
    }

    private int statusY;

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
        g.fill(left, top, left + panelW, top + panelH, 0xB0101010);
        g.renderOutline(left, top, panelW, panelH, 0xFF555555);
        for (Object[] l : labels) g.drawString(font, (Component) l[3], left + (int) l[0], top + (int) l[1], (int) l[2], false);
        PreviewManager.Stats stats = PreviewManager.stats();
        Component line = status;
        if (line.getString().isEmpty()) {
            line = stats.warnings().isEmpty()
                    ? Component.translatable("curvegen.road.net.stats", stats.blocks(), stats.layers()).withStyle(ChatFormatting.GRAY)
                    : Component.literal(font.plainSubstrByWidth(stats.warnings().get(0), panelW - 160)).withStyle(ChatFormatting.GOLD);
        }
        g.drawString(font, line, left + 150, top + statusY, 0xFFFFFF, true);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
