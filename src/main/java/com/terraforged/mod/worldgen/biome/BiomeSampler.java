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

import com.terraforged.engine.world.biome.type.BiomeType;
import com.terraforged.mod.TerraForged;
import com.terraforged.mod.util.storage.WeightMap;
import com.terraforged.mod.worldgen.biome.util.BiomeMapManager;
import com.terraforged.mod.worldgen.noise.INoiseGenerator;
import com.terraforged.mod.worldgen.noise.climate.ClimateSample;
import com.terraforged.mod.worldgen.noise.continent.ContinentPoints;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;

import java.util.Map;

public class BiomeSampler extends IBiomeSampler.Sampler implements IBiomeSampler {
    protected final BiomeMapManager biomeMapManager;
    /**
     * How wide a band of continent noise becomes beach, starting at {@link ContinentPoints#BEACH}.
     *
     * <p>Upstream hard-coded {@code 0.005f} here, a band a tenth the width its own constants imply:
     * {@link ContinentPoints} defines {@code BEACH = 0.5} and {@code COAST = 0.55} as adjacent
     * points, and then {@code COAST} is never used for anything. A band that narrow is why shores
     * came out as a ragged one-block fringe rather than a beach — at 0.005 the biome boundary is so
     * sensitive to noise that it breaks up into single blocks.
     */
    protected final float beachSize = ContinentPoints.COAST - ContinentPoints.BEACH;

    public BiomeSampler(INoiseGenerator noiseGenerator, BiomeMapManager biomeMapManager) {
        super(noiseGenerator);
        this.biomeMapManager = biomeMapManager;
    }

    /**
     * How close to a river a swamp has to be. {@code riverNoise} is 1 well away from a river and 0
     * in the channel itself, so this is the river valley rather than the water -- which is where a
     * swamp belongs. The same threshold is what {@code NoiseGenerator} treats as "in the valley".
     */
    protected final float wetlandRiverNoise = 0.75f;

    /** Continent noise below which land is coastal lowland. {@code INLAND} is 0.6. */
    protected final float wetlandContinentNoise = 0.7f;

    /** How far apart the height samples are taken, in blocks. One biome cell. */
    protected static final int SLOPE_STEP = 24;

    /**
     * Blocks of rise per block of travel above which ground counts as steep — 0.42 is about 23 degrees.
     *
     * <p>Chosen to be clearly steeper than rolling countryside and clearly shallower than the ridges
     * villages were failing on. It is deliberately not a knife-edge threshold: too low and every
     * gentle hill turns windswept, which would be a worse world than the one this is fixing.
     */
    protected final float maxFlatGradient = 0.42F;

    private volatile boolean reported;

    /**
     * Logs what fraction of the world the slope gate actually catches, once, on first use.
     *
     * <p>{@link #maxFlatGradient} is the kind of constant that is easy to get an order of magnitude
     * wrong, and the symptom of getting it wrong — every gentle hill turning windswept — is only
     * visible after generating and flying around a world. A number in the log is faster. Runs on the
     * first biome sample rather than in the constructor because the seed is not known until then.
     */
    private void reportSlope(int seed) {
        if (reported || !TerraForged.LOG.isDebugEnabled()) return;
        reported = true;

        int samples = 0, steep = 0;
        for (int z = -2048; z < 2048; z += 64) {
            for (int x = -2048; x < 2048; x += 64) {
                samples++;
                if (isSteep(seed, x, z)) steep++;
            }
        }

        TerraForged.LOG.debug("Slope gate: {}% of a 4096-block area is steeper than {} blocks/block",
                Math.round(100F * steep / samples), maxFlatGradient);
    }

    public Holder<Biome> sampleBiome(int seed, int x, int z) {
        reportSlope(seed);

        var sample = getSample(seed, x, z);
        var biome = getInitialBiome(seed, x, z, sample);
        return getBiomeOverride(biome, sample);
    }

    /**
     * The biome climate alone would give this column, then the highland substitution if it applies.
     *
     * <p>The substitution is refused when it would change whether snow settles here; see
     * {@link #keepsSnowCover}.
     */
    private Holder<Biome> getInitialBiome(int seed, int x, int z, ClimateSample sample) {
        var normal = fromPool(lowlandPool(sample), sample);
        if (normal == null) {
            return biomeMapManager.getBiomes().getOrThrow(Biomes.PLAINS);
        }

        // Mountain Biome Usage: the share of steep ground that takes mountain biomes. The roll is per biome
        // cell, so a mountainside is consistently one or the other rather than speckled. Rolled before the
        // slope is measured because it is much the cheaper of the two.
        float mountainUsage = noiseGenerator.getSettings().miscellaneous.mountainBiomeUsage;
        if (mountainUsage <= 0F) return normal;
        if (mountainUsage < 1F && cellRoll(sample, 7309) >= mountainUsage) return normal;

        float heightNoise = noiseGenerator.getHeightNoise(seed, x, z);
        if (!isSteep(seed, x, z)) return normal;

        var highland = fromPool(biomeMapManager.getHighlandBiomeMap(), sample);
        if (highland == null || highland == normal) return normal;

        var levels = noiseGenerator.getTerrainLevels();
        int height = levels.getHeight(levels.getScaledHeight(heightNoise));

        return keepsSnowCover(normal, highland, height) ? highland : normal;
    }

    private Holder<Biome> fromPool(Map<BiomeType, WeightMap<Holder<Biome>>> pool, ClimateSample sample) {
        var map = pool.get(sample.climateType);
        if (map == null || map.isEmpty()) return null;
        return map.getValue(sample.biomeNoise);
    }

    /**
     * Whether swapping in a highland biome would leave the ground looking the same weather-wise.
     *
     * <p>The steep gate exists to keep villages off cliffs. It has no business deciding the climate, and
     * when it does the result is the reported artifact: the highland pool of every temperate climate here
     * is the windswept biomes, whose base temperature is 0.2 against 0.6 for birch forest and 0.8 for
     * plains. 0.2 is the only one of those cold enough to take snow at TerraForged's altitudes -- vanilla
     * drops temperature 0.05 per 40 blocks above sea level + 17, so 0.2 freezes above about y 121, and the
     * median column on the reporter's seed stands at y 148. So the snow came out painted exactly over the
     * steep mask, which is a terrain gradient sampled every 4 blocks rather than a climate boundary, and
     * it met unfrozen biomes of the same height along every edge of it.
     *
     * <p>Measured over a 1024-block square of that seed: 92% of snow-line crossings were biome changes
     * rather than altitude, with a median height step across them of 2 blocks and a median base
     * temperature step of 0.40. Refusing these substitutions takes the snow line from 3424 crossings to
     * 340 -- what switching the gate off entirely gives -- while the gate still fires on 44% of steep
     * ground, which is the part below the snow line where the substitution costs nothing.
     */
    private boolean keepsSnowCover(Holder<Biome> normal, Holder<Biome> highland, int height) {
        return freezes(normal, height) == freezes(highland, height);
    }

    /**
     * Whether snow settles on a column of this height, by vanilla's own rule.
     *
     * <p>{@code Biome#getHeightAdjustedTemperature} takes 0.05 off the base temperature every 40 blocks
     * above {@code seaLevel + 17}, and {@code warmEnoughToRain} calls anything under 0.15 snow. Vanilla
     * also shifts the height by up to +-8 blocks of noise; that is left out because this only has to say
     * whether two biomes agree, and they can only disagree over it within one contour of the line.
     */
    private boolean freezes(Holder<Biome> biome, int height) {
        int threshold = noiseGenerator.getTerrainLevels().seaLevel + 17;
        float temperature = biome.value().getBaseTemperature();
        if (height > threshold) {
            temperature -= (height - threshold) * 0.05F / 40F;
        }
        return temperature < 0.15F;
    }

    /**
     * Chooses which of the two flat-ground pools this column draws from.
     *
     * <p>Flat ground away from water drops the wetlands, so swamps stop appearing on hilltops with
     * no water in them. The highland pool is not chosen here: it is a substitution made afterwards,
     * against the biome this returns, in {@link #getInitialBiome}.
     */
    private Map<BiomeType, WeightMap<Holder<Biome>>> lowlandPool(ClimateSample sample) {
        var settings = noiseGenerator.getSettings();

        // Wetlands: river and coast reach set where wetland biomes are eligible, chance thins them out.
        // Their defaults are the 0.75 and 0.7 thresholds this used before they were settings.
        var wetlands = settings.rivers.wetlands;
        boolean eligible = sample.riverNoise < wetlands.riverSize
                || sample.continentNoise < wetlands.coastSize;
        boolean wet = eligible && (wetlands.chance >= 1F || cellRoll(sample, 4153) < wetlands.chance);

        return wet ? biomeMapManager.getBiomeMap() : biomeMapManager.getDryBiomeMap();
    }

    /** A 0-1 value that is constant across one biome cell, for per-cell decisions. */
    private static float cellRoll(ClimateSample sample, int salt) {
        return com.terraforged.mod.util.MathUtil.rand(Float.floatToIntBits(sample.biomeNoise), salt);
    }

    /**
     * Whether the ground rises faster than {@link #maxFlatGradient} here.
     *
     * <p>Unlike the wetland test this is not free: the sampler has continent, river and climate
     * noise to hand but no heightmap, so this costs four height samples per biome cell. It is worth
     * it -- a fraction of the cost of generating the chunk the cell belongs to, and biome results are
     * cached in {@code Source}.
     */
    protected boolean isSteep(int seed, int x, int z) {
        var levels = noiseGenerator.getTerrainLevels();

        // Measured over SLOPE_STEP blocks centred on the column, not 4 blocks forward of it. A 4-block
        // forward difference reads every bump as a hillside, so single biome cells were flipping to the
        // highland pool and appearing as slivers of windswept forest -- one spruce tree and a patch of
        // snow in the middle of an oak wood. Centred so the mask sits on the slope rather than half a
        // step downhill of it.
        int half = Math.max(1, SLOPE_STEP / 2);
        float west = noiseGenerator.getHeightNoise(seed, x - half, z);
        float east = noiseGenerator.getHeightNoise(seed, x + half, z);
        float north = noiseGenerator.getHeightNoise(seed, x, z - half);
        float south = noiseGenerator.getHeightNoise(seed, x, z + half);

        // Height noise is 0-1 across the world height, so scale it back to blocks before comparing
        // against a gradient -- otherwise the threshold would shift whenever the world height did.
        float dx = (east - west) * levels.maxY;
        float dz = (south - north) * levels.maxY;

        float gradient = Math.max(Math.abs(dx), Math.abs(dz)) / (half * 2);

        return gradient > maxFlatGradient;
    }

    protected Holder<Biome> getBiomeOverride(Holder<Biome> input, ClimateSample sample) {
        var biomeType = sample.climateType;

        if (sample.continentNoise <= ContinentPoints.SHALLOW_OCEAN) {
            return switch (biomeType) {
                case TAIGA, COLD_STEPPE -> biomeMapManager.get(Biomes.DEEP_COLD_OCEAN);
                case TUNDRA -> biomeMapManager.get(Biomes.DEEP_FROZEN_OCEAN);
                case DESERT, SAVANNA, TROPICAL_RAINFOREST -> biomeMapManager.get(Biomes.DEEP_LUKEWARM_OCEAN);
                default -> biomeMapManager.get(Biomes.DEEP_OCEAN);
            };
        }

        if (sample.continentNoise <= ContinentPoints.BEACH) {
            return switch (biomeType) {
                case TAIGA, COLD_STEPPE -> biomeMapManager.get(Biomes.COLD_OCEAN);
                case TUNDRA -> biomeMapManager.get(Biomes.FROZEN_OCEAN);
                case DESERT, SAVANNA, TROPICAL_RAINFOREST -> biomeMapManager.get(Biomes.WARM_OCEAN);
                default -> biomeMapManager.get(Biomes.OCEAN);
            };
        }

        if (sample.continentNoise <= ContinentPoints.BEACH + beachSize) {
            return switch (biomeType) {
                case TUNDRA -> biomeMapManager.get(Biomes.SNOWY_BEACH);
                case COLD_STEPPE -> biomeMapManager.get(Biomes.STONY_SHORE);
                default -> biomeMapManager.get(Biomes.BEACH);
            };
        }

        if ((sample.terrainType.isRiver() || sample.terrainType.isLake()) && sample.riverNoise == 0) {
            return biomeType == BiomeType.TUNDRA ? biomeMapManager.get(Biomes.FROZEN_RIVER) : biomeMapManager.get(Biomes.RIVER);
        }

        return input;
    }
}
