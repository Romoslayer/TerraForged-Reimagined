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

package com.terraforged.engine.util;

/**
 * A small, allocation-free, reseedable random source.
 *
 * <p>The erosion filter keeps one of these per worker thread and reseeds it for every chunk and
 * every droplet iteration, so it is reseeded far more often than it is drawn from. That rules out
 * {@link java.util.Random} — the point here is that {@link #seed(long, long)} is cheap and that two
 * nearby seed pairs still produce well-separated streams.
 *
 * <p>Implementation is SplitMix64: the seed pair is mixed into 64 bits of state, and each draw
 * advances by the golden-gamma constant and applies the SplitMix64 finalizer.
 *
 * <p><b>This class did not survive in any published copy of the engine and is reconstructed from
 * its call sites.</b> It is deterministic and well-distributed, but its number stream is
 * necessarily not bit-identical to upstream's. The practical effect is that erosion droplets start
 * in different places than they did in original TerraForged, so fine erosion detail differs; the
 * terrain's overall shape, which comes from the noise modules, does not depend on this.
 */
public class FastRandom {

    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;

    private long state;

    public FastRandom() {
        this(0L);
    }

    public FastRandom(long seed) {
        this.state = mix(seed);
    }

    /**
     * Reseeds this generator from a pair of values. Both contribute to the whole state, so
     * {@code seed(a, b)} and {@code seed(b, a)} give unrelated streams.
     */
    public void seed(long a, long b) {
        this.state = mix(a * GOLDEN_GAMMA ^ Long.rotateLeft(b, 32) * 0xBF58476D1CE4E5B9L);
    }

    public long nextLong() {
        state += GOLDEN_GAMMA;
        return mix(state);
    }

    public int nextInt() {
        return (int) (nextLong() >>> 32);
    }

    /**
     * Returns a value in {@code [0, bound)}. Uses Lemire's multiply-shift reduction, which avoids
     * the modulo in the hot path; the tiny bias this leaves is irrelevant for scattering droplets.
     */
    public int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive: " + bound);
        }
        long value = nextLong() >>> 32;
        return (int) ((value * bound) >>> 32);
    }

    public float nextFloat() {
        return (nextLong() >>> 40) * 0x1.0p-24F;
    }

    public double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
