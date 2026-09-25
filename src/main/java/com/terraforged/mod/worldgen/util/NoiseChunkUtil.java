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
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

public class NoiseChunkUtil {
    /**
     * The noise chunk surface rules read their preliminary surface from, built on the level's random state.
     *
     * <p>Upstream also filled its preliminary-surface cache with TerraForged's heights. That never took effect
     * here: the aquifer fills the cache from this noise chunk's own (empty) router first, and the fill skipped
     * a non-empty cache. It could only fire where a datapack turns aquifers off, and emulating it on 26.3 left
     * slopes as bare stone. So it is gone, and surfaces no longer depend on the aquifer setting.
     */
    public static NoiseChunk getNoiseChunk(ChunkAccess chunk, RandomState state, Generator generator) {
        return chunk.getOrCreateNoiseChunk(c -> {
            var vanilla = generator.getVanillaGen();
            var fluidPicker = vanilla.getGlobalFluidPicker();
            var settings = vanilla.getSettings().value();
            return NoiseChunk.forChunk(c, state, NoopNoise.BEARDIFIER, settings, fluidPicker, Blender.empty());
        });
    }
}
