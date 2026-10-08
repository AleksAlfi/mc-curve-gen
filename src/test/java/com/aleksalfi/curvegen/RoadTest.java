package com.aleksalfi.curvegen;

import com.aleksalfi.curvegen.geom.Vec2;
import com.aleksalfi.curvegen.road.ArmPriority;
import com.aleksalfi.curvegen.road.ArmSettings;
import com.aleksalfi.curvegen.road.CellMap;
import com.aleksalfi.curvegen.road.CornerStyle;
import com.aleksalfi.curvegen.road.Junction;
import com.aleksalfi.curvegen.road.LaneKind;
import com.aleksalfi.curvegen.road.LaneProfile;
import com.aleksalfi.curvegen.road.LinkDir;
import com.aleksalfi.curvegen.road.Split;
import com.aleksalfi.curvegen.road.Merge;
import com.aleksalfi.curvegen.road.NodeKind;
import com.aleksalfi.curvegen.road.RoadChain;
import com.aleksalfi.curvegen.road.RoadClass;
import com.aleksalfi.curvegen.road.RoadGeometry;
import com.aleksalfi.curvegen.road.RoadNetwork;
import com.aleksalfi.curvegen.road.RoadNode;
import com.aleksalfi.curvegen.road.RoadPainter;
import com.aleksalfi.curvegen.road.Roundabout;
import com.aleksalfi.curvegen.road.Surface;
import com.aleksalfi.curvegen.road.TaperProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RoadTest {

    private static Surface at(CellMap cells, int x, int z) {
        RoadPainter.Cell c = cells.top(RoadPainter.key(x, z));
        return c == null ? Surface.NONE : c.surface;
    }

    private static int count(CellMap cells, Surface s) {
        return (int) cells.all().stream().filter(c -> c.surface == s).count();
    }

    @Test
    void laneProfileWidthsMatchClass() {
        RoadClass street = RoadClass.street(); // lw 6, 1 lane/dir, sidewalk 3, no edge lines
        LaneProfile p = LaneProfile.of(street);
        assertEquals(2 * street.halfTotal(), p.totalWidth(), 1e-9);
        assertEquals(LaneKind.SIDEWALK, p.kinds()[0]);
        assertEquals(LaneKind.CURB, p.kinds()[1]);
        assertEquals(LaneKind.CENTRE, p.kinds()[LaneProfile.CENTRE_INDEX]);
        assertEquals(LaneKind.SIDEWALK, p.kinds()[p.kinds().length - 1]);
        assertEquals(LaneKind.SIDEWALK, p.kindAt(-9));
        assertEquals(LaneKind.LANE, p.kindAt(-3));
        assertEquals(LaneKind.CENTRE, p.kindAt(0));
        assertNull(p.kindAt(11));
        RoadClass hw = RoadClass.highway(); // 2 lanes/dir, edge lines, no sidewalk
        LaneProfile h = LaneProfile.of(hw);
        assertEquals(2 * hw.halfTotal(), h.totalWidth(), 1e-9);
        assertEquals(LaneKind.EDGE, h.kindAt(-18.5));
        assertEquals(LaneKind.LANE_LINE, h.kindAt(-9.5));
        assertEquals(LaneKind.LANE, h.kindAt(-5));
    }

    @Test
    void straightRoadPaintsSidewalkCurbLanesAndDashedCentre() {
        RoadNetwork net = RoadNetwork.empty("t").addNode(0.5, 64, 0.5).addNode(100.5, 64, 0.5).addLink(1, 2, "street");
        List<RoadChain> chains = RoadGeometry.chains(net);
        assertEquals(1, chains.size());
        CellMap cells = RoadPainter.paint(net, chains);
        // Street: halfTotal = 6.5 + 4 = 10.5 -> z from -10 .. 10 around 0.5
        assertEquals(Surface.ASPHALT, at(cells, 50, 3));
        assertEquals(Surface.ASPHALT, at(cells, 50, -3));
        assertEquals(Surface.CURB, at(cells, 50, -7)); // left of travel (north): lateral 6.5..7.5 is the curb
        assertEquals(Surface.SIDEWALK, at(cells, 50, -9));
        assertEquals(Surface.CURB, at(cells, 50, 7));
        assertEquals(Surface.NONE, at(cells, 50, 12));
        // Centre line at z = 0: dashed (4 on, 8 off) -> both line and asphalt cells along x
        boolean line = false, asphalt = false;
        for (int x = 30; x < 70; x++) { Surface s = at(cells, x, 0); line |= s == Surface.LINE; asphalt |= s == Surface.ASPHALT; }
        assertTrue(line && asphalt, "centre line must be dashed on a plain road");
    }

    @Test
    void filletCornerIsRoundedAndChainHasOneLine() {
        RoadNetwork net = RoadNetwork.empty("c").addNode(0.5, 64, 0.5).addNode(60.5, 64, 0.5).addNode(60.5, 64, 60.5)
                .addLink(1, 2, "street").addLink(2, 3, "street");
        List<RoadChain> chains = RoadGeometry.chains(net);
        assertEquals(1, chains.size(), "pass-through node joins both links into one chain");
        RoadChain chain = chains.get(0);
        // Fillet radius 12 cuts the corner: the centerline never reaches the corner node
        double minDist = Double.MAX_VALUE;
        for (int i = 0; i < chain.line().size; i++) minDist = Math.min(minDist, new Vec2(chain.line().x[i], chain.line().z[i]).distanceTo(new Vec2(60.5, 0.5)));
        assertTrue(minDist > 3 && minDist < 6, "corner cut by fillet, got " + minDist);
        assertTrue(chain.solidRanges().size() == 1, "r=12 < 3*6 => tight bend, solid centre");
    }

    @Test
    void smoothCornerPassesThroughNode() {
        RoadNetwork net = RoadNetwork.empty("c").addNode(0.5, 64, 0.5).addNode(60.5, 64, 0.5).addNode(60.5, 64, 60.5)
                .addLink(1, 2, "street").addLink(2, 3, "street");
        net = net.putNode(net.nodes().get(2).withCorner(CornerStyle.SMOOTH));
        RoadChain chain = RoadGeometry.chains(net).get(0);
        double minDist = Double.MAX_VALUE;
        for (int i = 0; i < chain.line().size; i++) minDist = Math.min(minDist, new Vec2(chain.line().x[i], chain.line().z[i]).distanceTo(new Vec2(60.5, 0.5)));
        assertTrue(minDist < 0.6, "smooth corner passes through the node, got " + minDist);
    }

    @Test
    void tJunctionHasStopLineOnSideRoadAndFilletCorners() {
        // Main road east-west through node 2 at (50.5, 0.5); side road south from node 2.
        RoadNetwork net = RoadNetwork.empty("j").addNode(-100.5, 64, 0.5).addNode(50.5, 64, 0.5).addNode(100.5, 64, 0.5).addNode(50.5, 64, 60.5)
                .addLink(1, 2, "main").addLink(2, 3, "main").addLink(2, 4, "street");
        RoadNode j = net.nodes().get(2);
        assertTrue(RoadGeometry.isJunction(net, j));
        // Side road (link 7) must stop; main road arms have priority.
        net = net.putNode(j.withArm(3, new ArmSettings(ArmPriority.STOP, true)).withArm(1, ArmSettings.DEFAULT.withPriority(ArmPriority.PRIORITY)).withArm(2, ArmSettings.DEFAULT.withPriority(ArmPriority.PRIORITY)));
        List<RoadChain> chains = RoadGeometry.chains(net);
        assertEquals(3, chains.size());
        CellMap cells = RoadPainter.paint(net, chains);
        Junction jn = Junction.of(net, net.nodes().get(2));
        assertEquals(3, jn.arms.size());
        assertEquals(2, jn.fillets.size(), "two rounded corners between the side road and the main road");
        // Junction centre is asphalt, no curb poking through
        assertEquals(Surface.ASPHALT, at(cells, 50, 0));
        assertEquals(Surface.ASPHALT, at(cells, 50, 8));
        // Stop line on the side arm (direction south, u=(0,1)): entry half is left(u) = (1,0) side => x > 50.5,
        // at s in [box, box+1): box = maxHalfTotal(main: 7.5+1+4=12.5) + r(7) + 1 = 20.5 -> z = 21
        int zStop = (int) Math.floor(0.5 + jn.box);
        boolean stop = false;
        for (int x = 51; x <= 56; x++) stop |= at(cells, x, zStop) == Surface.LINE;
        assertTrue(stop, "stop line across the entry lane of the side road at z=" + zStop);
        // No stop line on the exit half (x < 50)
        assertEquals(Surface.ASPHALT, at(cells, 48, zStop));
        // Zebra between the stop line and the junction: alternating stripes across the side road
        int zZebra = zStop - 3;
        boolean zebraLine = false, zebraAsphalt = false;
        for (int x = 46; x <= 55; x++) { Surface s = at(cells, x, zZebra); zebraLine |= s == Surface.LINE; zebraAsphalt |= s == Surface.ASPHALT; }
        assertTrue(zebraLine && zebraAsphalt, "zebra stripes at z=" + zZebra);
        // Main road has priority: its centre line is solid on the approach (within approach distance)
        assertEquals(Surface.LINE, at(cells, 50 - 25, 0));
        assertEquals(Surface.LINE, at(cells, 50 - 28, 0));
        // Far away the centre line is dashed again
        boolean asphaltFar = false;
        for (int x = -80; x < -60; x++) asphaltFar |= at(cells, x, 0) == Surface.ASPHALT;
        assertTrue(asphaltFar);
        // The corner sidewalk is rounded: the cell at the sharp corner of the two sidewalks is asphalt
        Junction.Fillet f = jn.fillets.get(0);
        Vec2 cornerProbe = f.p().add(f.t1().sub(f.p()).scale(0.15)).add(f.t2().sub(f.p()).scale(0.15));
        assertEquals(Surface.ASPHALT, at(cells, (int) Math.floor(cornerProbe.x()), (int) Math.floor(cornerProbe.z())));
    }

    @Test
    void roundaboutHasIslandRingAndArmMouths() {
        RoadNetwork net = RoadNetwork.empty("r").addNode(50.5, 64, 50.5).addNode(0.5, 64, 50.5).addNode(100.5, 64, 50.5).addNode(50.5, 64, 0.5)
                .addLink(1, 2, "main").addLink(1, 3, "main").addLink(1, 4, "main");
        net = net.putNode(net.nodes().get(1).withKind(NodeKind.ROUNDABOUT).withRoundaboutRadius(8));
        List<RoadChain> chains = RoadGeometry.chains(net);
        assertEquals(3, chains.size());
        CellMap cells = RoadPainter.paint(net, chains);
        assertEquals(Surface.ISLAND, at(cells, 50, 50));
        assertEquals(Surface.LINE, at(cells, 50 + 8, 50));       // inner edge line at r in [8, 9)
        assertEquals(Surface.ASPHALT, at(cells, 50 + 12, 50));   // ring lane
        // Outer edge line at r in [16, 17) in a direction with no arm (south-east diagonal)
        int d = (int) Math.round(16.5 / Math.sqrt(2));
        assertEquals(Surface.LINE, at(cells, 50 + d, 50 + d));
        // Arm mouth to the east: asphalt continues through the outer ring on the exit half (south of the axis),
        // and the entry half (north, d > 0) carries the dashed give-way line at the ring.
        assertEquals(Surface.ASPHALT, at(cells, 50 + 17, 50 + 3));
        assertEquals(Surface.LINE, at(cells, 50 + 17, 50 - 3));
        assertEquals(Surface.ASPHALT, at(cells, 50 + 17, 50 - 2));
        // Beyond the flare the arm's own centre line is solid (approach)
        assertEquals(Surface.LINE, at(cells, 50 + 30, 50));
        assertTrue(count(cells, Surface.ISLAND) > 150);
    }

    @Test
    void removingNodeDropsLinksAndArmSettings() {
        RoadNetwork net = RoadNetwork.empty("x").addNode(0, 64, 0).addNode(10, 64, 0).addNode(10, 64, 10)
                .addLink(1, 2, "street").addLink(2, 3, "street");
        net = net.putNode(net.nodes().get(2).withArm(1, new ArmSettings(ArmPriority.STOP, true)));
        net = net.removeNode(1);
        assertEquals(1, net.links().size());
        assertFalse(net.nodes().get(2).arms().containsKey(1));
        assertNull(net.linkBetween(1, 2));
    }

    @Test
    void insertingNodeSplitsLinkKeepsClassAndMovesArmSettings() {
        RoadNetwork net = RoadNetwork.empty("x").addNode(0, 64, 0).addNode(20, 64, 0).addNode(20, 64, 20)
                .addLink(1, 2, "street").addLink(2, 3, "street");
        net = net.putNode(net.nodes().get(1).withArm(1, new ArmSettings(ArmPriority.STOP, true)));
        net = net.putNode(net.nodes().get(2).withArm(1, new ArmSettings(ArmPriority.GIVE_WAY, false)));
        RoadNetwork split = net.insertNode(1, 10, 64, 0);
        assertNotSame(net, split);
        assertEquals(4, split.nodes().size());
        assertEquals(3, split.links().size());
        assertNull(split.links().get(1));
        RoadLinkPair pair = new RoadLinkPair(split.linkBetween(1, 4), split.linkBetween(4, 2));
        assertNotNull(pair.a());
        assertNotNull(pair.b());
        assertEquals("street", pair.a().classId());
        assertEquals("street", pair.b().classId());
        // Arm settings follow the half that still touches their node.
        assertEquals(ArmPriority.STOP, split.nodes().get(1).arm(pair.a().id()).priority());
        assertEquals(ArmPriority.GIVE_WAY, split.nodes().get(2).arm(pair.b().id()).priority());
        assertFalse(split.nodes().get(1).arms().containsKey(1));
        // The new node is a plain pass-through: still one chain from 1 to 3.
        assertEquals(1, RoadGeometry.chains(split).size());
        // Unknown link: no change.
        assertSame(split, split.insertNode(99, 0, 0, 0));
    }

    private record RoadLinkPair(com.aleksalfi.curvegen.road.RoadLink a, com.aleksalfi.curvegen.road.RoadLink b) {}

    @Test
    void closedLoopOfPlainNodesIsOneSmoothRing() {
        RoadNetwork net = RoadNetwork.empty("x").addNode(0, 64, 0).addNode(60, 64, 0).addNode(60, 64, 60).addNode(0, 64, 60)
                .addLink(1, 2, "street").addLink(2, 3, "street").addLink(3, 4, "street").addLink(4, 1, "street");
        List<RoadChain> chains = RoadGeometry.chains(net);
        assertEquals(1, chains.size());
        RoadChain ring = chains.get(0);
        com.aleksalfi.curvegen.geom.Polyline line = ring.line();
        // Closed: the ends meet.
        assertEquals(line.x[0], line.x[line.size - 1], 0.6);
        assertEquals(line.z[0], line.z[line.size - 1], 0.6);
        // Every node, including the one the walk started at, is rounded: no sample lies on a corner.
        for (int[] c : new int[][]{{0, 0}, {60, 0}, {60, 60}, {0, 60}}) {
            double best = Double.MAX_VALUE;
            for (int i = 0; i < line.size; i++) best = Math.min(best, Math.hypot(line.x[i] - c[0], line.z[i] - c[1]));
            assertTrue(best > 3, "corner " + c[0] + "," + c[1] + " should be cut by the fillet, nearest sample " + best);
        }
        assertEquals(0, ring.startBox(), 1e-9);
    }

    /** Random junctions: the core is always asphalt and no arm loses its carriageway to a neighbour's sidewalk. */
    @Test
    void randomJunctionsKeepCoreAndArmsPaved() {
        java.util.Random rnd = new java.util.Random(42);
        for (int trial = 0; trial < 150; trial++) {
            int arms = 3 + rnd.nextInt(3);
            RoadNetwork net = RoadNetwork.empty("x").addNode(0.5, 64, 0.5);
            for (int a = 0; a < arms; a++) {
                double ang = rnd.nextDouble() * Math.PI * 2, len = 30 + rnd.nextDouble() * 80;
                net = net.addNode(Math.rint(Math.cos(ang) * len) + 0.5, 64, Math.rint(Math.sin(ang) * len) + 0.5);
                net = net.addLink(1, a + 2, rnd.nextInt(4) == 0 ? "main" : "street");
            }
            CellMap cells = RoadPainter.paint(net, RoadGeometry.chains(net));
            assertEquals(Surface.ASPHALT, at(cells, 0, 0), "trial " + trial + " centre");
            Junction j = Junction.of(net, net.nodes().get(1));
            for (Junction.Arm arm : j.arms) {
                RoadNode end = net.nodes().get(arm.link().other(1));
                double len = end.xz().distanceTo(net.nodes().get(1).xz());
                for (double s = 2; s < Math.min(len - 3, j.box + 30); s += 1) {
                    int x = (int) Math.floor(arm.u().x() * s + 0.5), z = (int) Math.floor(arm.u().z() * s + 0.5);
                    Surface sf = at(cells, x, z);
                    assertTrue(sf == Surface.ASPHALT || sf == Surface.LINE, "trial " + trial + " arm " + arm.link().id() + " at s=" + s + ": " + sf);
                }
            }
        }
    }

    /** Random roundabouts: the ring is always paved all the way round and every arm keeps its carriageway. */
    @Test
    void randomRoundaboutsKeepRingAndArmsPaved() {
        java.util.Random rnd = new java.util.Random(7);
        for (int trial = 0; trial < 100; trial++) {
            int arms = 3 + rnd.nextInt(5);
            RoadNetwork net = RoadNetwork.empty("x").addNode(0.5, 64, 0.5);
            net = net.putNode(net.nodes().get(1).withKind(NodeKind.ROUNDABOUT).withRoundaboutRadius(4 + rnd.nextInt(8)).withRoundaboutLanes(1 + rnd.nextInt(2)));
            for (int a = 0; a < arms; a++) {
                double ang = rnd.nextDouble() * Math.PI * 2, len = 40 + rnd.nextDouble() * 80;
                net = net.addNode(Math.rint(Math.cos(ang) * len) + 0.5, 64, Math.rint(Math.sin(ang) * len) + 0.5);
                net = net.addLink(1, a + 2, rnd.nextInt(4) == 0 ? "main" : "street");
            }
            CellMap cells = RoadPainter.paint(net, RoadGeometry.chains(net));
            Roundabout r = Roundabout.of(net, net.nodes().get(1));
            double mid = (r.innerRadius + 1 + r.outerRadius - 1) / 2;
            for (int deg = 0; deg < 360; deg += 3) {
                double a = Math.toRadians(deg);
                int x = (int) Math.floor(Math.cos(a) * mid + 0.5), z = (int) Math.floor(Math.sin(a) * mid + 0.5);
                Surface sf = at(cells, x, z);
                assertTrue(sf == Surface.ASPHALT || sf == Surface.LINE, "trial " + trial + " ring at " + deg + " deg: " + sf);
            }
            for (Junction.Arm arm : r.arms) {
                RoadNode end = net.nodes().get(arm.link().other(1));
                double len = end.xz().distanceTo(net.nodes().get(1).xz());
                for (double s = r.outerRadius + 1; s < Math.min(len - 3, r.flareEnd() + 30); s += 1) {
                    int x = (int) Math.floor(arm.u().x() * s + 0.5), z = (int) Math.floor(arm.u().z() * s + 0.5);
                    Surface sf = at(cells, x, z);
                    assertTrue(sf == Surface.ASPHALT || sf == Surface.LINE, "trial " + trial + " arm " + arm.link().id() + " at s=" + s + ": " + sf);
                }
            }
        }
    }

    /** A road that bends right after leaving a junction keeps its full width and sidewalks around the bend. */
    @Test
    void bendNextToJunctionKeepsSidewalks() {
        // T-junction at the origin; the east arm turns north 23 blocks out (inside the old repaint disc).
        RoadNetwork net = RoadNetwork.empty("x").addNode(0.5, 64, 0.5).addNode(-60.5, 64, 0.5).addNode(0.5, 64, 60.5)
                .addNode(23.5, 64, 0.5).addNode(23.5, 64, -60.5)
                .addLink(1, 2, "street").addLink(1, 3, "street").addLink(1, 4, "street").addLink(4, 5, "street");
        CellMap cells = RoadPainter.paint(net, RoadGeometry.chains(net));
        RoadClass street = net.classOrDefault("street");
        // Along the north-going leg beyond the bend, every lateral offset must be painted: lanes, curb, sidewalk.
        for (int z = -20; z >= -40; z -= 5) {
            for (double lat = -street.halfTotal() + 0.5; lat <= street.halfTotal() - 0.5; lat += 1) {
                int x = (int) Math.floor(23.5 + lat);
                assertNotEquals(Surface.NONE, at(cells, x, z), "missing column at x=" + x + " z=" + z);
            }
        }
        // Inner corner between the junction's north edge and the bend: the sidewalk is continuous (no slice).
        int gaps = 0;
        for (int x = 13; x <= 16; x++) for (int z = -16; z <= -11; z++) if (at(cells, x, z) == Surface.NONE) gaps++;
        assertEquals(0, gaps, "holes in the inner corner sidewalk");
    }

    /** A road 8 blocks above another crossing it keeps both surfaces in the shared columns. */
    @Test
    void roadOverRoadKeepsBothLevels() {
        RoadNetwork net = RoadNetwork.empty("x").addNode(-60.5, 64, 0.5).addNode(60.5, 64, 0.5)
                .addNode(0.5, 72, -60.5).addNode(0.5, 72, 60.5)
                .addLink(1, 2, "street").addLink(3, 4, "street");
        CellMap cells = RoadPainter.paint(net, RoadGeometry.chains(net));
        long k = RoadPainter.key(0, 0);
        RoadPainter.Cell low = cells.at(k, 64), high = cells.at(k, 72);
        assertNotNull(low, "lower road lost under the bridge");
        assertNotNull(high, "bridge lost over the road");
        assertEquals(64, low.height, 1e-9);
        assertEquals(72, high.height, 1e-9);
        // The lower road's sidewalk still runs under the bridge, and the bridge's over the lower road.
        assertEquals(Surface.SIDEWALK, cells.at(RoadPainter.key(0, 9), 64).surface);
        assertEquals(Surface.SIDEWALK, cells.at(RoadPainter.key(9, 0), 72).surface);
        // Both roads are complete: every column of each centre line exists at its own level.
        for (int i = -55; i <= 55; i++) {
            assertNotNull(cells.at(RoadPainter.key(i, 0), 64), "lower road missing at x=" + i);
            assertNotNull(cells.at(RoadPainter.key(0, i), 72), "bridge missing at z=" + i);
        }
    }

    /** A ramp leaving a junction follows the core plane through the box (one surface) and climbs beyond it. */
    @Test
    void rampStartsBeyondTheJunctionBox() {
        RoadNetwork net = RoadNetwork.empty("x").addNode(0.5, 64, 0.5).addNode(-60.5, 64, 0.5).addNode(0.5, 64, 60.5)
                .addNode(80.5, 84, 0.5)
                .addLink(1, 2, "street").addLink(1, 3, "street").addLink(1, 4, "street");
        CellMap cells = RoadPainter.paint(net, RoadGeometry.chains(net));
        Junction j = Junction.of(net, net.nodes().get(1));
        // The flat west arm and the 25% east ramp are one through road changing grade here: the core takes
        // the mean grade (12.5%), and the ramp picks up its full grade beyond the box.
        assertEquals(0.10, j.gradient.x(), 0.001);
        assertEquals(0, j.gradient.z(), 0.001);
        for (int x = 0; x < j.box; x++) {
            Vec2 q = new Vec2(x + 0.5, 0.5);
            RoadPainter.Cell c = cells.top(RoadPainter.key(x, 0));
            assertNotNull(c, "ramp arm missing at x=" + x);
            assertEquals(j.heightAt(q), c.height, 0.3, "ramp off the core plane inside the box at x=" + x);
            assertNull(cells.at(RoadPainter.key(x, 0), c.height + 3), "second surface at x=" + x);
        }
        RoadPainter.Cell far = cells.top(RoadPainter.key(70, 0));
        assertTrue(far.height > 78, "ramp should climb after the box, height at x=70 is " + far.height);
    }

    /** A through road on a gentle hill keeps its grade across the junction: the core is a tilted plane, one surface. */
    @Test
    void junctionOnAHillIsATiltedPlane() {
        RoadNetwork net = RoadNetwork.empty("x").addNode(0.5, 64, 0.5).addNode(-100.5, 60, 0.5).addNode(100.5, 68, 0.5)
                .addNode(0.5, 64, 60.5)
                .addLink(1, 2, "street").addLink(1, 3, "street").addLink(1, 4, "street");
        Junction j = Junction.of(net, net.nodes().get(1));
        assertEquals(0.04, j.gradient.x(), 0.002);
        assertEquals(0, j.gradient.z(), 0.002);
        CellMap cells = RoadPainter.paint(net, RoadGeometry.chains(net));
        for (int x = -40; x <= 40; x += 5) {
            RoadPainter.Cell c = cells.top(RoadPainter.key(x, 0));
            assertNotNull(c, "missing at x=" + x);
            assertEquals(64 + x * 0.04, c.height, 0.6, "grade across the junction at x=" + x);
            assertNull(cells.at(RoadPainter.key(x, 0), c.height + 3), "second surface at x=" + x);
        }
        // The level side road is banked to sit on the plane where it leaves the box, and is level again far away.
        int edge = (int) Math.ceil(j.box) + 1;
        for (int x = -5; x <= 5; x += 5) {
            assertEquals(64 + x * 0.04, cells.top(RoadPainter.key(x, edge)).height, 0.35, "side road off the plane at x=" + x + " z=" + edge);
        }
        assertEquals(cells.top(RoadPainter.key(-5, 56)).height, cells.top(RoadPainter.key(5, 56)).height, 0.15, "side road should be level far from the junction");
    }

    /** A steep through road still only tilts the junction core by 10%; the road takes its full grade beyond the box. */
    @Test
    void junctionTiltIsCapped() {
        RoadNetwork net = RoadNetwork.empty("x").addNode(0.5, 64, 0.5).addNode(-60.5, 52, 0.5).addNode(60.5, 76, 0.5)
                .addNode(0.5, 64, 60.5)
                .addLink(1, 2, "street").addLink(1, 3, "street").addLink(1, 4, "street");
        Junction j = Junction.of(net, net.nodes().get(1));
        assertEquals(Junction.MAX_TILT, j.gradient.length(), 1e-6);
        CellMap cells = RoadPainter.paint(net, RoadGeometry.chains(net));
        assertEquals(64 + 10 * Junction.MAX_TILT, cells.top(RoadPainter.key(10, 0)).height, 0.3);
        assertTrue(cells.top(RoadPainter.key(55, 0)).height > 73, "road must still climb at its own grade beyond the box");
        // The side road's banking never exceeds the cap across its width.
        int edge = (int) Math.ceil(j.box) + 1;
        double left = cells.top(RoadPainter.key(-8, edge)).height, right = cells.top(RoadPainter.key(8, edge)).height;
        assertTrue(Math.abs(right - left) <= 16 * Junction.MAX_TILT + 0.3, "banking too steep: " + (right - left));
    }

    /** A ramp joining a level through road: the through road stays level, the ramp meets it at the box edge. */
    @Test
    void rampJoiningLevelRoadDoesNotTiltIt() {
        RoadNetwork net = RoadNetwork.empty("x").addNode(0.5, 80, 0.5).addNode(-60.5, 80, 0.5).addNode(60.5, 80, 0.5)
                .addNode(0.5, 60, 60.5)
                .addLink(1, 2, "street").addLink(1, 3, "street").addLink(1, 4, "street");
        Junction j = Junction.of(net, net.nodes().get(1));
        assertEquals(0, j.gradient.length(), 1e-6);
        CellMap cells = RoadPainter.paint(net, RoadGeometry.chains(net));
        for (int x = -40; x <= 40; x += 4) assertEquals(80, cells.top(RoadPainter.key(x, 0)).height, 0.01, "through road dips at x=" + x);
        for (int z = 1; z < j.box; z++) assertEquals(80, cells.top(RoadPainter.key(0, z)).height, 0.3, "ramp off the plane inside the box at z=" + z);
        assertTrue(cells.top(RoadPainter.key(0, 50)).height < 66, "ramp should descend beyond the box");
    }

    /** A steep arm leaving a level junction eases into its grade: no hinge at the box edge, and no overshoot. */
    @Test
    void steepArmLeavesJunctionWithAVerticalCurve() {
        RoadNetwork net = RoadNetwork.empty("x").addNode(0.5, 80, 0.5).addNode(-60.5, 80, 0.5).addNode(60.5, 80, 0.5)
                .addNode(0.5, 60, 60.5)
                .addLink(1, 2, "street").addLink(1, 3, "street").addLink(1, 4, "street");
        RoadChain arm = null;
        for (RoadChain c : RoadGeometry.chains(net)) if (c.nodeIds().contains(4)) arm = c;
        assertNotNull(arm);
        com.aleksalfi.curvegen.geom.Polyline line = arm.line();
        double prev = Double.NaN, maxStep = 0, prevSlope = Double.NaN, maxKink = 0;
        for (int i = 0; i < line.size; i++) {
            double y = line.y[i];
            assertTrue(y <= 80.01 && y >= 59.99, "overshoot: " + y);
            if (i > 0) {
                double ds = line.s[i] - line.s[i - 1];
                if (ds > 1e-6) {
                    double slope = (y - prev) / ds;
                    maxStep = Math.max(maxStep, Math.abs(slope));
                    if (!Double.isNaN(prevSlope)) maxKink = Math.max(maxKink, Math.abs(slope - prevSlope));
                    prevSlope = slope;
                }
            }
            prev = y;
        }
        assertTrue(maxKink < 0.05, "grade changes abruptly between samples: " + maxKink);
        assertTrue(maxStep > 0.34, "the arm must still get steep in the middle: " + maxStep);
    }

    /** A short link between two junctions never doubles back: the centre line advances monotonically. */
    @Test
    void shortLinkBetweenJunctionsDoesNotFoldBack() {
        RoadNetwork net = RoadNetwork.empty("x")
                .addNode(0.5, 64, 0.5).addNode(30.5, 64, 0.5)            // 1, 2: the two junctions, 30 apart
                .addNode(0.5, 64, -60.5).addNode(0.5, 64, 60.5)          // 3, 4: arms of 1
                .addNode(30.5, 64, -60.5).addNode(30.5, 64, 60.5)        // 5, 6: arms of 2
                .addLink(1, 2, "street").addLink(1, 3, "street").addLink(1, 4, "street").addLink(2, 5, "street").addLink(2, 6, "street");
        for (RoadChain c : RoadGeometry.chains(net)) {
            if (!(c.nodeIds().contains(1) && c.nodeIds().contains(2))) continue;
            com.aleksalfi.curvegen.geom.Polyline line = c.line();
            double dir = Math.signum(line.x[line.size - 1] - line.x[0]);
            for (int i = 1; i < line.size; i++) {
                assertTrue((line.x[i] - line.x[i - 1]) * dir >= -1e-6, "centre line folds back at sample " + i);
                assertEquals(0.5, line.z[i], 1e-6, "centre line leaves the straight link at sample " + i);
            }
            return;
        }
        fail("no chain between the two junctions");
    }

    /** A class change tapers the wider road into the narrower one, entirely on the wider side, with a solid centre. */
    @Test
    void classChangeTapersOnTheWiderSide() {
        RoadNetwork net = RoadNetwork.empty("x").addNode(0.5, 64, 0.5).addNode(100.5, 64, 0.5).addNode(200.5, 64, 0.5)
                .addLink(1, 2, "main").addLink(2, 3, "street");
        List<RoadChain> chains = RoadGeometry.chains(net);
        assertEquals(1, chains.size(), "a class change is a pass-through node");
        RoadChain c = chains.get(0);
        double mainHalf = RoadClass.mainRoad().halfTotal(), streetHalf = RoadClass.street().halfTotal(); // 12.5 and 10.5
        double taper = TaperProfile.taperLength(2 * mainHalf, 2 * streetHalf); // 40
        assertEquals(mainHalf, c.halfAt(30), 1e-9);
        assertEquals(streetHalf, c.halfAt(150), 1e-9);
        // Halfway: the lanes are half way (S-curve), the sidewalk and curb keep full width, the edge line stays.
        double mid = 100 - taper / 2;
        double[] w = c.widthsAt(mid);
        assertEquals(6.5, w[LaneProfile.CENTRE_INDEX - 1], 1e-6, "lane halfway between 7 and 6");
        assertEquals(1, w[3], 1e-6, "edge line stays through the taper");
        // Curb and sidewalk are the main road's own all through its taper; the edge line is held; lanes ease.
        assertEquals(4, w[0] + w[1], 1e-6, "curb + sidewalk at full width");
        assertEquals(0, w[2], 1e-6, "no verge");
        assertEquals(0.5 + 1 + 6.5 + 4, c.halfAt(mid), 1e-6);
        assertEquals(mainHalf, c.halfAt(100 - taper - 1), 1e-9, "no taper on the narrow side");
        assertEquals(streetHalf, c.halfAt(101), 1e-9);
        CellMap cells = RoadPainter.paint(net, chains);
        assertEquals(Surface.SIDEWALK, at(cells, 30, 12));
        assertEquals(Surface.NONE, at(cells, 30, 13));
        assertEquals(Surface.SIDEWALK, at(cells, 150, 10));
        assertEquals(Surface.NONE, at(cells, 150, 11));
        assertEquals(Surface.SIDEWALK, at(cells, 80, 11), "sidewalk at full width in the taper");
        assertEquals(Surface.NONE, at(cells, 80, 13), "already narrower in the taper");
        for (int x = 62; x < 100; x += 4) assertEquals(Surface.LINE, at(cells, x, 0), "solid centre through the taper at x=" + x);
        assertEquals("main", cells.top(RoadPainter.key(90, 0)).cls.id(), "taper uses the wider class's blocks");
        assertEquals("street", cells.top(RoadPainter.key(110, 0)).cls.id());
    }

    @Test
    void classChangeWideningTapersAfterTheNode() {
        RoadNetwork net = RoadNetwork.empty("x").addNode(0.5, 64, 0.5).addNode(100.5, 64, 0.5).addNode(200.5, 64, 0.5)
                .addLink(1, 2, "street").addLink(2, 3, "highway");
        RoadChain c = RoadGeometry.chains(net).get(0);
        double streetHalf = RoadClass.street().halfTotal(), hwHalf = RoadClass.highway().halfTotal(); // 10.5 and 18.5
        double taper = TaperProfile.taperLength(2 * streetHalf, 2 * hwHalf); // 160 -> clamped to 45 (0.45 * 100)
        assertTrue(taper > 45);
        assertEquals(streetHalf, c.halfAt(99), 1e-9);
        assertEquals(hwHalf, c.halfAt(146), 1e-9, "taper clamped to 45% of the wider link");
        assertTrue(c.halfAt(122) > streetHalf + 2 && c.halfAt(122) < hwHalf - 2);
        // The second lane opens as a wedge; its lane line is absent while the lane is under half open and
        // present once it is at least half open. The street's sidewalk runs at full width to the taper's end.
        LaneKind[] k = LaneProfile.KINDS;
        int outerLane = LaneProfile.CENTRE_INDEX + 3, laneLine = LaneProfile.CENTRE_INDEX + 2;
        assertEquals(LaneKind.LANE, k[outerLane]);
        assertEquals(LaneKind.LANE_LINE, k[laneLine]);
        double[] early = c.widthsAt(104), late = c.widthsAt(140);
        assertTrue(early[outerLane] < 4 && early[laneLine] == 0, "no lane line while the lane is a sliver");
        assertTrue(late[outerLane] > 4 && late[laneLine] == 1, "lane line once the lane is half open");
        // Widening street -> highway: the street's pavement ends square at the node; the taper beyond it has
        // no sidewalk, no verge, and its outer edge eases smoothly up to the highway's.
        for (double sAlong = 100.5; sAlong <= 146; sAlong += 1) {
            double[] ws = c.widthsAt(sAlong);
            assertEquals(0, ws[0], 1e-9, "no sidewalk past the node at " + sAlong);
            assertEquals(0, ws[1], 1e-9, "no curb past the node at " + sAlong);
            double cw = 0.5 + 1 + (6 + 2 * ease((sAlong - 100) / 45)) + ws[LaneProfile.CENTRE_INDEX + 2] + ws[LaneProfile.CENTRE_INDEX + 3];
            assertEquals(cw + ws[LaneProfile.KINDS.length - 3], c.halfAt(sAlong), 1e-6, "outer edge = carriageway + eased shoulder at " + sAlong);
        }
        assertEquals(3, c.widthsAt(99.5)[0], 1e-9, "sidewalk right up to the node");
        assertEquals(0, c.widthsAt(146)[0], 1e-6, "pavement gone where the highway begins");
        for (double sAlong = 100; sAlong <= 146; sAlong += 2) assertTrue(c.halfAt(sAlong) <= hwHalf + 1e-6, "never wider than the highway at " + sAlong);
        // The shoulder setting is respected through the taper: it eases from 0 (street) up to the highway's 2.
        assertEquals(0, c.widthsAt(101)[LaneProfile.KINDS.length - 3], 0.05);
        assertEquals(1, c.widthsAt(100 + 22.5)[LaneProfile.KINDS.length - 3], 0.05, "shoulder half way at mid-taper");
        assertEquals(2, c.widthsAt(146)[LaneProfile.KINDS.length - 3], 1e-9);
        assertEquals(1, c.widthsAt(104)[3], 1e-6, "edge line present through the taper");
    }

    private static double ease(double f) { f = Math.max(0, Math.min(1, f)); return f * f * (3 - 2 * f); }

    private static RoadNetwork highwayWithRamp(double rampX, double rampZ, NodeKind kind) {
        RoadNetwork net = RoadNetwork.empty("x").addNode(0.5, 64, 0.5).addNode(-150.5, 64, 0.5).addNode(150.5, 64, 0.5)
                .addNode(rampX, 64, rampZ)
                .addLink(1, 2, "highway").addLink(1, 3, "highway").addLink(1, 4, "street");
        return net.putNode(net.nodes().get(1).withKind(kind));
    }

    /** A street joining a highway at a shallow angle from behind, on the right of eastbound traffic, is an entry. */
    @Test
    void shallowRampIsDetectedAsEntryOrExit() {
        RoadNetwork net = highwayWithRamp(-80.5, 40.5, NodeKind.AUTO);
        Merge m = Merge.at(net, net.nodes().get(1));
        assertNotNull(m);
        assertTrue(m.entry());
        assertEquals(1, m.d().x(), 1e-9);
        assertEquals(3, m.forward().other(1));
        assertFalse(RoadGeometry.isJunction(net, net.nodes().get(1)), "a merge is not painted as a junction");
        // Same ramp pointing forward along the traffic: an exit.
        RoadNetwork exit = highwayWithRamp(80.5, 40.5, NodeKind.AUTO);
        assertFalse(Merge.at(exit, exit.nodes().get(1)).entry());
        // Too steep an angle: a plain junction. Forced kind: still a merge.
        assertNull(Merge.at(highwayWithRamp(-20.5, 60.5, NodeKind.AUTO), highwayWithRamp(-20.5, 60.5, NodeKind.AUTO).nodes().get(1)));
        assertNotNull(Merge.at(highwayWithRamp(-20.5, 60.5, NodeKind.ENTRY), highwayWithRamp(-20.5, 60.5, NodeKind.ENTRY).nodes().get(1)));
        assertNull(Merge.at(highwayWithRamp(-80.5, 40.5, NodeKind.JUNCTION), highwayWithRamp(-80.5, 40.5, NodeKind.JUNCTION).nodes().get(1)));
    }

    /** The highway runs through the merge as one chain with an acceleration lane on the right after the node. */
    @Test
    void entryRampGetsAccelerationLane() {
        RoadNetwork net = highwayWithRamp(-80.5, 40.5, NodeKind.AUTO);
        List<RoadChain> chains = RoadGeometry.chains(net);
        assertEquals(2, chains.size(), "highway chain plus ramp chain");
        RoadChain hw = chains.get(0).linkIds().contains(3) ? chains.get(1) : chains.get(0);
        assertEquals(3, hw.nodeIds().size(), "highway is one chain through the merge");
        double sNode = hw.nodeAlong()[1];
        boolean forward = net.nodes().get(hw.nodeIds().get(2)).x() > net.nodes().get(hw.nodeIds().get(1)).x();
        int aux = forward ? LaneProfile.KINDS.length - 5 : 4, auxLine = forward ? LaneProfile.KINDS.length - 6 : 5;
        double dir = forward ? 1 : -1;
        RoadClass hwClass = RoadClass.highway();
        assertEquals(0, hw.widthsAt(sNode - 5 * dir)[aux], 1e-9, "no lane before the entry");
        assertEquals(hwClass.laneWidth(), hw.widthsAt(sNode + 30 * dir)[aux], 1e-9, "full lane after the entry");
        assertEquals(1, hw.widthsAt(sNode + 30 * dir)[auxLine], 1e-9, "aux lane line");
        assertTrue(hw.widthsAt(sNode + (hwClass.mergeLength() + 10) * dir)[aux] < hwClass.laneWidth() * 0.6, "tapering out");
        assertEquals(0, hw.widthsAt(sNode + (hwClass.mergeLength() + Merge.LANE_TAPER + 1) * dir)[aux], 1e-9, "gone");
        // The ramp chain ends on the nose: abreast of the node, on the aux lane's centre line.
        RoadChain ramp = chains.get(0) == hw ? chains.get(1) : chains.get(0);
        com.aleksalfi.curvegen.geom.Polyline rl = ramp.line();
        boolean endsAtNode = ramp.nodeIds().get(ramp.nodeIds().size() - 1) == 1;
        double ex = endsAtNode ? rl.x[rl.size - 1] : rl.x[0], ez = endsAtNode ? rl.z[rl.size - 1] : rl.z[0];
        Merge m = Merge.at(net, net.nodes().get(1));
        assertEquals(m.nose().x(), ex, 0.6);
        assertEquals(m.nose().z(), ez, 0.6);
        // The centre line does not move when the lane is added: it is still at z = 0 on both sides of the node.
        CellMap probe = RoadPainter.paint(net, chains);
        assertEquals(Surface.LINE, at(probe, -40, 0), "centre line before the merge");
        assertEquals(Surface.LINE, at(probe, 30, 0), "centre line within the acceleration lane");
        assertEquals(Surface.LINE, at(probe, 30, 1 + 8), "lane line at its usual place within the acceleration lane");
        // Painted: a full extra lane of asphalt on the south side 30 blocks east of the node, and a hatched gore behind it.
        CellMap cells = RoadPainter.paint(net, chains);
        int auxZ = (int) Math.floor(0.5 + m.auxCentre());
        Surface sAux = at(cells, 30, auxZ);
        assertTrue(sAux == Surface.ASPHALT || sAux == Surface.LINE, "acceleration lane paved at z=" + auxZ + ": " + sAux);
        int lines = 0, paved = 0;
        for (int x = -30; x <= -5; x++) for (int z = (int) hwClass.halfCarriageway() + 3; z < auxZ + 6; z++) {
            Surface sf = at(cells, x, z);
            if (sf == Surface.LINE) lines++;
            if (sf == Surface.ASPHALT || sf == Surface.LINE) paved++;
        }
        assertTrue(lines > 5 && paved > 40, "gore should be paved and hatched, lines=" + lines + " paved=" + paved);
        // In the merge zone the ramp has no pavement: nothing raised between the highway's edge and the ramp lane,
        // and the acceleration lane's outer edge line continues along the ramp's outer side up to the nose.
        for (int x = -12; x <= -2; x++) for (int z = 18; z <= 22; z++) assertFalse(at(cells, x, z).raised(), "raised cell in the merge zone at " + x + "," + z);
        int edgeLines = 0;
        for (int x = -12; x <= -2; x++) for (int z = 26; z <= 31; z++) if (at(cells, x, z) == Surface.LINE) { edgeLines++; break; }
        assertTrue(edgeLines >= 8, "edge line along the ramp's outer side in the merge zone: " + edgeLines);
        // Further up the ramp the sidewalk is back.
        int raised = 0;
        for (int x = -80; x <= -40; x++) for (int z = 20; z < 60; z++) if (at(cells, x, z).raised()) raised++;
        assertTrue(raised > 40, "ramp sidewalk beyond the merge zone, raised=" + raised);
    }

    /** An entry followed by an exit within twice the merge length share one continuous weaving lane. */
    @Test
    void closeEntryAndExitFormWeavingLane() {
        RoadNetwork net = RoadNetwork.empty("x").addNode(0.5, 64, 0.5).addNode(100.5, 64, 0.5)
                .addNode(-150.5, 64, 0.5).addNode(250.5, 64, 0.5)
                .addNode(-80.5, 64, 40.5).addNode(180.5, 64, 40.5)
                .addLink(3, 1, "highway").addLink(1, 2, "highway").addLink(2, 4, "highway")
                .addLink(1, 5, "street").addLink(2, 6, "street");
        RoadChain hw = null;
        for (RoadChain c : RoadGeometry.chains(net)) if (c.nodeIds().contains(3) && c.nodeIds().contains(4)) hw = c;
        assertNotNull(hw, "one highway chain through both merges");
        assertEquals(1, hw.profile().auxLanes().size(), "entry and exit lanes joined into one");
        double s1 = hw.nodeAlong()[1], s2 = hw.nodeAlong()[2];
        for (double s = s1; s <= s2; s += 5) assertEquals(8, hw.widthsAt(s)[LaneProfile.KINDS.length - 5], 1e-9, "weaving lane continuous at " + s);
    }

    /** One-way cross-sections: all lanes one way, lane lines between, no centre line; width = lanes only. */
    @Test
    void oneWayProfiles() {
        RoadClass street = RoadClass.street();   // 1 lane: the centre slot is the lane
        double[] w1 = LaneProfile.widthsOf(street, true);
        assertEquals(6, w1[LaneProfile.CENTRE_INDEX], 1e-9);
        assertEquals(2 * street.oneWayHalf() + 2 * (1 + 3), 2 * com.aleksalfi.curvegen.geom.Rasterizer.half(w1), 1e-9);
        RoadClass hw = RoadClass.highway();      // 2 lanes: a lane line in the centre slot, a lane each side
        double[] w2 = LaneProfile.widthsOf(hw, true);
        assertEquals(1, w2[LaneProfile.CENTRE_INDEX], 1e-9);
        assertEquals(8, w2[LaneProfile.CENTRE_INDEX - 1], 1e-9);
        assertEquals(8, w2[LaneProfile.CENTRE_INDEX + 1], 1e-9);
        assertEquals(0, w2[LaneProfile.CENTRE_INDEX - 2], 1e-9, "no second lane line");
        RoadClass three = hw.withLanesPerDirection(3); // 3 lanes: centre lane plus one each side with lines
        double[] w3 = LaneProfile.widthsOf(three, true);
        assertEquals(8, w3[LaneProfile.CENTRE_INDEX], 1e-9);
        assertEquals(0, w3[LaneProfile.CENTRE_INDEX - 1], 1e-9);
        assertEquals(1, w3[LaneProfile.CENTRE_INDEX - 2], 1e-9, "line beside the centre lane");
        assertEquals(8, w3[LaneProfile.CENTRE_INDEX - 3], 1e-9);
        assertEquals(3 * 8 + 2, 2 * three.oneWayHalf(), 1e-9);
    }

    /** A one-way chain runs with the traffic regardless of which end was drawn first, and stops where the direction turns. */
    @Test
    void oneWayChainsFollowTraffic() {
        RoadNetwork net = RoadNetwork.empty("x").addNode(0.5, 64, 0.5).addNode(50.5, 64, 0.5).addNode(100.5, 64, 0.5).addNode(150.5, 64, 0.5)
                .addLink(2, 1, "street").addLink(2, 3, "street").addLink(4, 3, "street");
        // 1<-2 reversed so traffic runs 1 -> 2 -> 3, then 4 -> 3 arrives against it.
        net = net.putLink(net.links().get(1).withDir(LinkDir.REVERSE)).putLink(net.links().get(2).withDir(LinkDir.FORWARD)).putLink(net.links().get(3).withDir(LinkDir.FORWARD));
        List<RoadChain> chains = RoadGeometry.chains(net);
        assertEquals(2, chains.size(), "the opposing link cannot join the chain");
        RoadChain a = chains.get(0).nodeIds().contains(1) ? chains.get(0) : chains.get(1);
        assertEquals(List.of(1, 2, 3), a.nodeIds(), "chain ordered with the traffic");
        assertTrue(a.oneWay());
        CellMap cells = RoadPainter.paint(net, chains);
        assertEquals(Surface.ASPHALT, at(cells, 25, 0), "no centre line on a one-way street");
        assertEquals(Surface.CURB, at(cells, 25, 4), "curb right beside the single lane");
        assertEquals(Surface.SIDEWALK, at(cells, 25, 6));
        assertEquals(Surface.SIDEWALK, at(cells, 25, -6));
    }

    /** A two-way street splitting into two one-way streets: offsets, solid approach, gore. */
    @Test
    void splitIntoOneWayRoads() {
        RoadNetwork net = RoadNetwork.empty("x").addNode(-100.5, 64, 0.5).addNode(0.5, 64, 0.5)
                .addNode(100.5, 64, 30.5).addNode(100.5, 64, -30.5)
                .addLink(1, 2, "street").addLink(2, 3, "street").addLink(4, 2, "street");
        net = net.putLink(net.links().get(2).withDir(LinkDir.FORWARD)).putLink(net.links().get(3).withDir(LinkDir.FORWARD));
        Split sp = Split.at(net, net.nodes().get(2));
        assertNotNull(sp);
        assertEquals(1, sp.d().x(), 1e-9);
        assertEquals(3.5, sp.offset(), 1e-9, "half a lane plus half the centre line");
        assertEquals(4.0, sp.outStart().z(), 1e-9, "leaving road starts on the right (south) of the centre");
        assertEquals(-3.0, sp.inEnd().z(), 1e-9);
        assertFalse(RoadGeometry.isJunction(net, net.nodes().get(2)));
        List<RoadChain> chains = RoadGeometry.chains(net);
        assertEquals(3, chains.size());
        for (RoadChain c : chains) {
            if (c.linkIds().contains(2)) { assertEquals(2, c.nodeIds().get(0)); assertEquals(sp.outStart().z(), c.line().z[0], 0.6); }
            if (c.linkIds().contains(3)) { assertEquals(2, c.nodeIds().get(c.nodeIds().size() - 1)); assertEquals(sp.inEnd().z(), c.line().z[c.line().size - 1], 0.6); }
        }
        CellMap cells = RoadPainter.paint(net, chains);
        for (int x = -20; x < 0; x += 4) assertEquals(Surface.LINE, at(cells, x, 0), "solid centre on the approach at x=" + x);
        // Just past the node the two lanes continue straight: asphalt at the lane centres, a border line between.
        assertEquals(Surface.ASPHALT, at(cells, 6, 4));
        assertEquals(Surface.ASPHALT, at(cells, 6, -4));
        // Between the diverging roads: hatched gore, or the roads' own inner curbs and sidewalks forming an island.
        int filled = 0;
        for (int x = 15; x < 45; x++) for (int z = -6; z <= 6; z++) { Surface sf = at(cells, x, z); if (sf == Surface.LINE || sf.raised()) filled++; }
        assertTrue(filled > 12, "nose between the diverging roads, filled=" + filled);
        // Far away the roads are separate and the ground between is empty.
        assertEquals(Surface.NONE, at(cells, 90, 0));
    }

    /** A one-way street ramp onto a highway is an entry when it arrives at the node and fits the acceleration lane. */
    @Test
    void oneWayRampMerges() {
        RoadNetwork net = RoadNetwork.empty("x").addNode(0.5, 64, 0.5).addNode(-150.5, 64, 0.5).addNode(150.5, 64, 0.5)
                .addNode(-80.5, 64, 40.5)
                .addLink(1, 2, "highway").addLink(1, 3, "highway").addLink(4, 1, "street");
        net = net.putLink(net.links().get(3).withDir(LinkDir.FORWARD));
        Merge m = Merge.at(net, net.nodes().get(1));
        assertNotNull(m);
        assertTrue(m.entry());
        assertEquals(3, m.rampHalf(), 1e-9);
        // Reversed ramp direction (leaving the node) would be an exit, but it points backwards: still a merge, forced by direction.
        RoadNetwork rev = net.putLink(net.links().get(3).withDir(LinkDir.REVERSE));
        assertFalse(Merge.at(rev, rev.nodes().get(1)).entry());
        List<RoadChain> chains = RoadGeometry.chains(net);
        RoadChain ramp = null;
        for (RoadChain c : chains) if (c.linkIds().contains(3)) ramp = c;
        assertNotNull(ramp);
        assertTrue(ramp.oneWay());
        assertEquals(1, ramp.nodeIds().get(ramp.nodeIds().size() - 1), "ramp chain runs towards the highway");
        CellMap cells = RoadPainter.paint(net, chains);
        int auxZ = (int) Math.floor(0.5 + m.auxCentre());
        Surface sAux = at(cells, 30, auxZ);
        assertTrue(sAux == Surface.ASPHALT || sAux == Surface.LINE);
    }

    /** Class order is stable through copies, codec round trips and edits, so cycling visits every class. */
    @Test
    void classOrderIsStable() {
        RoadNetwork net = RoadNetwork.empty("x");
        List<String> order = new java.util.ArrayList<>(net.classes().keySet());
        assertEquals(4, order.size());
        RoadNetwork edited = net.withDefaultClass(order.get(1)).addNode(0, 64, 0).putClass(net.classes().get(order.get(0)).withName("Renamed"));
        assertEquals(order, new java.util.ArrayList<>(edited.classes().keySet()));
        RoadNetwork decoded = new RoadNetwork(edited.name(), edited.classes(), edited.nodes(), edited.links(), edited.nextId(), edited.nextLinkId(),
                edited.defaultClass(), edited.owner(), edited.shares(), edited.playerNames(), edited.publicAccess());
        assertEquals(order, new java.util.ArrayList<>(decoded.classes().keySet()));
        // Cycling "next class" from each class visits all three.
        java.util.Set<String> seen = new java.util.HashSet<>();
        String cur = decoded.defaultClass();
        for (int i = 0; i < 4; i++) {
            List<String> ids = new java.util.ArrayList<>(decoded.classes().keySet());
            cur = ids.get((ids.indexOf(cur) + 1) % ids.size());
            decoded = decoded.withDefaultClass(cur);
            seen.add(cur);
        }
        assertEquals(4, seen.size());
    }

    /** Per-road overrides change the effective class, and two roads of one class with different overrides taper. */
    @Test
    void perRoadOverridesApply() {
        RoadNetwork net = RoadNetwork.empty("x").addNode(0.5, 64, 0.5).addNode(100.5, 64, 0.5).addNode(200.5, 64, 0.5)
                .addLink(1, 2, "street").addLink(2, 3, "street");
        net = net.putLink(net.links().get(2).withSidewalk(java.util.Optional.of(0)).withEdgeLines(java.util.Optional.of(true)).withShoulder(java.util.Optional.of(2)));
        RoadClass eff = net.classOf(net.links().get(2));
        assertEquals(0, eff.sidewalkWidth());
        assertTrue(eff.edgeLines());
        assertEquals(2, eff.shoulderWidth());
        assertEquals(3, net.classOf(net.links().get(1)).sidewalkWidth(), "the other road keeps the class value");
        RoadChain c = RoadGeometry.chains(net).get(0);
        assertEquals(1, RoadGeometry.chains(net).size());
        assertEquals(RoadClass.street().halfTotal(), c.halfAt(30), 1e-9);
        assertEquals(eff.halfTotal(), c.halfAt(150), 1e-9);
        assertTrue(c.profile().taperRanges().size() == 1, "a taper between the two overrides");
        CellMap cells = RoadPainter.paint(net, RoadGeometry.chains(net));
        assertEquals(Surface.SIDEWALK, at(cells, 30, 9));
        assertEquals(Surface.LINE, at(cells, 150, 7), "edge line on the overridden road");
        assertEquals(Surface.ASPHALT, at(cells, 150, 8), "shoulder outside the edge line");
        assertEquals(Surface.NONE, at(cells, 150, 10));
    }
}
