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

package com.terraforged.mod.data.gen;

import net.minecraft.core.registries.Registries;
import com.google.common.hash.Hashing;
import com.google.common.hash.HashingOutputStream;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.terraforged.mod.CommonAPI;
import com.terraforged.mod.TerraForged;
import com.terraforged.mod.data.codec.Codecs;
import com.terraforged.mod.data.util.JsonFormatter;
import com.terraforged.mod.registry.DataRegistry;
import com.terraforged.mod.util.FileUtil;
import com.terraforged.mod.util.TagLoader;
import com.terraforged.mod.worldgen.GeneratorPreset;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.Registry;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.CachedOutput;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class DataGen {
    /** Vanilla's overworld default is 192.33, which TerraForged's mountains routinely exceed. */
    private static final float CLOUD_HEIGHT = 300.0F;
    private static final String CLOUD_HEIGHT_ATTRIBUTE = "minecraft:visual/cloud_height";

    private final CachedOutput cache;
    private final List<CompletableFuture<?>> tasks = new ObjectArrayList<>();

    public DataGen(CachedOutput cache) {
        this.cache = cache;
    }

    protected CompletableFuture<?> doExport(Path dir) {
        FileUtil.delete(dir);

        var registries = HolderLookup.Provider.builtinCopy();
        var writeOps = RegistryOps.create(JsonOps.INSTANCE, registries);

        TagLoader.bindTags(registries);

        genPreset(dir, registries, writeOps);
        genBuiltin(dir, registries, writeOps);
        genDimensionType(dir, registries, writeOps);

        return CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new));
    }

    public static CompletableFuture<?> export(Path dir, CachedOutput cache) {
        return new DataGen(cache).doExport(dir);
    }

    private void genPreset(Path dir, HolderLookup.Provider registries, RegistryOps<JsonElement> writeOps) {
        var normal = registries.lookupOrThrow(Registries.WORLD_PRESET)
                .getValueOrThrow(WorldPresets.NORMAL);

        var json = Codecs.encode(normal, WorldPreset.DIRECT_CODEC, writeOps).getAsJsonObject();
        var dimension = GeneratorPreset.getDefault(registries);
        var dimensionJson = Codecs.encode(dimension, LevelStem.CODEC, writeOps);

        var dimensions = json.getAsJsonObject("dimensions");
        dimensions.add(LevelStem.OVERWORLD.identifier().toString(), dimensionJson);

        export(dir, Registries.WORLD_PRESET, TerraForged.WORLD_PRESET, json);
    }

    private void genDimensionType(Path dir, HolderLookup.Provider registries, RegistryOps<JsonElement> writeOps) {
        var registry = registries.lookupOrThrow(Registries.DIMENSION_TYPE);
        var overworld = registry.getValueOrThrow(BuiltinDimensionTypes.OVERWORLD);

        var json = Codecs.encode(overworld, DimensionType.DIRECT_CODEC, writeOps).getAsJsonObject();
        json.addProperty("height", 1024);
        json.addProperty("logical_height", 1024);

        // Lift the clouds above TerraForged's mountains, which routinely pass vanilla's 192.33.
        //
        // Upstream did this with an "effects" property naming a custom DimensionSpecialEffects that
        // it registered into a private map by reflection. Neither the property nor that class exists
        // any more -- cloud height is an environment attribute now, so it is plain data. Everything
        // else in this object is vanilla's own overworld, re-encoded, so the rest of the attribute
        // map comes along unchanged.
        var attributes = json.getAsJsonObject("attributes");
        if (attributes == null) {
            attributes = new JsonObject();
            json.add("attributes", attributes);
        }
        attributes.addProperty(CLOUD_HEIGHT_ATTRIBUTE, CLOUD_HEIGHT);

        export(dir, Registries.DIMENSION_TYPE, BuiltinDimensionTypes.OVERWORLD.identifier(), json);
    }

    private void genBuiltin(Path dir, HolderLookup.Provider registries, RegistryOps<JsonElement> writeOps) {
        for (var registry : CommonAPI.get().getRegistryManager().getRegistries()) {
            export(dir, registry, registries, writeOps);
        }
    }

    private void genTags(Path dir, HolderLookup.Provider registries, RegistryOps<JsonElement> writeOps) {

    }

    private <T> void export(Path dir, DataRegistry<T> builtin, HolderLookup.Provider access, DynamicOps<JsonElement> ops) {
        var registry = access.lookupOrThrow(builtin.key().get());

        TerraForged.LOG.info("Exporting registry: {}", registry.key());
        for (var entry : builtin) {
            try {
                var value = registry.getValueOrThrow(entry.getKey());

                var json = builtin.codec().encodeStart(ops, value)
                        .mapError(s -> {
                            logError(s);
                            return s;
                        })
                        .result()
                        .orElseThrow();

                export(dir, registry.key(), entry.getKey().identifier(), json);
            } catch (Throwable t) {
                new EncodingException(entry.getKey(), t).printStackTrace();
            }
        }
    }

    private void export(Path dir, ResourceKey<?> registry, Identifier name, JsonElement json) {
        var file = dir.resolve("data")
                .resolve(name.getNamespace())
                .resolve(registry.identifier().getPath())
                .resolve(name.getPath() + ".json");

        if (cache != null) {
            writeCached(file, json);
        } else {
            tasks.add(CompletableFuture.runAsync(() -> writeDirect(file, json)));
        }
    }

    protected void writeDirect(Path path, JsonElement json) {
        var parent = path.getParent();
        if (!Files.exists(parent)) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }
        }

        try (var out = Files.newBufferedWriter(path)) {
            JsonFormatter.format(json, out);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @SuppressWarnings("UnstableApiUsage")
    protected void writeCached(Path path, JsonElement json) {
        try {
            var byteOut = new ByteArrayOutputStream();
            var hashOut = new HashingOutputStream(Hashing.sha1(), byteOut);

            JsonFormatter.format(json, hashOut);

            cache.writeIfNeeded(path, byteOut.toByteArray(), hashOut.hash());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String logError(String s) {
        TerraForged.LOG.warn(s);
        return s;
    }
}
