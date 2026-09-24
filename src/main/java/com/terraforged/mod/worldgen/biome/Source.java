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

import com.google.common.base.Suppliers;
import com.mojang.serialization.MapCodec;
import com.terraforged.engine.util.pos.PosUtil;
import com.terraforged.mod.TerraForged;
import com.terraforged.mod.util.storage.LongCache;
import com.terraforged.mod.util.storage.LossyCache;
import com.terraforged.mod.worldgen.biome.util.BiomeMapManager;
import com.terraforged.mod.worldgen.cave.CaveType;
import com.terraforged.mod.worldgen.noise.INoiseGenerator;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.stream.Stream;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

public class Source extends BiomeSource implements BiomeResolver {
    public static final MapCodec<Source> CODEC = new SourceCodec();

    protected int seed;
    protected final HolderLookup.Provider registries;
    protected final LongCache<Holder<Biome>> cache = LossyCache.concurrent(2048, i -> (Holder<Biome>[]) new Holder[i]);

    private final Supplier<INoiseGenerator> noiseSupplier;
    private final Supplier<Parts> parts = Suppliers.memoize(this::createParts);

    /**
     * Everything that has to read whole registries, held behind a memoized supplier.
     *
     * <p>This used to be built in the constructor. It cannot be: the biome source is constructed
     * while the chunk generator's codec is being decoded, and that happens *during* datapack registry
     * loading. Minecraft supports resolving individual elements across registries mid-load — that is
     * what {@code RegistryOps.RegistryInfo#getter} is for — but not enumerating a registry, because
     * there is no point during a concurrent load at which a registry is known to be complete.
     * TerraForged enumerates (every terrain, every climate type), so it has to wait.
     *
     * <p>Deferring to first use moves that to generation time, by which point the registries are
     * populated and frozen. The lookup captured at decode time stays valid: it wraps the same
     * registry objects that the world ends up using.
     */
    private record Parts(BiomeMapManager biomeMapManager,
                         Set<Holder<Biome>> possibleBiomes,
                         BiomeSampler biomeSampler,
                         CaveBiomeSampler caveBiomeSampler) {}

    public Source(Supplier<INoiseGenerator> noise, HolderLookup.Provider access) {
        super();
        this.registries = access;
        this.noiseSupplier = noise;
    }

    private Parts createParts() {
        var manager = new BiomeMapManager(registries);
        return new Parts(
                manager,
                new ObjectLinkedOpenHashSet<>(manager.getOverworldBiomes()),
                new BiomeSampler(noiseSupplier.get(), manager),
                new CaveBiomeSampler(800, manager));
    }

    public void withSeed(long seed) {
        this.seed = (int) seed;
    }

    /**
     * Note: We provide the super-class an empty list to avoid the biome feature
     * order dependency exceptions (wtf mojang). We do not use the featuresByStep
     * list so order does not matter to us (thank god! Biome mods are going to
     * get this very wrong).
     * <p>
     * We instead maintain our own set with the actual biomes and override here :)
     */
    @Override
    public Set<Holder<Biome>> possibleBiomes() {
        return parts.get().possibleBiomes();
    }

    /**
     * Only here because {@code BiomeSource} declares it abstract — the base class calls it to build
     * the set that {@link #possibleBiomes()} returns, and that method is overridden above, so this
     * is never actually consulted. It reports the same set regardless, so the two cannot disagree.
     *
     * <p>Upstream expressed the same thing by passing an empty list to the old {@code BiomeSource}
     * constructor; that constructor takes no arguments now.
     */
    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return parts.get().possibleBiomes().stream();
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    /**
     * Height-aware, and it agrees with what {@code ChunkUtil#fillNoiseBiomes} stores -- but only
     * until the caves are carved.
     *
     * <p>Vanilla asks the biome source directly -- not the chunk -- in several places: structure
     * placement checks the biome at a structure's start position, and {@code /locate biome} searches in
     * 3D. This used to ignore {@code y} and always return the surface biome, so while the deep dark was
     * written into chunks, an ancient city (start height y=-27, biome {@code deep_dark} only) was always
     * checked against the grassland or forest above it and never placed.
     *
     * <p><b>This is a two-band 2D approximation and the world is not.</b> After
     * {@code fillNoiseBiomes} runs, {@code NoiseCaveCarver#carve} overwrites individual quart cells with
     * a cave biome at the carved cave's real height -- bounded only by {@code (surface - 16) >> 2}, so
     * up to ~y 348 on TerraForged terrain -- and {@code StructureSpace} stamps {@code deep_dark} around
     * an ancient city at its own height. Above {@code CAVE_BIOME_MAX_Y} this method therefore
     * <b>disagrees with the chunk</b>, and which one a caller sees depends on which it asks:
     *
     * <ul>
     *   <li>features ({@code BiomeFilter}) and mob spawning read the chunk, so they see the cave biome;
     *   <li>structure placement checks and {@code /locate biome} ask this method, so they do not.
     * </ul>
     *
     * <p>That gap is why {@code locate biome terraforged:cave} is useless above y=0 (use the coordinates
     * {@link #reportCaveLayer} logs instead), and it is what hid the missing-ore bug for the whole port:
     * the empty ore list on {@code terraforged:cave} was suppressing ore world-wide through
     * {@code BiomeFilter}, while reading this method said cave biomes only existed below y=0.
     *
     * <p>Closing it properly means answering "is there a cave at (x,y,z)" here, which needs every cave
     * config plus the column's terrain height on a path that structure placement calls constantly. It was
     * judged not worth that cost; nothing is known to be broken by the disagreement, because the only
     * vanilla structure gated on a cave biome is the ancient city and the deep band covers it.
     */
    /**
     * 26.3 asks a biome source for a resolver rather than a biome. TerraForged places biomes from its own
     * noise and never reads the climate sampler, so the source is its own resolver, as vanilla's
     * {@code FixedBiomeSource} is.
     */
    @Override
    public BiomeResolver createResolver(Climate.Sampler sampler) {
        return this;
    }

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z) {
        if (com.terraforged.mod.worldgen.util.ChunkUtil.isCaveBiomeQuart(y)) {
            var cave = getLayerBiome(x, z);
            if (cave != null) return cave;
        }
        return getSurfaceBiome(x, z);
    }

    /** The surface biome of a column, whatever the height. Quart coordinates. */
    public Holder<Biome> getSurfaceBiome(int x, int z) {
        return cache.computeIfAbsent(seed, PosUtil.pack(x, z), this::compute);
    }

    public HolderLookup.Provider getRegistries() {
        return registries;
    }

    public BiomeSampler getBiomeSampler() {
        return parts.get().biomeSampler();
    }

    public CaveBiomeSampler getCaveBiomeSampler() {
        return parts.get().caveBiomeSampler();
    }

    public Holder<Biome> getUnderGroundBiome(int seed, int x, int z, CaveType type) {
        return parts.get().caveBiomeSampler().getUnderGroundBiome(this.seed + seed, x, z, type);
    }

    /**
     * The cave biome for a deep column, or null to keep the surface biome.
     *
     * @param x quart (biome-resolution) x
     * @param z quart (biome-resolution) z
     * @see CaveBiomeSampler#getLayerBiome
     */
    public Holder<Biome> getLayerBiome(int x, int z) {
        return parts.get().caveBiomeSampler().getLayerBiome(seed, x << 2, z << 2);
    }

    public HolderLookup.RegistryLookup<Biome> getRegistry() {
        return parts.get().biomeMapManager().getBiomes();
    }

    protected Holder<Biome> compute(int seed, long index) {
        reportCaveLayer(seed);

        int x = PosUtil.unpackLeft(index) << 2;
        int z = PosUtil.unpackRight(index) << 2;
        return parts.get().biomeSampler().sampleBiome(seed, x, z);
    }

    private volatile boolean reported;

    /**
     * Logs how much of the underground the cave-biome layer covers, and with what, once.
     *
     * <p>Two things about this are easy to get wrong and invisible without a number: the mask
     * threshold, where too high means cave biomes still cannot be found and too low means the whole
     * underground changes its mob spawns; and whether {@code deep_dark} is present at all, which is
     * the difference between ancient cities generating and not.
     */
    private void reportCaveLayer(int seed) {
        if (reported || !TerraForged.LOG.isDebugEnabled()) return;
        reported = true;

        var counts = new java.util.TreeMap<String, Integer>();
        // The coordinate nearest the origin for each biome, so a region can be tested without
        // hunting for one. `locate biome` is the obvious alternative and a bad one: it is a 3D
        // search through this biome source, slow enough to be awkward to drive from a script.
        var nearest = new java.util.TreeMap<String, String>();
        var nearestDist = new java.util.HashMap<String, Long>();
        int samples = 0, covered = 0;

        for (int z = -2048; z < 2048; z += 64) {
            for (int x = -2048; x < 2048; x += 64) {
                samples++;

                var biome = parts.get().caveBiomeSampler().getLayerBiome(seed, x, z);
                if (biome == null) continue;

                covered++;
                String id = biome.unwrapKey().map(key -> key.identifier().toString()).orElse("?");
                counts.merge(id, 1, Integer::sum);

                long d2 = (long) x * x + (long) z * z;
                if (d2 < nearestDist.getOrDefault(id, Long.MAX_VALUE)) {
                    nearestDist.put(id, d2);
                    nearest.put(id, x + "," + z);
                }
            }
        }

        TerraForged.LOG.debug("Cave biome layer: {}% of the deep underground, made up of {}",
                Math.round(100F * covered / samples), counts);
        TerraForged.LOG.debug("Cave biome layer, nearest sample of each to the origin: {}", nearest);
    }
}
