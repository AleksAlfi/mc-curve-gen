package com.aleksalfi.curvegen.client.gui;

import com.aleksalfi.curvegen.build.BlockStates;
import com.aleksalfi.curvegen.build.CopycatSupport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Searchable grid of every block that has an item (vanilla and modded), plus manual block state entry. */
public class BlockPickerScreen extends Screen {
    private static final int CELL = 20;
    private static final int GRID_TOP = 66;

    private static final class Entry {
        final Block block; final ItemStack icon; final String id; final String name; final String search;
        @Nullable Boolean compatible;
        Entry(Block block) {
            this.block = block;
            this.icon = new ItemStack(block);
            this.id = BlockStates.id(block);
            this.name = block.getName().getString();
            this.search = (id + " " + name).toLowerCase(Locale.ROOT);
        }
    }

    private static List<Entry> ALL;

    /** Drops cached names and compatibility flags (they depend on the language and the world's tags). */
    public static void resetCache() { ALL = null; }

    private final Screen parent;
    private final boolean materialMode;
    private final Consumer<String> callback;
    private final String current;
    private EditBox search, custom;
    private String searchText = "";
    private List<Entry> filtered = List.of();
    private int scroll;
    private boolean compatibleOnly;
    private Component error = Component.empty();

    public BlockPickerScreen(Screen parent, String current, boolean materialMode, Consumer<String> callback) {
        super(Component.translatable("curvegen.gui.picker.title"));
        this.parent = parent;
        this.current = current;
        this.materialMode = materialMode;
        this.compatibleOnly = materialMode;
        this.callback = callback;
    }

    private static List<Entry> all() {
        if (ALL == null) {
            List<Entry> list = new ArrayList<>();
            for (Block b : BuiltInRegistries.BLOCK) {
                if (b.asItem() instanceof BlockItem bi && bi.getBlock() == b) list.add(new Entry(b));
            }
            list.sort((a, b) -> a.id.compareTo(b.id));
            ALL = list;
        }
        return ALL;
    }

    @Override
    protected void init() {
        search = new EditBox(font, 20, 20, width - 40, 18, Component.translatable("curvegen.gui.picker.search"));
        search.setHint(Component.translatable("curvegen.gui.picker.search"));
        search.setValue(searchText);
        search.setResponder(s -> { searchText = s; scroll = 0; refilter(); });
        addRenderableWidget(search);
        setInitialFocus(search);

        custom = new EditBox(font, 20, 42, width - 40 - 190, 18, Component.translatable("curvegen.gui.picker.custom"));
        custom.setHint(Component.translatable("curvegen.gui.picker.custom"));
        custom.setMaxLength(512);
        custom.setValue(current == null ? "" : current);
        addRenderableWidget(custom);
        addRenderableWidget(Button.builder(Component.translatable("curvegen.gui.picker.apply"), b -> applyCustom())
                .bounds(width - 20 - 186, 42, 50, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("curvegen.gui.picker.looked_at"), b -> useLookedAt())
                .bounds(width - 20 - 132, 42, 132, 18).build());

        int bottomY = height - 24;
        if (materialMode) {
            addRenderableWidget(Checkbox.builder(Component.translatable("curvegen.gui.picker.compatible_only"), font)
                    .pos(width - 90 - 200, bottomY).selected(compatibleOnly).onValueChange((cb, v) -> { compatibleOnly = v; scroll = 0; refilter(); }).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("curvegen.gui.picker.cancel"), b -> onClose())
                .bounds(width - 80, bottomY, 60, 18).build());
        refilter();
    }

    private void refilter() {
        String q = search.getValue().toLowerCase(Locale.ROOT).trim();
        List<Entry> out = new ArrayList<>();
        for (Entry e : all()) {
            if (!q.isEmpty() && !e.search.contains(q)) continue;
            if (compatibleOnly && !isCompatible(e)) continue;
            out.add(e);
        }
        filtered = out;
    }

    private boolean isCompatible(Entry e) {
        if (e.compatible == null) {
            Minecraft mc = Minecraft.getInstance();
            e.compatible = mc.level != null && CopycatSupport.isValidMaterial(mc.level, e.block.defaultBlockState());
        }
        return e.compatible;
    }

    private void applyCustom() {
        BlockState state = BlockStates.parse(custom.getValue());
        if (state == null) {
            error = Component.translatable("curvegen.gui.picker.invalid");
            return;
        }
        finish(BlockStates.serialize(state));
    }

    private void useLookedAt() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK) {
            BlockState state = mc.level.getBlockState(((BlockHitResult) mc.hitResult).getBlockPos());
            if (!state.isAir()) {
                finish(BlockStates.serialize(state));
                return;
            }
        }
        error = Component.translatable("curvegen.msg.no_target");
    }

    private void finish(String value) {
        callback.accept(value);
        Minecraft.getInstance().setScreen(parent);
    }

    private int columns() { return Math.max(1, (width - 40) / CELL); }
    private int rows() { return Math.max(1, (height - GRID_TOP - 30) / CELL); }
    private int maxScroll() { return Math.max(0, (filtered.size() + columns() - 1) / columns() - rows()); }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 7, 0xFFFFFF);
        int cols = columns(), rows = rows();
        int start = scroll * cols;
        Entry hovered = null;
        for (int i = 0; i < cols * rows; i++) {
            int idx = start + i;
            if (idx >= filtered.size()) break;
            Entry e = filtered.get(idx);
            int x = 20 + (i % cols) * CELL, y = GRID_TOP + (i / cols) * CELL;
            boolean hover = mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL;
            g.fill(x, y, x + CELL, y + CELL, hover ? 0x80FFFFFF : 0x40000000);
            g.renderItem(e.icon, x + 2, y + 2);
            if (hover) hovered = e;
        }
        if (filtered.size() > cols * rows) {
            int barH = Math.max(8, rows * CELL * rows / Math.max(rows, (filtered.size() + cols - 1) / cols));
            int barY = GRID_TOP + (int) ((rows * CELL - barH) * (maxScroll() == 0 ? 0 : scroll / (double) maxScroll()));
            g.fill(width - 18, barY, width - 14, barY + barH, 0xC0FFFFFF);
        }
        g.drawString(font, error, 20, height - 20, 0xFF5555, true);
        if (hovered != null) {
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(hovered.name));
            tip.add(Component.literal(hovered.id).withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
            if (materialMode && !isCompatible(hovered)) tip.add(Component.literal("Not usable as copycat material").withStyle(net.minecraft.ChatFormatting.RED));
            g.renderComponentTooltip(font, tip, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button != 0) return false;
        int cols = columns(), rows = rows();
        if (mouseX < 20 || mouseX >= 20 + cols * CELL || mouseY < GRID_TOP || mouseY >= GRID_TOP + rows * CELL) return false;
        int cx = (int) ((mouseX - 20) / CELL), cy = (int) ((mouseY - GRID_TOP) / CELL);
        int idx = (scroll + cy) * cols + cx;
        if (idx < 0 || idx >= filtered.size()) return false;
        finish(filtered.get(idx).id);
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) return true;
        scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.signum(scrollY)));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 && custom.isFocused()) { applyCustom(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
