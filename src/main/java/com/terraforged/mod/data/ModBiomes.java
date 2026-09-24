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

package com.terraforged.mod.data;

import com.terraforged.mod.TerraForged;
import com.terraforged.mod.registry.key.EntryKey;
import com.terraforged.mod.worldgen.biome.biomes.ModBiome;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.placement.CavePlacements;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.GenerationStep;

import static com.terraforged.mod.TerraForged.BIOMES;

public interface ModBiomes {
    EntryKey<Biome> CAVE = TerraForged.BIOMES.entryKey("cave");
    EntryKey<Biome> OAK_FOREST = TerraForged.BIOMES.entryKey("oak_forest");

    static void register(HolderLookup.Provider lookup) {
        // BiomeGenerationSettings.Builder resolves features and carvers up front now, so it needs the
        // two lookups rather than taking keys and resolving them later.
        var features = lookup.lookupOrThrow(Registries.PLACED_FEATURE);
        var carvers = lookup.lookupOrThrow(Registries.CONFIGURED_CARVER);

        TerraForged.register(BIOMES, "cave", ModBiome.create(lookup, Biomes.DRIPSTONE_CAVES, builder -> {
            var genSettings = new BiomeGenerationSettings.Builder(features, carvers);
            genSettings.addFeature(GenerationStep.Decoration.LOCAL_MODIFICATIONS, CavePlacements.LARGE_DRIPSTONE);
            genSettings.addFeature(GenerationStep.Decoration.UNDERGROUND_DECORATION, CavePlacements.POINTED_DRIPSTONE);
            builder.generationSettings(genSettings.build());
        }));

        // Copied from FOREST, not PLAINS. Upstream copied plains and then commented out
        // `biomeCategory(FOREST)` when that enum went away, which left a biome called "oak forest"
        // carrying plains' generation settings -- so it generated plains' occasional lone oak and
        // nothing else. It was never noticed because it also shipped without an `is_overworld` tag,
        // so it was never placed in a world at all.
        TerraForged.register(BIOMES, "oak_forest", ModBiome.create(lookup, Biomes.FOREST, builder -> {}));
    }
}
