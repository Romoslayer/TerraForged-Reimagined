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

package com.terraforged.mod.client.ui.preview;

/**
 * What the preview map colours by. The names match 1.16.5's so the button reads the same.
 *
 * <p>{@link #ELEVATION} is new: 1.16.5's map was flat colour, and the World and Terrain pages change
 * the shape of the ground far more than they change any biome, which a flat map cannot show.
 */
public enum RenderMode {
    BIOME_TYPE,
    ELEVATION,
    TRANSITION_POINTS,
    TEMPERATURE,
    MOISTURE,
    BIOME,
    TERRAIN_REGION;

    public RenderMode next() {
        var values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
