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

import com.google.common.base.Suppliers;
import com.terraforged.mod.worldgen.GeneratorResource;
import com.terraforged.mod.worldgen.biome.Source;
import com.terraforged.mod.worldgen.terrain.StructureTerrain;
import com.terraforged.mod.worldgen.terrain.TerrainData;
import com.terraforged.mod.worldgen.terrain.TerrainLevels;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.function.Supplier;

public class ChunkUtil {
    public static final FillerBlock FILLER = ChunkUtil::getFiller;
    public static final Supplier<ByteBuf> FULL_SECTION = Suppliers.memoize(ChunkUtil::createFullPalette);

    /**
     * The highest block that can be given a cave biome.
     *
     * <p>Biomes here used to be strictly two-dimensional: one biome per column, bedrock to sky. Cave
     * biomes therefore never appeared in a world at all — lush caves could not be found, and ancient
     * cities, whose only permitted biome is {@code deep_dark}, could never generate.
     *
     * <p>Zero rather than something nearer the surface so the deep dark stays deep, and because an
     * ancient city's start height is well below it. The cost of the extra layer is one more biome
     * buffer per chunk, not per section: within each of the two bands the biome is still constant
     * down the column, so this is two 2D layers rather than genuinely 3D noise.
     */
    private static final int CAVE_BIOME_MAX_Y = 0;

    /** Whether a whole section lies in the cave-biome band. */
    public static boolean isCaveBiomeSection(int sectionY) {
        return SectionPos.sectionToBlockCoord(sectionY) + 15 <= CAVE_BIOME_MAX_Y;
    }

    /** The same test for a quart y, so {@code Source#getNoiseBiome} answers what the chunk stores. */
    public static boolean isCaveBiomeQuart(int quartY) {
        return isCaveBiomeSection(SectionPos.blockToSectionCoord(QuartPos.toBlock(quartY)));
    }

    public static void fillNoiseBiomes(ChunkAccess chunk, Source source, GeneratorResource resource) {
        var pos = chunk.getPos();
        int biomeX = QuartPos.fromBlock(pos.getMinBlockX());
        int biomeZ = QuartPos.fromBlock(pos.getMinBlockZ());
        var heightAccessor = chunk.getHeightAccessorForGeneration();

        var biomeBuffer = resource.biomeBuffer2D;
        var caveBuffer = resource.caveBiomeBuffer2D;
        boolean anyCaveBiome = false;

        for (int dz = 0; dz < 4; dz++) {
            for (int dx = 0; dx < 4; dx++) {
                var biome = source.getSurfaceBiome(biomeX + dx, biomeZ + dz);
                biomeBuffer.set(dx, dz, biome);

                var cave = source.getLayerBiome(biomeX + dx, biomeZ + dz);
                anyCaveBiome |= cave != null;

                // Columns with no cave region keep their surface biome, so the deep buffer is always
                // complete and the two bands agree wherever there is nothing to change.
                caveBuffer.set(dx, dz, cave != null ? cave : biome);
            }
        }

        for (int i = heightAccessor.getMinSectionY(); i <= heightAccessor.getMaxSectionY(); ++i) {
            var chunkSection = chunk.getSection(chunk.getSectionIndexFromSectionY(i));

            // Only sections lying entirely within the band, so a section is never half one biome
            // layer and half the other -- fillBiomesFromNoise fills the whole section from one
            // resolver.
            boolean deep = anyCaveBiome && isCaveBiomeSection(i);

            // Now takes quart x/y/z rather than x/z; the buffer ignores position, but pass the
            // section's own y so the call is at least self-consistent.
            chunkSection.fillBiomesFromNoise(deep ? caveBuffer : biomeBuffer, 0, QuartPos.fromSection(i), 0);
        }
    }

    public static void fillChunk(int seaLevel, ChunkAccess chunk, TerrainData terrainData, FillerBlock filler, GeneratorResource resource) {
        int limit = chunk.getMaxY() + 1;
        int min = Math.min(limit, getLowestSection(terrainData));
        int max = Math.min(limit, getHighestSection(terrainData));

        // @Optimization Note:
        // Here, we've precomputed a full stone chunk section and written it to a bytebuffer
        // which we are then reading into each chunk section below the lowest non-full chunk
        // section (determined from our heightmap). This is waaay faster than setting blocks
        // individually in the section so helps reduce the impact of low minY values.
        var sectionData = resource.fullSection;
        for (int sy = chunk.getMinY(); sy < min; sy += 16) {
            int index = chunk.getSectionIndex(sy);
            var section = chunk.getSection(index);
            sectionData.resetReaderIndex();
            section.getStates().read(sectionData);
            section.recalcBlockCounts();
        }

        // Here we fill the chunk section bh the block
        for (int sy = min; sy <= max; sy += 16) {
            int index = chunk.getSectionIndex(sy);
            var section = chunk.getSection(index);
            fillSection(sy, seaLevel, terrainData, chunk, section, filler);
        }
    }

    public static void primeHeightmaps(int seaLevel, ChunkAccess chunk, TerrainData terrainData, FillerBlock filler) {
        var solid = Blocks.STONE.defaultBlockState();
        var oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        var worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);

        for (int z = 0, i = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++, i++) {
                int floor = terrainData.getHeight(x, z);
                int surface = Math.max(seaLevel, floor);
                var surfaceBlock = filler.getState(surface, floor);
                oceanFloor.update(x, floor, z, solid);
                worldSurface.update(x, surface, z, surfaceBlock);
            }
        }
    }

    public static void buildStructureTerrain(ChunkAccess chunk, TerrainData terrainData, StructureManager structureFeatures) {
        int x = chunk.getPos().getMinBlockX();
        int z = chunk.getPos().getMinBlockZ();
        var operation = new StructureTerrain(chunk, structureFeatures);

        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                operation.modify(x + dx, z + dz, chunk, terrainData);
            }
        }
    }

    private static void fillSection(int startY, int seaLevel, TerrainData terrainData, ChunkAccess chunk, LevelChunkSection section, FillerBlock filler) {
        section.acquire();

        int sectionMaxY = startY + 16;
        for (int z = 0, i = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++, i++) {
                int solidY = terrainData.getHeight(x, z);
                int waterY = TerrainLevels.getWaterLevel(x, z, seaLevel, terrainData);

                int firstAirY = Math.max(solidY, waterY) + 1;
                int exclusiveMaxY = Math.min(sectionMaxY, firstAirY);

                for (int y = startY; y < exclusiveMaxY; y++) {
                    var state = filler.getState(y, solidY);

                    section.setBlockState(x, y & 15, z, state, false);

                    // ProtoChunk#addLight is gone -- the light engine reads block states directly now.
                    // Nothing is lost here regardless: the filler only ever places stone, water or air,
                    // none of which emit light.
                }
            }
        }

        section.release();
    }

    public interface FillerBlock {
        BlockState getState(int y, int height);
    }

    protected static BlockState getFiller(int y, int surfaceSolid) {
        return y <= surfaceSolid ? Blocks.STONE.defaultBlockState() : Blocks.WATER.defaultBlockState();
    }

    protected static int getHighestSection(TerrainData terrainData) {
        int y = Math.max(terrainData.getMaxBase(), terrainData.getMax());
        return (y >> 4) << 4;
    }

    protected static int getLowestSection(TerrainData terrainData) {
        int y = terrainData.getMin();
        return (y >> 4) << 4;
    }

    protected static ByteBuf createFullPalette() {
        var stateRegistry = Block.BLOCK_STATE_REGISTRY;
        // The registry moved into Strategy, and Strategy is a top-level class now.
        var container = new PalettedContainer<>(Blocks.STONE.defaultBlockState(), Strategy.createForBlockStates(stateRegistry));

        container.acquire();
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    container.getAndSetUnchecked(x, y, z, Blocks.STONE.defaultBlockState());
                }
            }
        }
        container.release();

        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        container.write(buffer);

        return buffer;
    }

    public static FriendlyByteBuf getFullSection() {
        return new FriendlyByteBuf(FULL_SECTION.get().copy());
    }
}
