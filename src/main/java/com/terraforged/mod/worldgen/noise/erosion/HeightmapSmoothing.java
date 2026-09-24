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

package com.terraforged.mod.worldgen.noise.erosion;

import com.terraforged.mod.worldgen.settings.TerraSettings;
import com.terraforged.noise.util.NoiseUtil;

/**
 * The Filters page's Smoothing, ported from the 0.2.x engine's {@code Smoothing} filter.
 *
 * <p>Same algorithm: each cell moves toward the distance-weighted average of its neighbours within the
 * radius, by the smoothing rate, repeated for the iteration count. It runs on the erosion tile after
 * erosion, as 0.2.x ran it after erosion, and on a margin-inset area so every cell it changes has a
 * full neighbourhood -- the tile already carries a neighbour-chunk margin for erosion.
 *
 * <p>0.2.x additionally weakened smoothing with altitude using its per-cell terrain data, which this
 * heightmap does not carry; smoothing here applies evenly.
 */
final class HeightmapSmoothing {
    private HeightmapSmoothing() {}

    static void apply(float[] map, int length, TerraSettings.Smoothing settings) {
        int iterations = settings.iterations;
        if (iterations <= 0) return;

        float radiusValue = settings.smoothingRadius;
        int radius = NoiseUtil.round(radiusValue + 0.5F);
        float rad2 = radiusValue * radiusValue;
        float strength = settings.smoothingRate;
        if (radius <= 0 || rad2 <= 0 || strength <= 0) return;

        while (iterations-- > 0) {
            for (int z = radius; z < length - radius; z++) {
                for (int x = radius; x < length - radius; x++) {
                    float total = 0;
                    float weights = 0;

                    for (int dz = -radius; dz <= radius; dz++) {
                        for (int dx = -radius; dx <= radius; dx++) {
                            float dist2 = dx * dx + dz * dz;
                            if (dist2 > rad2) continue;

                            float weight = 1F - (dist2 / rad2);
                            total += map[(z + dz) * length + (x + dx)] * weight;
                            weights += weight;
                        }
                    }

                    if (weights > 0) {
                        int index = z * length + x;
                        map[index] -= (map[index] - total / weights) * strength;
                    }
                }
            }
        }
    }
}
