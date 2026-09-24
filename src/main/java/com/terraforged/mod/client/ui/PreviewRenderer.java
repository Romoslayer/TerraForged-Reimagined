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

import com.terraforged.mod.client.ui.preview.RenderMode;
import com.terraforged.mod.util.ColorUtil;
import com.terraforged.mod.worldgen.GeneratorPreset;
import com.terraforged.mod.worldgen.biome.BiomeSampler;
import com.terraforged.mod.worldgen.biome.util.BiomeMapManager;
import com.terraforged.mod.worldgen.noise.NoiseGenerator;
import com.terraforged.mod.worldgen.noise.continent.ContinentPoints;
import com.terraforged.mod.worldgen.settings.TerraSettings;
import com.terraforged.mod.worldgen.terrain.TerrainLevels;
import net.minecraft.core.HolderLookup;

/**
 * Renders the preview map for a set of world settings.
 *
 * <p>Samples the uneroded terrain noise: erosion is simulated per chunk from its neighbours, far too
 * slow to run across tens of thousands of blocks every time a slider moves. 1.16.5's preview skipped
 * it for the same reason, and the difference is local detail, not the shapes the map is for.
 *
 * <p>Runs off the render thread. The {@link Model} it builds is kept by the widget so hovering the map
 * can sample the same generator the picture was drawn from, rather than building another.
 */
public final class PreviewRenderer {
    /** Colours, low to high, for land above sea level in {@link RenderMode#ELEVATION}. */
    private static final int[] LAND = {
            0x4F7942, 0x5E8C3A, 0x76913C, 0x8F8F4A, 0x9C8A5E, 0x8D7F6E, 0x9A9A9A, 0xC8C8C8, 0xFFFFFF,
    };

    private static final int DEEP_WATER = 0x1B3A63;
    private static final int SHALLOW_WATER = 0x3A6EA5;

    private PreviewRenderer() {}

    /** A generator built from one set of settings, shared by the render and the hover readout. */
    public record Model(TerrainLevels levels, NoiseGenerator noise, BiomeSampler biomes, int seed) {
        public static Model create(TerrainLevels levels, TerraSettings settings, HolderLookup.Provider registries,
                                   BiomeMapManager biomeMap, long seed) {
            var noise = GeneratorPreset.createUnerodedNoiseGenerator(levels, settings, registries);
            return new Model(levels, noise, new BiomeSampler(noise, biomeMap), (int) seed);
        }
    }

    /** What the map shows at one point, for the text under the cursor. */
    public record Info(int x, int z, String terrain, String biome) {}

    public static Info info(Model model, RenderMode mode, int x, int z) {
        var climate = model.biomes().getSample(model.seed(), x, z);
        String climateName = climate.climateType.name().toLowerCase(java.util.Locale.ROOT);

        var noise = model.noise().getNoiseSample(model.seed(), x, z);
        String terrain = noise.terrainType.getName();

        String biome = mode == RenderMode.BIOME
                ? model.biomes().sampleBiome(model.seed(), x, z).unwrapKey()
                        .map(key -> key.identifier().getPath()).orElse("?")
                : climateName;

        return new Info(x, z, terrain, biome);
    }

    /**
     * @param area side length of the mapped square, in blocks, centred on the world origin
     * @return ARGB pixels, row-major
     */
    public static int[] render(Model model, RenderMode mode, int size, int area) {
        var levels = model.levels();
        int seed = model.seed();
        float step = (float) area / size;
        float origin = -area / 2F;

        var heights = new int[size * size];
        var colors = new int[size * size];
        var water = new boolean[size * size];

        for (int py = 0; py < size; py++) {
            int z = (int) (origin + py * step);

            for (int px = 0; px < size; px++) {
                int x = (int) (origin + px * step);
                int i = py * size + px;

                var noise = model.noise().getNoiseSample(seed, x, z);
                int height = levels.getHeight(levels.getScaledHeight(noise.heightNoise));
                String terrainName = noise.terrainType.getName();
                heights[i] = height;

                // Read the climate sample before sampleBiome: both reuse one thread-local sample, so
                // biome selection would overwrite these fields.
                var climate = model.biomes().getSample(seed, x, z);
                water[i] = climate.continentNoise < ContinentPoints.BEACH || climate.riverNoise <= 0F;

                colors[i] = switch (mode) {
                    case BIOME_TYPE -> ColorUtil.getColor(climate, 1F);
                    case ELEVATION -> water[i]
                            ? lerp(SHALLOW_WATER, DEEP_WATER, clamp((levels.seaLevel - height) / 40F))
                            : ramp(clamp((height - levels.seaLevel) / (float) Math.max(1, levels.maxY - levels.seaLevel)));
                    case TRANSITION_POINTS -> transition(climate.continentNoise);
                    case TEMPERATURE -> water[i] ? SHALLOW_WATER : lerp(0x3050FF, 0xFF4020, climate.temperature);
                    case MOISTURE -> water[i] ? SHALLOW_WATER : lerp(0xE0C070, 0x2060C0, climate.moisture);
                    case TERRAIN_REGION -> water[i] ? SHALLOW_WATER : hashColor(terrainName);
                    case BIOME -> hashColor(model.biomes().sampleBiome(seed, x, z).unwrapKey()
                            .map(key -> key.identifier().toString()).orElse(""));
                };
            }
        }

        return shade(colors, heights, water, size, step);
    }

    /**
     * Lights land from the north-west.
     *
     * <p>Without it the map is flat colour, and the difference between a plateau and a mountain range —
     * most of what the World and Terrain pages change — is invisible. The slope comes from neighbours
     * already sampled, so it costs nothing, and is normalised by the sample spacing so zooming out does
     * not turn every hill into a cliff.
     */
    private static int[] shade(int[] colors, int[] heights, boolean[] water, int size, float step) {
        var pixels = new int[size * size];
        float strength = 3F / Math.max(1F, step);

        for (int py = 0; py < size; py++) {
            for (int px = 0; px < size; px++) {
                int i = py * size + px;
                int color = colors[i] & 0xFFFFFF;

                if (!water[i]) {
                    int west = px > 0 ? heights[i - 1] : heights[i];
                    int north = py > 0 ? heights[i - size] : heights[i];
                    float relief = ((heights[i] - west) + (heights[i] - north)) * 0.5F;
                    color = scale(color, 0.85F + clamp(0.5F + relief * strength) * 0.3F);
                }

                pixels[i] = 0xFF000000 | color;
            }
        }

        return pixels;
    }

    private static int transition(float continent) {
        if (continent < ContinentPoints.DEEP_OCEAN) return 0x102A55;
        if (continent < ContinentPoints.SHALLOW_OCEAN) return DEEP_WATER;
        if (continent < ContinentPoints.BEACH) return SHALLOW_WATER;
        if (continent < ContinentPoints.COAST) return 0xE6DB9C;
        if (continent < ContinentPoints.INLAND) return 0x8FBF5A;
        return 0x4B8B3B;
    }

    /** A stable, reasonably saturated colour per name, so the same terrain or biome is always the same colour. */
    private static int hashColor(String name) {
        int hash = name.hashCode() * 0x9E3779B1;
        float hue = ((hash >>> 8) & 0xFFFF) / 65535F;
        return java.awt.Color.HSBtoRGB(hue, 0.55F, 0.8F) & 0xFFFFFF;
    }

    private static int ramp(float t) {
        float scaled = t * (LAND.length - 1);
        int index = (int) scaled;
        if (index >= LAND.length - 1) return LAND[LAND.length - 1];
        return lerp(LAND[index], LAND[index + 1], scaled - index);
    }

    private static int lerp(int from, int to, float t) {
        t = clamp(t);
        int r = (int) (((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = (int) (((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return (r << 16) | (g << 8) | b;
    }

    private static int scale(int color, float factor) {
        int r = Math.min(255, (int) (((color >> 16) & 0xFF) * factor));
        int g = Math.min(255, (int) (((color >> 8) & 0xFF) * factor));
        int b = Math.min(255, (int) ((color & 0xFF) * factor));
        return (r << 16) | (g << 8) | b;
    }

    private static float clamp(float value) {
        if (value < 0F) return 0F;
        return Math.min(value, 1F);
    }
}
