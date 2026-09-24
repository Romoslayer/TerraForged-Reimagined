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

package com.terraforged.mod.worldgen.biome.decorator;

import com.terraforged.mod.worldgen.Generator;
import com.terraforged.mod.worldgen.biome.surface.Surface;
import com.terraforged.mod.worldgen.util.NoiseChunkUtil;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;

public class SurfaceDecorator {
    /**
     * Runs vanilla's surface rules (26.3: material rules) over the chunk.
     *
     * @param terrainState TerraForged's own random state, whose router reports TerraForged's terrain height
     *                     as the preliminary surface; see {@link Generator#terrainState}.
     */
    public void decorate(ChunkAccess chunk, BiomeManager biomeManager, Generator generator, RandomState terrainState) {
        var context = new WorldGenerationContext(generator, chunk.getHeightAccessorForGeneration());
        var rule = generator.getVanillaGen().getSettings().value().materialRule().value();

        try (var noiseChunk = NoiseChunkUtil.createSurfaceNoiseChunk(chunk, terrainState, generator)) {
            // Every biome the source can place, as before 26.3, rather than only those near this chunk.
            terrainState.surfaceSystem().buildSurface(terrainState, biomeManager, context, chunk, noiseChunk, rule,
                    generator.getBiomeSource().possibleBiomes());
        }
    }

    public void decoratePost(ChunkAccess chunk, Generator generator) {
        var chunkData = generator.getChunkData(generator.getSeed(), chunk.getPos());
        // Miscellaneous > Erosion Decorator. On by default, as it always ran before it was a setting.
        if (generator.getSettings().miscellaneous.erosionDecorator) {
            Surface.apply(chunkData, chunk, generator, generator.getSettings().miscellaneous.plainStoneErosion);
        }
    }
}
