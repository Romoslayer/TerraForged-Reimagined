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

import com.google.common.base.Suppliers;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.terraforged.mod.data.codec.WorldGenCodec;
import com.terraforged.mod.worldgen.biome.BiomeGenerator;
import com.terraforged.mod.worldgen.biome.Source;
import com.terraforged.mod.worldgen.noise.INoiseGenerator;
import com.terraforged.mod.worldgen.terrain.TerrainCache;
import com.terraforged.mod.worldgen.terrain.TerrainData;
import com.terraforged.mod.worldgen.terrain.TerrainLevels;
import com.terraforged.mod.worldgen.settings.SettingsSerializer;
import com.terraforged.mod.worldgen.settings.TerraSettings;
import com.terraforged.mod.worldgen.util.ChunkUtil;
import com.terraforged.mod.worldgen.util.ThreadPool;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.concurrent.Executor;

import org.jspecify.annotations.Nullable;

public class Generator extends ChunkGenerator implements IGenerator {
    public static final MapCodec<Generator> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            TerrainLevels.CODEC.optionalFieldOf("levels", TerrainLevels.DEFAULT.get()).forGetter(g -> g.levels),
            // Optional, defaulting to TerraSettings' defaults, so presets and worlds saved before it
            // existed decode to exactly the settings they were generated with.
            SettingsSerializer.CODEC.optionalFieldOf("settings", new TerraSettings()).forGetter(g -> g.settings),
            WorldGenCodec.CODEC.forGetter(Generator::getRegistries)
    ).apply(instance, instance.stable(GeneratorPreset::build)));

    protected final Source biomeSource;
    protected final TerrainLevels levels;
    protected final TerraSettings settings;
    protected final HolderLookup.Provider registries;
    protected final ThreadLocal<GeneratorResource> localResource = ThreadLocal.withInitial(GeneratorResource::new);

    private final Supplier<INoiseGenerator> noiseSupplier;
    private final Supplier<Parts> parts;

    /**
     * The pieces that have to read whole registries, built on first use rather than in the constructor.
     *
     * <p>See {@link Source} for the reasoning: the generator is decoded during datapack registry
     * loading, and a registry cannot be enumerated at that point. Everything here is reached only
     * once generation actually starts, by which time the registries are complete.
     */
    private record Parts(VanillaGen vanillaGen, BiomeGenerator biomeGenerator, TerrainCache terrainCache) {}

    /**
     * The world seed, as TerraForged's noise uses it.
     *
     * <p>Upstream read this off {@code RandomState#legacyLevelSeed()} wherever it was needed.
     * {@code RandomState} no longer exposes a seed, so it is captured once in
     * {@link #createState(HolderLookup, RandomState, long)} — the one place the chunk generator is
     * still handed the level seed — and reused. Volatile because chunk generation is threaded and
     * the write happens on whichever thread sets the world up.
     */
    protected volatile int seed;

    /** The raw level seed, which vanilla's density functions are wired to; see {@code DeepCaves}. */
    protected volatile long levelSeed;

    public Generator(TerrainLevels levels, TerraSettings settings, HolderLookup.Provider registries) {
        this(levels, settings, registries,
                Suppliers.memoize(() -> GeneratorPreset.createNoiseGenerator(levels, settings, registries)));
    }

    private Generator(TerrainLevels levels, TerraSettings settings, HolderLookup.Provider registries,
                      Supplier<INoiseGenerator> noise) {
        this(levels, settings, registries, noise, new Source(noise, registries));
    }

    private Generator(TerrainLevels levels,
                      TerraSettings settings,
                      HolderLookup.Provider registries,
                      Supplier<INoiseGenerator> noise,
                      Source biomeSource) {
        super(biomeSource);
        this.levels = levels;
        this.settings = settings;
        this.registries = registries;
        this.biomeSource = biomeSource;
        this.noiseSupplier = noise;
        this.parts = Suppliers.memoize(() -> new Parts(
                GeneratorPreset.getVanillaGen(biomeSource, registries, levels),
                new BiomeGenerator(registries),
                new TerrainCache(levels, noise.get())));
    }

    /** The terrain settings this generator was built with, for the customize screen to start from. */
    public TerrainLevels getLevels() {
        return levels;
    }

    /** The generator settings this generator was built with, for the customize screen to start from. */
    public TerraSettings getSettings() {
        return settings;
    }

    private VanillaGen vanillaGen() {
        return parts.get().vanillaGen();
    }

    private BiomeGenerator biomeGenerator() {
        return parts.get().biomeGenerator();
    }

    private TerrainCache terrainCache() {
        return parts.get().terrainCache();
    }

    /**
     * Captures the level seed and seeds the biome source.
     *
     * <p>Upstream did this in {@code ensureStructuresGenerated}, which no longer exists. This is now
     * the earliest hook that receives the seed, and it runs before any chunk is generated.
     */
    @Override
    public ChunkGeneratorStructureState createState(HolderLookup<StructureSet> structureSets, RandomState state, long levelSeed) {
        this.seed = Seeds.get(levelSeed);
        this.levelSeed = levelSeed;
        biomeSource.withSeed(levelSeed);

        // Identical to super.createState when no structure is overridden -- StructureOverrides returns
        // vanilla's own state in that case.
        return com.terraforged.mod.worldgen.settings.StructureOverrides.createState(structureSets, state, levelSeed,
                getOrigin(state), biomeSource, settings.structures);
    }

    protected HolderLookup.Provider getRegistries() {
        return biomeSource.getRegistries();
    }

    public VanillaGen getVanillaGen() {
        return vanillaGen();
    }

    /** The world seed as TerraForged uses it, captured in {@link #createState}. */
    public int getSeed() {
        return seed;
    }

    public INoiseGenerator getNoiseGenerator() {
        return noiseSupplier.get();
    }

    public TerrainData getChunkData(int seed, ChunkPos pos) {
        return terrainCache().getNow(seed, pos);
    }

    public CompletableFuture<TerrainData> getChunkDataAsync(int seed, ChunkPos pos) {
        return terrainCache().getAsync(seed, pos);
    }

    @Override
    public MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public int getMinY() {
        return levels.minY;
    }

    @Override
    public int getSeaLevel() {
        return levels.seaLevel;
    }

    @Override
    public int getGenDepth() {
        return levels.maxY;
    }

    @Override
    public Source getBiomeSource() {
        return biomeSource;
    }

    @Override
    public void createStructures(RegistryAccess access, ChunkGeneratorStructureState state, StructureManager structures, ChunkAccess chunk, StructureTemplateManager templates, ResourceKey<Level> level) {
        terrainCache().hint(Seeds.get(state.getLevelSeed()), chunk.getPos());
        super.createStructures(access, state, structures, chunk, templates, level);
    }

    @Override
    public void createReferences(WorldGenLevel level, StructureManager structureFeatures, ChunkAccess chunk) {
        terrainCache().hint(Seeds.get(level.getSeed()), chunk.getPos());
        super.createReferences(level, structureFeatures, chunk);
    }

    @Override
    public CompletableFuture<ChunkAccess> createBiomes(RandomState state, Blender blender, StructureManager structures, ChunkAccess chunk) {
        terrainCache().hint(seed, chunk.getPos());
        return CompletableFuture.supplyAsync(() -> {
            ChunkUtil.fillNoiseBiomes(chunk, biomeSource, localResource.get());
            return chunk;
        }, ThreadPool.EXECUTOR);
    }

    /**
     * 26.3 folded the noise, surface and carver chunk stages into one terrain stage. TerraForged's own three
     * steps run here, in the order the separate stages used to call them -- fill, surface, carve -- so what
     * each step sees is unchanged: the near-surface Deep Caves pass still runs before surface rules (entrance
     * floors dressed) and the deep pass after them (deep floors bare rock). None of the three reads another
     * chunk, so running them back to back is equivalent to running them as separate stages.
     */
    @Override
    public CompletableFuture<ChunkAccess> buildTerrain(ChunkAccess chunkAccess, Blender blender, RandomState state,
                                                       StructureManager structureManager, BiomeManager biomes,
                                                       @Nullable WorldGenRegion carverBiomeRegion,
                                                       Set<Holder<Biome>> possibleBiomes) {
        return terrainCache().combineAsync(ThreadPool.EXECUTOR, seed, chunkAccess, (chunk, terrainData) -> {
            fillFromNoise(chunk, terrainData, structureManager);
            buildSurface(chunk, biomes, state);
            applyCarvers(chunk, terrainData, structureManager);
            return chunk;
        });
    }

    private void fillFromNoise(ChunkAccess chunk, TerrainData terrainData, StructureManager structureManager) {
        ChunkUtil.fillChunk(getSeaLevel(), chunk, terrainData, ChunkUtil.FILLER, localResource.get());
        ChunkUtil.primeHeightmaps(getSeaLevel(), chunk, terrainData, ChunkUtil.FILLER);
        ChunkUtil.buildStructureTerrain(chunk, terrainData, structureManager);
        // Miscellaneous > Deep Caves: TerraForged's own caves stop around y=-32. The near-surface part is carved
        // here, before surface rules, so entrance floors are dressed; the rest in applyCarvers. See DeepCaves.
        if (settings.miscellaneous.deepCaves) {
            deepCaves(levelSeed).carve(chunk, structureManager,
                    (x, z) -> terrainData.getHeight(Math.max(0, Math.min(15, x - chunk.getPos().getMinBlockX())),
                            Math.max(0, Math.min(15, z - chunk.getPos().getMinBlockZ()))),
                    (dx, dz) -> terrainData.getHeight(dx, dz),
                    (dx, dz) -> terrainData.getRiver().get(dx, dz), true);
        }
        if (settings.miscellaneous.strataDecorator) {
            com.terraforged.mod.worldgen.util.StrataDecorator.apply(chunk, seed, settings.miscellaneous);
        }
    }

    /**
     * Surface rules run on the level's own random state, as they did up to 26.2. For a non-vanilla generator
     * that state carries an empty noise router ({@code ChunkMap}), so the preliminary surface the rules read is
     * 0 everywhere and they dress every column above roughly y=-5. That is what 26.2 actually did: its
     * attempt to feed TerraForged's heights into the noise chunk's cache never took effect, because the
     * aquifer filled that cache first -- see {@code NoiseChunkUtil}.
     */
    private void buildSurface(ChunkAccess chunk, BiomeManager biomes, RandomState state) {
        biomeGenerator().surface(chunk, biomes, state, this);

        // Only when the Bedrock Layer settings differ from their defaults; vanilla's floor otherwise.
        if (!settings.world.bedrockLayer.isVanilla()) {
            com.terraforged.mod.worldgen.util.BedrockLayer.apply(chunk, seed, settings.world.bedrockLayer);
        }
    }

    private void applyCarvers(ChunkAccess chunk, TerrainData terrainData, StructureManager structures) {
        // The carver stage was handed the raw level seed, which the cave generator narrows to an int itself.
        biomeGenerator().carve(levelSeed, chunk, this, structures);

        // Deep Caves below the near-surface zone: after surface rules, so deep cave floors stay bare rock.
        if (settings.miscellaneous.deepCaves) {
            deepCaves(levelSeed).carve(chunk, structures,
                    (x, z) -> terrainData.getHeight(Math.max(0, Math.min(15, x - chunk.getPos().getMinBlockX())),
                            Math.max(0, Math.min(15, z - chunk.getPos().getMinBlockZ()))),
                    (dx, dz) -> terrainData.getHeight(dx, dz),
                    (dx, dz) -> terrainData.getRiver().get(dx, dz), false);
        } else {
            // The cavern around an ancient city, and its deep dark, used to exist only inside DeepCaves.
            // With Deep Caves switched off in the settings, a city would otherwise be entombed in solid
            // rock and outside its own biome.
            com.terraforged.mod.worldgen.cave.StructureSpace.carveCity(chunk, structures, deepDark());
        }

        // Last, because this is the first point where the column's air/solid profile is final: the five
        // noise-cave layers and both Deep Caves passes have all run. Clears flat sheets of rock left
        // hanging between two layers that stopped a block or two apart -- the floating blocks reported
        // inside mega caverns. See FloatingSheets.
        com.terraforged.mod.worldgen.cave.FloatingSheets.clear(chunk, structures);
    }


    private volatile net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> deepDark;

    /** Cached: the ancient-city cavern needs it per chunk, and a registry lookup each time is wasteful. */
    private net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> deepDark() {
        var holder = deepDark;
        if (holder == null) {
            synchronized (this) {
                holder = deepDark;
                if (holder == null) {
                    holder = getRegistries().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME)
                            .getOrThrow(net.minecraft.world.level.biome.Biomes.DEEP_DARK);
                    deepDark = holder;
                }
            }
        }
        return holder;
    }

    private volatile com.terraforged.mod.worldgen.cave.DeepCaves deepCaves;

    private com.terraforged.mod.worldgen.cave.DeepCaves deepCaves(long seed) {
        var caves = deepCaves;
        if (caves == null || caves.seed() != seed) {
            synchronized (this) {
                caves = deepCaves;
                if (caves == null || caves.seed() != seed) {
                    caves = com.terraforged.mod.worldgen.cave.DeepCaves.create(getRegistries(),
                            vanillaGen().getSettings().value(), seed);
                    deepCaves = caves;
                }
            }
        }
        return caves;
    }

    @Override
    public void applyBiomeDecoration(WorldGenLevel region, ChunkAccess chunk, StructureManager structures) {
        int seed = Seeds.get(region.getSeed());
        biomeGenerator().decorate(chunk, region, structures, this);
        terrainCache().drop(seed, chunk.getPos());
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {
        // See NoiseBasedChunkGenerator
        var settings = vanillaGen().getSettings().value();
        if (settings.disableMobGeneration()) return;

        var chunkPos = region.getCenter();
        var position = chunkPos.getWorldPosition().atY(region.getMaxY());

        var random = new WorldgenRandom(new LegacyRandomSource(region.getSeed()));
        random.setDecorationSeed(region.getSeed(), chunkPos.getMinBlockX(), chunkPos.getMinBlockZ());

        // 26.3 reads the spawn list from the environment attributes at the position, not from a biome holder.
        NaturalSpawner.spawnMobsForChunkGeneration(region, position, chunkPos, random);
    }

    @Override
    public int getBaseHeight(int x, int z, net.minecraft.world.level.levelgen.Heightmap.Types types, LevelHeightAccessor levelHeightAccessor, RandomState state) {
        var sample = terrainCache().getSample(seed, x, z);

        float scaledBase = levels.getScaledBaseLevel(sample.baseNoise);
        // The eroded height, not the raw noise: a structure placed at the pre-erosion height stands in the air.
        float scaledHeight = levels.getScaledHeight(terrainCache().getErodedHeightNoise(seed, x, z));

        int base = levels.getHeight(scaledBase);
        int height = levels.getHeight(scaledHeight);

        return switch (types) {
            case WORLD_SURFACE, WORLD_SURFACE_WG, MOTION_BLOCKING, MOTION_BLOCKING_NO_LEAVES -> Math.max(base, height) + 1;
            case OCEAN_FLOOR, OCEAN_FLOOR_WG -> height + 1;
        };
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor levelHeightAccessor, RandomState state) {
        var sample = terrainCache().getSample(seed, x, z);

        float scaledBase = levels.getScaledBaseLevel(sample.baseNoise);
        float scaledHeight = levels.getScaledHeight(sample.heightNoise);

        int base = levels.getHeight(scaledBase);
        int height = levels.getHeight(scaledHeight);
        int surface = Math.max(base, height);

        var states = new BlockState[surface];
        Arrays.fill(states, 0, height, Blocks.STONE.defaultBlockState());
        if (surface > height) {
            Arrays.fill(states, height, surface, Blocks.WATER.defaultBlockState());
        }

        return new NoiseColumn(height, states);
    }

    @Override
    public void addDebugScreenInfo(List<String> lines, RandomState state, BlockPos pos, SamplerContext samplerContext) {
        int seed = this.seed;

        var sample = biomeSource.getBiomeSampler().getSample();
        terrainCache().sample(seed, pos.getX(), pos.getZ(), sample);
        biomeSource.getBiomeSampler().sample(seed, pos.getX(), pos.getZ(), sample);

        lines.add("");
        lines.add("[TerraForged]");
        lines.add("Terrain Type: " + sample.terrainType.getName());
        lines.add("Climate Type: " + sample.climateType.name());
        lines.add("Base Noise: " + sample.baseNoise);
        lines.add("Height Noise: " + sample.heightNoise);
        lines.add("Ocean Proximity: " + (1 - sample.continentNoise));
        lines.add("River Proximity: " + (1 - sample.riverNoise));
    }

    public static boolean isTerraForged(ChunkGenerator generator) {
        return generator instanceof Generator || true; // TODO: remove || true
    }
}
