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

package com.terraforged.mod.registry.key;

import com.terraforged.mod.CommonAPI;
import com.terraforged.mod.TerraForged;
import com.terraforged.mod.registry.lazy.LazyValue;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;

import java.util.Comparator;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class RegistryKey<T> extends LazyValue<ResourceKey<Registry<T>>> {
    public RegistryKey(Identifier name) {
        super(name);
    }

    public EntryKey<T> entryKey(String name) {
        return new EntryKey<>(this, TerraForged.location(name));
    }

    /**
     * Resolves an entry from the given registries, or falls back to a direct holder over the default
     * when there are none (the built-in defaults path, where no world is loaded yet).
     *
     * <p>The fallback used to be a {@code LazyHolder} that also carried the entry's
     * {@link net.minecraft.resources.ResourceKey}. {@link Holder} is a sealed interface now, so a mod
     * cannot implement it, and the only ways to build a *keyed* holder outside {@code net.minecraft.core}
     * need reflection — {@code Holder.Reference#bindValue} and {@code bindKey} are not public.
     *
     * <p>So the fallback is a plain {@link Holder#direct} and the key is not carried. That matters only
     * to code that would encode such a holder through a registry codec, which is the datapack export
     * path — already excluded from the build and not ported. At runtime {@code access}
     * is never null, so the real, keyed registry entry is used.
     */
    public Holder<T> holder(String name, HolderLookup.Provider access, Supplier<T> defaultSupplier) {
        var key = entryKey(name);
        if (access == null) {
            return Holder.direct(defaultSupplier.get());
        }
        return access.lookupOrThrow(get()).getOrThrow(key.get());
    }

    public T[] entries(HolderLookup.Provider access, IntFunction<T[]> arrayFunc) {
        if (access == null) {
            return toSortedArray(CommonAPI.get().getRegistryManager().getRegistry(this).stream(), arrayFunc);
        }
        // A RegistryLookup enumerates as holders rather than key/value pairs; sort on the same key
        // so the ordering the generator depends on is unchanged.
        return access.lookupOrThrow(get()).listElements()
                .sorted(Comparator.comparing(holder -> holder.key().identifier()))
                .map(Holder::value)
                .toArray(arrayFunc);
    }

    /**
     * The ids of {@link #entries}, in the same order, so the two can be zipped.
     *
     * <p>TerraForged's own entries are named by path alone ("mountains_1"); a datapack from another
     * namespace keeps its namespace, so its entries cannot collide with ours.
     */
    public java.util.List<String> entryIds(HolderLookup.Provider access) {
        return access.lookupOrThrow(get()).listElements()
                .map(holder -> holder.key().identifier())
                .sorted()
                .map(id -> id.getNamespace().equals(com.terraforged.mod.TerraForged.MODID) ? id.getPath() : id.toString())
                .toList();
    }

    @Override
    protected ResourceKey<Registry<T>> compute() {
        return ResourceKey.createRegistryKey(name);
    }

    @Override
    public String toString() {
        return "RegistryKey{" + name + "}";
    }

    private static <T> T[] toSortedArray(Stream<Map.Entry<ResourceKey<T>, T>> stream, IntFunction<T[]> arrayFunc) {
        return stream.sorted(Comparator.comparing(e -> e.getKey().identifier()))
                .map(Map.Entry::getValue)
                .toArray(arrayFunc);
    }
}
