package com.aleksalfi.curvegen;

import com.aleksalfi.curvegen.geom.Vec2;
import com.aleksalfi.curvegen.road.ArmPriority;
import com.aleksalfi.curvegen.road.ArmSettings;
import com.aleksalfi.curvegen.road.CornerStyle;
import com.aleksalfi.curvegen.road.Junction;
import com.aleksalfi.curvegen.road.LaneKind;
import com.aleksalfi.curvegen.road.LaneProfile;
import com.aleksalfi.curvegen.road.NodeKind;
import com.aleksalfi.curvegen.road.RoadChain;
import com.aleksalfi.curvegen.road.RoadClass;
import com.aleksalfi.curvegen.road.RoadGeometry;
import com.aleksalfi.curvegen.road.RoadNetwork;
import com.aleksalfi.curvegen.road.RoadNode;
import com.aleksalfi.curvegen.road.RoadPainter;
import com.aleksalfi.curvegen.road.Surface;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RoadTest {

    private static Surface at(Map<Long, RoadPainter.Cell> cells, int x, int z) {
        RoadPainter.Cell c = cells.get(RoadPainter.key(x, z));
        return c == null ? Surface.NONE : c.surface;
    }

    private static int count(Map<Long, RoadPainter.Cell> cells, Surface s) {
        return (int) cells.values().stream().filter(c -> c.surface == s).count();
    }

    @Test
    void laneProfileWidthsMatchClass() {
        RoadClass street = RoadClass.street(); // lw 6, 1 lane/dir, sidewalk 3, no edge lines
        LaneProfile p = LaneProfile.of(street);
        assertEquals(2 * street.halfTotal(), p.totalWidth(), 1e-9);
        assertEquals(LaneKind.SIDEWALK, p.kinds()[0]);
        assertEquals(LaneKind.CURB, p.kinds()[1]);
        assertEquals(LaneKind.LANE, p.kinds()[2]);
        assertEquals(LaneKind.CENTRE, p.kinds()[3]);
        assertEquals(LaneKind.SIDEWALK, p.kinds()[p.kinds().length - 1]);
        RoadClass hw = RoadClass.highway(); // 2 lanes/dir, edge lines, no sidewalk
        LaneProfile h = LaneProfile.of(hw);
        assertEquals(LaneKind.EDGE, h.kinds()[0]);
        assertEquals(LaneKind.LANE_LINE, h.kinds()[2]);
        assertEquals(2 * hw.halfTotal(), h.totalWidth(), 1e-9);
    }

    @Test
    void straightRoadPaintsSidewalkCurbLanesAndDashedCentre() {
        RoadNetwork net = RoadNetwork.empty("t").addNode(0.5, 64, 0.5).addNode(100.5, 64, 0.5).addLink(1, 2, "street");
        List<RoadChain> chains = RoadGeometry.chains(net);
        assertEquals(1, chains.size());
        Map<Long, RoadPainter.Cell> cells = RoadPainter.paint(net, chains);
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
        Map<Long, RoadPainter.Cell> cells = RoadPainter.paint(net, chains);
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
        Map<Long, RoadPainter.Cell> cells = RoadPainter.paint(net, chains);
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
}
