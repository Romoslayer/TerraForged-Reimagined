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

import com.terraforged.mod.data.ModBiomes;
import com.terraforged.mod.util.storage.WeightMap;
import com.terraforged.mod.worldgen.biome.util.BiomeMapManager;
import com.terraforged.mod.worldgen.biome.util.BiomeUtil;
import com.terraforged.mod.worldgen.cave.CaveType;
import com.terraforged.noise.util.Noise;
import com.terraforged.noise.util.NoiseUtil;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

public class CaveBiomeSampler {
    public static final int OFFSET = 124897;

    /** A different offset so the mask does not correlate with the choice of biome. */
    public static final int MASK_OFFSET = 987541;

    /** Mask regions are coarser than the biome noise, so a region holds one biome, not a mosaic. */
    protected final float maskFrequency = 1F / 1600F;

    /** Simplex output clusters around the middle, so this sits a little above it. */
    protected final float maskThreshold = 0.62F;

    protected final int scale;
    protected final float frequency;
    protected final Map<CaveType, WeightMap<Holder<Biome>>> typeMap = new EnumMap<>(CaveType.class);

    public CaveBiomeSampler(int scale, BiomeMapManager biomeMapManager) {
        this.scale = scale;
        this.frequency = 1F / scale;

        var biomes = biomeMapManager.getBiomes();
        var cave = biomes.get(ModBiomes.CAVE.get());

        Holder<Biome>[] global = cave.isPresent() ? new Holder[]{cave.get()} : new Holder[0];

        // Every biome in the registry used to land here: the filter that should have narrowed it to
        // underground biomes was commented out when Biome.BiomeCategory was removed in 1.19.3, and
        // nothing replaced it. That let CaveType.UNIQUE pick anything at all -- nether and end
        // biomes included -- for a cave. ModTags.NON_SURFACE is the replacement, and because it is
        // a datapack tag, a biome mod's own cave biomes land in TerraForged's caves by being listed
        // in it.
        Holder<Biome>[] special = biomes.listElements()
                .filter(BiomeUtil::isNonSurface)
                .toArray(Holder[]::new);

        // An empty WeightMap has nothing to return, so fall back rather than hand out nulls. This
        // happens if a datapack empties the tag.
        if (special.length == 0) {
            special = global;
        }

        this.typeMap.put(CaveType.GLOBAL, create(global));
        this.typeMap.put(CaveType.UNIQUE, create(special));

        warnIncompleteCaveBiomes(special);
    }

    /**
     * Because {@link #getLayerBiome} is consulted by the biome source, a biome in
     * {@code ModTags.NON_SURFACE} is what the chunk stores at the bottom of the world -- and that is
     * the biome {@code FeatureDecorator} reads at the chunk origin to pick the feature list for the
     * <b>whole chunk</b>. A cave biome written as decoration rather than as a complete overworld
     * biome therefore starves every chunk it governs.
     *
     * <p>That is exactly what {@code terraforged:cave} did: it declared 8 of the 11 generation steps
     * with an empty {@code UNDERGROUND_ORES}, so roughly 8% of the world generated with no ores, no
     * dungeons, no geodes and no snow. Nothing reported it for the whole port -- the only visible
     * trace was snow boundaries landing on chunk edges 2.55x more often than chance. Any biome mod
     * that lists an incomplete cave biome in the tag can do the same, so say so at startup rather
     * than leaving it silent.
     */
    private static void warnIncompleteCaveBiomes(Holder<Biome>[] biomes) {
        int steps = net.minecraft.world.level.levelgen.GenerationStep.Decoration.values().length;
        int ores = net.minecraft.world.level.levelgen.GenerationStep.Decoration.UNDERGROUND_ORES.ordinal();

        for (var holder : biomes) {
            var features = holder.value().getGenerationSettings().features();
            String id = holder.unwrapKey().map(key -> key.identifier().toString()).orElse("?");

            if (features.size() < steps) {
                com.terraforged.mod.TerraForged.LOG.warn(
                        "Cave biome {} declares only {} of {} generation steps. It is placed by the biome"
                        + " source, so every chunk whose origin lands in it loses every step past {}"
                        + " -- including the top layer, so no snow or ice.",
                        id, features.size(), steps, features.size() - 1);
            } else if (features.get(ores).size() == 0) {
                com.terraforged.mod.TerraForged.LOG.warn(
                        "Cave biome {} has no UNDERGROUND_ORES features. It is placed by the biome source,"
                        + " so chunks whose origin lands in it generate with no ores at all.", id);
            }
        }
    }

    public CaveBiomeSampler(CaveBiomeSampler other) {
        this.scale = other.scale;
        this.frequency = 1F / other.scale;
        this.typeMap.putAll(other.typeMap);
    }

    public Holder<Biome> getUnderGroundBiome(int seed, int x, int z, CaveType type) {
        float noise = sample(seed + OFFSET, x, z, frequency);
        return typeMap.get(type).getValue(noise);
    }

    /**
     * The cave biome for a deep column, or null where the underground keeps the surface biome.
     *
     * <p>This is what makes cave biomes part of the world rather than decoration. Until now the only
     * caller of this class was {@code CarverChunk}, which used it to dress TerraForged's own carved
     * caves; the biome source itself never returned a cave biome, so as far as the rest of the game
     * was concerned they did not exist. Lush caves could not be found, and ancient cities — whose
     * only permitted biome is {@code deep_dark} — could never generate at all.
     *
     * <p>The mask is a second, coarser noise so that cave biomes form regions with ordinary
     * underground between them. Returning one everywhere would be worse than returning none: the
     * whole underground would change its mob spawns and decoration, and the deep dark in particular
     * suppresses spawning entirely.
     */
    public Holder<Biome> getLayerBiome(int seed, int x, int z) {
        float mask = sample(seed + MASK_OFFSET, x, z, maskFrequency);
        if (mask < maskThreshold) return null;

        return getUnderGroundBiome(seed, x, z, CaveType.UNIQUE);
    }

    protected static float sample(int seed, int x, int z, float frequency) {
        float nx = x * frequency;
        float nz = z * frequency;
        float noise = (1 + Noise.singleSimplex(nx, nz, seed)) * 0.5F;
        return NoiseUtil.clamp(noise, 0F, 1F);
    }

    protected static WeightMap<Holder<Biome>> create(Holder<Biome>[] biomes) {
        var weights = new float[biomes.length];
        Arrays.fill(weights, 1);
        return new WeightMap<>(biomes, weights);
    }
}
