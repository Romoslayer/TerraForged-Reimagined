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

import com.terraforged.mod.TerraForged;
import com.terraforged.mod.util.storage.ObjectPool;
import com.terraforged.mod.worldgen.Generator;
import com.terraforged.mod.worldgen.asset.NoiseCave;
import com.terraforged.noise.Module;
import com.terraforged.noise.Source;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.Arrays;

public class NoiseCaveGenerator {
    protected static final int POOL_SIZE = 32;
    protected static final float DENSITY = 0.05F;
    protected static final float BREACH_THRESHOLD = 0.7F;
    protected static final int GLOBAL_CAVE_REPS = 2;

    protected final NoiseCave[] caves;
    protected final Module uniqueCaveNoise;
    protected final Module caveBreachNoise;
    protected final ObjectPool<CarverChunk> pool;

    public NoiseCaveGenerator(HolderLookup.Provider access) {
        this.uniqueCaveNoise = createUniqueNoise(500, DENSITY);
        this.caveBreachNoise = createBreachNoise(300, BREACH_THRESHOLD);
        this.caves = createArray(access.lookupOrThrow(TerraForged.CAVES.get()).listElements().map(net.minecraft.core.Holder::value).toList());
        this.pool = new ObjectPool<>(POOL_SIZE, this::createCarverChunk);
    }

    public NoiseCaveGenerator(NoiseCaveGenerator other) {
        this.caves = other.caves;
        this.uniqueCaveNoise = createUniqueNoise(500, DENSITY);
        this.caveBreachNoise = createBreachNoise(300, BREACH_THRESHOLD);
        this.pool = new ObjectPool<>(POOL_SIZE, this::createCarverChunk);
    }

    public void carve(int seed, ChunkAccess chunk, Generator generator, net.minecraft.world.level.StructureManager structures) {
        var carver = pool.take().reset();
        carver.terrainData = generator.getChunkData(seed, chunk.getPos());
        carver.protection = StructureSpace.protectionBoxes(chunk, structures);
        carver.mask = caveBreachNoise;

        for (var config : caves) {
            carver.modifier = getModifier(config);

            NoiseCaveCarver.carve(seed, chunk, carver, generator, config);
        }

        pool.restore(carver);
    }

    private Module getModifier(NoiseCave cave) {
        return switch (cave.getType()) {
            case GLOBAL -> Source.ONE;
            case UNIQUE -> uniqueCaveNoise;
        };
    }

    private CarverChunk createCarverChunk() {
        return new CarverChunk();
    }

    private static Module createUniqueNoise(int scale, float density) {
        return new UniqueCaveDistributor(1286745, 1F / scale, 0.75F, density)
                .clamp(0.2, 1.0).map(0, 1)
                .warp(781624, 30, 1, 20);
    }

    private static Module createBreachNoise(int scale, float threshold) {
        return Source.simplexRidge(1567328, scale, 2).clamp(threshold * 0.8F, threshold).map(0, 1);
    }

    private static NoiseCave[] copyOf(long seed, NoiseCave[] other) {
        var array = Arrays.copyOf(other, other.length);
        for (int i = 0; i < array.length; i++) {
            array[i] = array[i].withSeed(seed);
        }
        return array;
    }

    private static NoiseCave[] createArray(Iterable<NoiseCave> source) {
        int length = 0;
        for (var cave : source) {
            length += getCount(cave);
        }

        var array = new NoiseCave[length];

        int i = 0;
        for (var cave : source) {
            int count = getCount(cave);
            for (int j = 0; j < count; j++) {
                array[i++] = cave.withSeed(j * 0xFA90C2L);
            }
        }

        return array;
    }

    private static int getCount(NoiseCave cave) {
        return cave.getType() == CaveType.GLOBAL ? GLOBAL_CAVE_REPS : 1;
    }
}
