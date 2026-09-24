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

package com.terraforged.mod.data;

import com.terraforged.mod.TerraForged;
import com.terraforged.mod.registry.lazy.LazyTag;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;

public interface ModTags {
    LazyTag<Biome> OVERWORLD = LazyTag.biome("overworld");

    /**
     * The blocks TerraForged's cave passes may carve away: natural terrain, not anything placed on it.
     *
     * <p>Until 26.2 this was vanilla's {@code #minecraft:overworld_carver_replaceables}. 26.3 deleted that
     * tag and turned vanilla's carvers around to carve everything except {@code #minecraft:uncarvable}
     * (bedrock alone), which would let the caves cut through ice, powder snow, clay and anything a mod
     * places. So TerraForged ships the 26.2 list as its own tag, unchanged, to keep its caves as they were.
     */
    TagKey<Block> CARVER_REPLACEABLES = TagKey.create(Registries.BLOCK, TerraForged.location("carver_replaceables"));

    /**
     * Biomes that belong underground rather than on the surface.
     *
     * <p>Minecraft has no tag for this -- {@code is_overworld} covers cave biomes too, and the
     * {@code BiomeCategory.UNDERGROUND} enum TerraForged used to test disappeared in 1.19.3. Without
     * some replacement, every cave biome in the registry is a candidate for surface placement, so
     * lush caves and the deep dark turn up in the open air.
     *
     * <p>The tag does double duty: biomes in it are excluded from surface selection, and they are
     * exactly the pool {@code CaveType.UNIQUE} draws from. That means a biome mod gets its cave
     * biomes placed underground by TerraForged simply by being listed here, and datapacks can
     * correct the list without a code change.
     */
    LazyTag<Biome> NON_SURFACE = LazyTag.biome("non_surface");

    /**
     * Biomes that only make sense in low, wet ground.
     *
     * <p>TerraForged picks land biomes purely by climate, and swamps are warm and wet, so a swamp
     * could be placed anywhere warm and wet — including a hilltop a hundred blocks above sea level,
     * where a swamp is just a dark forest with no water in it. Vanilla avoids this by keying swamps
     * to low continentalness and erosion rather than to climate alone.
     *
     * <p>{@code BiomeSampler} uses this to restrict these biomes to river valleys and coastal
     * lowland, substituting a drier biome from the same climate anywhere else.
     */
    LazyTag<Biome> WETLAND = LazyTag.biome("wetland");

    /**
     * Biomes that belong on steep ground.
     *
     * <p>The mirror of {@link #WETLAND}, and the same underlying problem: climate says nothing about
     * slope, so flat-country biomes were being placed on mountainsides. Plains and meadow on a
     * knife-edge ridge is not just odd to look at — those two are the only biomes vanilla allows
     * {@code village_plains} in, and a village cannot build on a ridge. Its pieces are projected
     * onto the heightmap individually and any that collide are dropped, which is why villages on
     * TerraForged mountains came out as a town centre and two houses.
     *
     * <p>{@code BiomeSampler} draws from these on steep ground instead. A climate with none of its
     * own borrows from the nearest climate that has some, so grassland hillsides become windswept
     * hills rather than staying plains.
     */
    LazyTag<Biome> HIGHLAND = LazyTag.biome("highland");

    // Trees
    LazyTag<Biome> COPSES = LazyTag.biome("trees/copses");
    LazyTag<Biome> HARDY = LazyTag.biome("trees/hardy");
    LazyTag<Biome> HARDY_SLOPES = LazyTag.biome("trees/hardy_slopes");
    LazyTag<Biome> PATCHY = LazyTag.biome("trees/patchy");
    LazyTag<Biome> RAINFOREST = LazyTag.biome("trees/rainforest");
    LazyTag<Biome> SPARSE = LazyTag.biome("trees/sparse");
    LazyTag<Biome> SPARSE_RAINFOREST = LazyTag.biome("trees/sparse_rainforest");
    LazyTag<Biome> TEMPERATE = LazyTag.biome("trees/temperate");
}
