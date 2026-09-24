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

package com.terraforged.mod.worldgen.util;

import com.terraforged.mod.TerraForged;
import com.terraforged.mod.util.MathUtil;
import com.terraforged.mod.worldgen.settings.TerraSettings;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * World > Bedrock Layer: replaces vanilla's bedrock floor with a layer of a chosen block, a minimum depth,
 * and a per-column variance, as 0.2.x did.
 *
 * <p>Runs after the surface rules, because that is where vanilla places its floor -- the
 * {@code bedrock_floor} rule, a gradient over the bottom five blocks. That floor is first cleared back to
 * deepslate, then the configured layer is built. Only used when the settings differ from their defaults;
 * see {@code TerraSettings.BedrockLayer#isVanilla}.
 */
public final class BedrockLayer {
    /** Height of vanilla's bedrock gradient, which is cleared before the configured layer is placed. */
    private static final int VANILLA_FLOOR_HEIGHT = 5;


    private BedrockLayer() {}

    public static void apply(ChunkAccess chunk, int seed, TerraSettings.BedrockLayer settings) {
        var material = resolve(settings.material);
        var bedrock = Blocks.BEDROCK.defaultBlockState();
        var deepslate = Blocks.DEEPSLATE.defaultBlockState();

        int minY = chunk.getMinY();
        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();
        int variance = Math.max(0, settings.variance);
        int minDepth = Math.max(0, settings.minDepth);

        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                for (int y = minY; y < minY + VANILLA_FLOOR_HEIGHT; y++) {
                    var section = chunk.getSection(chunk.getSectionIndex(y));
                    if (section.getBlockState(dx, y & 15, dz) == bedrock) {
                        section.setBlockState(dx, y & 15, dz, deepslate, false);
                    }
                }

                int extra = variance == 0 ? 0
                        : (int) (MathUtil.rand(MathUtil.hash(seed + 5261, startX + dx, startZ + dz)) * (variance + 1));
                int top = minY + minDepth + extra;

                for (int y = minY; y < top && y <= chunk.getMaxY(); y++) {
                    var section = chunk.getSection(chunk.getSectionIndex(y));
                    section.setBlockState(dx, y & 15, dz, material, false);
                }
            }
        }
    }

    /** The configured block, or bedrock if the id is unknown or air -- a typo must not open the void. */
    private static BlockState resolve(String id) {
        try {
            var block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(id));
            if (block != null && block != Blocks.AIR) return block.defaultBlockState();
        } catch (Exception ignored) {
            // Falls through to the warning below.
        }
        TerraForged.LOG.warn("Bedrock layer material '{}' is not a block; using bedrock", id);
        return Blocks.BEDROCK.defaultBlockState();
    }
}
