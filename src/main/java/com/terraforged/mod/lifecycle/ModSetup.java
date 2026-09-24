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

package com.terraforged.mod.lifecycle;

import com.mojang.serialization.Codec;
import com.terraforged.mod.CommonAPI;
import com.terraforged.mod.TerraForged;
import com.terraforged.mod.registry.key.RegistryKey;
import com.terraforged.mod.worldgen.asset.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.biome.Biome;

/**
 * Declares TerraForged's own datapack registries.
 *
 * <p>Each one is declared twice, for two different jobs. {@code RegistryManager#create} builds the
 * in-memory registry holding the built-in defaults, which is what the data generator writes out.
 * {@link CommonAPI#registerDataRegistry} tells Minecraft the registry exists as a datapack registry
 * (Fabric API's {@code DynamicRegistries}, NeoForge's {@code DataPackRegistryEvent}), so it is populated
 * from datapack JSON at world load and shows up in the world's registries.
 *
 * <p>Upstream had no equivalent of the second step. Custom datapack registries were not a thing a
 * mod could declare in 1.19, so it reflected its content into the registry map by hand as the world
 * loaded ({@code BuiltinHook}, driven by a mixin on {@code RegistryOps.createAndLoad}). Registries
 * became immutable shortly afterwards and that approach stopped being possible; this is the
 * supported replacement, and it also means the content comes from the datapack TerraForged already
 * ships and injects, rather than from a second, parallel path.
 */
public class ModSetup extends Stage {
    public static final ModSetup STAGE = new ModSetup();

    @Override
    protected void doInit() {
        TerraForged.LOG.info("Setting up registries");
        var registryManager = CommonAPI.get().getRegistryManager();

        create(registryManager, TerraForged.CAVES, NoiseCave.CODEC);
        create(registryManager, TerraForged.CLIMATES, ClimateType.CODEC);
        create(registryManager, TerraForged.TERRAINS, TerrainNoise.CODEC);
        create(registryManager, TerraForged.TERRAIN_TYPES, TerrainType.DIRECT);
        create(registryManager, TerraForged.VEGETATIONS, VegetationConfig.CODEC);

        // Biomes are vanilla's registry, not ours -- we only keep built-ins for the data generator,
        // so this one is neither injected nor declared as a datapack registry.
        registryManager.create(TerraForged.BIOMES, Biome.DIRECT_CODEC, false);
    }

    private static <T> void create(com.terraforged.mod.registry.RegistryManager manager,
                                   RegistryKey<T> key,
                                   Codec<T> codec) {
        manager.create(key, codec);
        CommonAPI.get().registerDataRegistry(key.get(), codec);
    }
}
