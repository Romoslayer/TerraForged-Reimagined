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

package com.terraforged.mod.worldgen.biome.util;

import net.minecraft.core.registries.Registries;
import com.terraforged.engine.world.biome.type.BiomeType;
import com.terraforged.mod.TerraForged;
import com.terraforged.mod.util.storage.WeightMap;
import com.terraforged.mod.worldgen.asset.ClimateType;
import it.unimi.dsi.fastutil.objects.Object2FloatLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2FloatMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;

import java.util.*;
import java.util.stream.Collectors;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

public class BiomeMapManager {
    private static final BiomeType[] TYPES = BiomeType.values();
    private static final BiomeTypeHolder[] HOLDERS = Stream.of(TYPES).map(BiomeTypeHolder::new).toArray(BiomeTypeHolder[]::new);

    private final HolderLookup.RegistryLookup<Biome> biomes;
    private final HolderLookup.RegistryLookup<ClimateType> climateTypes;
    private final List<Holder<Biome>> overworldBiomes;
    private final Map<BiomeType, WeightMap<Holder<Biome>>> biomeMap;
    private final Map<BiomeType, WeightMap<Holder<Biome>>> dryBiomeMap;
    private final Map<BiomeType, WeightMap<Holder<Biome>>> highlandBiomeMap;

    public BiomeMapManager(HolderLookup.Provider access) {
        biomes = access.lookupOrThrow(Registries.BIOME);
        climateTypes = access.lookupOrThrow(TerraForged.CLIMATES.get());
        overworldBiomes = getOverworldBiomes(biomes, climateTypes);
        biomeMap = buildBiomeMap();
        dryBiomeMap = buildDryBiomeMap();
        highlandBiomeMap = buildHighlandBiomeMap();
    }

    public Holder<Biome> get(ResourceKey<Biome> key) {
        return biomes.getOrThrow(key);
    }

    public HolderLookup.RegistryLookup<Biome> getBiomes() {
        return biomes;
    }

    public List<Holder<Biome>> getOverworldBiomes() {
        return overworldBiomes;
    }

    public Map<BiomeType, WeightMap<Holder<Biome>>> getBiomeMap() {
        return biomeMap;
    }

    /**
     * The same pools with the wetland biomes taken out, for ground that is too high or too dry to
     * hold one.
     *
     * <p>Built once here rather than filtered per sample: biome selection runs for every 4x4 column
     * in the world.
     *
     * @see com.terraforged.mod.data.ModTags#WETLAND
     */
    public Map<BiomeType, WeightMap<Holder<Biome>>> getDryBiomeMap() {
        return dryBiomeMap;
    }

    private Map<BiomeType, WeightMap<Holder<Biome>>> buildDryBiomeMap() {
        // A climate made up entirely of wetlands keeps its original pool. Substituting nothing would
        // mean falling through to plains, and a wet climate with a swamp in the wrong place still
        // reads better than a wet climate rendered as plains.
        return filtered(biome -> !BiomeUtil.isWetland(biome), true);
    }

    /**
     * The biomes of each climate that suit steep ground.
     *
     * <p>Unlike the wetland pool this is a positive filter, and most climates have nothing matching
     * it — vanilla's windswept and peak biomes cluster in a few climates. Those climates borrow from
     * the nearest one that has some, which is what makes the gate work where it matters most:
     * {@code GRASSLAND} holds only plains and sunflower plains, and plains is half of what
     * {@code village_plains} is allowed to spawn in, so grassland hillsides borrowing windswept
     * hills is precisely the case that stops villages being asked to build on a cliff.
     *
     * @see com.terraforged.mod.data.ModTags#HIGHLAND
     */
    public Map<BiomeType, WeightMap<Holder<Biome>>> getHighlandBiomeMap() {
        return highlandBiomeMap;
    }

    private Map<BiomeType, WeightMap<Holder<Biome>>> buildHighlandBiomeMap() {
        var result = filtered(BiomeUtil::isHighland, false);
        fillEmptyClimates(result);
        return result;
    }

    /**
     * Rebuilds every climate's pool keeping only the biomes that match.
     *
     * @param keepOriginalIfEmpty whether a climate left with nothing falls back to its full pool
     *                            (wetlands) or is left empty for {@link #fillEmptyClimates} to
     *                            borrow for (highlands).
     */
    private Map<BiomeType, WeightMap<Holder<Biome>>> filtered(Predicate<Holder<Biome>> keep,
                                                              boolean keepOriginalIfEmpty) {
        var result = new EnumMap<BiomeType, WeightMap<Holder<Biome>>>(BiomeType.class);

        for (var entry : biomeMap.entrySet()) {
            var kept = Arrays.stream(entry.getValue().getValues())
                    .filter(keep)
                    .toArray(Holder[]::new);

            if (kept.length == 0) {
                if (keepOriginalIfEmpty) result.put(entry.getKey(), entry.getValue());
                continue;
            }

            result.put(entry.getKey(), new WeightMap<>((Holder<Biome>[]) kept, uniformWeights(kept.length)));
        }

        return result;
    }

    private static float[] uniformWeights(int length) {
        var weights = new float[length];
        Arrays.fill(weights, 1F);
        return weights;
    }

    private Map<BiomeType, WeightMap<Holder<Biome>>> buildBiomeMap() {
        var map = getWeightsMap();

        var result = new EnumMap<BiomeType, WeightMap<Holder<Biome>>>(BiomeType.class);
        for (var entry : map.entrySet()) {
            var values = (Holder<Biome>[]) entry.getValue().keySet().toArray(Holder[]::new);
            var weights = entry.getValue().values().toFloatArray();
            result.put(entry.getKey(), new WeightMap<>(values, weights));
        }

        // Reported before filling, so the counts describe the biomes themselves rather than the
        // borrowed pools -- otherwise a borrowed pool is counted twice and the total is nonsense.
        report(result);
        fillEmptyClimates(result);

        return result;
    }

    /**
     * Gives every climate with no biomes of its own the pool of the nearest climate that has some.
     *
     * <p>Vanilla has nothing that classifies as a steppe, so {@code STEPPE} and {@code COLD_STEPPE}
     * come out empty — and between them they are about 5% of the climate space. Empty meant
     * {@code BiomeSampler} fell back to plains, so those regions became plains on top of the 10%
     * that {@code GRASSLAND} already covers with two biomes. Roughly a sixth of the world was plains
     * or sunflower plains, which is what "lots of plains" looks like from the ground.
     *
     * <p>Nearest-climate is used rather than a hand-written list of substitute biomes because it
     * keeps working when the biome set changes: install a mod with steppe-like biomes and they fill
     * their own climate, and nothing here has to know about it. Dry cold land becoming taiga, and
     * dry warm land becoming savanna, is also simply a better guess than plains.
     *
     * <p>Distance is measured between the midpoints of each climate's temperature and moisture
     * range, which {@link BiomeType} derives from the lookup table.
     */
    private static void fillEmptyClimates(Map<BiomeType, WeightMap<Holder<Biome>>> map) {
        // Snapshot of who has biomes of their own, taken before anything is filled in. Borrowing
        // from the live map lets a climate borrow a pool that was itself borrowed a moment earlier,
        // which makes the result depend on the order the enum happens to be declared in -- and it
        // did: TEMPERATE_RAINFOREST ended up holding SAVANNA's highland biomes, two hops away.
        var donors = new EnumMap<BiomeType, WeightMap<Holder<Biome>>>(BiomeType.class);
        for (var entry : map.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                donors.put(entry.getKey(), entry.getValue());
            }
        }

        for (var type : BiomeType.values()) {
            var current = map.get(type);
            if (current != null && !current.isEmpty()) continue;

            BiomeType nearest = null;
            float nearestDistance = Float.MAX_VALUE;

            for (var candidate : donors.keySet()) {
                if (candidate == type) continue;

                float distance = distance(type, candidate);
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearest = candidate;
                }
            }

            if (nearest == null) continue; // Every climate is empty; BiomeSampler falls back to plains.

            TerraForged.LOG.debug("Biome pool: {} has no biomes, borrowing {}", type, nearest);
            map.put(type, donors.get(nearest));
        }
    }

    /**
     * Distance between two climates, used to pick which one an empty climate borrows from.
     *
     * <p>Temperature counts for twice what moisture does. The two are not equally forgiving: borrow
     * across moisture and a dry hillside gets a damp one's biomes, which nobody notices; borrow
     * across temperature and warm grassland hills come out as snowy slopes, which is glaring. That
     * is not hypothetical — with both axes weighted equally, {@code GRASSLAND} borrowed its highland
     * biomes from {@code TAIGA} and put snow on temperate hills, beating {@code TEMPERATE_FOREST}
     * and its windswept hills purely on the moisture term.
     */
    private static float distance(BiomeType a, BiomeType b) {
        float dt = midpoint(a.getMinTemperature(), a.getMaxTemperature())
                - midpoint(b.getMinTemperature(), b.getMaxTemperature());
        float dm = midpoint(a.getMinMoisture(), a.getMaxMoisture())
                - midpoint(b.getMinMoisture(), b.getMaxMoisture());

        dt *= TEMPERATURE_BIAS;

        return dt * dt + dm * dm;
    }

    private static final float TEMPERATURE_BIAS = 2F;

    private static float midpoint(float min, float max) {
        return (min + max) * 0.5F;
    }

    /**
     * Logs how the biome registry was divided up.
     *
     * <p>Worth having permanently rather than as throwaway debugging: when a biome mod is installed,
     * "are its biomes actually being placed, and sensibly?" is the first question, and the
     * alternative is flying around a world reading F3. An empty climate falls back to plains, which
     * looks like a generation bug rather than a classification one, so the counts are the fastest
     * way to tell those apart.
     */
    private void report(Map<BiomeType, WeightMap<Holder<Biome>>> map) {
        int placed = map.values().stream().mapToInt(WeightMap::size).sum();

        TerraForged.LOG.info("Biome pool: {} of {} overworld biomes placed by climate ({} excluded as"
                        + " cave or water biomes)",
                placed, overworldBiomes.size(), overworldBiomes.size() - placed);

        for (var type : BiomeType.values()) {
            var entry = map.get(type);
            int count = entry == null ? 0 : entry.size();

            if (count == 0) {
                // Not necessarily wrong -- ALPINE is unreachable by design, and the steppes have no
                // vanilla biomes -- but it is always worth knowing which climates are empty.
                TerraForged.LOG.debug("Biome pool: {} is empty", type);
            } else {
                // The names, not just the count: "is this mod's biomes in there, and in a sensible
                // climate" is the question this is here to answer, and a count cannot answer it.
                TerraForged.LOG.debug("Biome pool: {} has {} biome(s): {}", type, count,
                        Arrays.stream(entry.getValues())
                                .map(holder -> holder.unwrapKey()
                                        .map(key -> key.identifier().toString())
                                        .orElse("?"))
                                .sorted()
                                .collect(Collectors.joining(", ")));
            }
        }
    }

    private Map<BiomeType, Object2FloatMap<Holder<Biome>>> getWeightsMap() {
        var map = new HashMap<BiomeType, Object2FloatMap<Holder<Biome>>>();
        var registered = new ObjectOpenHashSet<Holder<Biome>>();

        for (var typeHolder : HOLDERS) {
            // A RegistryLookup resolves by ResourceKey rather than by bare Identifier.
            var biomeType = climateTypes.get(ResourceKey.create(TerraForged.CLIMATES.get(), typeHolder.name))
                    .map(Holder::value).orElse(null);
            if (biomeType == null) {
                map.put(typeHolder.type(), newMutableWeightMap());
            } else {
                var typeMap = getBiomeWeights(biomeType, biomes, registered::add);
                map.put(typeHolder.type(), typeMap);
            }
        }

        for (var biome : overworldBiomes) {
            if (registered.contains(biome)) continue;

            // overworldBiomes is every overworld biome, because that list also feeds
            // Source#possibleBiomes. Only the ones actually chosen by climate belong in this map.
            if (!BiomeUtil.isClimateBiome(biome)) continue;

            var type = BiomeUtil.getType(biome);
            if (type == null) continue;

            map.computeIfAbsent(type, t -> new Object2FloatLinkedOpenHashMap<>()).put(biome, 1F);
        }

        return map;
    }

    private static Object2FloatMap<Holder<Biome>> getBiomeWeights(ClimateType type, HolderLookup.RegistryLookup<Biome> biomes, Consumer<Holder<Biome>> registered) {
        var map = newMutableWeightMap();

        for (var entry : type.getWeights().object2FloatEntrySet()) {
            var key = ResourceKey.create(Registries.BIOME, entry.getKey());

            // Skipped rather than thrown on, so a climate datapack can name biomes from a mod that
            // may not be installed. Without this, one absent biome takes down the whole world
            // preset, which presents as TerraForged vanishing from the world-type list -- with the
            // real cause buried in the log.
            var biome = biomes.get(key).orElse(null);
            if (biome == null) {
                TerraForged.LOG.debug("Climate {} lists unknown biome {}, skipping", type, entry.getKey());
                continue;
            }

            map.put(biome, entry.getFloatValue());
            registered.accept(biome);
        }

        return map;
    }

    private static List<Holder<Biome>> getOverworldBiomes(HolderLookup.RegistryLookup<Biome> biomes, HolderLookup.RegistryLookup<ClimateType> biomeTypes) {
        var list = BiomeUtil.getOverworldBiomes(biomes);
        var added = new ObjectOpenHashSet<>(list);

        for (var type : biomeTypes.listElements().map(Holder::value).toList()) {
            for (var name : type.getWeights().keySet()) {
                var key = ResourceKey.create(Registries.BIOME, name);

                // Absent biomes are skipped here for the same reason as in getBiomeWeights.
                var biome = biomes.get(key).orElse(null);
                if (biome == null) continue;

                if (added.add(biome)) {
                    list.add(biome);
                }
            }
        }

        list.sort(BiomeUtil.BIOME_SORTER);

        return list;
    }

    private static Object2FloatMap<Holder<Biome>> newMutableWeightMap() {
        return new Object2FloatLinkedOpenHashMap<>();
    }

    private record BiomeTypeHolder(BiomeType type, Identifier name) {
        public BiomeTypeHolder(BiomeType type) {
            this(type, TerraForged.location(type.name().toLowerCase(Locale.ROOT)));
        }
    }
}
