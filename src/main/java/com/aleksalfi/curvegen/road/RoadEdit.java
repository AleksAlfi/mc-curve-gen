package com.aleksalfi.curvegen.road;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Optional;

/** One edit of a road network sent by a client (or applied by a command). The server checks access. */
public record RoadEdit(Op op, int id, int id2, String text, Optional<RoadNode> node, Optional<RoadClass> roadClass, Access access) {

    public enum Op {
        NETWORK_CREATE, NETWORK_SELECT, NETWORK_DELETE, NETWORK_RENAME,
        NODE_UPDATE, NODE_DELETE, NODE_SELECT, LINK_TOGGLE, LINK_CLASS, LINK_DELETE,
        CLASS_PUT, CLASS_REMOVE, CLASS_DEFAULT, SHARE, PUBLIC_ACCESS, SCHEMATIC_NAME,
        /** Add a node at the position in {@code node}, linked to the selected node; selects it. */
        NODE_ADD,
        /** Click on node {@code id}: select it, connect it to the selected node, or deselect it when it is the selected one. */
        NODE_CLICK,
        /** Split link {@code id} with a new node at the position in {@code node} (linked to the selected node); selects it. */
        NODE_INSERT,
        /** Move node {@code id} to the position in {@code node}. */
        NODE_MOVE,
        /** Restore the previous version of the network. */
        UNDO,
        /** Set link {@code id}'s direction to {@code text} (a {@link LinkDir} name). */
        LINK_DIR,
        /** Per-road overrides of link {@code id}: {@code id2} is the value, -1 clears it (inherit from the class). */
        LINK_SIDEWALK, LINK_EDGE_LINES, LINK_SHOULDER, LINK_LANE_WIDTH, LINK_LANES,
        /** Make node {@code id} a split: set the directions of its two non-trunk roads. */
        NODE_SPLIT
    }

    public static final Codec<RoadEdit> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.xmap(s -> Op.valueOf(s), Op::name).fieldOf("op").forGetter(RoadEdit::op),
            Codec.INT.optionalFieldOf("id", -1).forGetter(RoadEdit::id),
            Codec.INT.optionalFieldOf("id2", -1).forGetter(RoadEdit::id2),
            Codec.STRING.optionalFieldOf("text", "").forGetter(RoadEdit::text),
            RoadCodecs.NODE.optionalFieldOf("node").forGetter(RoadEdit::node),
            RoadCodecs.ROAD_CLASS.optionalFieldOf("class").forGetter(RoadEdit::roadClass),
            RoadCodecs.ACCESS.optionalFieldOf("access", Access.NONE).forGetter(RoadEdit::access)
    ).apply(i, RoadEdit::new));

    public static RoadEdit of(Op op) { return new RoadEdit(op, -1, -1, "", Optional.empty(), Optional.empty(), Access.NONE); }
    public static RoadEdit of(Op op, int id) { return new RoadEdit(op, id, -1, "", Optional.empty(), Optional.empty(), Access.NONE); }
    public static RoadEdit of(Op op, int id, int id2) { return new RoadEdit(op, id, id2, "", Optional.empty(), Optional.empty(), Access.NONE); }
    public static RoadEdit of(Op op, String text) { return new RoadEdit(op, -1, -1, text, Optional.empty(), Optional.empty(), Access.NONE); }
    public static RoadEdit of(Op op, int id, String text) { return new RoadEdit(op, id, -1, text, Optional.empty(), Optional.empty(), Access.NONE); }
    /** An edit that carries a world position (in a throw-away node). */
    public static RoadEdit at(Op op, int id, double x, double y, double z) {
        return new RoadEdit(op, id, -1, "", Optional.of(RoadNode.at(-1, x, y, z)), Optional.empty(), Access.NONE);
    }
    public static RoadEdit node(RoadNode node) { return new RoadEdit(Op.NODE_UPDATE, node.id(), -1, "", Optional.of(node), Optional.empty(), Access.NONE); }
    public static RoadEdit roadClass(RoadClass cls) { return new RoadEdit(Op.CLASS_PUT, -1, -1, "", Optional.empty(), Optional.of(cls), Access.NONE); }
    public static RoadEdit share(String playerName, Access access) { return new RoadEdit(Op.SHARE, -1, -1, playerName, Optional.empty(), Optional.empty(), access); }
    public static RoadEdit publicAccess(Access access) { return new RoadEdit(Op.PUBLIC_ACCESS, -1, -1, "", Optional.empty(), Optional.empty(), access); }
}
