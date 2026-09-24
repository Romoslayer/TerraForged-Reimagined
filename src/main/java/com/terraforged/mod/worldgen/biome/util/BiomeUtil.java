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

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import com.terraforged.engine.world.biome.type.BiomeType;
import com.terraforged.mod.CommonAPI;
import com.terraforged.mod.TerraForged;
import com.terraforged.mod.data.ModTags;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;

import java.util.*;
import java.util.stream.Collectors;

public class BiomeUtil {
    /** Vanilla's coldest biome temperature (frozen/jagged peaks). */
    private static final float MIN_TEMPERATURE = -0.7F;

    /** See {@link #getByClimate} for why this is not vanilla's 2.0. */
    private static final float MAX_TEMPERATURE = 1.2F;

    private static final Map<BiomeType, Identifier> TYPE_NAMES = new EnumMap<>(BiomeType.class);

    private static final Comparator<ResourceKey<?>> KEY_COMPARATOR = Comparator.comparing(ResourceKey::identifier);

    public static Comparator<Holder<Biome>> BIOME_SORTER = (o1, o2) -> {
        var k1 = o1.unwrapKey().orElseThrow();
        var k2 = o2.unwrapKey().orElseThrow();
        Objects.requireNonNull(k1);
        Objects.requireNonNull(k2);
        return KEY_COMPARATOR.compare(k1, k2);
    };

    static {
        for (var type : BiomeType.values()) {
            TYPE_NAMES.put(type, TerraForged.location(type.name().toLowerCase(Locale.ROOT)));
        }
    }

    public static Identifier getRegistryName(BiomeType type) {
        return TYPE_NAMES.get(type);
    }

    public static List<Holder<Biome>> getOverworldBiomes(HolderLookup.Provider access) {
        return getOverworldBiomes(access.lookupOrThrow(Registries.BIOME));
    }

    /**
     * Takes a {@link HolderLookup.RegistryLookup} rather than a {@link Registry} so this works both
     * with a world's live biome registry and with the vanilla lookup the built-in defaults are built
     * from. {@code Registry} implements the interface, so live callers are unaffected.
     */
    public static List<Holder<Biome>> getOverworldBiomes(HolderLookup.RegistryLookup<Biome> biomes) {
        var holders = new ObjectArrayList<Holder<Biome>>();

        // Deliberately unfiltered. This list is what Source#possibleBiomes reports, and that has to
        // name every biome the source can return -- including the ocean, river and beach biomes
        // BiomeSampler substitutes in by position, and the cave biomes CaveBiomeSampler picks
        // underground. Narrowing the *climate* pool is a separate question; see isClimateBiome.
        biomes.listElements().forEach(biome -> {
            if (biome.is(BiomeTags.IS_OVERWORLD)) {
                holders.add(biome);
            }
        });

        holders.sort(BIOME_SORTER);

        return holders;
    }

    /**
     * Whether a biome generates underground rather than on the surface.
     *
     * <p>The {@link ModTags#NON_SURFACE} tag is the authoritative answer, but a tag can only list
     * biomes that were known when it was written, and getting this wrong is not subtle: a cave biome
     * in the surface pool paints hillsides with cave terrain and brings its mobs and structures with
     * it. Enumerating them by hand already failed once here — the tag shipped with vanilla's three
     * cave biomes, and 26.2 has four. {@code sulfur_caves} generated in the open air as a result.
     *
     * <p>So the tag is backed by a naming convention, which both vanilla and the biome mods follow:
     * a {@code cave/} path prefix (Terralith and friends) or a {@code _caves} suffix (vanilla). It
     * is a heuristic and is meant to be — it fails safe, since a datapack can always add to the tag,
     * and no surface biome in vanilla or the major biome mods is named either way.
     *
     * @see ModTags#NON_SURFACE
     */
    public static boolean isNonSurface(Holder<Biome> biome) {
        if (biome.is(ModTags.NON_SURFACE.get())) return true;

        return biome.unwrapKey()
                .map(key -> key.identifier().getPath())
                .filter(path -> path.startsWith("cave/") || path.endsWith("_caves"))
                .isPresent();
    }

    /**
     * Whether a biome needs low, wet ground.
     *
     * @see ModTags#WETLAND
     */
    public static boolean isWetland(Holder<Biome> biome) {
        return biome.is(ModTags.WETLAND.get());
    }

    /**
     * Whether a biome suits steep ground.
     *
     * @see ModTags#HIGHLAND
     */
    public static boolean isHighland(Holder<Biome> biome) {
        return biome.is(ModTags.HIGHLAND.get());
    }

    /**
     * Whether a biome should be picked by climate, i.e. belongs in the weighted pool that
     * {@code BiomeSampler#getInitialBiome} draws land biomes from.
     *
     * <p>Two kinds of biome must stay out of that pool even though both are tagged
     * {@code is_overworld}:
     *
     * <ul>
     *   <li><b>Water biomes.</b> Oceans, rivers and beaches are not climate choices -- they are
     *       decided by position, in {@code BiomeSampler#getBiomeOverride}, from continent and river
     *       noise. Leaving them in the pool meant dry inland terrain could roll "ocean" or "beach"
     *       as its biome, which is invisible in the terrain itself but drives everything keyed off
     *       biome: shipwrecks and buried treasure generating on hillsides, drowned spawning inland,
     *       the wrong grass and water colours. The override targets are resolved straight from the
     *       registry, so removing them here does not stop oceans being placed where they belong.
     *   <li><b>Cave biomes</b>, which belong underground -- see {@link #isNonSurface}.
     * </ul>
     *
     * <p>A climate datapack naming one of these explicitly still wins; this only governs the
     * fall-through that sweeps in every biome nothing else claimed.
     */
    public static boolean isClimateBiome(Holder<Biome> biome) {
        return !isNonSurface(biome) && !isWaterPlaced(biome);
    }

    private static boolean isWaterPlaced(Holder<Biome> biome) {
        if (biome.is(BiomeTags.IS_OCEAN)
                || biome.is(BiomeTags.IS_DEEP_OCEAN)
                || biome.is(BiomeTags.IS_RIVER)
                || biome.is(BiomeTags.IS_BEACH)) {
            return true;
        }

        return biome.unwrapKey().filter(POSITIONAL::contains).isPresent();
    }

    /**
     * Biomes {@code BiomeSampler#getBiomeOverride} substitutes in by position, which the tags above
     * do not already cover.
     *
     * <p>Only {@code stony_shore} at present: it is a shore biome that vanilla does not put in
     * {@code is_beach}, so it survived the tag check and was being handed out as an inland climate
     * biome. Anything the sampler places by position has to be kept out of the climate pool, or it
     * gets placed twice over — once where it belongs and once anywhere at all.
     *
     * <p>Keep in step with the overrides in {@code BiomeSampler}.
     */
    private static final Set<ResourceKey<Biome>> POSITIONAL = Set.of(Biomes.STONY_SHORE);

    public static BiomeType getType(Holder<Biome> biome) {
        if (biome.is(BiomeTags.IS_BADLANDS) || biome.is(BiomeTags.HAS_VILLAGE_DESERT))
            return BiomeType.DESERT;
        if (biome.is(BiomeTags.IS_SAVANNA))
            return BiomeType.SAVANNA;
        if (biome.is(BiomeTags.IS_JUNGLE))
            return BiomeType.TROPICAL_RAINFOREST;
        if (biome.is(BiomeTags.IS_TAIGA))
            return getByTemp(biome.value(), BiomeType.TUNDRA, BiomeType.TAIGA);
        if (biome.is(BiomeTags.IS_FOREST))
            return getByRain(biome.value(), BiomeType.TUNDRA, BiomeType.TEMPERATE_RAINFOREST, BiomeType.TEMPERATE_FOREST);
        return getByClimate(biome.value());
    }

    /**
     * Classifies a biome by its own declared temperature and downfall.
     *
     * <p>The cascade above only recognises the five vanilla tags it checks. Vanilla biomes mostly
     * carry one, so upstream's {@code return GRASSLAND} default was a reasonable last resort there.
     * Biomes from other mods usually carry none, so that default swept an entire mod's worth of
     * biomes -- Terralith's eighty-odd, for instance -- into a single climate, which both wrecks
     * their placement and starves the other climates.
     *
     * <p>Every biome declares a temperature and a downfall regardless of its tags, and
     * {@link BiomeType} is already a lookup over exactly those two axes, so this asks the mod's own
     * data instead of guessing. Nothing here is mod-specific: it works for any biome that is
     * honest about its climate.
     *
     * <p>{@link #MAX_TEMPERATURE} is 1.2 rather than vanilla's actual 2.0 maximum. The scale is
     * lopsided -- nearly every biome sits between -0.7 and 1.0, then desert, savanna and badlands
     * jump straight to 2.0 -- so normalising across the full span spends half the grid on an empty
     * gap and pushes every temperate biome into the cold third. 1.2 is the middle of the range that
     * best reproduces the cascade's own answers for vanilla biomes; see {@code tools/ClimateFit.java},
     * which is what picked it.
     */
    public static BiomeType getByClimate(Biome biome) {
        float temperature = normalize(biome.getBaseTemperature(), MIN_TEMPERATURE, MAX_TEMPERATURE);
        float moisture = normalize(getDownfall(biome), 0F, 1F);
        return BiomeType.get(temperature, moisture);
    }

    private static float normalize(float value, float min, float max) {
        float t = (value - min) / (max - min);
        if (t < 0F) return 0F;
        return Math.min(t, 1F);
    }

    public static BiomeType getByRain(Biome biome, BiomeType frozen, BiomeType wetter, BiomeType dryer) {
        if (getPrecipitation(biome) == Biome.Precipitation.SNOW) return frozen;

        return getDownfall(biome) >= 0.8 ? wetter : dryer;
    }

    public static BiomeType getByTemp(Biome biome, BiomeType colder, BiomeType warmer) {
        return getPrecipitation(biome) == Biome.Precipitation.SNOW ? colder : warmer;
    }

    public static BiomeType getByTemp(Biome biome, BiomeType cold, BiomeType temperate, BiomeType hot) {
        var precipitation = getPrecipitation(biome);
        if (precipitation == Biome.Precipitation.SNOW) return cold;
        if (precipitation == Biome.Precipitation.NONE || biome.getBaseTemperature() > 1.0) return hot;
        return temperate;
    }

    /**
     * The biome's precipitation, ignoring position.
     *
     * <p>{@code Biome#getPrecipitation()} is gone; the replacement,
     * {@code getPrecipitationAt(BlockPos, int)}, adjusts temperature for altitude and needs a
     * position. Classifying a biome into a {@link BiomeType} is a property of the biome, not of any
     * particular block in it, so this reproduces the unadjusted form: vanilla's own rule is
     * temperature below {@code 0.15} means snow (see {@code Biome#warmEnoughToRain}).
     */
    private static Biome.Precipitation getPrecipitation(Biome biome) {
        if (!biome.hasPrecipitation()) return Biome.Precipitation.NONE;

        return biome.getBaseTemperature() < 0.15F ? Biome.Precipitation.SNOW : Biome.Precipitation.RAIN;
    }

    /**
     * Read straight off the climate record, because there is no accessor for it any more; how each
     * loader reaches the record is in {@link CommonAPI#getDownfall}. Approximating it from tags would
     * change which biomes land in which climate, so the raw value is worth the platform hook.
     */
    private static float getDownfall(Biome biome) {
        return CommonAPI.get().getDownfall(biome);
    }
}
