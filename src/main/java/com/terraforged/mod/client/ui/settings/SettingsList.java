/*
 * MIT License
 *
 * Copyright (c) 2021 TerraForged
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.terraforged.mod.client.ui.settings;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The scrolling column of section headings and controls on the left of the settings screen.
 *
 * <p>Laid out like 1.16.5's pages: headings flush with the left edge of the controls, controls in a
 * single column taking most of the panel's width, and the scrollbar at the panel's right edge rather
 * than beside the row.
 *
 * <p>1.20.1's lists give every row the same height, so a heading takes a full row and sits at its
 * bottom, next to the control it introduces.
 */
public class SettingsList extends ContainerObjectSelectionList<SettingsList.Row> {
    private static final int ROW_HEIGHT = 24;

    private final Font font;

    public SettingsList(Minecraft minecraft, int x, int y, int width, int height) {
        super(minecraft, width, minecraft.getWindow().getGuiScaledHeight(), y, y + height, ROW_HEIGHT);
        this.font = minecraft.font;
        this.setLeftPos(x);
        // The screen paints the panel, so the list draws no background or edge shading of its own.
        this.setRenderBackground(false);
        this.setRenderTopAndBottom(false);
        this.centerListVertically = false;
    }

    public void addHeader(Component text) {
        addEntry(new HeaderRow(text));
    }

    public void addControl(AbstractWidget widget) {
        addEntry(new ControlRow(widget));
    }

    public void clear() {
        clearEntries();
        setScrollAmount(0);
    }

    /** Controls take seven tenths of the panel, set in from the left, as on 1.16.5's pages. */
    @Override
    public int getRowWidth() {
        return (int) (width * 0.7F);
    }

    @Override
    public int getRowLeft() {
        return x0 + (int) (width * 0.15F);
    }

    @Override
    protected int getScrollbarPosition() {
        return x0 + width - 6;
    }

    public abstract static class Row extends ContainerObjectSelectionList.Entry<Row> {}

    private class HeaderRow extends Row {
        private final Component text;

        private HeaderRow(Component text) {
            this.text = text;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height, int mouseX,
                           int mouseY, boolean hovered, float a) {
            graphics.drawString(font, text, getRowLeft(), top + height - font.lineHeight - 2, 0xFFFFFFFF, true);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of();
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of();
        }
    }

    private class ControlRow extends Row {
        private final AbstractWidget widget;

        private ControlRow(AbstractWidget widget) {
            this.widget = widget;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height, int mouseX,
                           int mouseY, boolean hovered, float a) {
            widget.setWidth(getRowWidth());
            widget.setX(getRowLeft());
            widget.setY(top);
            widget.render(graphics, mouseX, mouseY, a);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(widget);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(widget);
        }
    }
}
