package com.aleksalfi.curvegen;

import com.aleksalfi.curvegen.item.RoadPlannerItem;
import com.aleksalfi.curvegen.road.Access;
import com.aleksalfi.curvegen.road.ArmPriority;
import com.aleksalfi.curvegen.road.CornerStyle;
import com.aleksalfi.curvegen.road.LinkDir;
import com.aleksalfi.curvegen.road.NodeKind;
import com.aleksalfi.curvegen.road.RoadClass;
import com.aleksalfi.curvegen.road.RoadEdit;
import com.aleksalfi.curvegen.road.RoadLink;
import com.aleksalfi.curvegen.road.RoadNetwork;
import com.aleksalfi.curvegen.road.RoadNetworks;
import com.aleksalfi.curvegen.road.RoadNode;
import com.aleksalfi.curvegen.road.RoadPlannerState;
import com.aleksalfi.curvegen.road.RoadService;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.function.UnaryOperator;

/** {@code /roadgen}: everything the Road Planner can do, for scripting and remote testing. */
public final class RoadCommands {
    private RoadCommands() {}

    private interface EnumAction { int run(CommandContext<CommandSourceStack> ctx, Enum<?> value) throws CommandSyntaxException; }

    /** {@code linkdir <link> <twoway|forward|reverse>}: forward is from the road's first node to its second. */
    private static com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, ?> linkDir(com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, ?> link) {
        for (LinkDir d : LinkDir.values()) {
            String word = d == LinkDir.TWO_WAY ? "twoway" : d.name().toLowerCase(java.util.Locale.ROOT);
            link.then(Commands.literal(word).executes(ctx -> edit(ctx, RoadEdit.of(RoadEdit.Op.LINK_DIR, IntegerArgumentType.getInteger(ctx, "link"), d.name()))));
        }
        return link;
    }

    private static <E extends Enum<E>> LiteralArgumentBuilder<CommandSourceStack> enumCommand(String name, E[] values, EnumAction action) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(name);
        for (E v : values) root.then(Commands.literal(v.name().toLowerCase(java.util.Locale.ROOT)).executes(ctx -> action.run(ctx, v)));
        return root;
    }

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("roadgen")
                .then(Commands.literal("create").then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> edit(ctx, RoadEdit.of(RoadEdit.Op.NETWORK_CREATE, StringArgumentType.getString(ctx, "name"))))))
                .then(Commands.literal("select").then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> edit(ctx, RoadEdit.of(RoadEdit.Op.NETWORK_SELECT, StringArgumentType.getString(ctx, "name"))))))
                .then(Commands.literal("delete").then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> edit(ctx, RoadEdit.of(RoadEdit.Op.NETWORK_DELETE, StringArgumentType.getString(ctx, "name"))))))
                .then(Commands.literal("list").executes(RoadCommands::list))
                .then(Commands.literal("undo").executes(ctx -> edit(ctx, RoadEdit.of(RoadEdit.Op.UNDO))))
                .then(Commands.literal("info").executes(RoadCommands::info))
                .then(Commands.literal("node")
                        .then(Commands.literal("add").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(RoadCommands::nodeAdd)))
                        .then(Commands.literal("move").then(Commands.argument("id", IntegerArgumentType.integer(0))
                                .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(RoadCommands::nodeMove))))
                        .then(Commands.literal("insert").then(Commands.argument("link", IntegerArgumentType.integer(0))
                                .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(RoadCommands::nodeInsert))))
                        .then(Commands.literal("select").then(Commands.argument("id", IntegerArgumentType.integer(-1))
                                .executes(ctx -> edit(ctx, RoadEdit.of(RoadEdit.Op.NODE_SELECT, IntegerArgumentType.getInteger(ctx, "id"))))))
                        .then(Commands.literal("split").then(Commands.argument("id", IntegerArgumentType.integer(0))
                                .executes(ctx -> edit(ctx, RoadEdit.of(RoadEdit.Op.NODE_SPLIT, IntegerArgumentType.getInteger(ctx, "id"))))))
                        .then(Commands.literal("delete").then(Commands.argument("id", IntegerArgumentType.integer(0))
                                .executes(ctx -> edit(ctx, RoadEdit.of(RoadEdit.Op.NODE_DELETE, IntegerArgumentType.getInteger(ctx, "id"))))))
                        .then(enumCommand("kind", NodeKind.values(), (ctx, v) -> node(ctx, n -> n.withKind((NodeKind) v))))
                        .then(enumCommand("corner", CornerStyle.values(), (ctx, v) -> node(ctx, n -> n.withCorner((CornerStyle) v))))
                        .then(Commands.literal("radius").then(Commands.argument("r", DoubleArgumentType.doubleArg(0, 256))
                                .executes(ctx -> node(ctx, n -> n.withFilletRadius(DoubleArgumentType.getDouble(ctx, "r"))))))
                        .then(Commands.literal("roundabout").then(Commands.argument("radius", DoubleArgumentType.doubleArg(2, 128))
                                .then(Commands.argument("lanes", IntegerArgumentType.integer(1, 2))
                                        .executes(ctx -> node(ctx, n -> n.withKind(NodeKind.ROUNDABOUT)
                                                .withRoundaboutRadius(DoubleArgumentType.getDouble(ctx, "radius"))
                                                .withRoundaboutLanes(IntegerArgumentType.getInteger(ctx, "lanes")))))))
                        .then(Commands.literal("zebra").then(Commands.argument("on", BoolArgumentType.bool())
                                .executes(ctx -> node(ctx, n -> n.withZebra(BoolArgumentType.getBool(ctx, "on")))))))
                .then(Commands.literal("arm").then(Commands.argument("link", IntegerArgumentType.integer(0))
                        .then(enumCommand("priority", ArmPriority.values(), (ctx, v) -> node(ctx, n -> {
                            int link = IntegerArgumentType.getInteger(ctx, "link");
                            return n.withArm(link, n.arm(link).withPriority((ArmPriority) v));
                        })))
                        .then(Commands.literal("zebra").then(Commands.argument("on", BoolArgumentType.bool()).executes(ctx -> node(ctx, n -> {
                            int link = IntegerArgumentType.getInteger(ctx, "link");
                            return n.withArm(link, n.arm(link).withZebra(BoolArgumentType.getBool(ctx, "on")));
                        }))))))
                .then(Commands.literal("link").then(Commands.argument("a", IntegerArgumentType.integer(0)).then(Commands.argument("b", IntegerArgumentType.integer(0))
                        .executes(ctx -> edit(ctx, RoadEdit.of(RoadEdit.Op.LINK_TOGGLE, IntegerArgumentType.getInteger(ctx, "a"), IntegerArgumentType.getInteger(ctx, "b")))))))
                .then(Commands.literal("linkdir").then(linkDir(Commands.argument("link", IntegerArgumentType.integer(0)))))
                .then(Commands.literal("linkset").then(Commands.argument("link", IntegerArgumentType.integer(0))
                        .then(Commands.literal("sidewalk")
                                .then(Commands.literal("class").executes(ctx -> edit(ctx, RoadEdit.of(RoadEdit.Op.LINK_SIDEWALK, IntegerArgumentType.getInteger(ctx, "link"), -1))))
                                .then(Commands.argument("n", IntegerArgumentType.integer(0, 16)).executes(ctx -> edit(ctx, RoadEdit.of(RoadEdit.Op.LINK_SIDEWALK, IntegerArgumentType.getInteger(ctx, "link"), IntegerArgumentType.getInteger(ctx, "n"))))))
                        .then(Commands.literal("edgelines")
                                .then(Commands.literal("class").executes(ctx -> edit(ctx, RoadEdit.of(RoadEdit.Op.LINK_EDGE_LINES, IntegerArgumentType.getInteger(ctx, "link"), -1))))
                                .then(Commands.argument("on", BoolArgumentType.bool()).executes(ctx -> edit(ctx, RoadEdit.of(RoadEdit.Op.LINK_EDGE_LINES, IntegerArgumentType.getInteger(ctx, "link"), BoolArgumentType.getBool(ctx, "on") ? 1 : 0)))))
                        .then(Commands.literal("lanewidth")
                                .then(Commands.literal("class").executes(ctx -> edit(ctx, RoadEdit.of(RoadEdit.Op.LINK_LANE_WIDTH, IntegerArgumentType.getInteger(ctx, "link"), -1))))
                                .then(Commands.argument("n", IntegerArgumentType.integer(2, 32)).executes(ctx -> edit(ctx, RoadEdit.of(RoadEdit.Op.LINK_LANE_WIDTH, IntegerArgumentType.getInteger(ctx, "link"), IntegerArgumentType.getInteger(ctx, "n"))))))
                        .then(Commands.literal("shoulder")
                                .then(Commands.literal("class").executes(ctx -> edit(ctx, RoadEdit.of(RoadEdit.Op.LINK_SHOULDER, IntegerArgumentType.getInteger(ctx, "link"), -1))))
                                .then(Commands.argument("n", IntegerArgumentType.integer(0, 8)).executes(ctx -> edit(ctx, RoadEdit.of(RoadEdit.Op.LINK_SHOULDER, IntegerArgumentType.getInteger(ctx, "link"), IntegerArgumentType.getInteger(ctx, "n"))))))))
                .then(Commands.literal("linkclass").then(Commands.argument("link", IntegerArgumentType.integer(0)).then(Commands.argument("class", StringArgumentType.word())
                        .executes(ctx -> edit(ctx, RoadEdit.of(RoadEdit.Op.LINK_CLASS, IntegerArgumentType.getInteger(ctx, "link"), StringArgumentType.getString(ctx, "class")))))))
                .then(Commands.literal("class")
                        .then(Commands.literal("default").then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> edit(ctx, RoadEdit.of(RoadEdit.Op.CLASS_DEFAULT, StringArgumentType.getString(ctx, "id"))))))
                        .then(Commands.literal("remove").then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> edit(ctx, RoadEdit.of(RoadEdit.Op.CLASS_REMOVE, StringArgumentType.getString(ctx, "id"))))))
                        .then(Commands.literal("add").then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> edit(ctx, RoadEdit.roadClass(RoadClass.street().withName(StringArgumentType.getString(ctx, "id")).withId(StringArgumentType.getString(ctx, "id")))))))
                        .then(Commands.literal("set").then(Commands.argument("id", StringArgumentType.word())
                                .then(Commands.literal("lanewidth").then(Commands.argument("n", IntegerArgumentType.integer(2, 32)).executes(ctx -> cls(ctx, c -> c.withLaneWidth(IntegerArgumentType.getInteger(ctx, "n"))))))
                                .then(Commands.literal("lanes").then(Commands.argument("n", IntegerArgumentType.integer(1, 4)).executes(ctx -> cls(ctx, c -> c.withLanesPerDirection(IntegerArgumentType.getInteger(ctx, "n"))))))
                                .then(Commands.literal("sidewalk").then(Commands.argument("n", IntegerArgumentType.integer(0, 16)).executes(ctx -> cls(ctx, c -> c.withSidewalkWidth(IntegerArgumentType.getInteger(ctx, "n"))))))
                                .then(Commands.literal("curb").then(Commands.argument("n", IntegerArgumentType.integer(0, 7)).executes(ctx -> cls(ctx, c -> c.withCurbLayers(IntegerArgumentType.getInteger(ctx, "n"))))))
                                .then(Commands.literal("edgelines").then(Commands.argument("on", BoolArgumentType.bool()).executes(ctx -> cls(ctx, c -> c.withEdgeLines(BoolArgumentType.getBool(ctx, "on"))))))
                                .then(Commands.literal("smoothedges").then(Commands.argument("on", BoolArgumentType.bool()).executes(ctx -> cls(ctx, c -> c.withSmoothEdges(BoolArgumentType.getBool(ctx, "on"))))))
                                .then(Commands.literal("shoulder").then(Commands.argument("n", IntegerArgumentType.integer(0, 8)).executes(ctx -> cls(ctx, c -> c.withShoulderWidth(IntegerArgumentType.getInteger(ctx, "n"))))))
                                .then(Commands.literal("mergelength").then(Commands.argument("n", IntegerArgumentType.integer(20, 200)).executes(ctx -> cls(ctx, c -> c.withMergeLength(IntegerArgumentType.getInteger(ctx, "n"))))))
                                .then(Commands.literal("arrows").then(Commands.argument("on", BoolArgumentType.bool()).executes(ctx -> cls(ctx, c -> c.withPaintArrows(BoolArgumentType.getBool(ctx, "on"))))))
                                .then(Commands.literal("name").then(Commands.argument("name", StringArgumentType.greedyString()).executes(ctx -> cls(ctx, c -> c.withName(StringArgumentType.getString(ctx, "name"))))))
                                .then(Commands.literal("asphalt").then(Commands.argument("block", StringArgumentType.greedyString()).executes(ctx -> cls(ctx, c -> c.withAsphalt(StringArgumentType.getString(ctx, "block"))))))
                                .then(Commands.literal("line").then(Commands.argument("block", StringArgumentType.greedyString()).executes(ctx -> cls(ctx, c -> c.withLine(StringArgumentType.getString(ctx, "block"))))))
                                .then(Commands.literal("curbblock").then(Commands.argument("block", StringArgumentType.greedyString()).executes(ctx -> cls(ctx, c -> c.withCurb(StringArgumentType.getString(ctx, "block"))))))
                                .then(Commands.literal("sidewalkblock").then(Commands.argument("block", StringArgumentType.greedyString()).executes(ctx -> cls(ctx, c -> c.withSidewalk(StringArgumentType.getString(ctx, "block")))))))))
                .then(Commands.literal("share").then(Commands.argument("player", StringArgumentType.word())
                        .then(enumCommand("access", Access.values(), (ctx, v) -> edit(ctx, RoadEdit.share(StringArgumentType.getString(ctx, "player"), (Access) v))))))
                .then(enumCommand("public", Access.values(), (ctx, v) -> edit(ctx, RoadEdit.publicAccess((Access) v))))
                .then(Commands.literal("place").executes(ctx -> { RoadService.place(player(ctx), planner(ctx)); return 1; }))
                .then(Commands.literal("undoplace").executes(ctx -> { RoadService.undoPlace(player(ctx)); return 1; }))
                .then(Commands.literal("deploy").executes(ctx -> { RoadService.deploy(player(ctx)); return 1; })));
    }

    private static CommandSyntaxException fail(Component message) {
        return new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(message).create();
    }

    private static ServerPlayer player(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return ctx.getSource().getPlayerOrException();
    }

    private static ItemStack planner(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ItemStack stack = RoadPlannerItem.held(player(ctx));
        if (stack == null) throw fail(Component.translatable("curvegen.road.hold_planner"));
        return stack;
    }

    private static int edit(CommandContext<CommandSourceStack> ctx, RoadEdit edit) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        return RoadService.apply(player, planner(ctx), edit, true) ? 1 : 0;
    }

    private static RoadNetwork network(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        RoadNetwork net = RoadService.current(player(ctx), planner(ctx));
        if (net == null) throw fail(Component.translatable("curvegen.road.no_network"));
        return net;
    }

    /** Edits the selected node (or the one given by /roadgen node select). */
    private static int node(CommandContext<CommandSourceStack> ctx, UnaryOperator<RoadNode> change) throws CommandSyntaxException {
        RoadNetwork net = network(ctx);
        RoadPlannerState state = RoadPlannerItem.getState(planner(ctx));
        RoadNode node = net.nodes().get(state.selectedNode());
        if (node == null) throw fail(Component.translatable("curvegen.road.no_selection"));
        return edit(ctx, RoadEdit.node(change.apply(node)));
    }

    private static int cls(CommandContext<CommandSourceStack> ctx, UnaryOperator<RoadClass> change) throws CommandSyntaxException {
        RoadNetwork net = network(ctx);
        String id = StringArgumentType.getString(ctx, "id");
        RoadClass c = net.classes().get(id);
        if (c == null) throw fail(Component.translatable("curvegen.road.class_unknown", id));
        return edit(ctx, RoadEdit.roadClass(change.apply(c)));
    }

    private static int nodeAdd(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
        if (!player.serverLevel().isInWorldBounds(pos)) throw fail(Component.translatable("curvegen.cmd.out_of_world"));
        return edit(ctx, RoadEdit.at(RoadEdit.Op.NODE_ADD, -1, pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5));
    }

    private static int nodeMove(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
        if (!player.serverLevel().isInWorldBounds(pos)) throw fail(Component.translatable("curvegen.cmd.out_of_world"));
        return edit(ctx, RoadEdit.at(RoadEdit.Op.NODE_MOVE, IntegerArgumentType.getInteger(ctx, "id"), pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5));
    }

    private static int nodeInsert(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
        if (!player.serverLevel().isInWorldBounds(pos)) throw fail(Component.translatable("curvegen.cmd.out_of_world"));
        return edit(ctx, RoadEdit.at(RoadEdit.Op.NODE_INSERT, IntegerArgumentType.getInteger(ctx, "link"), pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5));
    }

    private static int list(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        StringBuilder sb = new StringBuilder();
        for (RoadNetwork n : RoadNetworks.get(player.server).visibleTo(player.getUUID(), RoadService.operator(player))) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(n.name()).append(" (").append(n.ownerName()).append(", ").append(n.nodes().size()).append(" nodes)");
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("curvegen.road.list", sb.length() == 0 ? "-" : sb.toString()), false);
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        RoadNetwork net = network(ctx);
        RoadPlannerState state = RoadPlannerItem.getState(planner(ctx));
        StringBuilder sb = new StringBuilder();
        sb.append("Network ").append(net.name()).append(" owner ").append(net.ownerName()).append(", public ").append(net.publicAccess().name().toLowerCase(java.util.Locale.ROOT))
                .append(", shares ").append(net.shares().size()).append(", nodes ").append(net.nodes().size()).append(", links ").append(net.links().size())
                .append(", default class ").append(net.defaultClass()).append(", selected node ").append(state.selectedNode()).append("\n");
        for (RoadNode n : net.nodes().values()) {
            sb.append("  node ").append(n.id()).append(" @ ").append((int) n.x()).append(',').append((int) n.y()).append(',').append((int) n.z())
                    .append(' ').append(n.kind().name().toLowerCase(java.util.Locale.ROOT)).append(" links:");
            for (RoadLink l : net.linksOf(n.id())) sb.append(' ').append(l.id()).append("->").append(l.other(n.id())).append('(').append(l.classId()).append(')');
            sb.append('\n');
        }
        String text = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }
}
