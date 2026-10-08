package com.aleksalfi.curvegen.road;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class RoadCodecs {
    private RoadCodecs() {}

    private static <E extends Enum<E>> Codec<E> simpleEnum(E[] values, E fallback) {
        return Codec.STRING.xmap(s -> {
            for (E e : values) if (e.name().equalsIgnoreCase(s)) return e;
            return fallback;
        }, e -> e.name().toLowerCase(Locale.ROOT));
    }

    public static final Codec<ArmPriority> PRIORITY = simpleEnum(ArmPriority.values(), ArmPriority.GIVE_WAY);
    public static final Codec<NodeKind> KIND = simpleEnum(NodeKind.values(), NodeKind.AUTO);
    public static final Codec<CornerStyle> CORNER = simpleEnum(CornerStyle.values(), CornerStyle.FILLET);
    public static final Codec<Access> ACCESS = simpleEnum(Access.values(), Access.NONE);

    public static final Codec<ArmSettings> ARM = RecordCodecBuilder.create(i -> i.group(
            PRIORITY.optionalFieldOf("priority", ArmPriority.GIVE_WAY).forGetter(ArmSettings::priority),
            Codec.BOOL.optionalFieldOf("zebra", false).forGetter(ArmSettings::zebra)
    ).apply(i, ArmSettings::new));

    public static final Codec<RoadClass> ROAD_CLASS = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("id").forGetter(RoadClass::id),
            Codec.STRING.optionalFieldOf("name", "Road").forGetter(RoadClass::name),
            Codec.INT.optionalFieldOf("lane_width", 6).forGetter(RoadClass::laneWidth),
            Codec.INT.optionalFieldOf("lanes_per_direction", 1).forGetter(RoadClass::lanesPerDirection),
            Codec.INT.optionalFieldOf("sidewalk_width", 0).forGetter(RoadClass::sidewalkWidth),
            Codec.INT.optionalFieldOf("curb_layers", 3).forGetter(RoadClass::curbLayers),
            Codec.BOOL.optionalFieldOf("edge_lines", true).forGetter(RoadClass::edgeLines),
            Codec.STRING.optionalFieldOf("asphalt", "minecraft:gray_concrete").forGetter(RoadClass::asphalt),
            Codec.STRING.optionalFieldOf("line", "minecraft:white_concrete").forGetter(RoadClass::line),
            Codec.STRING.optionalFieldOf("curb", "minecraft:stone_bricks").forGetter(RoadClass::curb),
            Codec.STRING.optionalFieldOf("sidewalk", "minecraft:smooth_stone").forGetter(RoadClass::sidewalk)
    ).apply(i, RoadClass::new));

    private static final Codec<Map<Integer, ArmSettings>> ARMS = Codec.unboundedMap(
            Codec.STRING.xmap(Integer::parseInt, String::valueOf), ARM);

    public static final Codec<RoadNode> NODE = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("id").forGetter(RoadNode::id),
            Codec.DOUBLE.optionalFieldOf("x", 0.0).forGetter(RoadNode::x),
            Codec.DOUBLE.optionalFieldOf("y", 64.0).forGetter(RoadNode::y),
            Codec.DOUBLE.optionalFieldOf("z", 0.0).forGetter(RoadNode::z),
            KIND.optionalFieldOf("kind", NodeKind.AUTO).forGetter(RoadNode::kind),
            CORNER.optionalFieldOf("corner", CornerStyle.FILLET).forGetter(RoadNode::corner),
            Codec.DOUBLE.optionalFieldOf("fillet_radius", 12.0).forGetter(RoadNode::filletRadius),
            Codec.DOUBLE.optionalFieldOf("roundabout_radius", 8.0).forGetter(RoadNode::roundaboutRadius),
            Codec.INT.optionalFieldOf("roundabout_lanes", 1).forGetter(RoadNode::roundaboutLanes),
            Codec.BOOL.optionalFieldOf("zebra", false).forGetter(RoadNode::zebra),
            ARMS.optionalFieldOf("arms", Map.of()).forGetter(RoadNode::arms)
    ).apply(i, RoadNode::new));

    public static final Codec<RoadLink> LINK = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("id").forGetter(RoadLink::id),
            Codec.INT.fieldOf("a").forGetter(RoadLink::a),
            Codec.INT.fieldOf("b").forGetter(RoadLink::b),
            Codec.STRING.optionalFieldOf("class", "street").forGetter(RoadLink::classId)
    ).apply(i, RoadLink::new));

    public static final Codec<RoadNetwork> NETWORK = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("name").forGetter(RoadNetwork::name),
            ROAD_CLASS.listOf().optionalFieldOf("classes", List.of()).forGetter(n -> List.copyOf(n.classes().values())),
            NODE.listOf().optionalFieldOf("nodes", List.of()).forGetter(n -> List.copyOf(n.nodes().values())),
            LINK.listOf().optionalFieldOf("links", List.of()).forGetter(n -> List.copyOf(n.links().values())),
            Codec.INT.optionalFieldOf("next_id", 1).forGetter(RoadNetwork::nextId),
            Codec.STRING.optionalFieldOf("default_class", "street").forGetter(RoadNetwork::defaultClass),
            Codec.STRING.optionalFieldOf("owner", "").forGetter(RoadNetwork::owner),
            Codec.unboundedMap(Codec.STRING, ACCESS).optionalFieldOf("shares", Map.of()).forGetter(RoadNetwork::shares),
            Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("player_names", Map.of()).forGetter(RoadNetwork::playerNames),
            ACCESS.optionalFieldOf("public_access", Access.NONE).forGetter(RoadNetwork::publicAccess)
    ).apply(i, (name, classes, nodes, links, nextId, def, owner, shares, names, pub) -> {
        Map<String, RoadClass> cm = new java.util.LinkedHashMap<>();
        if (classes.isEmpty()) cm.putAll(RoadNetwork.empty(name).classes());
        for (RoadClass c : classes) cm.put(c.id(), c);
        Map<Integer, RoadNode> nm = new java.util.LinkedHashMap<>();
        for (RoadNode n : nodes) nm.put(n.id(), n);
        Map<Integer, RoadLink> lm = new java.util.LinkedHashMap<>();
        for (RoadLink l : links) if (nm.containsKey(l.a()) && nm.containsKey(l.b())) lm.put(l.id(), l);
        int next = nextId;
        for (int id : nm.keySet()) next = Math.max(next, id + 1);
        for (int id : lm.keySet()) next = Math.max(next, id + 1);
        return new RoadNetwork(name, cm, nm, lm, next, cm.containsKey(def) ? def : cm.keySet().iterator().next(), owner, shares, names, pub);
    }));

    public static final StreamCodec<ByteBuf, RoadNetwork> NETWORK_STREAM =
            ByteBufCodecs.fromCodec(NETWORK, () -> NbtAccounter.create(4_194_304L));
}
