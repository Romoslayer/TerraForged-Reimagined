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

package com.terraforged.mod.worldgen.noise.continent.config;

import com.terraforged.mod.worldgen.noise.continent.cell.CellShape;
import com.terraforged.mod.worldgen.noise.continent.cell.CellSource;

public class ContinentConfig {
    public static final int CONTINENT_SAMPLE_SCALE = 400;

    public final Shape shape = new Shape();
    public final Noise noise = new Noise();
    public final Rivers rivers = new Rivers();
    public static class Shape {
        public int seed0;
        public int seed1;
        public int scale = CONTINENT_SAMPLE_SCALE;
        public float jitter = 0.75f;
        public float threshold = 0.525f;

        /** Continent Type SINGLE: only the continent nearest the world origin is land. */
        public boolean singleContinent;

        /**
         * Spawn Type CONTINENT_CENTER: shift the world so its origin sits on a continent centre, which is
         * where new players spawn. False is WORLD_ORIGIN -- no shift. True is what every world did before.
         */
        public boolean centreOnContinent = true;
        public float baseFalloffMin = 0.01f;
        public float baseFalloffMax = 0.25f;
        public CellShape cellShape = CellShape.SQUARE;
        public CellSource cellSource = CellSource.PERLIN;
    }

    public static class Noise {
        public float baseNoiseFalloff = 1.5f;
        public float continentNoiseFalloff = 1.0f;
    }

    public static class Rivers {
        public int seed = 0;
        public float lakeDensity = 0.75f;

        /** Share of river sources that produce a river. 1 keeps every one, as before this existed. */
        public float riverDensity = 1f;

        /** Lake size multipliers, applied to the lake's own random size. 1 and 1 leave lakes unchanged. */
        public float lakeSizeMin = 1f;
        public float lakeSizeMax = 1f;

        /** Profile for headwater rivers. Identical to {@link #rivers} by default. */
        public final RiverConfig branchRivers = new RiverConfig();

        /** Whether branch rivers differ from main ones, so the extra classification is only paid when used. */
        public boolean branchRiversDiffer;
        public final RiverConfig rivers = new RiverConfig();
        public final RiverConfig lakes = RiverConfig.lake();
    }
}
