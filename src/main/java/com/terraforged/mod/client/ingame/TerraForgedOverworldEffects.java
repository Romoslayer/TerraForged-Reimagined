/*
 * MIT License
 *
 * Copyright (c) 2026 Romoslayer
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

package com.terraforged.mod.client.ingame;

import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.world.level.dimension.DimensionType;

/**
 * Vanilla's overworld sky with the clouds raised to 300, above TerraForged's mountains, which pass vanilla's
 * cloud height of 192.
 *
 * <p>Upstream did this with a custom {@code effects} id in the dimension type. That id reaches every client,
 * and a client without the mod finds no effects for it and crashes rendering, so it would have made the mod
 * required on the client. Here the dimension type keeps {@code minecraft:overworld}; a client with the mod
 * swaps the effects in itself ({@code MixinClientLevel}), and a client without it simply keeps vanilla's
 * clouds. 26.x sets the same height as data instead ({@code visual/cloud_height} in the dimension type).
 */
public class TerraForgedOverworldEffects extends DimensionSpecialEffects.OverworldEffects {
    public static final float CLOUD_HEIGHT = 300F;

    /** Vanilla's overworld height; TerraForged's overworld is taller (1024). */
    private static final int VANILLA_HEIGHT = 384;

    /**
     * Whether a level's effects should be swapped: only vanilla's own overworld effects (not another mod's
     * replacement), in a dimension taller than vanilla's overworld, which is how a client tells a
     * TerraForged overworld apart without anything from the server.
     */
    public static boolean appliesTo(DimensionSpecialEffects effects, DimensionType type) {
        return effects != null && effects.getClass() == DimensionSpecialEffects.OverworldEffects.class
                && type.height() > VANILLA_HEIGHT;
    }

    @Override
    public float getCloudHeight() {
        return CLOUD_HEIGHT;
    }
}
