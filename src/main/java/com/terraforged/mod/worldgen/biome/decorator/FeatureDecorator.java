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
import com.terraforged.mod.worldgen.biome.vegetation.BiomeVegetationManager;
import com.terraforged.mod.worldgen.biome.vegetation.VegetationFeatures;
import com.terraforged.mod.worldgen.terrain.TerrainData;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.*;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class FeatureDecorator {
    public static final GenerationStep.Decoration[] STAGES = GenerationStep.Decoration.values();
    private static final int MAX_DECORATION_STAGE = GenerationStep.Decoration.TOP_LAYER_MODIFICATION.ordinal();

    private final BiomeVegetationManager vegetation;
    private final Map<GenerationStep.Decoration, List<Holder<Structure>>> structures;

    public FeatureDecorator(HolderLookup.Provider access) {
        this.vegetation = new BiomeVegetationManager(access);
        this.structures = VanillaDecorator.buildStructureMap(access);
    }

    public BiomeVegetationManager getVegetationManager() {
        return vegetation;
    }

    public List<Holder<Structure>> getStageStructures(int stage) {
        return structures.get(STAGES[stage]);
    }

    public HolderSet<PlacedFeature> getStageFeatures(int stage, Biome biome) {
        var stages = biome.getGenerationSettings().features();
        if (stage >= stages.size()) return null;
        return stages.get(stage);
    }

    public void decorate(ChunkAccess chunk,
                         WorldGenLevel level,
                         StructureManager structures,
                         CompletableFuture<TerrainData> terrain,
                         Generator generator) {
        var origin = getOrigin(level, chunk);
        var biome = level.getBiome(origin);
        var random = getRandom(level.getSeed());

        long seed = random.setDecorationSeed(level.getSeed(), origin.getX(), origin.getZ());

        // Read once and shared by every stage. Sorted by registry key so the order the extra features
        // are seeded in does not depend on palette insertion order; placeOther keeps palette order,
        // which is what it was measured with.
        var chunkBiomes = sortedChunkBiomes(chunk);

        decoratePre(seed, origin, biome, chunkBiomes, chunk, level, generator, random, structures);
        // Custom Biome Features: TerraForged's own slope- and climate-aware tree and plant placement, or
        // vanilla's feature list for the vegetation stage when turned off. On by default, as before.
        if (generator.getSettings().miscellaneous.customBiomeFeatures) {
            decorateVegetation(seed, origin, biome, chunk, level, generator, random, terrain);
        } else {
            VanillaDecorator.decorate(seed, VegetationFeatures.STAGE, VegetationFeatures.STAGE, origin, biome,
                    chunkBiomes, chunk, level, generator, random, structures, this);
        }
        decoratePost(seed, origin, biome, chunkBiomes, chunk, level, generator, random, structures);
    }

    /**
     * Every distinct biome stored anywhere in the chunk, cave biomes included -- the palette is read
     * rather than the columns, because a cave biome the carver stamped may occupy only a few cells and
     * still wants its decoration. An unused palette entry costs one extra biome-filtered attempt.
     */
    public static List<Holder<Biome>> chunkBiomes(ChunkAccess chunk) {
        var out = new ArrayList<Holder<Biome>>();
        for (int i = 0; i < chunk.getSections().length; i++) {
            var section = chunk.getSections()[i];
            if (section == null) continue;
            section.getBiomes().getAll(b -> { if (!out.contains(b)) out.add(b); });
        }
        return out;
    }

    private static List<Holder<Biome>> sortedChunkBiomes(ChunkAccess chunk) {
        var out = chunkBiomes(chunk);
        out.sort(Comparator.comparing(b -> b.unwrapKey().map(k -> k.identifier().toString()).orElse("")));
        return out;
    }

    private void decoratePre(long seed,
                             BlockPos origin,
                             Holder<Biome> biome,
                             List<Holder<Biome>> chunkBiomes,
                             ChunkAccess chunk,
                             WorldGenLevel level,
                             Generator generator,
                             WorldgenRandom random,
                             StructureManager structureManager) {

        VanillaDecorator.decorate(seed, 0, VegetationFeatures.STAGE - 1, origin, biome, chunkBiomes, chunk, level, generator, random, structureManager, this);
    }

    private void decoratePost(long seed,
                              BlockPos origin,
                              Holder<Biome> biome,
                              List<Holder<Biome>> chunkBiomes,
                              ChunkAccess chunk,
                              WorldGenLevel level,
                              Generator generator,
                              WorldgenRandom random,
                              StructureManager structureManager) {

        VanillaDecorator.decorate(seed, VegetationFeatures.STAGE + 1, MAX_DECORATION_STAGE, origin, biome, chunkBiomes, chunk, level, generator, random, structureManager, this);
    }

    private void decorateVegetation(long seed,
                                    BlockPos origin,
                                    Holder<Biome> biome,
                                    ChunkAccess chunk,
                                    WorldGenLevel level,
                                    Generator generator,
                                    WorldgenRandom random,
                                    CompletableFuture<TerrainData> terrain) {

        PositionSampler.placeVegetation(seed, origin, biome, chunk, level, generator, random, terrain, this);
    }

    private static BlockPos getOrigin(WorldGenLevel level, ChunkAccess chunk) {
        var chunkPos = chunk.getPos();
        var sectionPos = SectionPos.of(chunkPos, level.getMinSectionY());
        return sectionPos.origin();
    }

    private static WorldgenRandom getRandom(long seed) {
        return new WorldgenRandom(new LegacyRandomSource(seed));
    }
}
