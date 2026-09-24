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

package com.terraforged.mod.client.ui;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.terraforged.mod.TerraForged;
import com.terraforged.mod.worldgen.datapack.DataPackExporter;
import com.terraforged.mod.worldgen.settings.SettingsSerializer;
import com.terraforged.mod.worldgen.settings.TerraSettings;
import com.terraforged.mod.worldgen.terrain.TerrainLevels;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Named world settings: the built-in presets plus any the player saves, under
 * {@code config/terraforged/presets}.
 *
 * <p>A preset file holds the generator's {@code levels} and {@code settings} blocks in exactly the form
 * the world preset uses, so either can be pasted straight into a datapack. Files written by the
 * previous version of this screen held only the levels at the top level; those still load, with
 * default settings.
 */
public final class Presets {
    private static final String EXTENSION = ".json";
    private static final String DEFAULT_FILE = "default_preset.txt";

    public static final String DEFAULT = "Default";

    public record Preset(String name, String description, boolean builtIn, TerrainLevels levels,
                         TerraSettings settings) {}

    private Presets() {}

    public static Path directory() {
        return DataPackExporter.CONFIG_DIR.resolve("presets");
    }

    /** Built-ins first, in the order 1.16.5 listed them, then the player's own, alphabetically. */
    public static List<Preset> all() {
        var presets = new ArrayList<>(builtIn());
        presets.addAll(user());
        return presets;
    }

    public static Optional<Preset> get(String name) {
        return all().stream().filter(p -> p.name().equals(name)).findFirst();
    }

    /**
     * Our versions of 1.16.5's built-in presets.
     *
     * <p>Not its numbers. Those were tuned for 0.2.x's continent generator, which 0.3.x replaced: a
     * continent scale of 3000 was that generator's default, where 400 is this one's, so copying the
     * values would produce continents seven times too large. Each preset is instead expressed as the
     * same <em>ratio</em> to its generator's defaults — "Huge Biomes" had continents 1.34 times and
     * biomes 1.61 times the 0.2.x default, so here they are 1.34 and 1.61 times ours.
     */
    public static List<Preset> builtIn() {
        return List.of(
                builtIn(DEFAULT, "The default TerraForged settings", s -> {}),

                builtIn("TerraForged - Beautiful",
                        "Similar to 'Default' but with some settings dialed-up for more epic vistas", s -> {
                            s.climate.biomeShape.biomeSize = scale(220, 185 / 250F);
                            s.climate.temperature.falloff = 1;
                            s.climate.temperature.bias = 0F;
                            s.climate.moisture.bias = 0F;
                            s.filters.erosion.dropletsPerChunk = scale(350, 175 / 135F);
                        }),

                builtIn("TerraForged - Huge Biomes",
                        "Vast continents & huge biomes - everything's scaled up!", s -> {
                            s.world.continent.continentScale = scale(400, 4029 / 3000F);
                            s.climate.biomeShape.biomeSize = scale(220, 402 / 250F);
                            s.climate.biomeShape.biomeWarpScale = 180;
                            s.climate.biomeShape.biomeWarpStrength = 110;
                            s.climate.temperature.scale = 4;
                            s.climate.moisture.scale = 3;
                            s.filters.erosion.dropletsPerChunk = scale(350, 165 / 135F);
                            s.filters.erosion.dropletLifetime = scale(25, 15 / 12F);
                        }),

                builtIn("TerraForged - Lite",
                        "Smaller continents & biomes, more suited to lower render distances", Presets::lite),

                builtIn("TerraForged - Vanilla-ish",
                        "Similar to 'Lite' but with TerraForged's erosion turned off", s -> {
                            lite(s);
                            s.filters.erosion.dropletsPerChunk = 0;
                        })
        );
    }

    private static void lite(TerraSettings s) {
        s.world.continent.continentScale = scale(400, 2000 / 3000F);
        s.climate.biomeShape.biomeSize = scale(220, 176 / 250F);
        s.climate.temperature.scale = 4;
        s.climate.temperature.falloff = 1;
        s.climate.moisture.scale = 5;
        s.filters.erosion.dropletsPerChunk = scale(350, 100 / 135F);
    }

    private static int scale(int value, float ratio) {
        return Math.round(value * ratio);
    }

    private static Preset builtIn(String name, String description, Consumer<TerraSettings> modifier) {
        var settings = new TerraSettings();
        modifier.accept(settings);
        return new Preset(name, description, true, TerrainLevels.DEFAULT.get(), settings);
    }

    private static List<Preset> user() {
        var dir = directory();
        if (!Files.isDirectory(dir)) return List.of();

        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(EXTENSION))
                    .map(Presets::read)
                    .flatMap(Optional::stream)
                    .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                    .toList();
        } catch (IOException e) {
            TerraForged.LOG.warn("Could not list presets in {}", dir, e);
            return List.of();
        }
    }

    private static Optional<Preset> read(Path file) {
        String fileName = file.getFileName().toString();
        String name = fileName.substring(0, fileName.length() - EXTENSION.length());

        try (var reader = Files.newBufferedReader(file)) {
            var json = JsonParser.parseReader(reader).getAsJsonObject();

            String description = json.has("description") ? json.get("description").getAsString() : "";

            // Presets saved before settings existed are a bare TerrainLevels object.
            var levelsJson = json.has("levels") ? json.get("levels") : json;
            var levels = TerrainLevels.CODEC.parse(JsonOps.INSTANCE, levelsJson)
                    .resultOrPartial(error -> TerraForged.LOG.warn("Preset {} has invalid levels: {}", name, error))
                    .orElse(TerrainLevels.DEFAULT.get());

            var settings = SettingsSerializer.read(json.get("settings"));
            return Optional.of(new Preset(name, description, false, levels, settings));
        } catch (Exception e) {
            TerraForged.LOG.warn("Could not read preset {}", file, e);
            return Optional.empty();
        }
    }

    /** @return false if nothing was written, including when the name belongs to a built-in preset. */
    public static boolean save(String name, TerrainLevels levels, TerraSettings settings) {
        if (isBuiltIn(name)) return false;

        var json = new JsonObject();
        TerrainLevels.CODEC.encodeStart(JsonOps.INSTANCE, levels).result().ifPresent(l -> json.add("levels", l));
        json.add("settings", SettingsSerializer.write(settings));

        var file = directory().resolve(sanitize(name) + EXTENSION);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(json));
            TerraForged.LOG.info("Saved preset {}", file);
            return true;
        } catch (IOException e) {
            TerraForged.LOG.warn("Could not write preset {}", name, e);
            return false;
        }
    }

    public static boolean delete(String name) {
        if (isBuiltIn(name)) return false;
        try {
            // Deleting the default preset must not leave new worlds pointing at a file that is gone.
            if (name.equals(defaultName())) setDefault(DEFAULT);
            return Files.deleteIfExists(directory().resolve(sanitize(name) + EXTENSION));
        } catch (IOException e) {
            TerraForged.LOG.warn("Could not delete preset {}", name, e);
            return false;
        }
    }

    public static boolean isBuiltIn(String name) {
        return builtIn().stream().anyMatch(p -> p.name().equalsIgnoreCase(name));
    }

    /** The preset new TerraForged worlds start from. {@link #DEFAULT} unless the player picked another. */
    public static String defaultName() {
        try {
            var file = DataPackExporter.CONFIG_DIR.resolve(DEFAULT_FILE);
            if (!Files.exists(file)) return DEFAULT;
            String name = Files.readString(file).trim();
            return get(name).isPresent() ? name : DEFAULT;
        } catch (IOException e) {
            return DEFAULT;
        }
    }

    public static void setDefault(String name) {
        try {
            Files.createDirectories(DataPackExporter.CONFIG_DIR);
            Files.writeString(DataPackExporter.CONFIG_DIR.resolve(DEFAULT_FILE), name);
        } catch (IOException e) {
            TerraForged.LOG.warn("Could not set default preset {}", name, e);
        }
    }

    /**
     * Reduces a typed name to something safe to use as a file name. The name comes straight from a
     * text box and becomes a path, so separators and {@code ..} must not survive; everything outside
     * the allowed set becomes an underscore rather than being rejected.
     */
    public static String sanitize(String name) {
        var clean = new StringBuilder();
        for (char c : name.trim().toCharArray()) {
            clean.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == ' ' ? c : '_');
        }
        var result = clean.toString().trim();
        return result.isEmpty() ? "preset" : result.toLowerCase(Locale.ROOT).equals("default") ? "default_" : result;
    }
}
