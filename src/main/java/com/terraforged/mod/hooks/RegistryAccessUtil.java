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
import net.minecraft.core.Registry;
import net.minecraft.core.HolderLookup;
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
     * {@code RegistryAccess} yet. Each {@code RegistryInfo} does carry a {@code HolderGetter}, and in
     * every path Minecraft uses that getter is the registry's own
     * {@link HolderLookup.RegistryLookup} (see {@code RegistryInfo#fromRegistryLookup}, which passes
     * the lookup in as both owner and getter), so it can be handed back as one.
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
         * Prefers {@code owner()} over {@code getter()}, which is not an arbitrary choice.
         *
         * <p>For a registry still being loaded, {@code RegistryLoadTask#createRegistryInfo} sets
         * {@code owner} to the {@code MappedRegistry} itself but {@code getter} to a concurrent
         * wrapper that resolves single elements and blocks until they are registered. That wrapper is
         * deliberately not a {@link HolderLookup.RegistryLookup} — you cannot enumerate a registry
         * mid-load — so taking {@code getter} yields nothing and the registry looks absent. Taking
         * {@code owner} gives the real lookup.
         *
         * <p>That is only safe because every caller here is lazy and runs after loading has finished;
         * see {@code Source.Parts}. Enumerating through this during a load would race.
         */
        @Override
        @SuppressWarnings("unchecked")
        public <T> Optional<HolderLookup.RegistryLookup<T>> lookup(ResourceKey<? extends Registry<? extends T>> key) {
            return lookup.lookup(key).map(info -> {
                if (info.owner() instanceof HolderLookup.RegistryLookup<?> owner) {
                    return (HolderLookup.RegistryLookup<T>) owner;
                }
                if (info.getter() instanceof HolderLookup.RegistryLookup<?> getter) {
                    return (HolderLookup.RegistryLookup<T>) getter;
                }
                return null;
            });
        }
    }

    public static void printRegistryContents(Registry<?> registry) {
        if (!Environment.DEBUGGING) return;

        TerraForged.LOG.info(" - Registry: {}, Size: {}", registry.key().identifier(), registry.size());
        for (var entry : registry.entrySet()) {
            TerraForged.LOG.info("  - {}", entry.getKey().identifier());
        }
    }
}
