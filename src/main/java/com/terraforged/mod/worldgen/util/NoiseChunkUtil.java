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
     * <p>Up to 26.2 this also tried to reflect TerraForged's heights into the noise chunk's preliminary-surface
     * cache, and skipped doing so if the cache was already populated -- which it always was, because the
     * noise chunk's own aquifer fills it on construction. So the rules read the empty router's 0, and
     * dressed every column above roughly y=-5. 26.3's noise chunk has no such cache; the surface system
     * samples the router's {@code chunk_surface_level}, which for the level's random state is that same 0.
     *
     * <p>Close it when done: it borrows a density buffer pool from {@code state}.
     */
    public static NoiseChunk createSurfaceNoiseChunk(ChunkAccess chunk, RandomState state, Generator generator) {
        var vanilla = generator.getVanillaGen();
        var pos = chunk.getPos();
        var heights = chunk.getHeightAccessorForGeneration();
        var volume = new DensityVolume(16, heights.getHeight(), 16, pos.getMinBlockX(), heights.getMinY(), pos.getMinBlockZ());
        return new NoiseChunk(state, Beardifier.EMPTY, vanilla.getSurfaceSettings(), vanilla.getGlobalFluidPicker(),
                Blender.empty(), volume);
    }
}
