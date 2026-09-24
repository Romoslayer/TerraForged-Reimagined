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

package com.terraforged.mod.worldgen.settings;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

import java.util.ArrayList;

/**
 * Applies the Structures page to the structure state the world is generated with.
 *
 * <p>26.2 decides every structure position from the chunk generator's structure state — generation,
 * {@code /locate} and structure checks all read the same list of structure sets — so replacing sets
 * in that one list changes structures everywhere, modded ones included, with nothing else to patch.
 *
 * <p><b>Limitation:</b> a structure's exclusion zone names another structure set by its registry
 * holder (pillager outposts avoid villages, for instance) and reads that holder's placement, which is
 * the original. So an outpost keeps avoiding villages at their <em>original</em> spacing even if the
 * village spacing is edited. Rebuilding exclusion zones would mean replacing registry holders, which is
 * far more invasive than the effect warrants.
 */
public final class StructureOverrides {
    private StructureOverrides() {}

    /**
     * @return the state to use: vanilla's own when nothing is overridden or nothing actually differs,
     *         so an untouched world gets exactly the structures it always did
     */
    public static ChunkGeneratorStructureState createState(HolderLookup<StructureSet> structureSets,
                                                           RandomState randomState,
                                                           long levelSeed,
                                                           BiomeSource biomeSource,
                                                           TerraSettings.Structures config) {
        var base = ChunkGeneratorStructureState.createForNormal(randomState, levelSeed, biomeSource, structureSets);
        if (config.spread.isEmpty() && config.rings.isEmpty()) return base;

        var sets = new ArrayList<Holder<StructureSet>>();
        boolean changed = false;
        int replaced = 0, disabled = 0;

        for (var holder : base.possibleStructureSets()) {
            String id = holder.unwrapKey().map(key -> key.identifier().toString()).orElse(null);
            var set = holder.value();
            var placement = set.placement();

            if (id != null && placement instanceof RandomSpreadStructurePlacement spread && config.spread.containsKey(id)) {
                var entry = config.spread.get(id);
                if (entry.disabled) {
                    changed = true;
                    disabled++;
                    continue;
                }

                // Vanilla's codec rejects separation >= spacing, but a constructor call does not, and
                // placement then asks for a random int in (spacing - separation) and crashes chunk
                // generation. The sliders move independently, so this has to be enforced here.
                int spacing = Math.max(1, entry.spacing);
                int separation = Math.max(0, Math.min(entry.separation, spacing - 1));

                if (spacing == spread.spacing() && separation == spread.separation() && entry.salt == spread.salt()) {
                    sets.add(holder);
                    continue;
                }

                var replacement = new RandomSpreadStructurePlacement(spread.locateOffset(),
                        spread.frequencyReductionMethod(), spread.frequency(), entry.salt, spread.exclusionZone(),
                        spacing, separation, spread.spreadType());
                sets.add(Holder.direct(new StructureSet(set.structures(), replacement)));
                changed = true;
                replaced++;
                continue;
            }

            if (id != null && placement instanceof ConcentricRingsStructurePlacement rings && config.rings.containsKey(id)) {
                var entry = config.rings.get(id);
                if (entry.disabled) {
                    changed = true;
                    disabled++;
                    continue;
                }

                int distance = Math.max(0, entry.distance);
                int spreadCount = Math.max(1, entry.spread);
                int count = Math.max(1, entry.count);

                if (distance == rings.distance() && spreadCount == rings.spread() && count == rings.count()
                        && entry.salt == rings.salt() && entry.constrainToBiomes) {
                    sets.add(holder);
                    continue;
                }

                // Constrain To Biomes off: an empty preferred set, so ring positions are used where they
                // fall instead of being pulled toward a nearby preferred biome.
                var preferred = entry.constrainToBiomes ? rings.preferredBiomes()
                        : net.minecraft.core.HolderSet.<net.minecraft.world.level.biome.Biome>empty();

                var replacement = new ConcentricRingsStructurePlacement(rings.locateOffset(),
                        rings.frequencyReductionMethod(), rings.frequency(), entry.salt, rings.exclusionZone(),
                        distance, spreadCount, count, preferred);
                sets.add(Holder.direct(new StructureSet(set.structures(), replacement)));
                changed = true;
                replaced++;
                continue;
            }

            sets.add(holder);
        }

        if (!changed) return base;

        com.terraforged.mod.TerraForged.LOG.info("Structure overrides: {} structure set(s) re-spaced, {} disabled", replaced, disabled);

        return new ChunkGeneratorStructureState(randomState, biomeSource, levelSeed, levelSeed, java.util.List.copyOf(sets));
    }
}
