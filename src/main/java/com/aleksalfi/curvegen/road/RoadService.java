package com.aleksalfi.curvegen.road;

import com.aleksalfi.curvegen.ModRegistry;
import com.aleksalfi.curvegen.build.BlockPlan;
import com.aleksalfi.curvegen.build.WorldPlacer;
import com.aleksalfi.curvegen.compat.CreateCompat;
import com.aleksalfi.curvegen.item.RoadPlannerItem;
import com.aleksalfi.curvegen.network.Networking;
import com.aleksalfi.curvegen.network.RoadSyncPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Server-side operations on road networks: access checks, edits, sync, placement. */
public final class RoadService {
    private RoadService() {}

    public static boolean operator(ServerPlayer p) { return p.hasPermissions(2); }

    /** The network bound to the player's held planner, if they may view it. */
    @Nullable
    public static RoadNetwork current(ServerPlayer player, ItemStack planner) {
        RoadPlannerState state = RoadPlannerItem.getState(planner);
        if (state.network().isEmpty()) return null;
        RoadNetwork net = RoadNetworks.get(player.server).network(state.network());
        if (net == null || !net.canView(player.getUUID(), operator(player))) return null;
        return net;
    }

    /** Sends the player every network name they can see plus the full copy of their current network. */
    public static void sync(ServerPlayer player) {
        ItemStack planner = RoadPlannerItem.held(player);
        RoadNetworks all = RoadNetworks.get(player.server);
        List<String> names = new ArrayList<>();
        for (RoadNetwork n : all.visibleTo(player.getUUID(), operator(player))) names.add(n.name());
        RoadNetwork current = planner == null ? null : current(player, planner);
        PacketDistributor.sendToPlayer(player, new RoadSyncPayload(names, Optional.ofNullable(current)));
    }

    /** Re-syncs every online player that currently looks at this network. */
    public static void broadcast(MinecraftServer server, String networkName) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            ItemStack planner = RoadPlannerItem.held(p);
            if (planner != null && RoadPlannerItem.getState(planner).network().equals(networkName)) sync(p);
            else if (planner != null) sync(p); // name list may have changed (shares)
        }
    }

    private static void msg(ServerPlayer p, String key, Object... args) {
        p.displayClientMessage(Component.translatable(key, args), false);
    }

    private static void overlay(ServerPlayer p, String key, Object... args) {
        p.displayClientMessage(Component.translatable(key, args), true);
    }

    /** Applies an edit from the player; returns false when refused. */
    public static boolean apply(ServerPlayer player, ItemStack planner, RoadEdit edit) {
        RoadNetworks all = RoadNetworks.get(player.server);
        RoadPlannerState state = RoadPlannerItem.getState(planner);
        UUID uuid = player.getUUID();
        boolean op = operator(player);
        switch (edit.op()) {
            case NETWORK_CREATE -> {
                String name = RoadNetworks.normalize(edit.text());
                if (name.isEmpty()) { msg(player, "curvegen.road.bad_name"); return false; }
                if (all.network(name) != null) { msg(player, "curvegen.road.exists", name); return false; }
                all.put(RoadNetwork.empty(name, uuid, player.getGameProfile().getName()));
                RoadPlannerItem.setState(planner, state.withNetwork(name));
                msg(player, "curvegen.road.created", name);
                broadcast(player.server, name);
                return true;
            }
            case NETWORK_SELECT -> {
                RoadNetwork net = all.network(edit.text());
                if (net == null || !net.canView(uuid, op)) { msg(player, "curvegen.road.not_found", edit.text()); return false; }
                RoadPlannerItem.setState(planner, state.withNetwork(net.name()));
                sync(player);
                return true;
            }
            case NETWORK_DELETE -> {
                RoadNetwork net = all.network(edit.text());
                if (net == null || !(op || net.isOwner(uuid))) { msg(player, "curvegen.road.owner_only"); return false; }
                all.remove(net.name());
                if (state.network().equals(net.name())) RoadPlannerItem.setState(planner, state.withNetwork(""));
                msg(player, "curvegen.road.deleted", net.name());
                broadcast(player.server, net.name());
                return true;
            }
            case SCHEMATIC_NAME -> {
                RoadPlannerItem.setState(planner, state.withSchematicName(edit.text()));
                return true;
            }
            default -> {}
        }
        RoadNetwork net = state.network().isEmpty() ? null : all.network(state.network());
        if (net == null) { msg(player, "curvegen.road.no_network"); return false; }
        if (!net.canView(uuid, op)) { msg(player, "curvegen.road.no_access"); return false; }
        if (edit.op() == RoadEdit.Op.NODE_SELECT) {
            RoadPlannerItem.setState(planner, state.withSelected(edit.id()));
            return true;
        }
        if (edit.op() == RoadEdit.Op.NODE_CLICK) return clickNode(player, planner, all, net, state, edit.id());
        if (edit.op() == RoadEdit.Op.UNDO) {
            if (!net.canEdit(uuid, op)) { msg(player, "curvegen.road.read_only"); return false; }
            RoadNetwork restored = all.undo(net.name());
            if (restored == null) { overlay(player, "curvegen.road.nothing_to_undo"); return false; }
            if (!restored.nodes().containsKey(state.selectedNode())) RoadPlannerItem.setState(planner, state.withSelected(-1));
            overlay(player, "curvegen.road.undone", restored.nodes().size(), restored.links().size());
            broadcast(player.server, restored.name());
            return true;
        }
        if (!net.canEdit(uuid, op)) { msg(player, "curvegen.road.read_only"); return false; }
        RoadNetwork next = net;
        switch (edit.op()) {
            case NODE_ADD -> {
                if (edit.node().isEmpty() || !validPosition(player, edit.node().get())) return false;
                RoadNode p = edit.node().get();
                next = net.addNode(p.x(), p.y(), p.z());
                if (next == net) { overlay(player, "curvegen.road.node_limit"); return false; }
                int newId = next.nextId() - 1;
                if (state.selectedNode() >= 0 && next.nodes().containsKey(state.selectedNode())) {
                    next = next.addLink(state.selectedNode(), newId, next.defaultClass());
                }
                RoadPlannerItem.setState(planner, state.withSelected(newId));
                overlay(player, "curvegen.road.node_added", newId, next.nodes().size());
            }
            case NODE_INSERT -> {
                if (edit.node().isEmpty() || !validPosition(player, edit.node().get())) return false;
                RoadLink link = net.links().get(edit.id());
                if (link == null) return false;
                RoadNode p = edit.node().get();
                next = net.insertNode(link.id(), p.x(), p.y(), p.z());
                if (next == net) { overlay(player, "curvegen.road.node_limit"); return false; }
                int newId = next.nextId() - 1;
                int sel = state.selectedNode();
                if (sel >= 0 && !link.touches(sel) && next.nodes().containsKey(sel)) next = next.addLink(sel, newId, next.defaultClass());
                RoadPlannerItem.setState(planner, state.withSelected(newId));
                overlay(player, "curvegen.road.node_inserted", newId, link.id());
            }
            case NODE_MOVE -> {
                if (edit.node().isEmpty() || !validPosition(player, edit.node().get())) return false;
                RoadNode existing = net.nodes().get(edit.id());
                if (existing == null) return false;
                RoadNode p = edit.node().get();
                next = net.putNode(existing.withPosition(p.x(), p.y(), p.z()));
                overlay(player, "curvegen.road.node_moved", existing.id(), (int) Math.floor(p.x()), (int) p.y(), (int) Math.floor(p.z()));
            }
            case NODE_UPDATE -> {
                if (edit.node().isEmpty() || !net.nodes().containsKey(edit.id())) return false;
                RoadNode incoming = edit.node().get();
                RoadNode existing = net.nodes().get(edit.id());
                // Positions are only changed through clicks; keep the stored one.
                next = net.putNode(incoming.withPosition(existing.x(), existing.y(), existing.z()));
            }
            case NODE_DELETE -> {
                next = net.removeNode(edit.id());
                if (state.selectedNode() == edit.id()) RoadPlannerItem.setState(planner, state.withSelected(-1));
            }
            case LINK_TOGGLE -> {
                RoadLink existing = net.linkBetween(edit.id(), edit.id2());
                next = existing != null ? net.removeLink(existing.id()) : net.addLink(edit.id(), edit.id2(), net.defaultClass());
            }
            case LINK_CLASS -> {
                RoadLink link = net.links().get(edit.id());
                if (link == null || !net.classes().containsKey(edit.text())) return false;
                next = net.putLink(link.withClassId(edit.text()));
            }
            case LINK_DELETE -> next = net.removeLink(edit.id());
            case CLASS_PUT -> { if (edit.roadClass().isPresent()) next = net.putClass(edit.roadClass().get()); }
            case CLASS_REMOVE -> next = net.removeClass(edit.text());
            case CLASS_DEFAULT -> next = net.withDefaultClass(edit.text());
            case NETWORK_RENAME -> { msg(player, "curvegen.road.not_supported"); return false; }
            case SHARE, PUBLIC_ACCESS -> {
                if (!(op || net.isOwner(uuid))) { msg(player, "curvegen.road.owner_only"); return false; }
                if (edit.op() == RoadEdit.Op.PUBLIC_ACCESS) next = net.withPublicAccess(edit.access());
                else {
                    ServerPlayer target = player.server.getPlayerList().getPlayerByName(edit.text());
                    Optional<com.mojang.authlib.GameProfile> profile = target != null ? Optional.of(target.getGameProfile())
                            : player.server.getProfileCache() != null ? player.server.getProfileCache().get(edit.text()) : Optional.empty();
                    if (profile.isEmpty()) { msg(player, "curvegen.road.player_unknown", edit.text()); return false; }
                    if (profile.get().getId().equals(UUID.fromString(net.owner().isEmpty() ? uuid.toString() : net.owner()))) { msg(player, "curvegen.road.is_owner"); return false; }
                    next = net.share(profile.get().getId(), profile.get().getName(), edit.access());
                    msg(player, "curvegen.road.shared", profile.get().getName(), edit.access().name().toLowerCase(java.util.Locale.ROOT));
                }
            }
            default -> {}
        }
        if (next != net && !next.equals(net)) {
            if (edit.op() == RoadEdit.Op.SHARE || edit.op() == RoadEdit.Op.PUBLIC_ACCESS) all.put(next);
            else all.putRemembering(net, next, mergeKey(edit));
            broadcast(player.server, next.name());
        }
        return true;
    }

    /** Field edits of one node, class or road made in quick succession share an undo step; structural edits never merge. */
    private static String mergeKey(RoadEdit edit) {
        return switch (edit.op()) {
            case NODE_UPDATE -> "node:" + edit.id();
            case CLASS_PUT -> "class:" + edit.roadClass().map(RoadClass::id).orElse("");
            case LINK_CLASS -> "link:" + edit.id();
            default -> null;
        };
    }

    /** Right-click on a node: select it, connect it to the selected node, or deselect the selected node itself. */
    private static boolean clickNode(ServerPlayer player, ItemStack planner, RoadNetworks all, RoadNetwork net, RoadPlannerState state, int id) {
        RoadNode target = net.nodes().get(id);
        if (target == null) return false;
        int sel = state.selectedNode();
        if (sel == id) {
            RoadPlannerItem.setState(planner, state.withSelected(-1));
            overlay(player, "curvegen.road.deselected");
            return true;
        }
        if (sel >= 0 && net.nodes().containsKey(sel) && net.linkBetween(sel, id) == null) {
            if (!net.canEdit(player.getUUID(), operator(player))) { overlay(player, "curvegen.road.read_only"); return false; }
            RoadNetwork next = net.addLink(sel, id, net.defaultClass());
            if (next == net) { overlay(player, "curvegen.road.link_limit"); return false; }
            all.putRemembering(net, next);
            overlay(player, "curvegen.road.linked", sel, id);
        } else {
            overlay(player, "curvegen.road.selected", id);
        }
        RoadPlannerItem.setState(planner, state.withSelected(id));
        broadcast(player.server, net.name());
        return true;
    }

    /** Positions must be finite, inside the world and within long click range of the player. */
    private static boolean validPosition(ServerPlayer player, RoadNode p) {
        if (!Double.isFinite(p.x()) || !Double.isFinite(p.y()) || !Double.isFinite(p.z())) return false;
        net.minecraft.world.level.Level level = player.level();
        if (p.y() < level.getMinBuildHeight() || p.y() > level.getMaxBuildHeight() + 1) return false;
        double dx = p.x() - player.getX(), dz = p.z() - player.getZ();
        return dx * dx + dz * dz <= 320 * 320;
    }

    public static void place(ServerPlayer player, ItemStack planner) {
        if (!Networking.mayPlace(player)) { msg(player, "curvegen.msg.no_permission"); return; }
        RoadNetwork net = current(player, planner);
        if (net == null) { msg(player, "curvegen.road.no_network"); return; }
        BlockPlan blocks = RoadCompiler.compile(net, player.serverLevel()).blocks();
        if (blocks.isEmpty()) { msg(player, "curvegen.msg.nothing_to_place"); return; }
        if (blocks.size() > WorldPlacer.MAX_BLOCKS) { msg(player, "curvegen.msg.too_many", blocks.size(), WorldPlacer.MAX_BLOCKS); return; }
        WorldPlacer.Report report = WorldPlacer.place(player.serverLevel(), blocks, player, player.getUUID());
        msg(player, "curvegen.msg.placed", report.placed(), report.skippedUnloaded());
        if (report.itemsReturned() > 0) player.displayClientMessage(Networking.returnedMessage(player, report.itemsReturned()), false);
        if (report.containersReplaced() > 0) msg(player, "curvegen.msg.containers", report.containersReplaced());
        for (String w : blocks.warnings()) player.displayClientMessage(Component.literal("§e" + w), false);
    }

    public static void undoPlace(ServerPlayer player) {
        if (!Networking.mayPlace(player)) { msg(player, "curvegen.msg.no_permission"); return; }
        WorldPlacer.Report report = WorldPlacer.undo(player.server, player, player.getUUID());
        if (report == null) { msg(player, "curvegen.msg.nothing_to_undo"); return; }
        msg(player, "curvegen.msg.undone", report.placed());
        if (report.itemsReturned() > 0) player.displayClientMessage(Networking.returnedMessage(player, report.itemsReturned()), false);
    }

    public static void deploy(ServerPlayer player) { CreateCompat.deployHeldSchematic(player, true); }
}
