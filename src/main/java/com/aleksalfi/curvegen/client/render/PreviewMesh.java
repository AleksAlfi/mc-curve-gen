package com.aleksalfi.curvegen.client.render;

import com.aleksalfi.curvegen.build.PlannedBlock;
import com.aleksalfi.curvegen.geom.Polyline;
import com.aleksalfi.curvegen.build.CopycatSupport;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Map;
import org.jetbrains.annotations.Nullable;


/** GPU buffers for the translucent block preview plus the centerline and point markers. */
public final class PreviewMesh implements AutoCloseable {
    /** All vertices are relative to this origin to keep float precision far from spawn. */
    public final BlockPos origin;
    @Nullable public final VertexBuffer quads;
    @Nullable public final VertexBuffer lines;

    private PreviewMesh(BlockPos origin, @Nullable VertexBuffer quads, @Nullable VertexBuffer lines) {
        this.origin = origin;
        this.quads = quads;
        this.lines = lines;
    }

    /** Blocks beyond this are not drawn (the plan itself is still complete); keeps GPU memory bounded. */
    public static final int MAX_BLOCKS = 300_000;

    public static PreviewMesh build(Compiled c) {
        BlockPos origin = c.blocks().isEmpty() ? firstMarkerPos(c) : c.blocks().min();
        VertexBuffer quads = buildQuads(c, origin);
        VertexBuffer lines;
        try {
            lines = buildLines(c, origin);
        } catch (RuntimeException e) {
            if (quads != null) quads.close();
            throw e;
        }
        return new PreviewMesh(origin, quads, lines);
    }

    private static BlockPos firstMarkerPos(Compiled c) {
        if (!c.markers().isEmpty()) {
            Compiled.Marker m = c.markers().get(0);
            return BlockPos.containing(m.x(), m.y(), m.z());
        }
        for (Polyline l : c.lines()) if (l.size > 0) return BlockPos.containing(l.x[0], l.y[0], l.z[0]);
        return BlockPos.ZERO;
    }

    @Nullable
    private static VertexBuffer buildQuads(Compiled c, BlockPos origin) {
        Map<BlockPos, PlannedBlock> blocks = c.blocks().blocks();
        if (blocks.isEmpty()) return null;
        int budget = Math.min(blocks.size(), MAX_BLOCKS);
        // Private buffer (not the shared Tesselator) so a failure or a huge mesh never pollutes other rendering.
        try (ByteBufferBuilder memory = new ByteBufferBuilder(Math.max(1 << 16, budget * 6 * 4 * 16))) {
            BufferBuilder bb = new BufferBuilder(memory, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            int n = 0;
            BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();
            for (Map.Entry<BlockPos, PlannedBlock> e : blocks.entrySet()) {
                if (n++ >= budget) break;
                BlockPos pos = e.getKey();
                PlannedBlock block = e.getValue();
                int rgb = colorOf(block);
                float r = ((rgb >> 16) & 0xFF) / 255f, g = ((rgb >> 8) & 0xFF) / 255f, b = (rgb & 0xFF) / 255f;
                float ox = pos.getX() - origin.getX(), oy = pos.getY() - origin.getY(), oz = pos.getZ() - origin.getZ();
                boolean fullCube = isFullCube(block.state());
                // Faces shared with a neighbouring full block of the plan are invisible: skip them.
                boolean[] faces = new boolean[6];
                for (Direction dir : Direction.values()) {
                    neighbour.setWithOffset(pos, dir);
                    PlannedBlock other = blocks.get(neighbour);
                    faces[dir.ordinal()] = !(fullCube && other != null && isFullCube(other.state()));
                }
                VoxelShape shape = fullCube ? Shapes.block() : shapeOf(block.state());
                shape.forAllBoxes((x0, y0, z0, x1, y1, z1) -> box(bb, ox + (float) x0, oy + (float) y0, oz + (float) z0,
                        ox + (float) x1, oy + (float) y1, oz + (float) z1, r, g, b, 0.62f, faces));
            }
            return upload(bb.build());
        }
    }

    private static boolean isFullCube(BlockState state) {
        if (CopycatSupport.available() && CopycatSupport.isLayer(state)) return false;
        try {
            return state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        } catch (RuntimeException e) {
            return true;
        }
    }

    @Nullable
    private static VertexBuffer buildLines(Compiled c, BlockPos origin) {
        try (ByteBufferBuilder memory = new ByteBufferBuilder(1 << 18)) {
            BufferBuilder bb = new BufferBuilder(memory, VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
            boolean any = false;
            for (Polyline line : c.lines()) {
                for (int i = 0; i + 1 < line.size; i++) {
                    any = true;
                    float y0 = (float) (line.y[i] + 0.05 - origin.getY()), y1 = (float) (line.y[i + 1] + 0.05 - origin.getY());
                    bb.addVertex((float) (line.x[i] - origin.getX()), y0, (float) (line.z[i] - origin.getZ())).setColor(1f, 0.9f, 0.2f, 1f);
                    bb.addVertex((float) (line.x[i + 1] - origin.getX()), y1, (float) (line.z[i + 1] - origin.getZ())).setColor(1f, 0.9f, 0.2f, 1f);
                }
            }
            for (Compiled.Segment3 sg : c.segments()) {
                any = true;
                bb.addVertex((float) (sg.x0() - origin.getX()), (float) (sg.y0() - origin.getY()), (float) (sg.z0() - origin.getZ())).setColor(sg.r(), sg.g(), sg.b(), 1f);
                bb.addVertex((float) (sg.x1() - origin.getX()), (float) (sg.y1() - origin.getY()), (float) (sg.z1() - origin.getZ())).setColor(sg.r(), sg.g(), sg.b(), 1f);
            }
            for (Compiled.Marker m : c.markers()) {
                any = true;
                float x = (float) (m.x() - origin.getX()), y = (float) (m.y() - origin.getY()), z = (float) (m.z() - origin.getZ());
                float s = m.size();
                wireBox(bb, x - s, y - 0.9f, z - s, x + s, y + 0.4f, z + s, m.r(), m.g(), m.b());
            }
            MeshData data = bb.build();
            if (!any) {
                if (data != null) data.close();
                return null;
            }
            return upload(data);
        }
    }

    private static int colorOf(PlannedBlock block) {
        BlockState s = block.displayState();
        MapColor c;
        try {
            c = s.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        } catch (RuntimeException e) {
            c = MapColor.STONE;
        }
        int rgb = c == null || c == MapColor.NONE ? 0xA0A0A0 : c.col;
        return rgb;
    }

    private static VoxelShape shapeOf(BlockState state) {
        try {
            VoxelShape s = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            return s.isEmpty() ? net.minecraft.world.phys.shapes.Shapes.block() : s;
        } catch (RuntimeException e) {
            return net.minecraft.world.phys.shapes.Shapes.block();
        }
    }

    @Nullable
    private static VertexBuffer upload(@Nullable MeshData data) {
        if (data == null) return null;
        VertexBuffer vb = new VertexBuffer(VertexBuffer.Usage.STATIC);
        vb.bind();
        vb.upload(data);
        VertexBuffer.unbind();
        return vb;
    }

    /** @param faces which faces to emit, indexed by {@link Direction#ordinal()} (down, up, north, south, west, east) */
    private static void box(BufferBuilder bb, float x0, float y0, float z0, float x1, float y1, float z1, float r, float g, float b, float a, boolean[] faces) {
        // Slight outset so faces that coincide with existing terrain (blocks being replaced) stay visible.
        float e = 0.004f;
        x0 -= e; y0 -= e; z0 -= e; x1 += e; y1 += e; z1 += e;
        if (faces[1]) quad(bb, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, r, g, b, a, 1f);      // top (brightest)
        if (faces[0]) quad(bb, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, r, g, b, a, 0.5f);    // bottom
        if (faces[2]) quad(bb, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, r, g, b, a, 0.8f);    // north
        if (faces[3]) quad(bb, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, r, g, b, a, 0.8f);    // south
        if (faces[4]) quad(bb, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, r, g, b, a, 0.6f);    // west
        if (faces[5]) quad(bb, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, r, g, b, a, 0.6f);    // east
    }

    private static void quad(BufferBuilder bb, float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz,
                             float r, float g, float b, float a, float shade) {
        bb.addVertex(ax, ay, az).setColor(r * shade, g * shade, b * shade, a);
        bb.addVertex(bx, by, bz).setColor(r * shade, g * shade, b * shade, a);
        bb.addVertex(cx, cy, cz).setColor(r * shade, g * shade, b * shade, a);
        bb.addVertex(dx, dy, dz).setColor(r * shade, g * shade, b * shade, a);
    }

    private static void wireBox(BufferBuilder bb, float x0, float y0, float z0, float x1, float y1, float z1, float r, float g, float b) {
        float[][] c = {{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}, {x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}};
        int[][] e = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        for (int[] ed : e) {
            bb.addVertex(c[ed[0]][0], c[ed[0]][1], c[ed[0]][2]).setColor(r, g, b, 1f);
            bb.addVertex(c[ed[1]][0], c[ed[1]][1], c[ed[1]][2]).setColor(r, g, b, 1f);
        }
    }

    @Override
    public void close() {
        if (quads != null) quads.close();
        if (lines != null) lines.close();
    }
}
