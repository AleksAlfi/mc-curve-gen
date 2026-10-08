package com.aleksalfi.curvegen.client.gui;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;

/** Screens that show road network data and must refresh when a sync arrives. */
public interface RoadScreen {
    void onNetworkChanged();

    /**
     * Rebuilds the widgets but keeps the text box that was being typed in: its text, cursor and focus
     * survive, matched by its position among the screen's text boxes. Without this every keystroke
     * (which sends an edit, whose sync rebuilds the screen) would throw the player out of the field.
     */
    static void rebuildKeepingFocus(Screen screen, Runnable rebuild) {
        List<EditBox> before = boxes(screen);
        int focused = -1;
        String text = "";
        int cursor = 0;
        for (int i = 0; i < before.size(); i++) {
            if (before.get(i).isFocused()) { focused = i; text = before.get(i).getValue(); cursor = before.get(i).getCursorPosition(); }
        }
        rebuild.run();
        screen.clearFocus();
        if (focused < 0) return;
        List<EditBox> after = boxes(screen);
        if (focused >= after.size()) return;
        EditBox box = after.get(focused);
        if (!box.getValue().equals(text)) box.setValue(text);
        box.setCursorPosition(Math.min(cursor, text.length()));
        box.setHighlightPos(box.getCursorPosition());
        screen.setFocused(box);
    }

    private static List<EditBox> boxes(Screen screen) {
        List<EditBox> out = new ArrayList<>();
        for (GuiEventListener c : screen.children()) if (c instanceof EditBox e) out.add(e);
        return out;
    }
}
