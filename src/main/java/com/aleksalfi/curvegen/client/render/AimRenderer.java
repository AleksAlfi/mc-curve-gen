package com.aleksalfi.curvegen.client.render;

import com.aleksalfi.curvegen.client.RoadAim;
import com.aleksalfi.curvegen.geom.Polyline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4fStack;

/** Draws the Road Planner's aim feedback: hovered node/road highlight, ghost node box and the ghost centre line. */
public final class AimRenderer {
    private AimRenderer() {}

    public static void render(RenderLevelStageEvent event) {
        RoadAim.Target t = RoadAim.target;
        RoadAim.Action a = RoadAim.action;
        if (t.kind() == RoadAim.Kind.NONE && RoadAim.ghost.isEmpty()) return;
        Vec3 cam = event.getCamera().getPosition();
        float[] c = colorOf(a);
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        for (Polyline line : RoadAim.ghost) {
            for (int i = 0; i + 1 < line.size; i++) {
                bb.addVertex((float) (line.x[i] - cam.x), (float) (line.y[i] + 0.15 - cam.y), (float) (line.z[i] - cam.z)).setColor(c[0], c[1], c[2], 1f);
                bb.addVertex((float) (line.x[i + 1] - cam.x), (float) (line.y[i + 1] + 0.15 - cam.y), (float) (line.z[i + 1] - cam.z)).setColor(c[0], c[1], c[2], 1f);
            }
        }
        float x = (float) (t.x() - cam.x), y = (float) (t.y() - cam.y), z = (float) (t.z() - cam.z);
        switch (t.kind()) {
            case NODE -> box(bb, x, y, z, 0.75f, 1f, 1f, 1f);
            case ROAD -> { box(bb, x, y, z, 0.45f, c[0], c[1], c[2]); box(bb, x, y, z, 0.6f, 1f, 1f, 1f); }
            case GROUND -> {
                box(bb, x, y, z, 0.45f, c[0], c[1], c[2]);
                if (t.snapped()) { // a short cross marks the snapped axis
                    bb.addVertex(x - 2f, y - 0.2f, z).setColor(c[0], c[1], c[2], 1f);
                    bb.addVertex(x + 2f, y - 0.2f, z).setColor(c[0], c[1], c[2], 1f);
                    bb.addVertex(x, y - 0.2f, z - 2f).setColor(c[0], c[1], c[2], 1f);
                    bb.addVertex(x, y - 0.2f, z + 2f).setColor(c[0], c[1], c[2], 1f);
                }
            }
            default -> {}
        }
        MeshData data = bb.build();
        if (data == null) return;
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        Matrix4fStack stack = RenderSystem.getModelViewStack();
        stack.pushMatrix();
        stack.set(event.getModelViewMatrix());
        RenderSystem.applyModelViewMatrix();
        BufferUploader.drawWithShader(data);
        stack.popMatrix();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    /** Green: creates something; orange: connects; cyan: moves; red: not allowed; grey: nothing. */
    public static float[] colorOf(RoadAim.Action a) {
        return switch (a) {
            case PLACE, INSERT -> new float[]{0.35f, 1f, 0.35f};
            case CONNECT -> new float[]{1f, 0.65f, 0.15f};
            case MOVE -> new float[]{0.3f, 0.9f, 1f};
            case READ_ONLY, NO_NETWORK -> new float[]{1f, 0.3f, 0.3f};
            case SELECT, DESELECT -> new float[]{1f, 1f, 1f};
            default -> new float[]{0.7f, 0.7f, 0.7f};
        };
    }

    private static void box(BufferBuilder bb, float x, float y, float z, float s, float r, float g, float b) {
        float x0 = x - s, x1 = x + s, z0 = z - s, z1 = z + s, y0 = y - 0.95f, y1 = y + 0.45f;
        float[][] e = {
                {x0, y0, z0, x1, y0, z0}, {x1, y0, z0, x1, y0, z1}, {x1, y0, z1, x0, y0, z1}, {x0, y0, z1, x0, y0, z0},
                {x0, y1, z0, x1, y1, z0}, {x1, y1, z0, x1, y1, z1}, {x1, y1, z1, x0, y1, z1}, {x0, y1, z1, x0, y1, z0},
                {x0, y0, z0, x0, y1, z0}, {x1, y0, z0, x1, y1, z0}, {x1, y0, z1, x1, y1, z1}, {x0, y0, z1, x0, y1, z1}};
        for (float[] l : e) {
            bb.addVertex(l[0], l[1], l[2]).setColor(r, g, b, 1f);
            bb.addVertex(l[3], l[4], l[5]).setColor(r, g, b, 1f);
        }
    }
}
