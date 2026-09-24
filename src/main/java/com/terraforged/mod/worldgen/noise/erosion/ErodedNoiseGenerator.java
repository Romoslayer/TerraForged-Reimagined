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

package com.terraforged.mod.worldgen.noise.erosion;

import com.terraforged.engine.settings.FilterSettings;
import com.terraforged.engine.util.FastRandom;
import com.terraforged.engine.util.pos.PosUtil;
import com.terraforged.engine.world.terrain.Terrain;
import com.terraforged.mod.worldgen.noise.IContinentNoise;
import com.terraforged.mod.worldgen.noise.INoiseGenerator;
import com.terraforged.mod.worldgen.noise.NoiseData;
import com.terraforged.mod.worldgen.noise.NoiseGenerator;
import com.terraforged.mod.worldgen.noise.NoiseLevels;
import com.terraforged.mod.worldgen.noise.NoiseSample;
import com.terraforged.mod.worldgen.settings.TerraSettings;
import com.terraforged.mod.worldgen.terrain.TerrainLevels;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Erosion, simulated once per <i>region</i> of chunks and cached.
 *
 * <p>It used to run once per chunk, over a tile of the 5x5 chunks around it, keeping only the middle 16x16 and
 * throwing the rest away. That had two costs. Every droplet was simulated once for each tile that contained it,
 * 25 times over; and because each tile eroded its own edges with a truncated set of droplets, the heightmap near
 * a tile's edge was wrong, differently wrong in each tile. Droplets starting there flowed inward across that
 * wrong surface and deposited differently inside the chunk being kept, so two neighbouring chunks disagreed
 * about terrain they share and left a visible seam on every chunk border -- the reported "hard lines" in snowy
 * mountains, where erosion does the most work. Measured: the worst chunk-border crease in a test region was
 * 1.72 against an interior median of 0.50, and it vanished with erosion turned off.
 *
 * <p>Now a block of {@link #REGION_CHUNKS}<sup>2</sup> chunks is eroded in one pass, over a map that extends
 * {@link #MARGIN_CHUNKS} chunks past it on every side, and every chunk in the block reads its slice out of the
 * result. Inside a block there are no seams at all, because there is only one simulation. The edge effect
 * survives only at block boundaries, which are {@value #REGION_CHUNKS} chunks apart instead of one, and the
 * margin is wider than a droplet's reach ({@code dropletLifetime + erosionRadius} = 32 blocks against
 * {@value #MARGIN_BLOCKS}).
 *
 * <p>It is also cheaper: each droplet is simulated once per region rather than once per tile, which is
 * {@code (REGION + 2 * MARGIN)² / REGION²} = 4 chunk-droplet-simulations per chunk generated, against 25 before.
 *
 * <p>The cost is a latency spike: the first chunk to ask for a region pays for the whole thing and the other 63
 * get it free. The build runs on the calling thread on purpose -- handing it to a pool would let every worker
 * block waiting for a region that no free thread is left to build.
 */
public class ErodedNoiseGenerator implements INoiseGenerator {
    /** Chunks per side of one eroded, cached region. */
    private static final int REGION_CHUNKS = 8;
    /**
     * Chunks of margin simulated around a region and then discarded. Must exceed a droplet's reach --
     * {@code dropletLifetime} (25) steps plus the erosion brush radius (7) -- or the block's own edge columns
     * are eroded with a truncated droplet set and the seam comes back at the block boundary.
     */
    private static final int MARGIN_CHUNKS = 4;
    private static final int TOTAL_CHUNKS = REGION_CHUNKS + 2 * MARGIN_CHUNKS;
    private static final int MAP_SIZE = TOTAL_CHUNKS * NoiseTileSize.CHUNK_SIZE;
    private static final int MARGIN_BLOCKS = MARGIN_CHUNKS * NoiseTileSize.CHUNK_SIZE;
    /** Regions kept in memory. Each is {@code MAP_SIZE²} floats -- 256 KB at the sizes above. */
    private static final int REGION_CACHE = 16;

    protected final ErosionFilter erosion;
    protected final NoiseGenerator generator;
    private final TerraSettings.Smoothing smoothing;

    /**
     * Scratch space for one chunk, taken per call rather than per thread: a thread that blocks -- here, waiting
     * on another thread's region build -- can run other queued tasks meanwhile, including another chunk's
     * {@link #generate}. With a thread-local this nested call overwrote the outer chunk's data mid-flight.
     */
    protected final Queue<NoiseResource> resources = new ConcurrentLinkedQueue<>();

    /** Access-ordered, so the least recently used region is the one dropped. Guarded by its own monitor. */
    private final Map<Long, CompletableFuture<float[]>> regions =
            new LinkedHashMap<>(REGION_CACHE * 2, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, CompletableFuture<float[]>> eldest) {
                    return size() > REGION_CACHE;
                }
            };

    private int regionSeed;
    private boolean hasRegionSeed;

    public ErodedNoiseGenerator(NoiseGenerator generator, TerraSettings.Filters filters) {
        this.smoothing = filters.smoothing;
        var erosion = filters.erosion;

        // FilterSettings.Erosion is what ErosionFilter reads. Its own defaults plus the hard-coded 350
        // droplets used to be every world's erosion; TerraSettings.Erosion defaults to the same six
        // values.
        var settings = new FilterSettings.Erosion();
        settings.dropletsPerChunk = erosion.dropletsPerChunk;
        settings.dropletLifetime = erosion.dropletLifetime;
        settings.dropletVolume = erosion.dropletVolume;
        settings.dropletVelocity = erosion.dropletVelocity;
        settings.erosionRate = erosion.erosionRate;
        settings.depositeRate = erosion.depositeRate;

        this.generator = generator;
        this.erosion = new ErosionFilter(MAP_SIZE, settings);
    }

    @Override
    public NoiseLevels getLevels() {
        return generator.getLevels();
    }

    @Override
    public TerrainLevels getTerrainLevels() {
        return generator.getTerrainLevels();
    }

    @Override
    public TerraSettings getSettings() {
        return generator.getSettings();
    }

    @Override
    public IContinentNoise getContinent() {
        return generator.getContinent();
    }

    /**
     * These three stay on the <b>un-eroded</b> generator, deliberately.
     *
     * <p>{@link com.terraforged.mod.worldgen.biome.BiomeSampler#isSteep} calls {@link #getHeightNoise} for
     * neighbouring columns on every biome sample, thousands of times per chunk. Routing that through the
     * region cache made each of those calls able to trigger a full region's erosion with every other worker
     * blocked behind it: a single tick ran past the 60 s watchdog and the server was killed. Slope estimation
     * does not need the exact eroded height, and never had it.
     *
     * <p>What does need it is {@link #erodedHeightNoise}, below.
     */
    @Override
    public NoiseSample getNoiseSample(int seed, int x, int z) {
        return generator.getNoiseSample(seed, x, z);
    }

    @Override
    public void sample(int seed, int x, int z, NoiseSample sample) {
        generator.sample(seed, x, z, sample);
    }

    @Override
    public float getHeightNoise(int seed, int x, int z) {
        return generator.getHeightNoise(seed, x, z);
    }

    /**
     * The height of one column as the world will actually be built, erosion and rivers included.
     *
     * <p>Structures choose their height through {@code Generator#getBaseHeight}, which used to read the
     * un-eroded height -- so on eroded ground a structure was placed at the height the terrain had before
     * erosion cut the slope away, and ended up standing in the air. Ruined portals on a terraced plateau were
     * the visible case.
     *
     * <p>This is the expensive accessor: it may have to build the region covering the block. During chunk
     * generation that region is already cached, because the chunk itself is built from it. It is called per
     * structure placement attempt, not per biome sample -- keep it that way.
     */
    public float erodedHeightNoise(int seed, int x, int z) {
        var sample = new NoiseSample();
        float nx = getNoiseCoord(x);
        float nz = getNoiseCoord(z);

        generator.sampleTerrain(seed, nx, nz, sample, generator.getBlenderResource());
        sample.heightNoise = erodedHeight(seed, x, z);
        generator.sampleRiver(seed, nx, nz, sample);

        return sample.heightNoise;
    }

    /** The eroded height at one block, read out of the cached region covering it. */
    private float erodedHeight(int seed, int x, int z) {
        float[] region = getRegion(seed, x >> 4, z >> 4);

        int offsetX = MARGIN_BLOCKS + (Math.floorMod(x >> 4, REGION_CHUNKS) << 4) + (x & 15);
        int offsetZ = MARGIN_BLOCKS + (Math.floorMod(z >> 4, REGION_CHUNKS) << 4) + (z & 15);

        return region[offsetZ * MAP_SIZE + offsetX];
    }

    @Override
    public long find(int seed, int x, int z, int minRadius, int maxRadius, Terrain terrain) {
        return generator.find(seed, x, z, minRadius, maxRadius, terrain);
    }

    @Override
    public void generate(int seed, int chunkX, int chunkZ, Consumer<NoiseData> consumer) {
        // Outside the resource borrow: this may block while another thread builds the region.
        float[] region = getRegion(seed, chunkX, chunkZ);

        int offsetX = MARGIN_BLOCKS + (Math.floorMod(chunkX, REGION_CHUNKS) << 4);
        int offsetZ = MARGIN_BLOCKS + (Math.floorMod(chunkZ, REGION_CHUNKS) << 4);

        var resource = resources.poll();
        if (resource == null) resource = new NoiseResource();
        try {
            var blender = generator.getBlenderResource();

            int startX = chunkX << 4;
            int startZ = chunkZ << 4;
            int min = resource.chunk.min();
            int max = resource.chunk.max();

            for (int dz = min; dz < max; dz++) {
                float nz = getNoiseCoord(startZ + dz);
                int row = (offsetZ + dz) * MAP_SIZE + offsetX;

                for (int dx = min; dx < max; dx++) {
                    float nx = getNoiseCoord(startX + dx);

                    int index = resource.chunk.index().of(dx, dz);
                    var sample = resource.chunkSample.get(index);

                    // Base, continent and terrain type come from the raw noise; only the height is eroded.
                    generator.sampleTerrain(seed, nx, nz, sample, blender);
                    sample.heightNoise = region[row + dx];
                    generator.sampleRiver(seed, nx, nz, sample);

                    resource.chunk.setNoise(index, sample);
                }
            }

            consumer.accept(resource.chunk);
        } catch (Throwable t) {
            // Loudly: swallowing this leaves the chunk with no noise data at all, because
            // consumer.accept above never ran, and a silently empty chunk is far harder to
            // trace back here than a line in the log saying which chunk it was.
            com.terraforged.mod.TerraForged.LOG.error(
                    "Eroded noise failed for chunk {}, {} -- that chunk will be left unfilled",
                    chunkX, chunkZ, t);
        } finally {
            resources.offer(resource);
        }
    }

    /**
     * The eroded heightmap covering this chunk's region, building it if nobody has. The first caller for a
     * region builds it here, on its own thread; everyone else waits on the same future.
     */
    private float[] getRegion(int seed, int chunkX, int chunkZ) {
        int regionX = Math.floorDiv(chunkX, REGION_CHUNKS);
        int regionZ = Math.floorDiv(chunkZ, REGION_CHUNKS);
        long key = PosUtil.pack(regionX, regionZ);

        CompletableFuture<float[]> existing;
        CompletableFuture<float[]> mine = null;

        synchronized (regions) {
            // Worlds do not change seed while running; this is a safety net, not a hot path.
            if (!hasRegionSeed || regionSeed != seed) {
                regions.clear();
                regionSeed = seed;
                hasRegionSeed = true;
            }

            existing = regions.get(key);
            if (existing == null) {
                mine = new CompletableFuture<>();
                regions.put(key, mine);
            }
        }

        if (existing != null) return existing.join();

        try {
            float[] map = buildRegion(seed, regionX, regionZ);
            mine.complete(map);
            return map;
        } catch (RuntimeException | Error t) {
            synchronized (regions) {
                regions.remove(key, mine);
            }
            mine.completeExceptionally(t);
            throw t;
        }
    }

    private float[] buildRegion(int seed, int regionX, int regionZ) {
        int originChunkX = regionX * REGION_CHUNKS - MARGIN_CHUNKS;
        int originChunkZ = regionZ * REGION_CHUNKS - MARGIN_CHUNKS;
        int startX = originChunkX << 4;
        int startZ = originChunkZ << 4;

        var map = new float[MAP_SIZE * MAP_SIZE];
        var sample = new NoiseSample();
        var blender = generator.getBlenderResource();

        for (int z = 0; z < MAP_SIZE; z++) {
            float nz = getNoiseCoord(startZ + z);
            int row = z * MAP_SIZE;

            for (int x = 0; x < MAP_SIZE; x++) {
                float nx = getNoiseCoord(startX + x);
                map[row + x] = generator.sampleTerrain(seed, nx, nz, sample, blender).heightNoise;
            }
        }

        erosion.apply(seed, originChunkX, originChunkZ, TOTAL_CHUNKS, MAP_SIZE,
                new ErosionFilter.Resource(), new FastRandom(), map);
        HeightmapSmoothing.apply(map, MAP_SIZE, smoothing);

        return map;
    }
}
