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

package com.terraforged.engine.world.terrain;

import com.terraforged.noise.util.NoiseUtil;

/**
 * A named kind of terrain.
 *
 * <p>Every terrain inherits its behaviour from a delegate. For the built-in terrains registered by
 * {@link TerrainType} that delegate is a {@link TerrainCategory}; for one created through
 * {@link TerrainType#getOrCreate(String, Terrain)} it is the parent {@code Terrain} it was derived
 * from, which is how a custom terrain such as {@code torridonian} picks up the behaviour of
 * {@link TerrainType#HILLS}.
 *
 * <p>The distinction matters to callers: {@link #getDelegate()} returning a {@code Terrain} is how
 * the mod tells a derived terrain from a built-in one.
 *
 * <p>Instances are compared by identity. Terrains are interned by {@link TerrainType}, so a given
 * name always resolves to the same object.
 */
public class Terrain implements ITerrain.Delegate {

    private final String name;
    private final float weight;
    private final ITerrain delegate;

    public Terrain(String name, ITerrain delegate) {
        this(name, 1F, delegate);
    }

    public Terrain(String name, double weight, ITerrain delegate) {
        this.name = name;
        this.weight = (float) weight;
        this.delegate = delegate;
    }

    @Override
    public ITerrain getDelegate() {
        return delegate;
    }

    public String getName() {
        return name;
    }

    public float getWeight() {
        return weight;
    }

    public float getMax(float noise) {
        return 1F;
    }

    public float getHue() {
        return NoiseUtil.valCoord2D(name.hashCode(), 0, 0);
    }

    @Override
    public String toString() {
        return name;
    }
}
