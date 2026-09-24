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

package com.terraforged.mod.worldgen.biome;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import com.terraforged.mod.hooks.RegistryAccessUtil;
import com.terraforged.mod.worldgen.noise.INoiseGenerator;

import java.util.stream.Stream;

/**
 * Builds the biome source purely from the registries the ops carry — it has no serialised fields of
 * its own.
 *
 * <p>This is a {@link MapCodec} rather than a plain {@code Codec} because biome source codecs are
 * registered as {@code MapCodec} since 1.20.5; it used to implement the mod's own
 * {@code WorldGenCodec} interface, which cannot express that.
 */
public class SourceCodec extends MapCodec<Source> {
    @Override
    public <T> Stream<T> keys(DynamicOps<T> ops) {
        return Stream.empty();
    }

    @Override
    public <T> DataResult<Source> decode(DynamicOps<T> ops, MapLike<T> input) {
        var access = RegistryAccessUtil.getLookup(ops);
        if (access.isEmpty()) {
            return DataResult.error(() -> "Cannot build the TerraForged biome source: no registries on these ops");
        }
        // Upstream passed a null noise generator here, because this path -- a datapack naming the
        // TerraForged biome source without the TerraForged chunk generator -- has no terrain noise to
        // sample. That used to NPE deep inside sampling; now it fails with something that says why.
        return DataResult.success(new Source(SourceCodec::noNoiseGenerator, access.get()));
    }

    private static INoiseGenerator noNoiseGenerator() {
        throw new IllegalStateException(
                "The TerraForged biome source needs the TerraForged chunk generator: it samples the same"
                        + " terrain noise. Use the terraforged world preset rather than naming this biome"
                        + " source on its own.");
    }

    @Override
    public <T> RecordBuilder<T> encode(Source input, DynamicOps<T> ops, RecordBuilder<T> prefix) {
        return prefix;
    }
}
