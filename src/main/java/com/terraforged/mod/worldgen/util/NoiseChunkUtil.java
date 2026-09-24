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

package com.terraforged.mod.worldgen.util;

import com.terraforged.mod.worldgen.Generator;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;

public class NoiseChunkUtil {
    /**
     * The {@code NoiseChunk} the surface system reads while dressing a chunk.
     *
     * <p>Up to 26.2 this reflected TerraForged's heights into the noise chunk's preliminary-surface cache.
     * 26.3's noise chunk has no such cache -- it is only a density volume, its samplers and an aquifer --
     * and the surface system samples the preliminary surface from {@code terrainState}'s router instead,
     * which carries {@link TerrainSurfaceLevel}. See {@link Generator#terrainState}.
     *
     * <p>Close it when done: it borrows a density buffer pool from {@code terrainState}.
     */
    public static NoiseChunk createSurfaceNoiseChunk(ChunkAccess chunk, RandomState terrainState, Generator generator) {
        var vanilla = generator.getVanillaGen();
        var pos = chunk.getPos();
        var heights = chunk.getHeightAccessorForGeneration();
        var volume = new DensityVolume(16, heights.getHeight(), 16, pos.getMinBlockX(), heights.getMinY(), pos.getMinBlockZ());
        return new NoiseChunk(terrainState, Beardifier.EMPTY, vanilla.getSurfaceSettings(), vanilla.getGlobalFluidPicker(),
                Blender.empty(), volume);
    }
}
