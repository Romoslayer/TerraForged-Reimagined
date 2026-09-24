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
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Vanilla's 1.18+ underground: cheese caverns, spaghetti and noodle caves, carved into TerraForged's terrain
 * all the way down to bedrock.
 *
 * <p>TerraForged's own caves are a handful of 2D noise layers, the deepest bottoming out around y=-32, so
 * below that the world was solid. Vanilla builds these caves inside its terrain density function; here the
 * same density functions are evaluated on their own and anything they mark as open is carved out of the
 * freshly filled terrain.
 *
 * <p><b>When:</b> straight after the terrain is filled, before surface rules -- the same order vanilla's
 * density gives. Carving afterwards (the vanilla carver stage, where this first ran) cut through grass, dirt
 * and snow that had already been placed: entrances were left with stray surface blocks, bare stone floors,
 * and shaved strips where the placed surface sat a block off the terrain height. Now the surface stage finds
 * the carved ground through the heightmaps (a proto-chunk keeps its worldgen heightmaps current as blocks
 * change) and dresses entrance floors like any other ground.
 *
 * <p>Vanilla's formula, from {@code NoiseRouterData#overworld} and {@code #underground}, is
 * <pre>
 *   sloped &lt; 1.5625 : min(sloped, 5 * entrances)                       -- near the surface
 *   otherwise        : min(cheese + layers + topSlide, entrances, spaghetti2d + roughness)
 *   then             : min(that, noodle), with a slide to solid over the bottom 24 blocks
 * </pre>
 * where {@code sloped} is vanilla's terrain density. TerraForged has no such density, so it is replaced by
 * depth below the surface -- 0.125 per block, which is about what vanilla's gives on ordinary ground: fully
 * open caverns start some 19 blocks down, and only cave entrances reach higher.
 *
 * <p><b>No pillars.</b> Vanilla also keeps pillars standing in its caverns. Its pillar noise is stretched
 * vertically so a pillar always spans floor to ceiling; in TerraForged's much taller caverns they came out
 * as thick, frequent columns that did not suit the terrain, and were removed.
 */
public final class DeepCaves {
    private static final int CELL_WIDTH = 4;
    private static final int CELL_HEIGHT = 8;
    private static final int CORNERS = 16 / CELL_WIDTH + 1;

    private static final double SLOPE_PER_BLOCK = 0.125;
    /**
     * How much the ground resists being opened by the entrance noise, at the surface, fading to nothing
     * { ENTRANCE_ROOF / SLOPE_PER_BLOCK} blocks down. At this value an entrance has to reach -0.31
     * to break the surface, where before any value below zero did. Lower it to let more caves open.
     */
    private static final double ENTRANCE_ROOF = 1.5625;
    private static final double SURFACE_ZONE = 1.5625;
    private static final double BOTTOM_TARGET = 0.1171875;
    private static final int BOTTOM_SLIDE = 24;
    /** Vanilla's lava level: open cave below this fills with lava, as vanilla's aquifers do. */
    private static final int LAVA_LEVEL = -54;
    /** Columns under or beside water keep this much rock above any cave, so nothing drains into one. */
    private static final int WET_MARGIN = 16;
    /** Height above sea level over which the sea's share of that margin fades to nothing. */
    private static final int SEA_FADE = 10;
    /**
     * Depth below the terrain surface where carving splits in two. The part above is carved before surface
     * rules, so entrance floors are dressed; the part below after them, so deep cave floors stay bare rock.
     * The surface rules cannot tell the two apart themselves: TerraForged feeds them the chunk's lowest
     * height as the preliminary surface, so inside a mountain every cave floor above the valley counted as
     * surface and got grass, snow or ice. 13 covers vanilla's entrance zone (sloped 1.5625 at 0.125 per block).
     */
    private static final int SPLIT_DEPTH = 13;
    /** Depths over which the entrance formula gives way to the full cave formula. */
    private static final int BLEND_START = 6;
    private static final int BLEND_END = 13;
    /** Thickest cap over a freshly opened entrance that is removed rather than left floating. */
    private static final int MAX_CAP = 4;
    /** Thickest roof cleared in the deeper pass, and the opening it must sit over. */
    private static final int CAP_OVER_ANY = 5;
    private static final int MIN_OPENING = 3;

    /**
     * Vertical squash of {@code CAVE_LAYER} in the cheese term. <b>A deliberate divergence from vanilla,
     * which uses 8.0.</b>
     *
     * <p>At 8.0 the noise is compressed eightfold vertically, so its contours are nearly horizontal --
     * and wherever the cheese noise has saturated, a cavern's ceiling lies on one of those contours, which
     * is where the reported flat cave roofs came from. It is also the only lever found that separates
     * cavern <i>size</i> from ceiling <i>flatness</i>; the layer weight and the xz scale each move both,
     * because they are really the same dial. Measured, one build, same box: 8.0 gave 4.9% flat ceilings
     * and 5.4% of columns with a 40+ block void; 2.0 gives 3.4% and 7.5%.
     */
    private static final double CHEESE_LAYER_Y = 2.0;

    private static final int CHEESE = 0, ENTRANCES = 1, NOODLE = 2, PARTS = 3;

    // Vanilla's cave terms, by id: NoiseRouterData's keys for them are private.
    private static final ResourceKey<DensityFunction> ENTRANCES_KEY = ResourceKey.create(Registries.DENSITY_FUNCTION,
            Identifier.withDefaultNamespace("overworld/caves/entrances"));
    private static final ResourceKey<DensityFunction> NOODLE_KEY = ResourceKey.create(Registries.DENSITY_FUNCTION,
            Identifier.withDefaultNamespace("overworld/caves/noodle"));

    private final long seed;
    private final int seaLevel;
    private final DensitySampler[] parts;
    private final net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> deepDark;

    private DeepCaves(long seed, int seaLevel, DensitySampler[] parts, net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> deepDark) {
        this.seed = seed;
        this.seaLevel = seaLevel;
        this.parts = parts;
        this.deepDark = deepDark;
    }

    /** A per-column float read out of this chunk's terrain data. */
    @FunctionalInterface
    public interface FloatGrid {
        float get(int dx, int dz);
    }

    public long seed() {

        return seed;
    }

    /**
     * Wires the density functions to the level seed. Up to 26.2 the only public route to that wiring was
     * {@code RandomState}'s noise router, so the parts had to travel in router slots. 26.3's {@code RandomState}
     * compiles any density function against the seed directly, with each noise still seeded from its own key.
     */
    public static DeepCaves create(HolderLookup.Provider registries, NoiseGeneratorSettings overworld, long seed) {
        var functions = registries.lookupOrThrow(Registries.DENSITY_FUNCTION);
        var noises = registries.lookupOrThrow(Registries.NOISE);

        // Vanilla's cheese term, verbatim except for CAVE_LAYER's vertical scale -- see CHEESE_LAYER_Y.
        // noise(h, d) is noise(h, 1.0, d), so only the y scale differs from NoiseRouterData.
        var layers = DensityFunctions.mul(DensityFunctions.constant(4.0F),
                DensityFunctions.noise(noises.getOrThrow(Noises.CAVE_LAYER), 1.0, CHEESE_LAYER_Y).square());
        var cheese = DensityFunctions.add(layers, DensityFunctions.add(DensityFunctions.constant(0.27F),
                DensityFunctions.noise(noises.getOrThrow(Noises.CAVE_CHEESE), 0.6666666666666666)).clamp(-1.0F, 1.0F));
        var entrances = get(functions, ENTRANCES_KEY);
        var noodle = get(functions, NOODLE_KEY);
        // No spaghetti_2d term: see density(). Every noise is seeded from its own key, so leaving one out
        // does not shift any other.

        var state = RandomState.create(noises, seed, overworld);
        // Order must match CHEESE, ENTRANCES, NOODLE.
        return new DeepCaves(seed, overworld.seaLevel(), new DensitySampler[]{
                state.getSampler(cheese), state.getSampler(entrances), state.getSampler(noodle)},
                registries.lookupOrThrow(Registries.BIOME).getOrThrow(net.minecraft.world.level.biome.Biomes.DEEP_DARK));
    }

    private static DensityFunction get(HolderLookup.RegistryLookup<DensityFunction> functions,
                                       ResourceKey<DensityFunction> key) {
        return new DensityFunctions.HolderHolder(functions.getOrThrow(key));
    }

    /**
     * @param surfaceAt     the terrain height at any block x/z, from TerraForged's noise. It varies smoothly and
     *                      is available beyond this chunk, so it shapes the cell grid; the heightmap jumps
     *                      wherever a cliff steps, and using it there gave neighbouring columns different caves.
     * @param columnSurface this chunk's exact terrain height at a local x/z, the height the terrain was filled
     *                      to. The near-surface zone is decided against it per block, so entrances meet the
     *                      real ground; the noise height can sit 20+ blocks off it on mountains.
     */
    public void carve(ChunkAccess chunk, net.minecraft.world.level.StructureManager structures,
                      java.util.function.IntBinaryOperator surfaceAt, java.util.function.IntBinaryOperator columnSurface,
                      FloatGrid riverAt, boolean nearSurface) {
        var space = new StructureSpace(chunk, structures);
        // Once per chunk, in the deeper pass: it runs after TerraForged's own caves, which write their cave
        // biomes into the same sections.
        if (!nearSurface) space.applyCityBiome(chunk, deepDark);
        int minY = chunk.getMinY();
        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();

        var surfaces = new int[256];
        var margins = new int[256];
        int maxSurface = minY;
        var pos = new BlockPos.MutableBlockPos();

        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                // getHeight is the topmost solid block itself.
                int surface = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, dx, dz);
                surfaces[dz * 16 + dx] = surface;
                maxSurface = Math.max(maxSurface, surface);
            }
        }

        // How much rock each column keeps above any cave, so water cannot drain into one.
        //
        // This used to be a boolean: the full 16 blocks for a column with fluid over it or a neighbour, none
        // one block further out. That left a vertical rock face along every shoreline and a dead flat ceiling
        // behind it wherever a river ran into a cave -- the reported hard walls. It also only looked at fluid
        // inside this chunk, so it stopped at chunk borders.
        //
        // The margin now fades. Both inputs are smooth fields that carry on across chunk borders: the river
        // mask from the terrain data, which is 0 in a river bed and rises to 1 clear of one, and the column's
        // height above sea level. A column with fluid actually resting on it still takes the full margin, but
        // by then one of the two fields is saturated anyway, so it is a floor rather than a step.
        // Fluid resting on a column, and on its immediate neighbours, still forces the full margin: that is
        // what the old rule protected and nothing here should weaken it. The smooth fields only ever add
        // margin further out, so this is strictly more conservative than before, not less.
        var flooded = new boolean[256];
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                pos.set(dx, surfaces[dz * 16 + dx] + 1, dz);
                if (chunk.getFluidState(pos).isEmpty()) continue;

                for (int oz = -1; oz <= 1; oz++) {
                    for (int ox = -1; ox <= 1; ox++) {
                        int nx = dx + ox, nz = dz + oz;
                        if (nx >= 0 && nx < 16 && nz >= 0 && nz < 16) flooded[nz * 16 + nx] = true;
                    }
                }
            }
        }

        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int i = dz * 16 + dx;
                int surface = surfaces[i];

                float river = 1F - riverAt.get(dx, dz);
                float sea = (seaLevel + SEA_FADE - surface) / (float) SEA_FADE;
                float wetness = flooded[i] ? 1F : Math.max(river, sea);

                wetness = Math.max(0F, Math.min(1F, wetness));
                margins[i] = Math.round((float) (WET_MARGIN * smoothstep(wetness)));
            }
        }

        // As vanilla does: the parts are combined at each cell corner, and only the result is interpolated.
        int cellsY = Math.floorDiv(maxSurface - minY, CELL_HEIGHT) + 1;
        var openGrid = new double[CORNERS][CORNERS][cellsY + 1];
        var cheeseGrid = new double[CORNERS][CORNERS][cellsY + 1];
        var entranceGrid = new double[CORNERS][CORNERS][cellsY + 1];
        var values = new double[PARTS];

        for (int cz = 0; cz < CORNERS; cz++) {
            for (int cx = 0; cx < CORNERS; cx++) {
                int x = startX + cx * CELL_WIDTH;
                int z = startZ + cz * CELL_WIDTH;
                int cornerSurface = surfaceAt.applyAsInt(x, z);

                for (int cy = 0; cy <= cellsY; cy++) {
                    int y = minY + cy * CELL_HEIGHT;
                    for (int p = 0; p < PARTS; p++) {
                        values[p] = parts[p].sampleValue(SamplerContext.EMPTY_UNCACHED, x, y, z);
                    }

                    double slide = Math.min(1.0, (y - minY) / (double) BOTTOM_SLIDE);
                    openGrid[cx][cz][cy] = BOTTOM_TARGET + slide * (density(values, cornerSurface - y) - BOTTOM_TARGET);
                    cheeseGrid[cx][cz][cy] = values[CHEESE];
                    entranceGrid[cx][cz][cy] = values[ENTRANCES];
                }
            }
        }

        var air = Blocks.AIR.defaultBlockState();
        var lava = Blocks.LAVA.defaultBlockState();

        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int i = dz * 16 + dx;
                int x = startX + dx;
                int z = startZ + dz;
                int surface = surfaces[i];
                int margin = margins[i];
                int top = surface - margin;

                int cavernTop = space.ceiling[i];
                if (cavernTop != StructureSpace.NONE) top = Math.max(top, Math.min(surface - margin, cavernTop));
                top = Math.min(top, maxSurface);

                int realSurface = columnSurface.applyAsInt(dx, dz);
                int bottom = minY;
                if (nearSurface) {
                    bottom = Math.max(minY, realSurface - SPLIT_DEPTH);
                } else {
                    top = Math.min(top, realSurface - SPLIT_DEPTH);
                }

                boolean carvedAbove = false;
                int topCarved = Integer.MIN_VALUE;
                for (int y = top; y > bottom; y--) {
                    int ry = y - minY;

                    boolean cavern = cavernTop != StructureSpace.NONE
                            && space.cavernDensity(i, x, y, z, sample(cheeseGrid, dx, ry, dz)) < 0;
                    boolean keep = space.isProtected(i, x, y, z);

                    // Depth is measured against this column's own terrain height, not the grid's smoothed one.
                    double density = blended(openGrid, entranceGrid, dx, dz, ry, realSurface - y);
                    if (space.hasKept() && density < 0) density += space.solidBias(x, y, z);

                    if (keep || (density >= 0 && !cavern)) {
                        carvedAbove = false;
                        continue;
                    }

                    pos.set(dx, y, dz);
                    var state = chunk.getBlockState(pos);
                    if (!state.is(com.terraforged.mod.data.ModTags.CARVER_REPLACEABLES) || !state.getFluidState().isEmpty()) {
                        carvedAbove = false;
                        continue;
                    }

                    // Never open a block with fluid resting on it.
                    if (!carvedAbove && !chunk.getFluidState(pos.move(0, 1, 0)).isEmpty()) continue;
                    pos.set(dx, y, dz);

                    chunk.setBlockState(pos, y < LAVA_LEVEL ? lava : air, 0);
                    carvedAbove = true;
                    topCarved = Math.max(topCarved, y);
                }

                // In the deeper pass the chunk is final -- TerraForged's own caves have carved too -- so any thin roof
                // left over a real opening at the top of a column is cleared here. Bounded deliberately: at most
                // CAP_OVER_ANY blocks thick, over at least MIN_OPENING blocks of air, never under fluid and never
                // on structure rock, so it cannot eat into terrain that has no cave under it.
                if (!nearSurface && margins[i] == 0 && surface - MIN_OPENING - CAP_OVER_ANY > minY
                        && chunk.getFluidState(pos.set(dx, surface + 1, dz)).isEmpty()) {
                    int cap = 0;
                    while (cap < CAP_OVER_ANY && !chunk.getBlockState(pos.set(dx, surface - cap, dz)).isAir()) cap++;

                    int gap = 0;
                    while (gap < MIN_OPENING && chunk.getBlockState(pos.set(dx, surface - cap - gap, dz)).isAir()) gap++;

                    // Only ever lift a roof off air that this generator opened. TerraForged's own carver leaves
                    // sealed pockets close under the surface; taking the roof off one turns it into a wide pit
                    // with a flat floor and no way on, which is what the reported surface pits were. The density
                    // under the cap tells the two apart without keeping any state: negative means Deep Caves
                    // would have opened that block, so the space below is ours.
                    boolean ours = false;
                    if (cap > 0 && cap < CAP_OVER_ANY && gap == MIN_OPENING) {
                        int under = surface - cap;
                        double below = blended(openGrid, entranceGrid, dx, dz, under - minY, realSurface - under);
                        if (space.hasKept() && below < 0) below += space.solidBias(x, under, z);
                        ours = below < 0;
                    }

                    if (ours) {
                        boolean removable = true;
                        for (int c = 0; c < cap && removable; c++) {
                            var state = chunk.getBlockState(pos.set(dx, surface - c, dz));
                            removable = state.is(com.terraforged.mod.data.ModTags.CARVER_REPLACEABLES) && state.getFluidState().isEmpty()
                                    && !space.isProtected(i, x, surface - c, z);
                        }
                        if (removable) {
                            for (int c = 0; c < cap; c++) chunk.setBlockState(pos.set(dx, surface - c, dz), air, 0);
                        }
                    }
                }

                // An entrance whose top edge sits a few blocks under the ground leaves a cap over it, which reads as
                // loose floating blocks once the surface is dressed. A cap is only removed when this same pass
                // opened the block directly beneath it, so nothing it did not carve under is touched.
                if (nearSurface && top == surface && topCarved != Integer.MIN_VALUE
                        && chunk.getFluidState(pos.set(dx, surface + 1, dz)).isEmpty()) {
                    int cap = surface - topCarved;
                    if (cap >= 1 && cap <= MAX_CAP) {
                        boolean removable = true;
                        for (int y = surface; y > topCarved && removable; y--) {
                            var state = chunk.getBlockState(pos.set(dx, y, dz));
                            removable = state.is(com.terraforged.mod.data.ModTags.CARVER_REPLACEABLES) && state.getFluidState().isEmpty()
                                    && !space.isProtected(i, x, y, z);
                        }
                        if (removable) {
                            for (int y = surface; y > topCarved; y--) chunk.setBlockState(pos.set(dx, y, dz), air, 0);
                        }
                    }
                }
            }
        }
    }

    /**
     * The carving density at one block: vanilla's near-surface entrance formula at the top, the full cave
     * formula below, blended between {@link #BLEND_START} and {@link #BLEND_END}. The two are measured against
     * different surfaces -- the cell grid measures depth from a smoothed noise height that sits well off the
     * real one on mountains -- so switching straight between them left a slab hanging wherever the deep side
     * opened and the shallow side did not.
     */
    private static double blended(double[][][] openGrid, double[][][] entranceGrid, int dx, int dz, int ry, int depth) {
        depth = Math.max(0, depth);
        // The surface term has to RESIST opening, and inside a min() it cannot: min(positive, negative)
        // is always the negative one, so depth * SLOPE_PER_BLOCK never closed anything and any dip of
        // the entrance noise below zero stripped the ground, however shallow. Measured at the reported
        // pit: entrances = -0.056 five blocks under the surface, times five = -0.28, and 20 blocks of
        // hillside came out. It is added as a roof instead, strongest at the surface and gone by the
        // bottom of the entrance zone, so a strong entrance still breaks through and a marginal one no
        // longer does.
        double noise = 5.0 * sample(entranceGrid, dx, ry, dz);
        double entrance = noise + Math.max(0.0, ENTRANCE_ROOF - depth * SLOPE_PER_BLOCK);
        if (depth <= BLEND_START) return entrance;

        double open = sample(openGrid, dx, ry, dz);
        if (depth >= BLEND_END) return open;

        double t = smoothstep((depth - BLEND_START) / (double) (BLEND_END - BLEND_START));
        return entrance + t * (open - entrance);
    }

    private static double smoothstep(double t) {

        t = Math.max(0.0, Math.min(1.0, t));
        return t * t * (3 - 2 * t);
    }

    /**
     * The underground density at a cell corner; the surface zone is decided per block in carve.
     *
     * <p>Vanilla's formula is {@code min(cheese + topSlide, entrances, spaghetti_2d + roughness, noodle)}.
     * This drops the {@code spaghetti_2d} term, <b>deliberately</b>. It is the one term that produced the
     * reported flat cave roofs, and a per-term density probe named it: at a flat ceiling the minimum was
     * spaghetti, crossing zero at y=-15.9 -- the ceiling to the block. Its tube centre is
     * {@code y = 64 * elevation_noise} from a 2D {@code flat_cache}, so every tube has a level roof by
     * construction. Isolated on one build it was 6.2% flat ceilings with the term, 3.4% without.
     *
     * <p>Dropping it costs nothing else: the giant caverns are {@code cheese}, and the winding tunnels
     * that connect the surface to the deep caves are {@code noodle} plus the spaghetti-3D structure that
     * sits <i>inside</i> {@code entrances} (vanilla's entrances is
     * {@code min(cave_entrance_noise, spaghetti_roughness + spaghetti_3d)}).
     */
    private static double density(double[] v, int depth) {
        double sloped = Math.max(0, depth) * SLOPE_PER_BLOCK;
        double topSlide = Math.max(0.0, Math.min(0.5, 1.5 - 0.64 * sloped));
        double open = Math.min(v[CHEESE] + topSlide, v[ENTRANCES]);
        return Math.min(open, v[NOODLE]);
    }

    /** Trilinear interpolation within the cell grid. */
    private static double sample(double[][][] grid, int dx, int ry, int dz) {
        int cx = dx / CELL_WIDTH, cz = dz / CELL_WIDTH, cy = ry / CELL_HEIGHT;
        double fx = (dx % CELL_WIDTH) / (double) CELL_WIDTH;
        double fz = (dz % CELL_WIDTH) / (double) CELL_WIDTH;
        double fy = (ry % CELL_HEIGHT) / (double) CELL_HEIGHT;

        double c00 = lerp(fy, grid[cx][cz][cy], grid[cx][cz][cy + 1]);
        double c10 = lerp(fy, grid[cx + 1][cz][cy], grid[cx + 1][cz][cy + 1]);
        double c01 = lerp(fy, grid[cx][cz + 1][cy], grid[cx][cz + 1][cy + 1]);
        double c11 = lerp(fy, grid[cx + 1][cz + 1][cy], grid[cx + 1][cz + 1][cy + 1]);
        return lerp(fz, lerp(fx, c00, c10), lerp(fx, c01, c11));
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }
}
