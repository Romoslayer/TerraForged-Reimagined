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

import com.terraforged.mod.worldgen.terrain.StructureTerrain;
import com.terraforged.noise.util.Noise;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * How {@link DeepCaves} treats structures that shape the terrain around themselves. None of this exists in
 * original TerraForged, which did not carve caves around structures at all.
 *
 * <ul>
 *   <li><b>Buried, beard-adapted structures</b> (ancient cities: {@code beard_box}) get a cavern. Vanilla's
 *       beardifier clears the space around them; TerraForged's terrain has no density for it to act on.
 *       Under each piece the floor is that piece's ground, exactly, so nothing floats. The ceiling follows
 *       the pieces beneath it -- a few blocks over each, sloping down away from it -- so the cavern hugs the
 *       city rather than sitting under one roof at the height of its tallest building. Walls sit a few blocks
 *       out, and everything is roughened by vanilla's cheese-cave noise.
 *   <li><b>Everything else that adapts terrain</b> -- surface structures, and buried ones that encase
 *       themselves ({@code encapsulate}: trial chambers; {@code bury}) -- keeps its rock the way vanilla's
 *       beardifier does it: the pieces themselves are never carved, and around them solid density is added
 *       that fades out with distance, so nearby caves narrow away smoothly. A hard margin box, the previous
 *       approach, cut caves off in flat faces.
 * </ul>
 */
public final class StructureSpace {
    /** Reach of the added solid density around kept structures; vanilla's encapsulate reaches 12. */
    private static final float SURFACE_REACH = 8F;
    private static final float ENCASED_REACH = 12F;
    private static final double SOLID_STRENGTH = 0.8;

    /** Average reach of the cavern beyond the pieces, varied by WALL_VARIANCE. */
    private static final int CAVERN_MARGIN = 8;
    private static final float WALL_VARIANCE = 4F;
    /** Roof height above a piece, varied by CEILING_VARIANCE, falling ROOF_SLOPE blocks per block away. */
    private static final int CEILING_HEADROOM = 9;
    private static final float CEILING_VARIANCE = 4F;
    private static final float ROOF_SLOPE = 0.6F;
    /** Height of the mounds on the floor beyond the pieces. */
    private static final float FLOOR_MOUNDS = 4F;
    /** How much vanilla's cheese noise roughens the cavern's surfaces. */
    private static final double ROUGHNESS = 0.45;
    private static final int FOUNDATION_DEPTH = 4;
    /** Blocks of rock kept under a structure that does not shape the terrain itself. */
    private static final int FOOTING = 4;
    /** How far past the cavern the deep dark reaches, in blocks. */
    private static final int BIOME_MARGIN = 6;
    private static final int SEED = 90617;

    static final int NONE = Integer.MIN_VALUE;

    private record Piece(BoundingBox box, int ground) {}

    private record Kept(BoundingBox box, float reach) {}

    private final List<Kept> kept = new ArrayList<>();
    private final List<Piece> cavernPieces = new ArrayList<>();

    /** Per column, chunk-local index dz * 16 + dx: the highest block the cavern can reach, or NONE. */
    final int[] ceiling = new int[256];

    private final int[] standingGround = new int[256];
    private final int[] standingTop = new int[256];
    private final int[] nearbyFloor = new int[256];
    private final float[] localRoof = new float[256];
    private final float[] distance = new float[256];

    StructureSpace(ChunkAccess chunk, StructureManager structures) {
        var chunkPos = chunk.getPos();
        int cavernReach = CAVERN_MARGIN + (int) WALL_VARIANCE + 1;

        structures.startsForStructure(chunkPos, s -> true).forEach(start -> {
            var structure = start.getStructure();
            var adaptation = structure.terrainAdaptation();
            boolean buried = StructureTerrain.isBuried(structure);
            boolean cavern = buried
                    && (adaptation == TerrainAdjustment.BEARD_BOX || adaptation == TerrainAdjustment.BEARD_THIN);
            // Structures that do not adapt terrain (ruined portals, desert wells, ...) still must not be undermined:
            // they pick their height from the generator before any cave exists, so a cave carved under one later
            // leaves it standing on air. They keep their own box and a few blocks of footing, with no bias around.
            float reach = adaptation == TerrainAdjustment.NONE ? 0F : buried ? ENCASED_REACH : SURFACE_REACH;
            boolean shapesTerrain = adaptation != TerrainAdjustment.NONE;

            for (var piece : start.getPieces()) {
                if (cavern) {
                    if (!piece.isCloseToChunk(chunkPos, cavernReach)) continue;
                    int ground = piece.getBoundingBox().minY();
                    if (piece instanceof PoolElementStructurePiece element) ground += element.getGroundLevelDelta();
                    cavernPieces.add(new Piece(piece.getBoundingBox(), ground));
                } else if (piece.isCloseToChunk(chunkPos, Math.max(1, (int) reach))) {
                    var box = piece.getBoundingBox();
                    // Deep structures that shape no terrain are left to the caves, exactly as in
                    // protectionBoxes -- and for the same reason. This is the path that actually reaches a
                    // mineshaft: the caverns here are carved by DeepCaves, not by NoiseCaveCarver, so fixing
                    // only protectionBoxes left mineshaft corridors standing on their FOOTING plinth.
                    if (!shapesTerrain && isUnderground(chunk, box)) continue;

                    kept.add(new Kept(new BoundingBox(box.minX(), box.minY() - FOOTING, box.minZ(),
                            box.maxX(), box.maxY(), box.maxZ()), reach));
                }
            }
        });

        Arrays.fill(ceiling, NONE);
        Arrays.fill(standingGround, NONE);
        if (cavernPieces.isEmpty()) return;

        int startX = chunkPos.getMinBlockX();
        int startZ = chunkPos.getMinBlockZ();
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                computeColumn(dz * 16 + dx, startX + dx, startZ + dz, cavernReach);
            }
        }
    }

    private void computeColumn(int index, int x, int z, int reach) {
        int ground = NONE;
        int groundTop = NONE;
        int floor = Integer.MAX_VALUE;
        float roof = Float.NEGATIVE_INFINITY;
        float nearest = Float.MAX_VALUE;

        for (var piece : cavernPieces) {
            var box = piece.box;
            int dx = Math.max(0, Math.max(box.minX() - x, x - box.maxX()));
            int dz = Math.max(0, Math.max(box.minZ() - z, z - box.maxZ()));
            float d = (float) Math.sqrt(dx * dx + dz * dz);
            if (d > reach) continue;

            nearest = Math.min(nearest, d);
            floor = Math.min(floor, piece.ground);
            roof = Math.max(roof, box.maxY() + CEILING_HEADROOM - d * ROOF_SLOPE);
            // Where pieces stand on this column, the highest one's ground is the floor, so no piece's footing
            // is carved away from under it.
            if (d == 0) {
                if (piece.ground > ground) ground = piece.ground;
                groundTop = Math.max(groundTop, box.maxY());
            }
        }

        if (nearest == Float.MAX_VALUE) return;

        standingGround[index] = ground;
        standingTop[index] = groundTop;
        nearbyFloor[index] = floor;
        localRoof[index] = roof;
        distance[index] = nearest;
        ceiling[index] = (int) Math.ceil(roof + CEILING_VARIANCE) + 2;
    }

    /**
     * Below zero where the cavern is open. Each surface -- floor, walls, ceiling -- is a distance in blocks
     * scaled down, and the nearest one wins; the cheese noise then pushes them in and out.
     */
    double cavernDensity(int index, int x, int y, int z, double cheese) {
        if (ceiling[index] == NONE) return 1;

        int ground = standingGround[index];
        if (ground != NONE) {
            // A piece stands here: its own volume is always open, and its footing always kept.
            if (y < ground) return 1;
            if (y <= standingTop[index]) return -1;
        }

        float wallNoise = Noise.singleSimplex(x / 32F, z / 32F, SEED);
        float roofNoise = Noise.singleSimplex(x / 20F, z / 20F, SEED + 1);
        float floorNoise = Noise.singleSimplex(x / 16F, z / 16F, SEED + 2);

        float wall = CAVERN_MARGIN + wallNoise * WALL_VARIANCE;
        float edge = Math.min(1F, distance[index] / Math.max(1F, wall));

        double roof = localRoof[index] + roofNoise * CEILING_VARIANCE;
        // Mounds grow away from the pieces, so doorways and streets stay level with their surroundings.
        double floor = ground != NONE ? ground
                : nearbyFloor[index] + (1F + Math.max(0F, floorNoise)) * FLOOR_MOUNDS * edge;

        double roofTerm = (y - roof) / 4.0;
        double floorTerm = ground != NONE ? (floor - y) : (floor - y) / 3.0;
        double wallTerm = (distance[index] - wall) / 3.0;

        double density = Math.max(Math.max(roofTerm, floorTerm), wallTerm);
        return density + Math.max(-1, Math.min(1, cheese)) * ROUGHNESS;
    }

    /**
     * The piece boxes of every structure in this chunk, each with {@link #FOOTING} blocks of rock beneath it.
     * A structure picks its height from the generator before any cave is carved, so anything carved under one
     * later leaves it standing on air -- ruined portals on legs, most visibly.
     */
    /**
     * Depth below the surface past which a structure that does not shape the terrain is left unprotected.
     * Caves may then cut through it, as vanilla's do.
     */
    private static final int UNDERGROUND_DEPTH = 16;

    /**
     * Boxes the carvers must not cut into.
     *
     * <p>This exists because a structure picks its height from the chunk generator long before any cave is
     * carved, so carving underneath one leaves it standing on air -- ruined portals on legs, most visibly.
     *
     * <p>It deliberately does <b>not</b> cover structures that are already deep underground and do not shape
     * the terrain around them, because protecting those was worse than the problem. A mineshaft is the clear
     * case: vanilla lets caves cut straight through one, and the open corridor ends you meet in a cavern are
     * normal. Keeping its pieces whole instead left the corridors hanging across the cavern on a plinth of
     * rock, which reads as broken -- and TerraForged's caverns are far larger than vanilla's, so it showed up
     * badly. Structures that adapt the terrain (villages, ancient cities, strongholds) stay protected at any
     * depth: their surroundings were shaped for them, and a cave cutting through would undo that.
     */
    public static java.util.List<BoundingBox> protectionBoxes(ChunkAccess chunk, StructureManager structures) {
        var boxes = new ArrayList<BoundingBox>();
        var chunkPos = chunk.getPos();

        structures.startsForStructure(chunkPos, s -> true).forEach(start -> {
            boolean shapesTerrain = start.getStructure().terrainAdaptation() != TerrainAdjustment.NONE;

            for (var piece : start.getPieces()) {
                if (!piece.isCloseToChunk(chunkPos, 1)) continue;
                var box = piece.getBoundingBox();

                if (!shapesTerrain && isUnderground(chunk, box)) continue;

                boxes.add(new BoundingBox(box.minX(), box.minY() - FOOTING, box.minZ(),
                        box.maxX(), box.maxY(), box.maxZ()));
            }
        });

        return boxes;
    }

    /** Whether a piece sits clear of the surface, measured at the column nearest its centre in this chunk. */
    private static boolean isUnderground(ChunkAccess chunk, BoundingBox box) {
        int dx = Mth.clamp(((box.minX() + box.maxX()) >> 1) - chunk.getPos().getMinBlockX(), 0, 15);
        int dz = Mth.clamp(((box.minZ() + box.maxZ()) >> 1) - chunk.getPos().getMinBlockZ(), 0, 15);
        int surface = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, dx, dz);
        return box.maxY() < surface - UNDERGROUND_DEPTH;
    }

    /**
     * Makes the whole city area the deep dark, as vanilla has it: an ancient city only places where its start
     * point is {@code deep_dark}, but underground biomes here are a noise mask covering about a third of the
     * deep underground, so most of a city sat in whatever surface biome was above it. It read as Taiga (or
     * whatever) in F3, generated that biome's underground decoration -- pointed dripstone through the city --
     * and skipped the deep dark's own sculk, ambience and warden spawns.
     */
    void applyCityBiome(ChunkAccess chunk, Holder<Biome> deepDark) {
        if (cavernPieces.isEmpty()) return;

        for (int qz = 0; qz < 4; qz++) {
            for (int qx = 0; qx < 4; qx++) {
                int lo = Integer.MAX_VALUE;
                int hi = Integer.MIN_VALUE;

                for (int dz = qz * 4; dz < qz * 4 + 4; dz++) {
                    for (int dx = qx * 4; dx < qx * 4 + 4; dx++) {
                        int i = dz * 16 + dx;
                        if (ceiling[i] == NONE) continue;
                        int floor = standingGround[i] != NONE ? standingGround[i] : nearbyFloor[i];
                        lo = Math.min(lo, floor - BIOME_MARGIN);
                        hi = Math.max(hi, ceiling[i] + BIOME_MARGIN);
                    }
                }

                if (hi == Integer.MIN_VALUE) continue;

                for (int y = lo; y <= hi; y += 4) setBiome(chunk, qx, y, qz, deepDark);
                setBiome(chunk, qx, hi, qz, deepDark);
            }
        }
    }

    private static void setBiome(ChunkAccess chunk, int qx, int y, int qz, Holder<Biome> biome) {
        if (y < chunk.getMinY() || y > chunk.getMaxY()) return;
        var section = chunk.getSection(chunk.getSectionIndex(y));
        @SuppressWarnings("unchecked")
        var container = (PalettedContainer<Holder<Biome>>) section.getBiomes();
        container.set(qx, (y & 15) >> 2, qz, biome);
    }

    /**
     * Solid density added around structures that keep their rock, as vanilla's beardifier adds it: strongest
     * against the pieces, gone at the reach.
     */
    double solidBias(int x, int y, int z) {
        double bias = 0;
        for (int i = 0; i < kept.size(); i++) {
            var k = kept.get(i);
            var box = k.box;
            int dx = Math.max(0, Math.max(box.minX() - x, x - box.maxX()));
            int dy = Math.max(0, Math.max(box.minY() - y, y - box.maxY()));
            int dz = Math.max(0, Math.max(box.minZ() - z, z - box.maxZ()));
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d < k.reach) bias += SOLID_STRENGTH * (1.0 - d / k.reach);
        }
        return bias;
    }


    boolean hasKept() {
        return !kept.isEmpty();
    }

    /** Whether a block must stay: inside a kept structure's pieces, or in a cavern piece's foundation. */
    boolean isProtected(int index, int x, int y, int z) {
        int ground = standingGround[index];
        if (ground != NONE && y < ground && y >= ground - FOUNDATION_DEPTH) return true;

        for (int i = 0; i < kept.size(); i++) {
            if (kept.get(i).box.isInside(x, y, z)) return true;
        }
        return false;
    }

    /**
     * Carve the cavern around an ancient city, and stamp deep dark through it, <b>without</b> Deep Caves.
     *
     * <p>Both of those used to happen only inside {@link DeepCaves#carve}, so switching Deep Caves off
     * buried every ancient city in solid rock and left it outside its own biome. That coupling did not
     * matter while Deep Caves was the only thing carving below y=-32; it does now that the deep band is
     * filled by TerraForged's own cave layers instead.
     *
     * <p>{@link #cavernDensity} takes the cheese noise only as a roughness term
     * ({@code density + clamp(cheese, -1, 1) * ROUGHNESS}). Passing zero, as this first did, is not
     * harmless: it removes the roughening entirely and leaves the cavern as its bare geometry, which was
     * reported from play as square pillar-like walls where the cavern pinches between city pieces. The
     * wall, roof and floor noises above only vary in x and z, so the missing term is specifically the
     * one that varied with <b>height</b>. {@link #roughness} substitutes it.
     *
     * <p>Runs after the surface stage, as the deep half of Deep Caves did, so the cavern floor stays bare
     * rock instead of being dressed with grass.
     */
    /**
     * A stand-in for vanilla's cheese noise, used only to roughen the ancient-city cavern.
     *
     * <p>{@code Noise} here offers 2D simplex only, so three samples on the xz, zy and yx planes are
     * averaged: that varies in all three axes without the diagonal banding a single {@code (x+z, y)}
     * sample would give. Roughly unit range, which is what {@code cavernDensity} clamps it to.
     */
    private static float roughness(int x, int y, int z) {
        float a = Noise.singleSimplex(x / 14F, z / 14F, SEED + 3);
        float b = Noise.singleSimplex(z / 12F, y / 9F, SEED + 4);
        float c = Noise.singleSimplex(y / 9F, x / 12F, SEED + 5);
        return (a + b + c) / 3F;
    }

    public static void carveCity(ChunkAccess chunk, StructureManager structures, Holder<Biome> deepDark) {
        var space = new StructureSpace(chunk, structures);
        if (space.cavernPieces.isEmpty()) return;

        space.applyCityBiome(chunk, deepDark);

        var air = Blocks.AIR.defaultBlockState();
        var pos = new BlockPos.MutableBlockPos();
        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();
        int minY = chunk.getMinY();

        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int i = dz * 16 + dx;
                if (space.ceiling[i] == NONE) continue;

                int x = startX + dx;
                int z = startZ + dz;
                for (int y = Math.min(space.ceiling[i], chunk.getMaxY()); y > minY; y--) {
                    if (space.isProtected(i, x, y, z)) continue;
                    if (space.cavernDensity(i, x, y, z, roughness(x, y, z)) >= 0) continue;

                    var state = chunk.getBlockState(pos.set(dx, y, dz));
                    if (state.isAir()) continue;
                    if (!state.is(BlockTags.OVERWORLD_CARVER_REPLACEABLES)) continue;
                    if (!state.getFluidState().isEmpty()) continue;

                    chunk.setBlockState(pos, air, 0);
                }
            }
        }
    }
}
