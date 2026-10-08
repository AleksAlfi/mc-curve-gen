package com.aleksalfi.curvegen.client.gui;

import com.aleksalfi.curvegen.client.ClientActions;
import com.aleksalfi.curvegen.client.ClientEvents;
import com.aleksalfi.curvegen.client.RoadClientCache;
import com.aleksalfi.curvegen.road.ArmSettings;
import com.aleksalfi.curvegen.road.RoadClass;
import com.aleksalfi.curvegen.road.RoadEdit;
import com.aleksalfi.curvegen.road.RoadLink;
import com.aleksalfi.curvegen.road.RoadNetwork;
import com.aleksalfi.curvegen.road.RoadNode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Settings of one road (link): its class, and priority / zebra at each end node. */
public class RoadLinkScreen extends Screen implements RoadScreen {
    private static final int W = 360, H = 150;
    private final int linkId;
    private int left, top;
    private boolean needsRebuild;
    private final List<Object[]> labels = new ArrayList<>();

    public RoadLinkScreen(int linkId) {
        super(Component.translatable("curvegen.road.link.title"));
        this.linkId = linkId;
    }

    private Button button(int x, int y, int w, Component text, Button.OnPress press) {
        return addRenderableWidget(Button.builder(text, press).bounds(left + x, top + y, w, 18).build());
    }

    private void label(int x, int y, int color, Component text) { labels.add(new Object[]{x, y, color, text}); }

    @Override
    protected void init() {
        labels.clear();
        left = Math.max(0, (width - W) / 2);
        top = Math.max(0, (height - H) / 2);
        RoadNetwork net = RoadClientCache.current();
        RoadLink link = net == null ? null : net.links().get(linkId);
        if (net == null || link == null) {
            label(8, 8, 0xFF5555, Component.translatable("curvegen.road.link.gone"));
            button(8, 24, 100, Component.translatable("curvegen.gui.picker.cancel"), b -> onClose());
            return;
        }
        RoadClass cls = net.classOf(link);
        int y = 6;
        label(8, y, 0x55FFFF, Component.translatable("curvegen.road.link.header", linkId, link.a(), link.b()));
        y += 14;
        label(8, y + 5, 0xAAAAAA, Component.translatable("curvegen.road.link.class"));
        button(60, y, 120, Component.literal(cls.name()), b -> {
            List<String> ids = new ArrayList<>(net.classes().keySet());
            int idx = ids.indexOf(link.classId());
            ClientActions.sendRoadEdit(RoadEdit.of(RoadEdit.Op.LINK_CLASS, linkId, ids.get((idx + 1) % ids.size())));
        });
        button(190, y, W - 8 - 190, Component.translatable("curvegen.road.link.dir." + link.dir().name().toLowerCase(Locale.ROOT), link.a(), link.b()),
                b -> ClientActions.sendRoadEdit(RoadEdit.of(RoadEdit.Op.LINK_DIR, linkId, link.dir().next().name())));
        y += 24;
        label(8, y, 0x55FFFF, Component.translatable("curvegen.road.link.ends"));
        y += 12;
        for (int nodeId : new int[]{link.a(), link.b()}) {
            RoadNode n = net.nodes().get(nodeId);
            if (n == null) continue;
            ArmSettings arm = n.arm(linkId);
            label(8, y + 5, 0xCCCCCC, Component.translatable("curvegen.road.link.at", nodeId, ClientEvents.describeNode(net, n)));
            button(150, y, 90, Component.translatable("curvegen.enum.priority." + arm.priority().name().toLowerCase(Locale.ROOT)),
                    b -> ClientActions.sendRoadEdit(RoadEdit.node(n.withArm(linkId, n.arm(linkId).withPriority(n.arm(linkId).priority().next())))));
            addRenderableWidget(Checkbox.builder(Component.translatable("curvegen.road.node.arm_zebra"), font).pos(left + 246, top + y).selected(arm.zebra())
                    .onValueChange((cb, v) -> ClientActions.sendRoadEdit(RoadEdit.node(n.withArm(linkId, n.arm(linkId).withZebra(v))))).build());
            button(W - 8 - 44, y, 44, Component.translatable("curvegen.road.link.edit_node"), b -> Minecraft.getInstance().setScreen(new RoadNodeScreen(nodeId)));
            y += 22;
        }
        y = H - 26;
        button(8, y, 110, Component.translatable("curvegen.road.link.delete"), b -> { ClientActions.sendRoadEdit(RoadEdit.of(RoadEdit.Op.LINK_DELETE, linkId)); onClose(); });
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
        g.fill(left, top, left + W, top + H, 0xB0101010);
        g.renderOutline(left, top, W, H, 0xFF555555);
        for (Object[] l : labels) g.drawString(font, (Component) l[3], left + (int) l[0], top + (int) l[1], (int) l[2], false);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
