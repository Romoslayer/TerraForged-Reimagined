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

import net.minecraft.core.registries.Registries;
import com.terraforged.mod.TerraForged;
import com.terraforged.mod.worldgen.asset.TerrainNoise;
import com.terraforged.mod.worldgen.biome.BiomeGenerator;
import com.terraforged.mod.worldgen.biome.Source;
import com.terraforged.mod.worldgen.noise.INoiseGenerator;
import com.terraforged.mod.worldgen.noise.NoiseGenerator;
import com.terraforged.mod.worldgen.terrain.TerrainLevels;
import com.terraforged.mod.worldgen.settings.TerraSettings;
import net.minecraft.core.Registry;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.WorldGenSettings;

public class GeneratorPreset {
    /**
     * Builds the generator from the world preset.
     *
     * <p>Note what this deliberately does *not* do: touch the registries. It runs while the datapack
     * registries are still being loaded, so it only stores the lookup; everything that has to read a
     * registry is built on first use inside {@link Generator} and {@link Source}. Enumerating a
     * registry here is what made TerraForged's world preset fail to load at all, taking the world type
     * out of the create-world screen with it.
     */
    public static Generator build(TerrainLevels levels, TerraSettings settings, HolderLookup.Provider registries) {
        return new Generator(levels, settings, registries);
    }

    public static Generator build(TerrainLevels levels, HolderLookup.Provider registries) {
        return build(levels, new TerraSettings(), registries);
    }

    /** Called lazily, once the registries are populated -- see {@link #build}. */
    public static INoiseGenerator createNoiseGenerator(TerrainLevels levels, TerraSettings settings,
                                                      HolderLookup.Provider registries) {
        return createUnerodedNoiseGenerator(levels, settings, registries).withErosion();
    }

    /**
     * The same generator without the erosion pass, for the preview map.
     *
     * <p>Erosion is simulated per chunk from its neighbours, which is far too slow to run for a map
     * covering tens of thousands of blocks every time a slider moves. The preview samples the terrain
     * noise directly, as 1.16.5's did.
     */
    public static NoiseGenerator createUnerodedNoiseGenerator(TerrainLevels levels, TerraSettings settings,
                                                              HolderLookup.Provider registries) {
        var terrain = TerraForged.TERRAINS.entries(registries, TerrainNoise[]::new);
        var ids = TerraForged.TERRAINS.entryIds(registries);
        return new NoiseGenerator(levels, settings, applyTerrainOverrides(terrain, ids, settings));
    }

    /**
     * Applies the Terrain page's per-terrain weight and scale overrides.
     *
     * <p>Keyed by registry id ("mountains_1"), not by terrain type: five of the shipped terrains share
     * the type {@code mountains}, three {@code hills}, two {@code flats}, and keying by type would make
     * them share one weight.
     *
     * <p>A terrain with no entry passes through untouched — the same {@code TerrainNoise} instance —
     * which is what keeps a world with no saved terrain settings identical to one generated before
     * those settings existed. Scales of exactly 1 are also skipped rather than wrapped: wrapping is
     * mathematically neutral but not guaranteed to be bit-identical in floating point.
     */
    private static boolean isMountain(String id) {
        return id.contains("mountain") || id.contains("dolomites")
                || id.contains("torridonian") || id.contains("dales");
    }

    public static TerrainNoise[] applyTerrainOverrides(TerrainNoise[] terrains, java.util.List<String> ids,
                                                       TerraSettings settings) {
        var overrides = settings.terrain.terrains;
        var general = settings.terrain.general;
        boolean globalScale = general.globalVerticalScale != 1F;
        // SettingsSerializer now clamps to the @Range on load, so this should never see 0. Kept anyway:
        // 1/0 would hand the noise an infinite frequency, which comes out as a world of NaN, and a
        // guard this cheap is worth having against a settings object built some other way.
        float stretch = general.mountainWidth;
        if (!(stretch > 0F)) stretch = 1F;
        if (overrides.isEmpty() && !globalScale && general.fancyMountains && stretch == 1F) return terrains;

        var result = new java.util.ArrayList<TerrainNoise>(terrains.length);
        for (int i = 0; i < terrains.length; i++) {
            var terrain = terrains[i];
            String id = ids.get(i);

            // Fancy Mountains off drops the ridged mountain variants entirely.
            if (!general.fancyMountains && id.contains("ridge")) continue;

            var entry = overrides.get(id);
            boolean stretched = stretch != 1F && isMountain(id);
            if (entry == null && !globalScale && !stretched) {
                result.add(terrain);
                continue;
            }

            var noise = terrain.noise();
            float weight = terrain.weight();

            if (entry != null) {
                weight = entry.weight;

                if (entry.horizontalScale != 1F && entry.horizontalScale > 0F) {
                    // Frequency is the inverse of feature size, so stretching features means lowering it.
                    double frequency = 1.0 / Math.max(0.01, entry.horizontalScale);
                    noise = noise.freq(frequency, frequency);
                }
                if (entry.verticalScale != 1F) {
                    noise = noise.scale(entry.verticalScale);
                }
                if (entry.baseScale != 1F) {
                    // Base Scale shifts the terrain's whole height up or down; a tenth of the world
                    // height per unit away from 1, clamped so it cannot leave the 0-1 height range.
                    noise = noise.bias((entry.baseScale - 1F) * 0.1F).clamp(0, 1);
                }
            }

            if (stretched) {
                // Stretch the mountain terrains horizontally. Frequency is the inverse of feature
                // size, so this widens them without touching their height -- the peaks stay where
                // they were and the faces between them get longer, which is the only way to make a
                // near-vertical face gentler without flattening the range.
                double frequency = 1.0 / stretch;
                noise = noise.freq(frequency, frequency);
            }

            if (globalScale) {
                noise = noise.scale(general.globalVerticalScale);
            }

            result.add(new TerrainNoise(terrain.type(), weight, noise));
        }

        // Every terrain removed would leave the blender with nothing to choose from; keep the originals.
        return result.isEmpty() ? terrains : result.toArray(TerrainNoise[]::new);
    }

    public static LevelStem getDefault(HolderLookup.Provider registries) {
        var generator = build(TerrainLevels.DEFAULT.get().copy(), registries);
        var type = registries.lookupOrThrow(Registries.DIMENSION_TYPE);
        return new LevelStem(type.getOrThrow(BuiltinDimensionTypes.OVERWORLD), generator);
    }

    public static VanillaGen getVanillaGen(BiomeSource biomes, HolderLookup.Provider access, TerrainLevels levels) {
        // The structure-set and noise-parameter registries used to be passed through to the vanilla
        // generator; it resolves both from the world itself now, so only the settings are needed.
        var settings = access.lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        return new VanillaGen(biomes, settings, levels.seaLevel);
    }

    public static boolean isTerraForgedWorld(WorldGenSettings settings) {
        // dimensions() is a WorldDimensions record now rather than a Registry<LevelStem>, and it
        // hands back the overworld's generator directly.
        return getGenerator(settings.dimensions().overworld()) != null;
    }

    public static boolean isTerraForgedWorld(ServerLevel level) {
        return getGenerator(level) != null;
    }

    public static Generator getGenerator(ServerLevel level) {
        return getGenerator(level.getChunkSource().getGenerator());
    }

    public static Generator getGenerator(ChunkGenerator chunkGenerator) {
//        if (chunkGenerator instanceof GeneratorProfiler profiler) {
//            chunkGenerator = profiler.getGenerator();
//        }

        if (chunkGenerator instanceof Generator generator) {
            return generator;
        }

        return null;
    }
}
