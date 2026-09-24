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

package com.terraforged.mod.client.ui;

import com.mojang.blaze3d.platform.NativeImage;
import com.terraforged.mod.TerraForged;
import com.terraforged.mod.client.ui.preview.RenderMode;
import com.terraforged.mod.worldgen.biome.util.BiomeMapManager;
import com.terraforged.mod.worldgen.settings.SettingsSerializer;
import com.terraforged.mod.worldgen.settings.TerraSettings;
import com.terraforged.mod.worldgen.terrain.TerrainLevels;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The preview map: a top-down render of the world the current settings would generate.
 *
 * <p>Rendering happens off-thread and the result is uploaded on the next frame. Requests are
 * collapsed rather than queued — a slider drag fires a change per pixel of travel — so at most one
 * render runs, and when it finishes with the settings having moved on, one more starts.
 *
 * <p>Owns a native texture. The screen must {@link #close()} it when it goes away and must not reuse
 * a closed widget: the image is freed memory at that point, and writing to it crashes the game.
 */
public class PreviewWidget extends AbstractWidget {
    private static final int RESOLUTION = 160;

    public static final int MIN_ZOOM = 1;
    public static final int MAX_ZOOM = 100;

    /**
     * Blocks across the map at a given zoom, matching 1.16.5's slider: its screenshots show 38400 at
     * zoom 1 and 12800 at zoom 68, which fits a curve dividing the area by three every 67 steps. An
     * earlier version here used 12000 at zoom 1 and divided linearly, so even zoom 1 was already more
     * than three times closer in than the original and the slider could only go closer still.
     */
    static int areaFor(int zoom) {
        int z = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
        return (int) Math.round(38400 * Math.pow(3, -(z - 1) / 67.0));
    }

    private static final Component TITLE = Component.translatable("terraforged.preview");
    private static final Component PENDING = Component.translatable("terraforged.preview.pending");

    private final Minecraft minecraft = Minecraft.getInstance();
    private final Identifier textureId;
    private final NativeImage image;
    private final DynamicTexture texture;

    private final HolderLookup.Provider registries;
    private final BiomeMapManager biomeMap;

    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Request requested;
    private volatile Result pending;

    /** The model the picture on screen was drawn from, for the hover readout. */
    private volatile Result shown;
    private boolean closed;

    private int hoverPixel = -1;
    private PreviewRenderer.Info hoverInfo;

    public boolean showCoords;

    public record Request(TerrainLevels levels, TerraSettings settings, long seed, RenderMode mode, int zoom) {
        /**
         * The part of a request that determines the generator. Mode and zoom only change how an
         * existing generator is drawn, so switching them reuses the model instead of rebuilding it —
         * which is most of the cost.
         */
        String modelKey() {
            // Through the codec, not toString: TerrainLevels#toString omits fields such as the
            // horizontal scale, and a key that missed one would keep drawing a stale generator.
            var levelsJson = TerrainLevels.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, levels)
                    .result().map(Object::toString).orElse(String.valueOf(System.identityHashCode(levels)));
            return levelsJson + "|" + SettingsSerializer.write(settings) + "|" + seed;
        }

        int area() {
            return areaFor(zoom);
        }
    }

    private record Result(Request request, PreviewRenderer.Model model, String modelKey, int[] pixels) {}

    public PreviewWidget(HolderLookup.Provider registries) {
        super(0, 0, 100, 100, TITLE);

        this.registries = registries;
        // Independent of the settings, and it logs its biome pool on construction, so it is built once
        // per widget rather than once per render.
        this.biomeMap = new BiomeMapManager(registries);

        this.image = new NativeImage(RESOLUTION, RESOLUTION, false);
        this.texture = new DynamicTexture(() -> "terraforged-preview", image);
        this.textureId = TerraForged.location("preview/" + java.util.UUID.randomUUID());
        minecraft.getTextureManager().register(textureId, texture);
    }

    /**
     * Requests a render. The settings are copied here, on the calling thread: the screen keeps editing
     * its own instance while the render reads this one on a worker.
     */
    public void update(TerrainLevels levels, TerraSettings settings, long seed, RenderMode mode, int zoom) {
        requested = new Request(levels, settings.copy(), seed, mode, zoom);
        startIfIdle();
    }

    private void startIfIdle() {
        if (closed || !running.compareAndSet(false, true)) return;

        var request = requested;
        var previous = shown;

        CompletableFuture.supplyAsync(() -> {
                    String key = request.modelKey();
                    var model = previous != null && previous.modelKey().equals(key)
                            ? previous.model()
                            : PreviewRenderer.Model.create(request.levels(), request.settings(), registries, biomeMap,
                            request.seed());

                    int[] pixels = PreviewRenderer.render(model, request.mode(), RESOLUTION, request.area());
                    return new Result(request, model, key, pixels);
                })
                .whenComplete((result, error) -> {
                    if (error != null) {
                        // A broken preview must not take the screen with it; the settings are still usable.
                        TerraForged.LOG.warn("Failed to render terrain preview", error);
                    } else {
                        pending = result;
                    }

                    running.set(false);
                    if (requested != request) startIfIdle();
                });
    }

    private void upload() {
        if (closed) return;

        var result = pending;
        if (result == null) return;
        pending = null;

        for (int y = 0; y < RESOLUTION; y++) {
            for (int x = 0; x < RESOLUTION; x++) {
                image.setPixel(x, y, result.pixels()[y * RESOLUTION + x]);
            }
        }

        texture.upload();
        shown = result;
        hoverPixel = -1;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        upload();

        var result = shown;
        if (result == null || closed) {
            graphics.fill(getX(), getY(), getX() + width, getY() + height, 0xFF202020);
            graphics.text(minecraft.font, PENDING, getX() + (width - minecraft.font.width(PENDING)) / 2,
                    getY() + height / 2 - 4, -1);
            return;
        }

        graphics.blit(RenderPipelines.GUI_TEXTURED, textureId, getX(), getY(), 0F, 0F,
                width, height, RESOLUTION, RESOLUTION, RESOLUTION, RESOLUTION);

        extractInfo(graphics, result, mouseX, mouseY);
    }

    /**
     * The text in the corner of the map: always the area covered, and the terrain and biome under the
     * cursor while hovering, as 1.16.5 showed it.
     */
    private void extractInfo(GuiGraphicsExtractor graphics, Result result, int mouseX, int mouseY) {
        int area = result.request().area();
        var lines = new java.util.ArrayList<String>();
        lines.add(label("area") + area + "x" + area);

        if (isMouseOver(mouseX, mouseY)) {
            int px = (mouseX - getX()) * RESOLUTION / Math.max(1, width);
            int py = (mouseY - getY()) * RESOLUTION / Math.max(1, height);
            int pixel = py * RESOLUTION + px;

            // One sample per hovered pixel, not per frame: sampling the biome is several noise lookups.
            if (pixel != hoverPixel) {
                hoverPixel = pixel;
                int blockX = (int) (-area / 2F + (px + 0.5F) * area / RESOLUTION);
                int blockZ = (int) (-area / 2F + (py + 0.5F) * area / RESOLUTION);
                hoverInfo = PreviewRenderer.info(result.model(), result.request().mode(), blockX, blockZ);
            }

            if (hoverInfo != null) {
                lines.add(label("terrain") + hoverInfo.terrain());
                lines.add(label("biome") + hoverInfo.biome());
                if (showCoords) lines.add("X: " + hoverInfo.x() + "  Z: " + hoverInfo.z());
            }
        }

        int lineHeight = minecraft.font.lineHeight + 1;
        int y = getY() + height - lines.size() * lineHeight - 2;
        for (var line : lines) {
            graphics.text(minecraft.font, line, getX() + 3, y, 0xFFFFFFFF, true);
            y += lineHeight;
        }
    }

    private static String label(String key) {
        return Component.translatable("terraforged.preview." + key).getString() + " ";
    }

    /** The map is looked at, not clicked: no click sound, no focus. */
    @Override
    protected boolean isValidClickButton(net.minecraft.client.input.MouseButtonInfo buttonInfo) {
        return false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, TITLE);
    }

    /** Frees the texture. Idempotent; a closed widget must not be added to a screen again. */
    public void close() {
        if (closed) return;
        closed = true;

        minecraft.getTextureManager().release(textureId);
        texture.close();
    }
}
