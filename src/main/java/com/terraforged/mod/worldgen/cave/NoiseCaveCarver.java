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

import com.terraforged.mod.util.MathUtil;
import com.terraforged.mod.worldgen.Generator;
import com.terraforged.mod.worldgen.asset.NoiseCave;
import com.terraforged.noise.util.NoiseUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;

public class NoiseCaveCarver {
    /** Thinnest roof a cave may leave under the surface; below this it is pushed down. */
    private static final int MIN_ROOF = 5;

    private static final int CHUNK_AREA = 16 * 16;

    public static void carve(int seed,

                             ChunkAccess chunk,
                             CarverChunk carver,
                             Generator generator,
                             NoiseCave config) {
        var pos = new BlockPos.MutableBlockPos();

        int minY = generator.getMinY();
        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();

        for (int i = 0; i < CHUNK_AREA; i++) {
            int dx = i & 15;
            int dz = i >> 4;
            int x = startX + dx;
            int z = startZ + dz;

            int surface = getSurface(seed, x, z, chunk, generator, carver);
            int y = config.getHeight(seed, x, z);

            float value = carver.modifier.getValue(seed, x, z);
            int cavern = config.getCavernSize(seed, x, z, value);
            if (cavern == 0) continue;

            int floor = config.getFloorDepth(seed, x, z, cavern);
            int top = MathUtil.clamp(y + cavern, minY, surface);
            
            // Never stop just under the ground. getSurface() is the topmost solid block minus one, so a
            // cave reaching it hollows out everything beneath the surface block and leaves the block
            // itself as a lid -- carrying the grass and snow buildSurface already put on it, because this
            // carver runs after the surface stage. Where the lid is only as wide as the column it reads as
            // a block of snow hanging in mid air, which is what was reported at cave mouths. The mask still
            // decides whether a cave breaks the surface: a breach pushes the top above the ground and is
            // left alone. Only the in-between is removed, so a cave either opens or keeps a real roof.
            int topSolid = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, dx, dz);
            if (top < topSolid && topSolid - top < MIN_ROOF) top = topSolid - MIN_ROOF;
            int bottom = MathUtil.clamp(y - floor, minY, surface);

            if (top - bottom < 2) continue;

            var biome = carver.getBiome(x, z, config, generator);

            carve(chunk, biome, dx, dz, bottom, top, surface, pos, carver.protection, x, z);
        }
    }

    private static void carve(ChunkAccess chunk, Holder<Biome> biome, int dx, int dz, int bottom, int top, int surface,
                              BlockPos.MutableBlockPos pos, java.util.List<net.minecraft.world.level.levelgen.structure.BoundingBox> protection, int x, int z) {
        var air = Blocks.AIR.defaultBlockState();

        int biomeX = dx >> 2;
        int biomeZ = dz >> 2;
        int maxBiomeY = (surface - 16) >> 2;

        for (int cy = bottom; cy <= top; cy++) {
            pos.set(dx, cy, dz);

            var existing = chunk.getBlockState(pos);
            if (!existing.getFluidState().isEmpty()) continue;

            // Never carve the world floor out. `bottom` is clamped to the generator's minY, which is
            // BELOW the bedrock band, so a low enough layer opens a hole straight into the void -- which
            // is what happened the moment cave configs were added at min_y=-56. Upstream's layers all
            // bottom out around y=-32 so this never fired in four years.
            //
            // Deliberately only bedrock, not ModTags.CARVER_REPLACEABLES: that tag leaves out
            // gold, diamond, redstone and lapis ore, so filtering on it would strand those as loose
            // blocks in cave walls -- floating blocks, the exact complaint this carver keeps producing.
            if (existing.is(Blocks.BEDROCK)) continue;

            // Structures pick their height before any cave exists, so carving under one leaves it on air.
            boolean kept = false;
            for (int p = 0; p < protection.size(); p++) {
                if (protection.get(p).isInside(x, cy, z)) { kept = true; break; }
            }
            if (kept) continue;

            chunk.setBlockState(pos, air, 0);

            if ((cy >> 2) >= maxBiomeY) continue;

            int biomeY = (cy & 15) >> 2;
            int sectionIndex = chunk.getSectionIndex(cy);
            var section = chunk.getSection(sectionIndex);

            // TODO:
            var container = (PalettedContainer<Holder<Biome>>) section.getBiomes();
            container.set(biomeX, biomeY, biomeZ, biome);
        }
    }

    private static int getSurface(int seed, int x, int z, ChunkAccess chunk, Generator generator, CarverChunk carverChunk) {
        float mask = carverChunk.getCarvingMask(seed, x, z);
        int surface = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) - 1;
        if (surface > generator.getSeaLevel() || surface < generator.getSeaLevel() - 16) {
            surface += 9;
        }
        return surface - NoiseUtil.floor(16 * mask);
    }
}
