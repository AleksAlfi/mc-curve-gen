package com.aleksalfi.curvegen.road;

import com.aleksalfi.curvegen.geom.Vec2;

import java.util.HashMap;
import java.util.Map;

/**
 * A node of the road network. {@code x,z} are block centres and {@code y} is the road surface height
 * (top of the asphalt). {@code arms} holds per-link settings keyed by link id.
 */
public record RoadNode(int id, double x, double y, double z, NodeKind kind, CornerStyle corner, double filletRadius,
                       double roundaboutRadius, int roundaboutLanes, boolean zebra, Map<Integer, ArmSettings> arms) {

    public RoadNode {
        filletRadius = Math.max(0, Math.min(256, filletRadius));
        roundaboutRadius = Math.max(2, Math.min(128, roundaboutRadius));
        roundaboutLanes = Math.max(1, Math.min(2, roundaboutLanes));
        arms = Map.copyOf(arms);
    }

    public static RoadNode at(int id, double x, double y, double z) {
        return new RoadNode(id, x, y, z, NodeKind.AUTO, CornerStyle.FILLET, 12, 8, 1, false, Map.of());
    }

    public Vec2 xz() { return new Vec2(x, z); }

    public ArmSettings arm(int linkId) { return arms.getOrDefault(linkId, ArmSettings.DEFAULT); }

    public RoadNode withArm(int linkId, ArmSettings settings) {
        Map<Integer, ArmSettings> m = new HashMap<>(arms);
        m.put(linkId, settings);
        return new RoadNode(id, x, y, z, kind, corner, filletRadius, roundaboutRadius, roundaboutLanes, zebra, m);
    }

    public RoadNode withoutArm(int linkId) {
        Map<Integer, ArmSettings> m = new HashMap<>(arms);
        m.remove(linkId);
        return new RoadNode(id, x, y, z, kind, corner, filletRadius, roundaboutRadius, roundaboutLanes, zebra, m);
    }

    public RoadNode withPosition(double nx, double ny, double nz) { return new RoadNode(id, nx, ny, nz, kind, corner, filletRadius, roundaboutRadius, roundaboutLanes, zebra, arms); }
    public RoadNode withKind(NodeKind k) { return new RoadNode(id, x, y, z, k, corner, filletRadius, roundaboutRadius, roundaboutLanes, zebra, arms); }
    public RoadNode withCorner(CornerStyle c) { return new RoadNode(id, x, y, z, kind, c, filletRadius, roundaboutRadius, roundaboutLanes, zebra, arms); }
    public RoadNode withFilletRadius(double r) { return new RoadNode(id, x, y, z, kind, corner, r, roundaboutRadius, roundaboutLanes, zebra, arms); }
    public RoadNode withRoundaboutRadius(double r) { return new RoadNode(id, x, y, z, kind, corner, filletRadius, r, roundaboutLanes, zebra, arms); }
    public RoadNode withRoundaboutLanes(int n) { return new RoadNode(id, x, y, z, kind, corner, filletRadius, roundaboutRadius, n, zebra, arms); }
    public RoadNode withZebra(boolean v) { return new RoadNode(id, x, y, z, kind, corner, filletRadius, roundaboutRadius, roundaboutLanes, v, arms); }
}
