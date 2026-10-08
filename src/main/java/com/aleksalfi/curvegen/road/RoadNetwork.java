package com.aleksalfi.curvegen.road;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A named road network: classes, nodes and links, plus who may see or edit it. Immutable; every edit
 * returns a new network.
 *
 * @param owner        UUID string of the creator ("" for none: everyone may edit)
 * @param shares       per-player access by UUID string
 * @param playerNames  last known names of the owner and shared players, for display
 * @param publicAccess access granted to everyone else
 */
public record RoadNetwork(String name, Map<String, RoadClass> classes, Map<Integer, RoadNode> nodes, Map<Integer, RoadLink> links,
                          int nextId, int nextLinkId, String defaultClass, String owner, Map<String, Access> shares, Map<String, String> playerNames,
                          Access publicAccess) {

    public static final int MAX_NODES = 2000;
    public static final int MAX_LINKS = 4000;
    public static final int MAX_CLASSES = 16;

    public RoadNetwork {
        classes = Map.copyOf(new LinkedHashMap<>(classes));
        nodes = Map.copyOf(new LinkedHashMap<>(nodes));
        links = Map.copyOf(new LinkedHashMap<>(links));
        shares = Map.copyOf(new LinkedHashMap<>(shares));
        playerNames = Map.copyOf(new LinkedHashMap<>(playerNames));
        if (owner == null) owner = "";
    }

    public static RoadNetwork empty(String name) {
        Map<String, RoadClass> classes = new LinkedHashMap<>();
        for (RoadClass c : List.of(RoadClass.street(), RoadClass.mainRoad(), RoadClass.highway())) classes.put(c.id(), c);
        return new RoadNetwork(name, classes, Map.of(), Map.of(), 1, 1, "street", "", Map.of(), Map.of(), Access.NONE);
    }

    public static RoadNetwork empty(String name, java.util.UUID owner, String ownerName) {
        return empty(name).withOwner(owner, ownerName);
    }

    // ---- access ---------------------------------------------------------------------------------------

    public Access accessOf(java.util.UUID player) {
        if (player == null) return publicAccess;
        String id = player.toString();
        if (owner.isEmpty() || owner.equals(id)) return Access.EDIT;
        Access shared = shares.getOrDefault(id, Access.NONE);
        return shared.atLeast(publicAccess) ? shared : publicAccess;
    }

    public boolean canView(java.util.UUID player, boolean operator) { return operator || accessOf(player).atLeast(Access.VIEW); }
    public boolean canEdit(java.util.UUID player, boolean operator) { return operator || accessOf(player).atLeast(Access.EDIT); }
    public boolean isOwner(java.util.UUID player) { return owner.isEmpty() || owner.equals(player.toString()); }

    public String ownerName() { return playerNames.getOrDefault(owner, owner.isEmpty() ? "-" : owner); }

    private RoadNetwork with(Map<String, Access> newShares, Map<String, String> newNames, String newOwner, Access newPublic) {
        return new RoadNetwork(name, classes, nodes, links, nextId, nextLinkId, defaultClass, newOwner, newShares, newNames, newPublic);
    }

    public RoadNetwork withOwner(java.util.UUID player, String playerName) {
        Map<String, String> names = new LinkedHashMap<>(playerNames);
        names.put(player.toString(), playerName);
        return with(shares, names, player.toString(), publicAccess);
    }

    public RoadNetwork share(java.util.UUID player, String playerName, Access access) {
        Map<String, Access> s = new LinkedHashMap<>(shares);
        Map<String, String> names = new LinkedHashMap<>(playerNames);
        if (access == Access.NONE) { s.remove(player.toString()); if (!owner.equals(player.toString())) names.remove(player.toString()); }
        else { s.put(player.toString(), access); names.put(player.toString(), playerName); }
        return with(s, names, owner, publicAccess);
    }

    public RoadNetwork withPublicAccess(Access access) { return with(shares, playerNames, owner, access); }

    private RoadNetwork copy(Map<String, RoadClass> c, Map<Integer, RoadNode> n, Map<Integer, RoadLink> l, int next, String def) {
        return new RoadNetwork(name, c, n, l, next, nextLinkId, def, owner, shares, playerNames, publicAccess);
    }

    private RoadNetwork copyLinks(Map<Integer, RoadNode> n, Map<Integer, RoadLink> l, int nextLink) {
        return new RoadNetwork(name, classes, n, l, nextId, nextLink, defaultClass, owner, shares, playerNames, publicAccess);
    }

    public RoadClass classOf(RoadLink link) {
        RoadClass c = classes.get(link.classId());
        if (c != null) return c;
        c = classes.get(defaultClass);
        return c != null ? c : RoadClass.street();
    }

    public RoadClass classOrDefault(String id) {
        RoadClass c = classes.get(id);
        return c != null ? c : classOf(new RoadLink(0, 0, 0, defaultClass));
    }

    public List<RoadLink> linksOf(int nodeId) {
        List<RoadLink> out = new ArrayList<>();
        for (RoadLink l : links.values()) if (l.touches(nodeId)) out.add(l);
        return out;
    }

    public RoadLink linkBetween(int a, int b) {
        for (RoadLink l : links.values()) if ((l.a() == a && l.b() == b) || (l.a() == b && l.b() == a)) return l;
        return null;
    }

    public int degree(int nodeId) { return linksOf(nodeId).size(); }

    /** Nearest node to a point, or null if none within {@code maxDist}. */
    public RoadNode nearestNode(double x, double z, double maxDist) {
        RoadNode best = null;
        double bestD = maxDist * maxDist;
        for (RoadNode n : nodes.values()) {
            double d = (n.x() - x) * (n.x() - x) + (n.z() - z) * (n.z() - z);
            if (d <= bestD) { bestD = d; best = n; }
        }
        return best;
    }

    // ---- edits ---------------------------------------------------------------------------------------

    public RoadNetwork addNode(double x, double y, double z) {
        if (nodes.size() >= MAX_NODES) return this;
        Map<Integer, RoadNode> n = new LinkedHashMap<>(nodes);
        n.put(nextId, RoadNode.at(nextId, x, y, z));
        return copy(classes, n, links, nextId + 1, defaultClass);
    }

    public RoadNetwork putNode(RoadNode node) {
        if (!nodes.containsKey(node.id())) return this;
        Map<Integer, RoadNode> n = new LinkedHashMap<>(nodes);
        n.put(node.id(), node);
        return copy(classes, n, links, nextId, defaultClass);
    }

    public RoadNetwork removeNode(int id) {
        if (!nodes.containsKey(id)) return this;
        Map<Integer, RoadNode> n = new LinkedHashMap<>(nodes);
        n.remove(id);
        Map<Integer, RoadLink> l = new LinkedHashMap<>();
        for (RoadLink link : links.values()) if (!link.touches(id)) l.put(link.id(), link);
        // Drop arm settings that pointed at removed links.
        for (RoadNode other : new ArrayList<>(n.values())) {
            RoadNode cleaned = other;
            for (int linkId : other.arms().keySet()) if (!l.containsKey(linkId)) cleaned = cleaned.withoutArm(linkId);
            n.put(cleaned.id(), cleaned);
        }
        return copy(classes, n, l, nextId, defaultClass);
    }

    public RoadNetwork addLink(int a, int b, String classId) {
        if (a == b || !nodes.containsKey(a) || !nodes.containsKey(b) || linkBetween(a, b) != null) return this;
        if (links.size() >= MAX_LINKS) return this;
        Map<Integer, RoadLink> l = new LinkedHashMap<>(links);
        l.put(nextLinkId, new RoadLink(nextLinkId, a, b, classId));
        return copyLinks(nodes, l, nextLinkId + 1);
    }

    public RoadNetwork removeLink(int id) {
        if (!links.containsKey(id)) return this;
        Map<Integer, RoadLink> l = new LinkedHashMap<>(links);
        RoadLink removed = l.remove(id);
        Map<Integer, RoadNode> n = new LinkedHashMap<>(nodes);
        for (int nodeId : new int[]{removed.a(), removed.b()}) {
            RoadNode node = n.get(nodeId);
            if (node != null) n.put(nodeId, node.withoutArm(id));
        }
        return copy(classes, n, l, nextId, defaultClass);
    }

    public RoadNetwork putLink(RoadLink link) {
        if (!links.containsKey(link.id())) return this;
        Map<Integer, RoadLink> l = new LinkedHashMap<>(links);
        l.put(link.id(), link);
        return copy(classes, nodes, l, nextId, defaultClass);
    }

    /**
     * Splits a link at a point: the new node joins the two halves, which keep the class, and arm settings
     * stored for the old link move to the half that touches the same node.
     */
    public RoadNetwork insertNode(int linkId, double x, double y, double z) {
        RoadLink old = links.get(linkId);
        if (old == null || nodes.size() >= MAX_NODES || links.size() + 1 > MAX_LINKS) return this;
        int newId = nextId;
        int la = nextLinkId, lb = nextLinkId + 1;
        Map<Integer, RoadNode> n = new LinkedHashMap<>(nodes);
        n.put(newId, RoadNode.at(newId, x, y, z));
        Map<Integer, RoadLink> l = new LinkedHashMap<>(links);
        l.remove(linkId);
        l.put(la, new RoadLink(la, old.a(), newId, old.classId()));
        l.put(lb, new RoadLink(lb, newId, old.b(), old.classId()));
        RoadNode a = n.get(old.a()), b = n.get(old.b());
        if (a != null && a.arms().containsKey(linkId)) n.put(a.id(), a.withoutArm(linkId).withArm(la, a.arm(linkId)));
        if (b != null && b.arms().containsKey(linkId)) n.put(b.id(), b.withoutArm(linkId).withArm(lb, b.arm(linkId)));
        return new RoadNetwork(name, classes, n, l, nextId + 1, nextLinkId + 2, defaultClass, owner, shares, playerNames, publicAccess);
    }

    public RoadNetwork putClass(RoadClass c) {
        if (!classes.containsKey(c.id()) && classes.size() >= MAX_CLASSES) return this;
        Map<String, RoadClass> m = new LinkedHashMap<>(classes);
        m.put(c.id(), c);
        return copy(m, nodes, links, nextId, defaultClass);
    }

    public RoadNetwork removeClass(String id) {
        if (classes.size() <= 1 || !classes.containsKey(id)) return this;
        Map<String, RoadClass> m = new LinkedHashMap<>(classes);
        m.remove(id);
        String def = defaultClass.equals(id) ? m.keySet().iterator().next() : defaultClass;
        Map<Integer, RoadLink> l = new LinkedHashMap<>();
        for (RoadLink link : links.values()) l.put(link.id(), link.classId().equals(id) ? link.withClassId(def) : link);
        return copy(m, nodes, l, nextId, def);
    }

    public RoadNetwork withDefaultClass(String id) {
        return classes.containsKey(id) ? copy(classes, nodes, links, nextId, id) : this;
    }
}
