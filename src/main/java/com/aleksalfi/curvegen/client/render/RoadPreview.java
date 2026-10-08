package com.aleksalfi.curvegen.client.render;

import com.aleksalfi.curvegen.road.RoadCompiler;
import com.aleksalfi.curvegen.road.RoadLink;
import com.aleksalfi.curvegen.road.RoadNetwork;
import com.aleksalfi.curvegen.road.RoadNode;
import net.minecraft.world.level.BlockGetter;

import java.util.ArrayList;
import java.util.List;

/** Compiles a road network for the preview: blocks, chain centre lines, node markers and link lines. */
public final class RoadPreview {
    private RoadPreview() {}

    /** Cache key: the network itself plus the selected node. */
    public record Key(RoadNetwork network, int selected) {}

    public static Compiled compile(RoadNetwork net, int selected, BlockGetter level) {
        RoadCompiler.Result r = RoadCompiler.compile(net, level);
        List<Compiled.Marker> markers = new ArrayList<>();
        for (RoadNode n : net.nodes().values()) {
            boolean sel = n.id() == selected;
            boolean special = n.kind() != com.aleksalfi.curvegen.road.NodeKind.AUTO || net.degree(n.id()) >= 3;
            markers.add(new Compiled.Marker(n.x(), n.y(), n.z(), sel ? 1f : special ? 1f : 0.2f, sel ? 0.9f : special ? 0.6f : 1f, sel ? 0.2f : special ? 0.1f : 1f, sel ? 0.6f : 0.4f));
        }
        List<Compiled.Segment3> segs = new ArrayList<>();
        for (RoadLink l : net.links().values()) {
            RoadNode a = net.nodes().get(l.a()), b = net.nodes().get(l.b());
            if (a == null || b == null) continue;
            segs.add(new Compiled.Segment3(a.x(), a.y() + 0.1, a.z(), b.x(), b.y() + 0.1, b.z(), 1f, 0.4f, 1f));
            if (l.oneWay()) {
                // Chevrons along the link showing the direction of travel.
                RoadNode from = net.nodes().get(l.from()), to = net.nodes().get(l.to());
                double dx = to.x() - from.x(), dz = to.z() - from.z(), len = Math.hypot(dx, dz);
                if (len > 4) {
                    dx /= len; dz /= len;
                    double lx = dz, lz = -dx;
                    for (double f = 0.25; f < 1; f += 0.25) {
                        double px = from.x() + dx * len * f, pz = from.z() + dz * len * f, py = from.y() + (to.y() - from.y()) * f + 0.3;
                        segs.add(new Compiled.Segment3(px - dx * 2 + lx * 1.5, py, pz - dz * 2 + lz * 1.5, px, py, pz, 1f, 0.8f, 0.2f));
                        segs.add(new Compiled.Segment3(px - dx * 2 - lx * 1.5, py, pz - dz * 2 - lz * 1.5, px, py, pz, 1f, 0.8f, 0.2f));
                    }
                }
            }
        }
        return new Compiled(r.blocks(), r.centerlines(), markers, segs, r.blocks().warnings());
    }
}
