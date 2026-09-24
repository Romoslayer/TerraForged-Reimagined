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

package com.terraforged.mod.hooks;

import com.mojang.serialization.DynamicOps;
import com.terraforged.mod.Environment;
import com.terraforged.mod.TerraForged;
import com.terraforged.mod.util.ReflectionUtil;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Registry;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.ConcurrentHolderGetter;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.RegistryOps;

import java.lang.invoke.MethodHandle;
import java.util.stream.Stream;
import java.util.Optional;

/**
 * Recovers the registries from the {@link DynamicOps} a codec is decoding with.
 *
 * <p>TerraForged's chunk generator is built during codec decode and has to look up several registries
 * to assemble itself, so it needs to reach the registries the ops were created over.
 *
 * <p>Upstream read a {@code RegistryAccess} field straight off {@code RegistryOps}. That field is
 * gone — {@code RegistryOps} holds a {@code RegistryInfoLookup} now — and, more importantly, a
 * {@code RegistryAccess} is not always what is behind it. When the chunk generator is decoded as part
 * of *loading* the datapack registries, the ops are built over an anonymous {@code RegistryInfoLookup}
 * and no {@code RegistryAccess} exists yet. That is why this hands back a
 * {@link HolderLookup.Provider} rather than a {@code RegistryAccess}: it is the widest thing available
 * in every path, and {@code RegistryAccess} is one.
 *
 * <p>Reading the private field is still reflection over an implementation detail, and is the most
 * likely thing here to break on a Minecraft update. If the generator ever stops decoding, start here.
 */
public class RegistryAccessUtil {
    private static final MethodHandle LOOKUP_PROVIDER_GETTER =
            ReflectionUtil.field(RegistryOps.class, RegistryOps.RegistryInfoLookup.class);

    public static Optional<HolderLookup.Provider> getLookup(DynamicOps<?> ops) {
        if (!(ops instanceof RegistryOps<?> registryOps)) {
            return Optional.empty();
        }

        try {
            return Optional.ofNullable(getLookup(registryOps));
        } catch (Throwable t) {
            t.printStackTrace();
            return Optional.empty();
        }
    }

    public static HolderLookup.Provider getLookup(RegistryOps<?> ops) {
        try {
            Object lookup = LOOKUP_PROVIDER_GETTER.invoke(ops);
            if (lookup == null) return null;

            // Straightforward when the ops were built over a provider (the world-load path); the
            // adapter below covers the datapack-load path, where they are not.
            if (lookup instanceof HolderLookup.Provider provider) return provider;

            if (lookup instanceof RegistryOps.RegistryInfoLookup infoLookup) {
                return new InfoLookupProvider(infoLookup);
            }

            TerraForged.LOG.warn("Unable to recover registries from {}", lookup.getClass().getName());
            return null;
        } catch (Throwable t) {
            t.printStackTrace();
            return null;
        }
    }

    /**
     * Presents a {@link RegistryOps.RegistryInfoLookup} as a {@link HolderLookup.Provider}.
     *
     * <p>This is the case that matters during datapack loading: {@code RegistryDataLoader} builds ops
     * over an anonymous {@code RegistryInfoLookup} that is not a {@code RegistryAccess} and holds no
     * reference to one, so there is nothing to unwrap — the registries being loaded do not exist as a
     * {@code RegistryAccess} yet. What it hands back per registry is a {@code HolderGetter}; see
     * {@link #asRegistryLookup} for turning that back into the registry.
     */
    private record InfoLookupProvider(RegistryOps.RegistryInfoLookup lookup) implements HolderLookup.Provider {
        @Override
        public Stream<ResourceKey<? extends Registry<?>>> listRegistryKeys() {
            // A RegistryInfoLookup answers lookups but cannot enumerate. Nothing in TerraForged asks
            // for the registry list -- it only ever resolves registries it names -- so reporting none
            // is honest rather than lossy.
            return Stream.empty();
        }

        /**
         * Only safe because every caller here is lazy and runs after loading has finished; see
         * {@code Source.Parts}. Enumerating through this during a load would race.
         */
        @Override
        @SuppressWarnings("unchecked")
        public <T> Optional<HolderLookup.RegistryLookup<T>> lookup(ResourceKey<? extends Registry<? extends T>> key) {
            return lookup.lookup(key).map(getter -> (HolderLookup.RegistryLookup<T>) asRegistryLookup(getter));
        }
    }

    private static final MethodHandle CONCURRENT_ORIGINAL =
            ReflectionUtil.field(ConcurrentHolderGetter.class, HolderGetter.class);

    /**
     * The registry behind a getter a {@code RegistryInfoLookup} returned.
     *
     * <p>Registries that were already loaded come back as themselves. A registry still being loaded comes
     * back as its load task's {@link ConcurrentHolderGetter}, a wrapper that resolves single elements and
     * blocks until they are registered -- deliberately not a {@link HolderLookup.RegistryLookup}, since a
     * registry cannot be enumerated mid-load -- so taken at face value the registry looks absent.
     *
     * <p>Up to 26.2 the lookup returned a {@code RegistryInfo} whose {@code owner} was the
     * {@code MappedRegistry} itself, and that was used. 26.3 returns the getter alone, so the registry is
     * recovered from it: the wrapper's {@code original} is the registry's registration lookup
     * ({@code MappedRegistry#createRegistrationLookup}), an inner class whose enclosing instance is the
     * registry. Same object as the old {@code owner}, reached two steps further in.
     */
    private static HolderLookup.RegistryLookup<?> asRegistryLookup(HolderGetter<?> getter) {
        if (getter instanceof HolderLookup.RegistryLookup<?> lookup) return lookup;

        try {
            Object inner = getter instanceof ConcurrentHolderGetter<?> concurrent
                    ? CONCURRENT_ORIGINAL.invoke(concurrent)
                    : getter;
            if (inner instanceof HolderLookup.RegistryLookup<?> lookup) return lookup;

            for (var field : inner.getClass().getDeclaredFields()) {
                if (HolderLookup.RegistryLookup.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    return (HolderLookup.RegistryLookup<?>) field.get(inner);
                }
            }
        } catch (Throwable t) {
            TerraForged.LOG.warn("Unable to recover a registry from {}", getter.getClass().getName(), t);
            return null;
        }

        TerraForged.LOG.warn("Unable to recover a registry from {}", getter.getClass().getName());
        return null;
    }

    public static void printRegistryContents(Registry<?> registry) {
        if (!Environment.DEBUGGING) return;

        TerraForged.LOG.info(" - Registry: {}, Size: {}", registry.key().identifier(), registry.size());
        for (var entry : registry.entrySet()) {
            TerraForged.LOG.info("  - {}", entry.getKey().identifier());
        }
    }
}
