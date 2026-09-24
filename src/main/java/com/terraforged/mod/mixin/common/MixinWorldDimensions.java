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

import com.terraforged.mod.worldgen.Generator;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.Map;
import java.util.Set;

/**
 * World > Dimensions > Include Extra Dimensions.
 *
 * <p>{@code WorldDimensions#bake} merges every dimension any datapack registers into a new world. When a
 * TerraForged world has the setting off, the set of known dimension keys is cut down to the Overworld,
 * Nether and End before that merge, so extra dimensions are never registered. Any other world, and any
 * TerraForged world with the setting on (the default), is untouched.
 */
@Mixin(WorldDimensions.class)
public abstract class MixinWorldDimensions {
    @Shadow
    public abstract Map<ResourceKey<LevelStem>, LevelStem> dimensions();

    @ModifyVariable(method = "bake", at = @At("STORE"), ordinal = 0)
    private Set<ResourceKey<LevelStem>> onBakeKnownDimensions(Set<ResourceKey<LevelStem>> known) {
        var overworld = dimensions().get(LevelStem.OVERWORLD);
        if (overworld == null || !(overworld.generator() instanceof Generator generator)) return known;
        if (generator.getSettings().world.dimensions.includeExtraDimensions) return known;

        return Set.copyOf(known.stream()
                .filter(key -> key.equals(LevelStem.OVERWORLD) || key.equals(LevelStem.NETHER) || key.equals(LevelStem.END))
                .toList());
    }
}
