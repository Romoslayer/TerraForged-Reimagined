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

package com.terraforged.mod.worldgen.biome;

import com.terraforged.mod.worldgen.Generator;
import com.terraforged.mod.worldgen.Seeds;
import com.terraforged.mod.worldgen.biome.decorator.FeatureDecorator;
import com.terraforged.mod.worldgen.biome.decorator.SurfaceDecorator;
import com.terraforged.mod.worldgen.biome.surface.Surface;
import com.terraforged.mod.worldgen.cave.NoiseCaveGenerator;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.RandomState;

public class BiomeGenerator {
    private final SurfaceDecorator surfaceDecorator;
    private final FeatureDecorator featureDecorator;
    private final NoiseCaveGenerator noiseCaveGenerator;

    public BiomeGenerator(HolderLookup.Provider access) {
        this.surfaceDecorator = new SurfaceDecorator();
        this.featureDecorator = new FeatureDecorator(access);
        this.noiseCaveGenerator = new NoiseCaveGenerator(access);
    }

    public BiomeGenerator(BiomeGenerator other) {
        this.surfaceDecorator = other.surfaceDecorator;
        this.featureDecorator = other.featureDecorator;
        this.noiseCaveGenerator = new NoiseCaveGenerator(other.noiseCaveGenerator);
    }

    public void surface(ChunkAccess chunk, WorldGenRegion region, RandomState state, Generator generator) {
        surfaceDecorator.decorate(chunk, region, generator, state);
        surfaceDecorator.decoratePost(chunk, region, generator);
    }

    public void carve(long seed,
                      ChunkAccess chunk,
                      WorldGenRegion region,
                      BiomeManager biomes,
                      Generator generator,
                      net.minecraft.world.level.StructureManager structures) {

        noiseCaveGenerator.carve((int) seed, chunk, generator, structures);
    }

    public void decorate(ChunkAccess chunk, WorldGenLevel region, StructureManager structures, Generator generator) {
        int seed = Seeds.get(region.getSeed());
        var terrain = generator.getChunkDataAsync(seed, chunk.getPos());

        // This decorates the caves' biomes too: the carver writes each into the chunk's biome storage, and
        // the decorator runs every biome stored in the chunk. Upstream also ran each cave's biome features
        // again from the carver; here that placed a second set of any feature whose seed differed, which
        // raised ore by ~60% and doubled dripstone.
        featureDecorator.decorate(chunk, region, structures, terrain, generator);
        // The passes below are TerraForged's own, so a warning from them must not name the last feature.
        region.setCurrentlyGenerating(null);

        Surface.smoothWater(chunk, region, terrain.join());
        // Miscellaneous > Natural Snow Decorator. On by default, as it always ran before it was a setting.
        // Smooth Layer Decorator (snow smoothing on gentle ground) and Natural Snow Decorator (snow cleared
        // from steep faces) are the two halves of this pass. Both on by default, as it always ran.
        var misc = generator.getSettings().miscellaneous;
        if (misc.smoothLayerDecorator || misc.naturalSnowDecorator) {
            Surface.applyPost(chunk, terrain.join(), generator, misc.smoothLayerDecorator, misc.naturalSnowDecorator);
        }
    }
}
