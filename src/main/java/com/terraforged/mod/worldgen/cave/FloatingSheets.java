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

package com.terraforged.mod.worldgen.cave;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.List;

/**
 * Removes flat sheets of rock left hanging in mid-air inside large caverns.
 *
 * <p>Five cave configs carve independently ({@code mega}, {@code mega_deep}, {@code synapse_low},
 * {@code synapse_mid}, {@code synapse_high}), each taking one contiguous {@code [bottom, top]} per
 * column with no knowledge of the others. Where two of them stop within a block or two of each other
 * the rock between survives, and because both layers' heights vary smoothly it survives as a broad
 * flat sheet attached to the wall only where the gap happened to widen. {@code NoiseCaveCarver}'s
 * {@code MIN_ROOF} guard protects the roof against the <b>surface</b> only, so nothing catches this.
 *
 * <p><b>A thin column is not the same thing as a floating sheet</b>, and conflating them would be
 * ruinous: a census of "solid run with air above and below, at most 4 thick" finds <b>45 per chunk</b>,
 * almost all of it ordinary cave ceilings and floors that are thin in one column but firmly attached
 * all around. Clearing those would gut every cave in the world. What separates the artifact is that it
 * hangs between two <b>large</b> air volumes, so this requires a tall open space on both sides. At
 * {@code thickness <= 2} with {@code >= 8} blocks of air each way the census drops to 4.7 per chunk,
 * and at {@code thickness <= 1} to 1.8 — the latter matching the residual the Deep Caves slab census
 * measured (320 over ~300 chunks) and described as "thin rock between two cave systems".
 *
 * <p>The test is per column and purely vertical, so it needs no neighbour data and cannot leave the
 * one-block fringe at chunk borders that a horizontal rule would. Runs touching fluid are skipped
 * because "air strictly above and below" already excludes them, so no pool is ever drained.
 *
 * <p>Runs at the very end of {@code applyCarvers}, after both the noise caves and the Deep Caves deep
 * pass, because that is the first point at which the column's air/solid profile is final. That is also
 * after surface rules, so a floor newly exposed by this pass is bare rock — consistent with the
 * existing deep-cave behaviour, where deep floors are deliberately left undressed.
 */
public final class FloatingSheets {
    /** Thickest sheet to remove. 1 is the reported artifact; 2 also reads as unnatural when suspended. */
    private static final int MAX_THICKNESS = 2;

    /** Open air required on BOTH sides. This is what separates a sheet in a cavern from cave rock. */
    private static final int MIN_AIR = 8;

    /** Sweeps per column. Bounded so a pathological column cannot spin. */
    private static final int MAX_PASSES = 4;

    /**
     * A second, thicker tier, allowed only when the void on each side is much larger.
     *
     * <p>Measured against upstream: with Deep Caves off, so only the five cave layers upstream itself
     * ships, rock up to 4 thick with 16+ blocks of air both sides occurs <b>2 times in 400 chunks</b>.
     * With the port as shipped it is <b>432</b>. Upstream does not make these; the added Deep Caves pass
     * interacting with the five layers does, so clearing them moves the port toward upstream rather than
     * away from it. Thickness alone is not enough to separate them -- at air >= 8 upstream still makes
     * 242 -- which is why the thicker tier demands the larger void.
     *
     * <p><b>The air threshold was 16 and is now 12</b>, measured on the build that runs Deep Caves and
     * TerraForged's layers together. Detached rock per chunk went 4.33 to 3.38 at air >= 8, 1.84 to 0.95
     * in the band the change actually targets, and open cave volume did not move at all -- 800 to 801
     * blocks per chunk at y -48..-32. It trims sheets rather than hollowing caves.
     *
     * <p>It is only a partial fix and no threshold reaches upstream's figure: upstream makes 1.08 runs
     * per chunk at air >= 8 against this build's 3.38, and even in the 8-9 band alone it is 0.58 against
     * 1.37. Clearing that band would take the port below upstream's own rock density. The remainder is
     * the price of running both cave systems at once.
     */
    private static final int THICK_MAX = 4;
    private static final int THICK_AIR = 12;

    private FloatingSheets() {}

    public static void clear(ChunkAccess chunk, StructureManager structures) {
        if (Math.max(MAX_THICKNESS, THICK_MAX) <= 0) return;

        var protection = StructureSpace.protectionBoxes(chunk, structures);
        var air = Blocks.AIR.defaultBlockState();
        var pos = new BlockPos.MutableBlockPos();

        int minY = chunk.getMinY();
        int maxY = chunk.getMaxY();
        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();

        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int x = startX + dx;
                int z = startZ + dz;

                // Clearing a sheet merges the air above it with the air below, which can bring a run
                // that was previously short of MIN_AIR up over the threshold. One sweep therefore
                // leaves a second-order residue, so sweep until the column stops changing. A single
                // sweep took the target rule from 848 runs to 418; iterating takes it further.
                for (int pass = 0; pass < MAX_PASSES; pass++) {
                    boolean changed = false;

                    int y = minY + 1;
                    while (y < maxY) {
                        if (!isSolid(chunk, pos, dx, y, dz)) { y++; continue; }

                        int end = y;
                        while (end + 1 < maxY && isSolid(chunk, pos, dx, end + 1, dz)) end++;
                        int thickness = end - y + 1;

                        boolean bounded = isAir(chunk, pos, dx, y - 1, dz) && isAir(chunk, pos, dx, end + 1, dz);
                        int openSpace = bounded ? Math.min(
                                openAir(chunk, pos, dx, dz, y - 1, -1, minY, maxY),
                                openAir(chunk, pos, dx, dz, end + 1, 1, minY, maxY)) : 0;

                        // Thin rock in a moderate void, or thicker rock in a large one.
                        boolean suspended = (thickness <= MAX_THICKNESS && openSpace >= MIN_AIR)
                                || (thickness <= THICK_MAX && openSpace >= THICK_AIR);

                        if (bounded && suspended
                                && !isProtected(protection, x, y, end, z)
                                && !hasBedrock(chunk, pos, dx, y, end, dz)) {

                            for (int cy = y; cy <= end; cy++) {
                                chunk.setBlockState(pos.set(dx, cy, dz), air, 0);
                            }
                            changed = true;
                        }

                        y = end + 2;
                    }

                    if (!changed) break;
                }
            }
        }
    }

    /** Contiguous air running away from {@code from} in {@code step}, capped so it stays cheap. */
    private static int openAir(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int dx, int dz,
                               int from, int step, int minY, int maxY) {
        int n = 0;
        int cap = Math.max(MIN_AIR, THICK_AIR);
        for (int cy = from; cy >= minY && cy <= maxY && n < cap; cy += step) {
            if (!isAir(chunk, pos, dx, cy, dz)) break;
            n++;
        }
        return n;
    }

    private static boolean isAir(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int dx, int y, int dz) {
        return chunk.getBlockState(pos.set(dx, y, dz)).isAir();
    }

    /** Fluid counts as neither air nor solid, so a run beside water or lava never matches. */
    private static boolean isSolid(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int dx, int y, int dz) {
        var state = chunk.getBlockState(pos.set(dx, y, dz));
        return !state.isAir() && state.getFluidState().isEmpty();
    }

    private static boolean hasBedrock(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int dx, int from, int to, int dz) {
        for (int cy = from; cy <= to; cy++) {
            if (chunk.getBlockState(pos.set(dx, cy, dz)).is(Blocks.BEDROCK)) return true;
        }
        return false;
    }

    private static boolean isProtected(List<BoundingBox> protection, int x, int from, int to, int z) {
        for (int i = 0; i < protection.size(); i++) {
            var box = protection.get(i);
            for (int cy = from; cy <= to; cy++) {
                if (box.isInside(x, cy, z)) return true;
            }
        }
        return false;
    }
}
