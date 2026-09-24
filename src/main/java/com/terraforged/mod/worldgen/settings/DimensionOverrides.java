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

package com.terraforged.mod.worldgen.settings;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * World > Dimensions: which world preset's Nether and End a TerraForged world uses.
 *
 * <p>"default" keeps whatever the world ends up with -- and note that already includes mods. When a world
 * is created, {@code WorldDimensions#bake} prefers a datapack's own {@code minecraft:the_nether} or
 * {@code minecraft:the_end} over the preset's, so mods that replace those dimensions that way apply to
 * TerraForged worlds without any setting. Choosing a preset here swaps in that preset's stem instead.
 *
 * <p>Include Extra Dimensions is applied in {@code MixinWorldDimensions}, where extra dimensions are merged.
 */
public final class DimensionOverrides {
    public static final String DEFAULT = "default";

    private DimensionOverrides() {}

    /** The choices for a dimension: "default" plus every world preset that defines it. */
    public static List<String> options(HolderLookup.Provider registries, ResourceKey<LevelStem> dimension) {
        var options = new java.util.ArrayList<String>();
        options.add(DEFAULT);

        registries.lookupOrThrow(Registries.WORLD_PRESET).listElements()
                .filter(preset -> preset.value().createWorldDimensions().dimensions().containsKey(dimension))
                .map(preset -> preset.key().identifier().toString())
                .sorted()
                .forEach(options::add);

        return options;
    }

    public static WorldDimensions apply(HolderLookup.Provider registries, WorldDimensions dimensions,
                                        TerraSettings.Dimensions settings) {
        var result = new LinkedHashMap<>(dimensions.dimensions());
        boolean changed = replace(registries, result, LevelStem.NETHER, settings.nether);
        changed |= replace(registries, result, LevelStem.END, settings.end);
        return changed ? new WorldDimensions(result) : dimensions;
    }

    private static boolean replace(HolderLookup.Provider registries, java.util.Map<ResourceKey<LevelStem>, LevelStem> map,
                                   ResourceKey<LevelStem> dimension, String presetId) {
        if (presetId == null || DEFAULT.equals(presetId)) return false;

        try {
            var key = ResourceKey.create(Registries.WORLD_PRESET, Identifier.parse(presetId));
            var stem = registries.lookupOrThrow(Registries.WORLD_PRESET).get(key)
                    .map(preset -> preset.value().createWorldDimensions().dimensions().get(dimension))
                    .orElse(null);
            if (stem == null) return false;

            map.put(dimension, stem);
            return true;
        } catch (Exception e) {
            com.terraforged.mod.TerraForged.LOG.warn("Ignoring unknown world preset '{}' for {}", presetId, dimension.identifier());
            return false;
        }
    }
}
