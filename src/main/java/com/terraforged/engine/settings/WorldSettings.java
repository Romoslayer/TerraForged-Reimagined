/*
 * MIT License
 *
 * Copyright (c) 2020 TerraForged
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

package com.terraforged.engine.settings;

import com.terraforged.engine.serialization.annotation.Comment;
import com.terraforged.engine.serialization.annotation.Limit;
import com.terraforged.engine.serialization.annotation.Range;
import com.terraforged.engine.serialization.annotation.Serializable;
import com.terraforged.noise.func.DistanceFunc;
import com.terraforged.engine.world.continent.ContinentMode;
import com.terraforged.engine.world.continent.SpawnType;

@Serializable
public class WorldSettings {

    public static final int DEFAULT_CONTINENT_SCALE = 3000;

    public transient long seed = 0L;

    public Continent continent = new Continent();

    public ControlPoints controlPoints = new ControlPoints();

    public Properties properties = new Properties();

    @Serializable
    public static class Continent {

        @Comment("Controls the continent generator type")
        public ContinentMode continentMode = ContinentMode.MULTI;

        @Comment({
                "Controls how continent shapes are calculated.",
                "You may also need to adjust the transition points to ensure beaches etc still form."
        })
        public DistanceFunc continentShape = DistanceFunc.EUCLIDEAN;

        @Range(min = 100, max = 10000)
        @Comment({
                "Controls the size of continents.",
                "You may also need to adjust the transition points to ensure beaches etc still form."
        })
        public int continentScale = DEFAULT_CONTINENT_SCALE;
    }

    @Serializable
    public static class ControlPoints {

        // Defaults carried over from the engine's Heightmap, which is otherwise unused here.
        public static final float DEEP_OCEAN_VALUE = 0.1F;
        public static final float SHALLOW_OCEAN_VALUE = 0.25F;
        public static final float BEACH_VALUE = 0.327F;
        public static final float COAST_VALUE = 0.448F;
        public static final float INLAND_VALUE = 0.502F;

        @Range(min = 0F, max = 1F)
        @Limit(upper = "shallowOcean")
        @Comment({
                "Controls the point above which deep oceans transition into shallow oceans.",
                "The greater the gap to the shallow ocean slider, the more gradual the transition."
        })
        public float deepOcean = DEEP_OCEAN_VALUE;

        @Range(min = 0F, max = 1F)
        @Limit(lower = "deepOcean", upper = "beach")
        @Comment({
                "Controls the point above which shallow oceans transition into coastal terrain.",
                "The greater the gap to the coast slider, the more gradual the transition."
        })
        public float shallowOcean = SHALLOW_OCEAN_VALUE;

        @Range(min = 0F, max = 1F)
        @Limit(lower = "shallowOcean", upper = "coast")
        @Comment("Controls how much of the coastal terrain is assigned to beach biomes.")
        public float beach = BEACH_VALUE;

        @Range(min = 0F, max = 1F)
        @Limit(lower = "beach", upper = "inland")
        @Comment({
                "Controls the size of coastal regions and is also the point below",
                "which inland terrain transitions into oceans. Certain biomes such",
                "as Mushroom Fields only generate in coastal areas."
        })
        public float coast = COAST_VALUE;

        @Range(min = 0F, max = 1F)
        @Limit(lower = "coast")
        @Comment("Controls the overall transition from ocean to inland terrain.")
        public float inland = INLAND_VALUE;
    }

    @Serializable
    public static class Properties {

        @Comment("Set whether spawn should be close to x=0,z=0 or the centre of the nearest continent")
        public SpawnType spawnType = SpawnType.CONTINENT_CENTER;

        @Range(min = 0, max = 256)
        @Comment("Controls the world height")
        public int worldHeight = 256;

        @Range(min = 0, max = 255)
        @Comment("Controls the sea level")
        public int seaLevel = 63;
    }
}
