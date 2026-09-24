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

package com.terraforged.engine.world;

import com.terraforged.engine.Seed;
import com.terraforged.engine.settings.Settings;
import com.terraforged.engine.world.heightmap.Levels;

/**
 * Carries the settings and the seed sequence used to build a world's noise modules.
 *
 * <p>The engine's original version also owned a terrain provider, a world-generator factory and a
 * tile cache. TerraForged 0.3.x moved all of that into the mod itself (see
 * {@code com.terraforged.mod.worldgen.noise}), and uses this type purely as a
 * {@code (seed, settings)} holder, so the rest is gone.
 *
 * <p>Note that {@link #seed} is a deterministic counter used to derive distinct sub-seeds for each
 * noise module as the generator is assembled — it is not the world seed. The world seed is threaded
 * separately through {@code Module#getValue(int seed, float x, float y)} at sample time.
 */
public class GeneratorContext {

    public final Seed seed;
    public final Levels levels;
    public final Settings settings;

    public GeneratorContext(Settings settings) {
        this.settings = settings;
        this.seed = new Seed(settings.world.seed);
        this.levels = new Levels(settings.world);
    }

    private GeneratorContext(GeneratorContext src) {
        this.settings = src.settings;
        this.levels = src.levels;
        this.seed = new Seed(src.seed.get());
    }

    public GeneratorContext copy() {
        return new GeneratorContext(this);
    }
}
