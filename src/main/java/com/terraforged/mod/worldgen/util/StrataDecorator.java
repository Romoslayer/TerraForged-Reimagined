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

import com.terraforged.mod.util.MathUtil;
import com.terraforged.mod.worldgen.settings.TerraSettings;
import com.terraforged.noise.util.Noise;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Miscellaneous > Strata Decorator: bands of different stone types underground, in the spirit of 0.2.x's
 * strata. Each region of Strata Region Size blocks gets its own sequence of bands, tilted by a
 * low-frequency noise so they are not flat sheets.
 *
 * <p>Runs straight after the terrain is filled, before surface rules and ores, and only replaces plain
 * stone. Two margins matter:
 * <ul>
 *   <li><b>Below the soil.</b> Vanilla's surface rules only replace the default block, so a band reaching
 *       the surface would leave bare stone where grass and dirt should be.
 *   <li><b>Above the deepslate transition.</b> The same rule turns stone into deepslate near y=0; a band
 *       there would punch stone-coloured holes through the deepslate layer.
 * </ul>
 *
 * <p>With Ore Compatible Stone Only on, bands use only granite, diorite, andesite and stone -- exactly the
 * blocks in {@code stone_ore_replaceables} -- so ores still generate through them.
 */
public final class StrataDecorator {
    private static final int SOIL_MARGIN = 8;
    private static final int DEEPSLATE_TOP = 9;
    private static final int TILT_SCALE = 200;
    private static final float TILT_BLOCKS = 12F;

    private static final BlockState[] ORE_COMPATIBLE = {
            Blocks.GRANITE.defaultBlockState(),
            Blocks.DIORITE.defaultBlockState(),
            Blocks.ANDESITE.defaultBlockState(),
            Blocks.STONE.defaultBlockState(),
    };

    private static final BlockState[] ALL = {
            Blocks.GRANITE.defaultBlockState(),
            Blocks.DIORITE.defaultBlockState(),
            Blocks.ANDESITE.defaultBlockState(),
            Blocks.STONE.defaultBlockState(),
            Blocks.TUFF.defaultBlockState(),
            Blocks.CALCITE.defaultBlockState(),
            Blocks.DRIPSTONE_BLOCK.defaultBlockState(),
            Blocks.SMOOTH_BASALT.defaultBlockState(),
    };


    private StrataDecorator() {}

    public static void apply(ChunkAccess chunk, int seed, TerraSettings.Miscellaneous misc) {
        var palette = misc.oreCompatibleStoneOnly ? ORE_COMPATIBLE : ALL;
        int regionSize = Math.max(16, misc.strataRegionSize);
        var stone = Blocks.STONE.defaultBlockState();

        var bandStates = new BlockState[6];
        var bandDepths = new int[6];

        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();
        int minY = Math.max(chunk.getMinY(), DEEPSLATE_TOP);

        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int x = startX + dx;
                int z = startZ + dz;

                int top = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, dx, dz) - SOIL_MARGIN;
                if (top <= minY) continue;

                int bands = buildBands(seed, Math.floorDiv(x, regionSize), Math.floorDiv(z, regionSize),
                        palette, bandStates, bandDepths);
                int total = 0;
                for (int i = 0; i < bands; i++) total += bandDepths[i];

                int tilt = (int) (Noise.singleSimplex(x / (float) TILT_SCALE, z / (float) TILT_SCALE, seed + 31337) * TILT_BLOCKS);

                for (int y = top; y >= minY; y--) {
                    var section = chunk.getSection(chunk.getSectionIndex(y));
                    int lx = dx, ly = y & 15, lz = dz;
                    if (section.getBlockState(lx, ly, lz) != stone) continue;

                    int depth = Math.floorMod(y + tilt, total);
                    var state = bandStates[bands - 1];
                    for (int i = 0, acc = 0; i < bands; i++) {
                        acc += bandDepths[i];
                        if (depth < acc) {
                            state = bandStates[i];
                            break;
                        }
                    }

                    if (state != stone) section.setBlockState(lx, ly, lz, state, false);
                }
            }
        }
    }

    /** A region's band sequence: 4-6 bands, each 3-14 blocks thick, drawn from the palette by hash. */
    private static int buildBands(int seed, int rx, int rz, BlockState[] palette, BlockState[] states, int[] depths) {
        int hash = MathUtil.hash(seed + 7741, rx, rz);
        int bands = 4 + (int) (MathUtil.rand(hash, 1) * 3);

        for (int i = 0; i < bands; i++) {
            states[i] = palette[(int) (MathUtil.rand(hash, 10 + i) * palette.length) % palette.length];
            depths[i] = 3 + (int) (MathUtil.rand(hash, 20 + i) * 12);
        }

        return bands;
    }
}
