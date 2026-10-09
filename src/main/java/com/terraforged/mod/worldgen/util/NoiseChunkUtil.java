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
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

public class NoiseChunkUtil {
    private static final Object LOCK = new Object();
    private static NoiseGeneratorSettings cachedSettings;
    private static long cachedSeed;
    private static RandomState cachedState;

    /**
     * The noise chunk surface rules read their preliminary surface from.
     *
     * <p>Upstream also filled its preliminary-surface cache with TerraForged's heights. That never took effect
     * on 26.x: the aquifer fills the cache from this noise chunk's own (empty) router first, and the fill skipped
     * a non-empty cache. It is gone, and surfaces do not depend on the aquifer setting.
     *
     * <p>What 26.2 does instead is read a preliminary surface of 0 everywhere: its router has a
     * {@code preliminarySurfaceLevel} density function, which is zero in a non-vanilla generator's empty
     * router, so every column above about y=-5 is dressed. 1.21.1 has no such function. It scans
     * {@code initialDensityWithoutJaggedness} down from the top for the first density above 0.390625 and,
     * finding none in an empty router, returns {@code Integer.MAX_VALUE} -- so {@code above_preliminary_surface}
     * failed everywhere and hills and mountains were left as bare stone. This noise chunk's router steps
     * that density from 1 at y=0 to 0 at y=8, so the scan stops at 0, as 26.2 reads it.
     */
    public static NoiseChunk getNoiseChunk(ChunkAccess chunk, WorldGenRegion region, Generator generator) {
        return chunk.getOrCreateNoiseChunk(c -> {
            var vanilla = generator.getVanillaGen();
            var fluidPicker = vanilla.getGlobalFluidPicker();
            var settings = vanilla.getSettings().value();
            var state = surfaceState(settings, region);
            return NoiseChunk.forChunk(c, state, NoopNoise.BEARDIFIER, settings, fluidPicker, Blender.empty());
        });
    }

    private static RandomState surfaceState(NoiseGeneratorSettings settings, WorldGenRegion region) {
        long seed = region.getSeed();
        synchronized (LOCK) {
            if (cachedState == null || cachedSettings != settings || cachedSeed != seed) {
                var zero = DensityFunctions.zero();
                DensityFunction surfaceAtZero = DensityFunctions.yClampedGradient(0, 8, 1.0, 0.0);
                var router = new NoiseRouter(zero, zero, zero, zero, zero, zero, zero, zero, zero, zero,
                        surfaceAtZero, zero, zero, zero, zero);
                var surfaceSettings = new NoiseGeneratorSettings(settings.noiseSettings(), settings.defaultBlock(),
                        settings.defaultFluid(), router, settings.surfaceRule(), settings.spawnTarget(),
                        settings.seaLevel(), settings.disableMobGeneration(), settings.aquifersEnabled(),
                        settings.oreVeinsEnabled(), settings.useLegacyRandomSource());
                cachedState = RandomState.create(surfaceSettings, region.registryAccess().lookupOrThrow(Registries.NOISE), seed);
                cachedSettings = settings;
                cachedSeed = seed;
            }
            return cachedState;
        }
    }
}
