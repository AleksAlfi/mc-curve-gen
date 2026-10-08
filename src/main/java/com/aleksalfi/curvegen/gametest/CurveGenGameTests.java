package com.aleksalfi.curvegen.gametest;

import com.aleksalfi.curvegen.CurveGen;
import com.aleksalfi.curvegen.build.BlockPlan;
import com.aleksalfi.curvegen.build.CopycatSupport;
import com.aleksalfi.curvegen.build.PlanCompiler;
import com.aleksalfi.curvegen.build.PlannedBlock;
import com.aleksalfi.curvegen.build.SchematicWriter;
import com.aleksalfi.curvegen.build.WorldPlacer;
import com.aleksalfi.curvegen.plan.ArcMode;
import com.aleksalfi.curvegen.plan.CurvePlan;
import com.aleksalfi.curvegen.plan.LaneSpec;
import com.aleksalfi.curvegen.plan.PlanCodecs;
import com.aleksalfi.curvegen.plan.PlanPoint;
import com.aleksalfi.curvegen.plan.ProfileSpec;
import com.aleksalfi.curvegen.plan.SegmentSpec;
import com.aleksalfi.curvegen.plan.SegmentType;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

@GameTestHolder(CurveGen.MOD_ID)
@PrefixGameTestTemplate(false)
public class CurveGenGameTests {

    private static CurvePlan roadPlan(GameTestHelper helper) {
        BlockPos a = helper.absolutePos(new BlockPos(3, 2, 20));
        BlockPos b = helper.absolutePos(new BlockPos(36, 2, 20));
        ProfileSpec profile = new ProfileSpec(List.of(
                new LaneSpec(1, "minecraft:stone_bricks", ""),
                new LaneSpec(2.5, "minecraft:gray_concrete", ""),
                new LaneSpec(1, "minecraft:white_concrete", "")),
                1, "", 0, true, true, com.aleksalfi.curvegen.plan.ElevationMode.LINEAR, 2);
        CurvePlan plan = new CurvePlan(List.of(), SegmentSpec.defaults().withType(SegmentType.STRAIGHT), profile, "gametest");
        plan = plan.addPoint(new PlanPoint(a.getX() + 0.5, a.getY() + 1, a.getZ() + 0.5));
        plan = plan.addPoint(new PlanPoint(b.getX() + 0.5, b.getY() + 1, b.getZ() + 0.5));
        return plan;
    }

    @GameTest(template = "empty")
    public static void straightRoadPlacesLanesAndEdgeLayers(GameTestHelper helper) {
        CurvePlan plan = roadPlan(helper);
        PlanCompiler.Result result = PlanCompiler.compile(plan, helper.getLevel());
        BlockPlan blocks = result.blocks();
        if (blocks.isEmpty()) helper.fail("no blocks generated");
        WorldPlacer.Report report = WorldPlacer.place(helper.getLevel(), blocks, null, UUID.randomUUID());
        if (report.placed() != blocks.size()) helper.fail("placed " + report.placed() + " of " + blocks.size());

        // Width 4.5 centred on z=20.5 -> covers z in [18.25, 22.75]. Travelling east, lane 0 (stone bricks) is on the
        // north side: z in [18.25, 19.25). Column z=19 is 75% gray concrete, so it is gray; z=18 is a 6/8 stone brick layer.
        helper.assertBlockPresent(Blocks.GRAY_CONCRETE, new BlockPos(20, 2, 20));
        helper.assertBlockPresent(Blocks.GRAY_CONCRETE, new BlockPos(20, 2, 19));
        helper.assertBlockPresent(Blocks.GRAY_CONCRETE, new BlockPos(20, 2, 21));
        helper.assertBlockPresent(Blocks.AIR, new BlockPos(20, 2, 17));
        helper.assertBlockPresent(Blocks.AIR, new BlockPos(20, 2, 23));
        BlockState south = helper.getBlockState(new BlockPos(20, 2, 22));
        if (!CopycatSupport.isLayer(south) || south.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING) != Direction.SOUTH)
            helper.fail("expected a south-facing layer on the south edge, got " + south);
        BlockPos edge = new BlockPos(20, 2, 18);
        BlockState edgeState = helper.getBlockState(edge);
        if (!CopycatSupport.isLayer(edgeState)) helper.fail("expected copycat layer at north edge, got " + edgeState);
        if (edgeState.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING) != Direction.NORTH)
            helper.fail("edge layer facing should be NORTH, got " + edgeState);
        int layers = edgeState.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LAYERS);
        if (layers != 6) helper.fail("expected 6 layers, got " + layers);
        // Orientation check: with FACING=NORTH the filled part must touch the south face (z=1) — the road side.
        AABB bounds = edgeState.getShape(helper.getLevel(), helper.absolutePos(edge)).bounds();
        if (Math.abs(bounds.maxZ - 1.0) > 1e-6 || Math.abs(bounds.minZ - 0.25) > 1e-6)
            helper.fail("layer with FACING=NORTH should fill z in [0.25,1], got " + bounds);
        // Material survives placement.
        BlockEntity be = helper.getBlockEntity(edge);
        if (be == null) helper.fail("no block entity on copycat layer");
        CompoundTag saved = be.saveWithoutMetadata(helper.getLevel().registryAccess());
        String material = saved.getCompound("Material").getString("Name");
        if (!"minecraft:stone_bricks".equals(material)) helper.fail("material was " + material + " in " + saved);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void undoRestoresBlocksWithoutDroppingItems(GameTestHelper helper) {
        CurvePlan plan = roadPlan(helper);
        BlockPlan blocks = PlanCompiler.compile(plan, helper.getLevel()).blocks();
        UUID owner = UUID.randomUUID();
        WorldPlacer.place(helper.getLevel(), blocks, null, owner);
        // Place twice; the second undo turns copycat layers back into air, which makes them pop their material.
        WorldPlacer.place(helper.getLevel(), blocks, null, owner);
        WorldPlacer.Report undo = WorldPlacer.undo(helper.getLevel().getServer(), null, owner);
        if (undo == null || undo.placed() != blocks.size()) helper.fail("undo restored " + (undo == null ? "nothing" : undo.placed()));
        helper.assertBlockPresent(Blocks.GRAY_CONCRETE, new BlockPos(20, 2, 20));
        WorldPlacer.Report undo2 = WorldPlacer.undo(helper.getLevel().getServer(), null, owner);
        if (undo2 == null) helper.fail("second undo missing");
        if (undo2.itemsReturned() == 0) helper.fail("expected removed copycat layers to yield material items");
        helper.assertBlockPresent(Blocks.AIR, new BlockPos(20, 2, 20));
        helper.assertBlockPresent(Blocks.AIR, new BlockPos(20, 2, 18));
        AABB area = AABB.encapsulatingFullBlocks(helper.absolutePos(BlockPos.ZERO), helper.absolutePos(new BlockPos(40, 12, 40)));
        int items = helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, area).size();
        if (items != 0) helper.fail(items + " item entities were dropped");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void roadNetworkWithJunctionPlacesLanesLinesAndSidewalks(GameTestHelper helper) {
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        // T-junction inside the 40x40 template: main road east-west at z=20, side road south from x=20.
        com.aleksalfi.curvegen.road.RoadNetwork network = com.aleksalfi.curvegen.road.RoadNetwork.empty("test")
                .addNode(o.getX() + 2.5, o.getY() + 3, o.getZ() + 20.5).addNode(o.getX() + 30.5, o.getY() + 3, o.getZ() + 20.5)
                .addNode(o.getX() + 38.5, o.getY() + 3, o.getZ() + 20.5).addNode(o.getX() + 30.5, o.getY() + 3, o.getZ() + 38.5)
                .addLink(1, 2, "street").addLink(2, 3, "street").addLink(2, 4, "street");
        network = network.putNode(network.nodes().get(2).withArm(7, new com.aleksalfi.curvegen.road.ArmSettings(com.aleksalfi.curvegen.road.ArmPriority.STOP, false)));
        com.aleksalfi.curvegen.road.RoadCompiler.Result result = com.aleksalfi.curvegen.road.RoadCompiler.compile(network, helper.getLevel());
        BlockPlan blocks = result.blocks();
        if (blocks.isEmpty()) helper.fail("no road blocks generated: " + blocks.warnings());
        WorldPlacer.Report report = WorldPlacer.place(helper.getLevel(), blocks, null, UUID.randomUUID());
        if (report.placed() != blocks.size()) helper.fail("placed " + report.placed() + " of " + blocks.size());
        // Street: lane 6, sidewalk 3, curb 1 (no edge lines). Surface blocks sit at y+2 (height y+3 -> top-1).
        helper.assertBlockPresent(Blocks.GRAY_CONCRETE, new BlockPos(10, 2, 23)); // right lane of the main road
        helper.assertBlockPresent(Blocks.GRAY_CONCRETE, new BlockPos(30, 2, 20)); // junction core
        helper.assertBlockPresent(Blocks.GRAY_CONCRETE, new BlockPos(30, 2, 30)); // side road, still inside the core
        helper.assertBlockPresent(Blocks.STONE_BRICKS, new BlockPos(10, 2, 13)); // curb north side: lateral 6.5..7.5 -> z = 13
        helper.assertBlockPresent(Blocks.SMOOTH_STONE, new BlockPos(10, 2, 11)); // sidewalk
        // Raised sidewalk: a copycat layer on top of the sidewalk block
        BlockState above = helper.getBlockState(new BlockPos(10, 3, 11));
        if (!CopycatSupport.isLayer(above) || above.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING) != Direction.UP)
            helper.fail("expected an upward copycat layer on the sidewalk, got " + above);
        // Centre line of the main road far from the junction must contain white (dashed)
        boolean white = false;
        for (int x = 3; x < 9; x++) white |= helper.getBlockState(new BlockPos(x, 2, 20)).is(Blocks.WHITE_CONCRETE);
        if (!white) helper.fail("no centre line found on the main road");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void rampUsesUpwardLayers(GameTestHelper helper) {
        BlockPos a = helper.absolutePos(new BlockPos(3, 2, 10));
        BlockPos b = helper.absolutePos(new BlockPos(35, 6, 10));
        ProfileSpec profile = new ProfileSpec(List.of(new LaneSpec(3, "minecraft:gray_concrete", "")),
                1, "", 0, true, true, com.aleksalfi.curvegen.plan.ElevationMode.LINEAR, 1);
        CurvePlan plan = new CurvePlan(List.of(), SegmentSpec.defaults().withType(SegmentType.STRAIGHT), profile, "ramp");
        plan = plan.addPoint(new PlanPoint(a.getX() + 0.5, a.getY() + 1, a.getZ() + 0.5));
        plan = plan.addPoint(new PlanPoint(b.getX() + 0.5, b.getY() + 1, b.getZ() + 0.5));
        BlockPlan blocks = PlanCompiler.compile(plan, helper.getLevel()).blocks();
        WorldPlacer.place(helper.getLevel(), blocks, null, UUID.randomUUID());
        int up = 0;
        for (PlannedBlock pb : blocks.blocks().values()) {
            if (CopycatSupport.isLayer(pb.state()) && pb.state().getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING) == Direction.UP) up++;
        }
        if (up == 0) helper.fail("expected upward layers on the ramp");
        // The ramp rises 4 blocks over 32: at x=19 the surface is ~ y+1 + 2 => full block at y=4 (rel) and maybe a layer at 5
        helper.assertBlockPresent(Blocks.GRAY_CONCRETE, new BlockPos(19, 4, 10));
        // No block should float: every full block must have the previous column's top within 1 block
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void arcRoadIsContinuous(GameTestHelper helper) {
        BlockPos a = helper.absolutePos(new BlockPos(3, 2, 3));
        BlockPos b = helper.absolutePos(new BlockPos(36, 2, 36));
        ProfileSpec profile = new ProfileSpec(List.of(new LaneSpec(3, "minecraft:gray_concrete", "")),
                1, "", 0, true, false, com.aleksalfi.curvegen.plan.ElevationMode.FLAT, 1);
        CurvePlan plan = new CurvePlan(List.of(), SegmentSpec.defaults().withType(SegmentType.ARC)
                .withArcMode(ArcMode.TANGENT).withHeading(com.aleksalfi.curvegen.plan.Heading.EAST), profile, "arc");
        plan = plan.addPoint(new PlanPoint(a.getX() + 0.5, a.getY() + 1, a.getZ() + 0.5));
        plan = plan.addPoint(new PlanPoint(b.getX() + 0.5, b.getY() + 1, b.getZ() + 0.5));
        if (plan.segments().size() != 1) helper.fail("arc should be complete after the end point, segments=" + plan.segments().size());
        BlockPlan blocks = PlanCompiler.compile(plan, helper.getLevel()).blocks();
        WorldPlacer.place(helper.getLevel(), blocks, null, UUID.randomUUID());
        // Quarter circle of radius 33 from (3,3) heading east, ending heading south at (36,36): the centre is at (3,36).
        // The point at 45° is at (3 + 33*0.707, 36 - 33*0.707) ~= (26.3, 12.7)
        helper.assertBlockPresent(Blocks.GRAY_CONCRETE, new BlockPos(26, 2, 12));
        helper.assertBlockPresent(Blocks.GRAY_CONCRETE, new BlockPos(3, 2, 3));
        helper.assertBlockPresent(Blocks.GRAY_CONCRETE, new BlockPos(36, 2, 36));
        // Far from the arc nothing is placed
        helper.assertBlockPresent(Blocks.AIR, new BlockPos(10, 2, 30));
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void schematicRoundTripsThroughStructureTemplate(GameTestHelper helper) {
        try {
            CurvePlan plan = roadPlan(helper);
            BlockPlan blocks = PlanCompiler.compile(plan, helper.getLevel()).blocks();
            Path dir = Files.createTempDirectory("curvegen-test");
            Path file = SchematicWriter.write(blocks, dir, "road", true);
            CompoundTag nbt = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
            StructureTemplate template = new StructureTemplate();
            template.load(helper.getLevel().holderLookup(Registries.BLOCK), nbt);
            net.minecraft.core.Vec3i size = template.getSize();
            BlockPos expected = blocks.max().subtract(blocks.min()).offset(1, 1, 1);
            if (!size.equals(expected)) helper.fail("template size " + size + " != " + expected);
            if (!nbt.contains(SchematicWriter.ROOT_TAG)) helper.fail("missing curvegen anchor tag");
            // Place the template elsewhere in the test area and verify a copycat material survived serialization.
            BlockPos at = helper.absolutePos(new BlockPos(2, 6, 2));
            template.placeInWorld(helper.getLevel(), at, at, new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings(), helper.getLevel().getRandom(), 2);
            BlockPos edgeRel = new BlockPos(20, 2, 18).subtract(blocks.min().subtract(helper.absolutePos(BlockPos.ZERO)));
            BlockPos edgeAbs = at.offset(edgeRel);
            BlockState s = helper.getLevel().getBlockState(edgeAbs);
            if (!CopycatSupport.isLayer(s)) helper.fail("expected layer after template placement at " + edgeAbs + ", got " + s);
            BlockEntity be = helper.getLevel().getBlockEntity(edgeAbs);
            if (be == null) helper.fail("no block entity after template placement");
            String material = be.saveWithoutMetadata(helper.getLevel().registryAccess()).getCompound("Material").getString("Name");
            if (!"minecraft:stone_bricks".equals(material)) helper.fail("material after template placement was '" + material + "'");
            // Plan codec round trip
            var json = PlanCodecs.PLAN.encodeStart(JsonOps.INSTANCE, plan).getOrThrow();
            CurvePlan back = PlanCodecs.PLAN.parse(JsonOps.INSTANCE, json).getOrThrow();
            if (!back.equals(plan)) helper.fail("plan codec round trip mismatch");
            helper.succeed();
        } catch (net.minecraft.gametest.framework.GameTestAssertException e) {
            throw e;
        } catch (Exception e) {
            helper.fail(e.toString());
        }
    }
}
