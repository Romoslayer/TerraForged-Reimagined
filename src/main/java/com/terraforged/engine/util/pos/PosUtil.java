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

package com.terraforged.engine.util.pos;

/**
 * Packs a pair of values into a single {@code long}, so coordinate pairs can be used as map keys
 * or returned from a method without allocating.
 *
 * <p>The first value occupies the high 32 bits and the second the low 32 bits. The {@code f}
 * variants store the raw IEEE-754 bits of a {@code float} in each half, so
 * {@code unpackLeftf(packf(a, b))} returns {@code a} exactly, including for negative and
 * fractional values.
 *
 * <p>This class did not survive in any published copy of the engine and is reconstructed from its
 * call sites.
 */
public class PosUtil {

    private PosUtil() {}

    public static long pack(int left, int right) {
        return ((long) left << 32) | (right & 0xFFFFFFFFL);
    }

    public static int unpackLeft(long packed) {
        return (int) (packed >> 32);
    }

    public static int unpackRight(long packed) {
        return (int) packed;
    }

    public static long packf(float left, float right) {
        return pack(Float.floatToRawIntBits(left), Float.floatToRawIntBits(right));
    }

    public static float unpackLeftf(long packed) {
        return Float.intBitsToFloat(unpackLeft(packed));
    }

    public static float unpackRightf(long packed) {
        return Float.intBitsToFloat(unpackRight(packed));
    }
}
