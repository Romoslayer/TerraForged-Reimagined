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

package com.terraforged.engine.world.biome.type;

import com.terraforged.noise.util.NoiseUtil;
import com.terraforged.noise.util.Vec2f;

import java.awt.Color;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Classifies a (temperature, moisture) pair into a broad climate type.
 *
 * <p>Upstream derived this lookup at runtime by reading {@code biomes.png} through {@code ImageIO}
 * and colour-matching each pixel to the nearest {@link #getLookup() lookup} colour. That table is
 * now baked ahead of time into {@code /terraforged/biome_types.bin} — one unsigned byte per cell,
 * row-major, {@link #RESOLUTION}x{@link #RESOLUTION}, each holding a {@code BiomeType} ordinal.
 * The bake reproduces upstream's mapping exactly; doing it ahead of time keeps {@code java.awt}
 * image decoding off the runtime path, which matters because this class is also loaded on
 * dedicated servers.
 *
 * <p>The display colours below are the values upstream shipped in its {@code biomes.txt} override
 * file, which replaced every default, so they are inlined here rather than re-read from a file.
 */
public enum BiomeType {
    TROPICAL_RAINFOREST(7, 83, 48, 0x347d3f),
    SAVANNA(151, 165, 39, 0x88ad3e),
    DESERT(200, 113, 55, 0xe6db9c),
    TEMPERATE_RAINFOREST(10, 84, 109, 0x528c35),
    TEMPERATE_FOREST(44, 137, 160, 0x5d9948),
    GRASSLAND(179, 124, 6, 0x4bb34f),
    COLD_STEPPE(131, 112, 71, 0xc2bb84),
    STEPPE(199, 155, 60, 0xe0cb89),
    TAIGA(91, 143, 82, 0x4d733b),
    TUNDRA(147, 167, 172, 0xd7dbc8),
    ALPINE(0, 0, 0, 0xeff2ed);

    public static final int RESOLUTION = 256;
    public static final int MAX = RESOLUTION - 1;

    private static final String LOOKUP_RESOURCE = "/terraforged/biome_types.bin";

    private final Color lookup;
    private final Color color;

    private float minTemp;
    private float maxTemp;
    private float minMoist;
    private float maxMoist;

    BiomeType(int r, int g, int b, int rgb) {
        this.lookup = new Color(r, g, b);
        this.color = new Color(rgb);
    }

    Color getLookup() {
        return lookup;
    }

    public Color getColor() {
        return color;
    }

    public float mapTemperature(float value) {
        return (value - minTemp) / (maxTemp - minTemp);
    }

    public float mapMoisture(float value) {
        return (value - minMoist) / (maxMoist - minMoist);
    }

    public float getMinMoisture() {
        return minMoist;
    }

    public float getMaxMoisture() {
        return maxMoist;
    }

    public float getMinTemperature() {
        return minTemp;
    }

    public float getMaxTemperature() {
        return maxTemp;
    }

    public boolean isExtreme() {
        return this == TUNDRA || this == DESERT;
    }

    public static BiomeType get(float temperature, float moisture) {
        return getCurve(temperature, moisture);
    }

    public static BiomeType getLinear(float temperature, float moisture) {
        int x = NoiseUtil.round(MAX * temperature);
        int y = getYLinear(x, temperature, moisture);
        return getType(x, y);
    }

    public static BiomeType getCurve(float temperature, float moisture) {
        int x = NoiseUtil.round(MAX * temperature);
        int y = getYCurve(x, moisture);
        return getType(x, y);
    }

    private static BiomeType getType(int x, int y) {
        return Lookup.MAP[clamp(y)][clamp(x)];
    }

    private static int clamp(int index) {
        if (index < 0) return 0;
        return Math.min(index, MAX);
    }

    private static int getYLinear(int x, float temperature, float moisture) {
        if (moisture > temperature) {
            return x;
        }
        return NoiseUtil.round(MAX * moisture);
    }

    private static int getYCurve(int x, float moisture) {
        int max = x + ((MAX - x) / 2);
        int y = NoiseUtil.round(max * moisture);
        return Math.min(x, y);
    }

    /**
     * Holder so the table is read once, lazily, on first classification rather than during
     * enum construction (where the constants themselves are not yet available).
     */
    private static final class Lookup {
        static final BiomeType[][] MAP = load();

        private static BiomeType[][] load() {
            BiomeType[] values = BiomeType.values();
            byte[] data = new byte[RESOLUTION * RESOLUTION];

            try (InputStream in = BiomeType.class.getResourceAsStream(LOOKUP_RESOURCE)) {
                if (in == null) {
                    throw new IOException("missing resource " + LOOKUP_RESOURCE);
                }
                new DataInputStream(in).readFully(data);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to load the TerraForged biome-type lookup table", e);
            }

            var map = new BiomeType[RESOLUTION][RESOLUTION];
            for (int y = 0; y < RESOLUTION; y++) {
                for (int x = 0; x < RESOLUTION; x++) {
                    int ordinal = data[y * RESOLUTION + x] & 0xFF;
                    if (ordinal >= values.length) {
                        throw new IllegalStateException("Corrupt biome-type lookup table at " + x + "," + y);
                    }
                    map[y][x] = values[ordinal];
                }
            }

            for (BiomeType type : values) {
                Vec2f[] ranges = getRanges(map, type);
                type.minTemp = ranges[0].x;
                type.maxTemp = ranges[0].y;
                type.minMoist = ranges[1].x;
                type.maxMoist = ranges[1].y;
            }

            return map;
        }

        private static Vec2f[] getRanges(BiomeType[][] map, BiomeType type) {
            float minTemp = 1F, maxTemp = 0F, minMoist = 1F, maxMoist = 0F;
            for (int moist = 0; moist < map.length; moist++) {
                BiomeType[] row = map[moist];
                for (int temp = 0; temp < row.length; temp++) {
                    if (row[temp] != type) continue;
                    float temperature = temp / (float) (row.length - 1);
                    float moisture = moist / (float) (map.length - 1);
                    minTemp = Math.min(minTemp, temperature);
                    maxTemp = Math.max(maxTemp, temperature);
                    minMoist = Math.min(minMoist, moisture);
                    maxMoist = Math.max(maxMoist, moisture);
                }
            }
            return new Vec2f[]{new Vec2f(minTemp, maxTemp), new Vec2f(minMoist, maxMoist)};
        }
    }
}
