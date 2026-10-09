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

package com.terraforged.mod.mixin.common;

import com.mojang.serialization.Lifecycle;
import com.terraforged.mod.TerraForged;
import com.terraforged.mod.worldgen.Generator;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.stream.Stream;

/**
 * World > Dimensions > Include Extra Dimensions.
 *
 * <p>{@code WorldDimensions#bake} merges every dimension any datapack registers into a new world. When a
 * TerraForged world has the setting off, the known dimension keys are cut down to the Overworld, Nether
 * and End before that merge, so extra dimensions are never registered. Any other world, and any
 * TerraForged world with the setting on (the default), is untouched.
 *
 * <p>On 1.20.1 those keys are a {@code Stream} (the first local {@code bake} stores), not a {@code Set},
 * and the dimensions are a {@code Registry} rather than a {@code Map}.
 */
@Mixin(WorldDimensions.class)
public abstract class MixinWorldDimensions {
    @Shadow
    public abstract net.minecraft.core.Registry<LevelStem> dimensions();

    /**
     * Keeps TerraForged's overworld when a datapack defines one too.
     *
     * <p>{@code bake} takes each dimension from the datapacks first and from the world only as a
     * fallback, so a datapack's {@code data/minecraft/dimension/overworld.json} replaces the world's own
     * overworld -- new and existing worlds alike. Terralith ships one (its own vanilla-noise overworld)
     * whenever Lithostitched is absent: its datapack zip, every jar on Forge, and its 1.20.1 / older 1.21.1
     * jars. A world created as TerraForged then generated as Terralith's terrain, with nothing in the log
     * and {@code terraforged:generator} still saved in the world. For a TerraForged world the datapacks'
     * overworld is dropped here, so the world keeps its generator; the datapack's biomes still reach it
     * through the biome registry, and its other dimensions are untouched. Any other world is unaffected.
     */
    @ModifyVariable(method = "bake", at = @At("HEAD"), argsOnly = true)
    private Registry<LevelStem> onBakeKeepOverworld(Registry<LevelStem> datapackDimensions) {
        var overworld = dimensions().get(LevelStem.OVERWORLD);
        if (overworld == null || !(overworld.generator() instanceof Generator)) return datapackDimensions;
        if (!datapackDimensions.registryKeySet().contains(LevelStem.OVERWORLD)) return datapackDimensions;

        if (!loggedKeptOverworld) {
            loggedKeptOverworld = true;
            TerraForged.LOG.info("A datapack defines its own overworld dimension; keeping TerraForged's");
        }

        var withoutOverworld = new MappedRegistry<LevelStem>(Registries.LEVEL_STEM, Lifecycle.stable());
        for (var entry : datapackDimensions.entrySet()) {
            var key = entry.getKey();
            if (key.equals(LevelStem.OVERWORLD)) continue;
            withoutOverworld.register(key, entry.getValue(), datapackDimensions.lifecycle(entry.getValue()));
        }
        return withoutOverworld.freeze();
    }

    @Unique
    private static boolean loggedKeptOverworld;

    @ModifyVariable(method = "bake", at = @At("STORE"), ordinal = 0)
    private Stream<ResourceKey<LevelStem>> onBakeKnownDimensions(Stream<ResourceKey<LevelStem>> known) {
        var overworld = dimensions().get(LevelStem.OVERWORLD);
        if (overworld == null || !(overworld.generator() instanceof Generator generator)) return known;
        if (generator.getSettings().world.dimensions.includeExtraDimensions) return known;

        return known.filter(key -> key.equals(LevelStem.OVERWORLD) || key.equals(LevelStem.NETHER) || key.equals(LevelStem.END));
    }
}
