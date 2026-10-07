package com.aleksalfi.curvegen.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

public final class PreviewRenderer {
    private PreviewRenderer() {}

    public static void render(RenderLevelStageEvent event) {
        PreviewMesh mesh = PreviewManager.mesh();
        if (mesh == null) return;
        Vec3 cam = event.getCamera().getPosition();
        Matrix4f modelView = new Matrix4f(event.getModelViewMatrix());
        modelView.translate((float) (mesh.origin.getX() - cam.x), (float) (mesh.origin.getY() - cam.y), (float) (mesh.origin.getZ() - cam.z));

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        if (mesh.quads != null) {
            mesh.quads.bind();
            mesh.quads.drawWithShader(modelView, event.getProjectionMatrix(), RenderSystem.getShader());
            VertexBuffer.unbind();
        }
        if (mesh.lines != null) {
            RenderSystem.disableDepthTest();
            mesh.lines.bind();
            mesh.lines.drawWithShader(modelView, event.getProjectionMatrix(), RenderSystem.getShader());
            VertexBuffer.unbind();
            RenderSystem.enableDepthTest();
        }
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }
}
