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

package com.terraforged.mod.worldgen.biome.biomes;

import net.minecraft.core.registries.Registries;
import com.terraforged.mod.TerraForged;
import net.minecraft.core.Registry;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;

import java.util.function.Consumer;
import java.util.function.Supplier;

public record ModBiome(ResourceKey<Biome> key, Supplier<Biome> factory) {
    public Biome create() {
        return factory.get();
    }

    public static ModBiome of(HolderLookup.Provider lookup, String name, ResourceKey<Biome> parent, Consumer<Biome.BiomeBuilder> modifier) {
        var key = ResourceKey.create(Registries.BIOME, TerraForged.location(name));
        var factory = copyFactory(lookup, parent, modifier);
        return new ModBiome(key, factory);
    }

    private static Supplier<Biome> copyFactory(HolderLookup.Provider lookup, ResourceKey<Biome> parent, Consumer<Biome.BiomeBuilder> modifier) {
        return () -> {
            var builder = builderOf(lookup, parent);
            modifier.accept(builder);
            return builder.build();
        };
    }

    /**
     * Copies a vanilla biome's settings so they can be tweaked.
     *
     * <p>The biome lookup has to be passed in: {@code BuiltinRegistries.BIOME} is gone, because
     * biomes are datapack-loaded and there is no static registry to read from any more.
     */
    private static Biome.BiomeBuilder builderOf(HolderLookup.Provider lookup, ResourceKey<Biome> parent) {
        var biome = lookup.lookupOrThrow(Registries.BIOME).getOrThrow(parent).value();
        var builder = new Biome.BiomeBuilder();
        // downfall is read off the climate record (opened by the access widener) because Biome no
        // longer exposes it, and precipitation became the boolean hasPrecipitation.
        builder.downfall(biome.climateSettings.downfall());
        builder.temperature(biome.getBaseTemperature());
        builder.mobSpawnSettings(biome.getMobSettings());
        builder.hasPrecipitation(biome.hasPrecipitation());
        builder.specialEffects(biome.getSpecialEffects());
        builder.generationSettings(biome.getGenerationSettings());
        builder.putAttributes(biome.getAttributes());
        return builder;
    }

    public static Biome create(HolderLookup.Provider lookup, ResourceKey<Biome> parent, Consumer<Biome.BiomeBuilder> modifier) {
        var builder = builderOf(lookup, parent);
        modifier.accept(builder);
        return builder.build();
    }
}
