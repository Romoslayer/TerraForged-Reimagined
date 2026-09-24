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

package com.terraforged.mod.worldgen;

import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.carver.CarvingContext;

/**
 * Wraps a vanilla {@link NoiseBasedChunkGenerator} so TerraForged can borrow parts of vanilla's
 * generation (aquifers, carving context) while driving the terrain itself.
 *
 * <p>The structure-set and noise-parameter registries this used to carry are gone:
 * {@code NoiseBasedChunkGenerator} takes only {@code (BiomeSource, Holder<NoiseGeneratorSettings>)}
 * now, and {@code ChunkGenerator} no longer takes a structure-set registry either — both are
 * resolved from the world at generation time.
 */
public class VanillaGen {
    protected final NoiseBasedChunkGenerator vanillaGenerator;
    protected final Holder<NoiseGeneratorSettings> settings;

    protected final int lavaLevel;
    protected final Aquifer.FluidStatus fluidStatus1;
    protected final Aquifer.FluidStatus fluidStatus2;
    protected final Aquifer.FluidPicker globalFluidPicker;

    public VanillaGen(BiomeSource biomeSource, VanillaGen other) {
        this(biomeSource, other.settings, other.fluidStatus2.fluidLevel());
    }

    /**
     * @param seaLevel TerraForged's own sea level, not the one on {@code settings}.
     *                 <p>This used to read {@code settings.value().seaLevel()}, which is vanilla's
     *                 overworld sea level and has nothing to do with where TerraForged actually puts
     *                 water. The two differ by a block even with stock settings (63 against 62), and
     *                 they diverge completely as soon as the sea level is changed in the customize
     *                 screen, or by a mod that edits vanilla's overworld noise settings -- terrain
     *                 mods such as Continents do exactly that. The aquifers would then fill to a
     *                 different height than the oceans, which shows up as flooded or half-empty
     *                 caves near the coast rather than as an error.
     */
    public VanillaGen(BiomeSource biomeSource, Holder<NoiseGeneratorSettings> settings, int seaLevel) {
        this.settings = settings;
        this.lavaLevel = Math.min(-54, seaLevel);
        this.fluidStatus1 = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        this.fluidStatus2 = new Aquifer.FluidStatus(seaLevel, settings.value().defaultFluid());
        this.globalFluidPicker = (x, y, z) -> y < lavaLevel ? fluidStatus1 : fluidStatus2;
        this.vanillaGenerator = new NoiseBasedChunkGenerator(biomeSource, settings);
    }

    public Holder<NoiseGeneratorSettings> getSettings() {
        return settings;
    }

    public Aquifer.FluidPicker getGlobalFluidPicker() {
        return globalFluidPicker;
    }

    public CarvingContext createCarvingContext(WorldGenRegion region, ChunkAccess chunk, NoiseChunk noiseChunk, RandomState state) {
        return new CarvingContext(vanillaGenerator, region.registryAccess(), chunk.getHeightAccessorForGeneration(), noiseChunk, state, null);
    }
}
