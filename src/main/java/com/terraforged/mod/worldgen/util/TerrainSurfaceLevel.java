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

package com.terraforged.mod.worldgen.util;

import com.mojang.serialization.MapCodec;
import com.terraforged.mod.worldgen.Generator;
import net.minecraft.util.Interval;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

/**
 * TerraForged's terrain height, as the "preliminary surface level" vanilla's surface rules read.
 *
 * <p>Surface rules decide how deep grass, dirt and sand go from that level ({@code stone_depth},
 * {@code above_preliminary_surface}), so it has to describe TerraForged's ground, not the flat nothing of
 * the empty noise router a non-vanilla generator is given. Up to 26.2 it was supplied by reflecting into
 * {@code NoiseChunk}'s per-chunk height cache. 26.3 removed that cache: the surface system now samples a
 * density function, the router's {@code chunk_surface_level}, so this is that function.
 *
 * <p>It reproduces what the 26.2 cache fed the rules, value for value, so surfaces do not change between
 * versions. The rules interpolated across each chunk between four corner heights; the cache held the true
 * height only at the chunk's own (0,0) corner and the chunk's lowest height (over its 4-block grid) at the
 * other three, which lie in neighbouring chunks. Hence
 * {@code lerp2(fx, fz, corner, min, min, min)}. Every term is a small dyadic fraction, exact in a float, so
 * the floor the rules take matches 26.2's to the block.
 */
public record TerrainSurfaceLevel(Generator generator) implements DensityFunction {
    @Override
    public DensitySampler compileSampler(DensityFunction.CompileContext context) {
        return new Sampler(generator);
    }

    @Override
    public DensityFunction rewriteChildren(DfRewriteRule rule) {
        return this;
    }

    @Override
    public Interval range() {
        return Interval.of(generator.getMinY(), generator.getMinY() + generator.getGenDepth());
    }

    @Override
    public @DensityFunction.Axes int domainAxes() {
        return DensityFunction.AXIS_X | DensityFunction.AXIS_Z;
    }

    @Override
    public MapCodec<? extends DensityFunction> codec() {
        throw new UnsupportedOperationException("TerrainSurfaceLevel is built in code and never encoded");
    }

    private record Sampler(Generator generator) implements DensitySampler {
        @Override
        public float sampleValue(SamplerContext context, int blockX, int blockY, int blockZ) {
            var data = generator.getChunkData(generator.getSeed(), new ChunkPos(blockX >> 4, blockZ >> 4));

            int corner = data.getHeight(0, 0);
            int min = Integer.MAX_VALUE;
            for (int dz = 0; dz < 16; dz += 4) {
                for (int dx = 0; dx < 16; dx += 4) {
                    min = Math.min(min, data.getHeight(dx, dz));
                }
            }

            return (float) Mth.lerp2((blockX & 15) / 16.0, (blockZ & 15) / 16.0, corner, min, min, min);
        }

        @Override
        public void sampleVolume(SamplerContext context, DensityBuffer outputBuffer, DensityVolume volume) {
            DensitySampler.sampleVolumeNaive(context, outputBuffer, volume, this);
        }
    }
}
