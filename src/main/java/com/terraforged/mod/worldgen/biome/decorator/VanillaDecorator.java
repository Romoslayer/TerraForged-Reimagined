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

import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
import net.minecraft.core.registries.Registries;
import com.terraforged.mod.worldgen.Generator;
import com.terraforged.mod.worldgen.settings.TerraSettings;
import net.minecraft.core.*;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.*;

public class VanillaDecorator {
    public static void decorate(long seed,
                                int from, int to,
                                BlockPos origin,
                                Holder<Biome> biome,
                                List<Holder<Biome>> chunkBiomes,
                                ChunkAccess chunk,
                                WorldGenLevel level,
                                Generator generator,
                                WorldgenRandom random,
                                StructureManager structureManager,
                                FeatureDecorator decorator) {

        for (int stage = from; stage <= to; stage++) {
            var structures = decorator.getStageStructures(stage);

            placeStructures(seed, stage, chunk, level, generator, random, structureManager, structures);

            // A biome's "features" list is only as long as its last non-empty generation step, so a
            // missing entry means "no features at this step" -- not "skip the step". This used to
            // `continue` before placing the step's structures as well, so a biome that stopped short
            // silently disabled whole steps for every chunk it governed. Harmless for the stages
            // vanilla puts structures in, but it is the wrong shape and modded structures do sit in
            // the later stages. Feature seeds are absolute in (index, stage), so running structures
            // first does not shift any feature's seed.
            var features = decorator.getStageFeatures(stage, biome.value());
            int originCount = features == null ? 0 : features.size();

            if (features != null) {
                placeFeatures(seed, structures.size(), stage, origin, level, generator, random, features);
            }

            placeOtherBiomeFeatures(seed, structures.size() + originCount, stage, origin, features,
                    chunkBiomes, level, generator, random, decorator);
        }
    }

    /**
     * Vanilla runs the features of <b>every biome in the chunk</b> at every generation step, each placed
     * feature once, and lets each feature's own {@code minecraft:biome} filter decide per position. This
     * decorator ran the <b>origin</b> biome alone -- the one sampled at y=minY -- so a biome the carver
     * stamped higher up got nothing but its stage-9 vegetation. Reported from play as sulfur caves with
     * no pools: a sulfur cave at y=-9, inside the deep biome band, had pools and spikes; one at y=15,
     * where the carver had merely stamped the biome, had neither.
     *
     * <p>The origin biome keeps its existing seed indices and the biomes the chunk merely contains are
     * appended after them, so every feature that already generated is untouched and only the missing
     * ones are added. That is a deliberate divergence from vanilla, which indexes features by their
     * position in a global per-step list; matching that would reseed every feature in the world.
     *
     * <p>Features are deduplicated against the origin biome and each other, because vanilla runs each
     * placed feature at most once per chunk -- without that the ~29 shared ore features would run once
     * per biome in the chunk.
     *
     * <p>Safe by construction: every placed feature declared by any of the 58 overworld biomes carries a
     * {@code minecraft:biome} filter, and that filter passes only where the biome <i>stored at the target
     * position</i> declares the same feature ({@code BiomeFilter} is
     * {@code level.getBiome(pos).getGenerationSettings().hasFeature(f)}). So a feature contributed by a
     * biome the chunk merely contains can land only on that biome's own cells.
     */
    private static void placeOtherBiomeFeatures(long seed,
                                                int offset,
                                                int stage,
                                                BlockPos origin,
                                                HolderSet<PlacedFeature> originFeatures,
                                                List<Holder<Biome>> chunkBiomes,
                                                WorldGenLevel level,
                                                Generator generator,
                                                WorldgenRandom random,
                                                FeatureDecorator decorator) {

        var misc = generator.getSettings().miscellaneous;
        Set<PlacedFeature> seen = null;
        int index = 0;

        for (int b = 0; b < chunkBiomes.size(); b++) {
            var features = decorator.getStageFeatures(stage, chunkBiomes.get(b).value());
            if (features == null || features.size() == 0) continue;

            // Built lazily: most stages are empty for most biomes, and this runs per chunk per stage.
            if (seen == null) {
                seen = Collections.newSetFromMap(new IdentityHashMap<>());
                if (originFeatures != null) {
                    for (int i = 0; i < originFeatures.size(); i++) seen.add(originFeatures.get(i).value());
                }
            }

            for (int i = 0; i < features.size(); i++) {
                var holder = features.get(i);
                if (!seen.add(holder.value())) continue;

                // Seeded before the skip, as in placeFeatures, so a switched-off feature does not
                // reshuffle the ones after it.
                random.setFeatureSeed(seed, offset + index, stage);
                index++;

                if (isDisabled(holder, misc)) continue;

                new FeaturePlacer(level, generator).placeWithBiomeCheck(holder.value(), random, origin);
            }
        }
    }

    private static void placeStructures(long seed,
                                        int stage,
                                        ChunkAccess chunk,
                                        WorldGenLevel level,
                                        Generator generator,
                                        WorldgenRandom random,
                                        StructureManager structureManager,
                                        List<Holder<Structure>> structures) {

        var chunkPos = chunk.getPos();
        var sectionPos = SectionPos.of(chunkPos, level.getMinSectionY());

        for (int structureIndex = 0; structureIndex < structures.size(); structureIndex++) {
            random.setFeatureSeed(seed, structureIndex, stage);

            var structure = structures.get(structureIndex);
            var starts = structureManager.startsForStructure(sectionPos.x(), sectionPos.z(), structure.value());
            for (int startIndex = 0; startIndex < starts.size(); startIndex++) {
                var start = starts.get(startIndex);
                start.placeInChunk(level, structureManager, generator, random, getWritableArea(chunk), chunkPos);
            }
        }
    }

    private static void placeFeatures(long seed,
                                      int offset,
                                      int stage,
                                      BlockPos origin,
                                      WorldGenLevel level,
                                      Generator generator,
                                      WorldgenRandom random,
                                      HolderSet<PlacedFeature> features) {

        var misc = generator.getSettings().miscellaneous;

        for (int i = 0; i < features.size(); i++) {
            // Seeded before the skip, so turning one feature off does not reshuffle every feature after
            // it -- the rest of the chunk decorates exactly as it would have.
            random.setFeatureSeed(seed, offset + i, stage);

            var holder = features.get(i);
            if (isDisabled(holder, misc)) continue;

            new FeaturePlacer(level, generator).placeWithBiomeCheck(holder.value(), random, origin);
        }
    }

    /** Miscellaneous page switches for vanilla features, matched by placed-feature id. */
    private static boolean isDisabled(Holder<PlacedFeature> feature, TerraSettings.Miscellaneous misc) {
        if (misc.vanillaSprings && misc.vanillaLavaLakes && misc.vanillaLavaSprings) return false;

        var key = feature.unwrapKey();
        if (key.isEmpty() || !key.get().identifier().getNamespace().equals("minecraft")) return false;

        return switch (key.get().identifier().getPath()) {
            case "spring_water" -> !misc.vanillaSprings;
            case "lake_lava_underground", "lake_lava_surface" -> !misc.vanillaLavaLakes;
            case "spring_lava", "spring_lava_frozen" -> !misc.vanillaLavaSprings;
            default -> false;
        };
    }

    public static Map<GenerationStep.Decoration, List<Holder<Structure>>> buildStructureMap(HolderLookup.Provider access) {
        final var map = new EnumMap<GenerationStep.Decoration, List<Holder<Structure>>>(GenerationStep.Decoration.class);
        final var registry = access.lookupOrThrow(Registries.STRUCTURE);

        // listElements() yields the holders directly, rather than key/value pairs to be re-resolved.
        registry.listElements().forEach(structure ->
                map.computeIfAbsent(structure.value().step(), s -> new ArrayList<>()).add(structure));

        for (var stage : FeatureDecorator.STAGES) {
            if (!map.containsKey(stage)) {
                map.put(stage, Collections.emptyList());
            }
        }

        return map;
    }

    private static BoundingBox getWritableArea(ChunkAccess chunkAccess) {
        var chunkPos = chunkAccess.getPos();
        int minX = chunkPos.getMinBlockX();
        int minZ = chunkPos.getMinBlockZ();

        LevelHeightAccessor levelHeightAccessor = chunkAccess.getHeightAccessorForGeneration();
        int minY = levelHeightAccessor.getMinY() + 1;
        int maxY = levelHeightAccessor.getMaxY();

        return new BoundingBox(minX, minY, minZ, minX + 15, maxY, minZ + 15);
    }
}
