/*
 * MIT License
 *
 * Copyright (c) 2026 Romoslayer
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

import com.terraforged.mod.TerraForged;
import com.terraforged.mod.client.ui.Presets;
import com.terraforged.mod.mixin.server.SettingsAccessor;
import com.terraforged.mod.worldgen.Generator;
import com.terraforged.mod.worldgen.GeneratorPreset;
import com.terraforged.mod.worldgen.datapack.DataPackExporter;
import com.terraforged.mod.worldgen.settings.DimensionOverrides;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.dedicated.Settings;
import net.minecraft.world.level.levelgen.WorldDimensions;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

/**
 * Gives a new dedicated-server world TerraForged's datapack when {@code server.properties} asks for a
 * TerraForged world type.
 *
 * <p>The world type is a world preset in that datapack. On a client the create-world screen copies the
 * pack into the new world (see {@link DatapackHook}); a dedicated server has no such screen, so
 * {@code level-type=terraforged:normal} used to fail to parse and the server generated a vanilla world
 * unless the pack had been copied in by hand. This does the same copy before the server first loads the
 * world's datapacks. Vanilla then enables it by itself: on a world's first load, every pack found in
 * {@code world/datapacks} is added automatically.
 *
 * <p>The pack is not made available to every world, because it replaces vanilla's overworld dimension
 * type (1024 blocks tall, clouds at 300) and would change vanilla worlds too. Existing worlds are left
 * alone: their datapacks and world type are already recorded in {@code level.dat}.
 *
 * <p>It also gives the server a say in the world's settings. In single-player the create-world screen
 * starts a TerraForged world from the preset named in {@code config/terraforged/default_preset.txt};
 * a server reads the same file from its own config folder (writing it, with an explanation, if it is
 * missing) and applies that preset to the world it creates.
 */
public final class ServerDatapackHook {
    private static final String PRESET_PREFIX = TerraForged.MODID + ":";
    private static final String PRESET_DIR = "data/" + TerraForged.MODID + "/worldgen/world_preset/";

    private static @Nullable Path worldDatapackDir;

    private ServerDatapackHook() {
    }

    /** Records the {@code datapacks} directory of the world the server is about to load. */
    public static void setWorldDatapackDir(Path dir) {
        worldDatapackDir = dir;
    }

    /**
     * Copies TerraForged's datapack into the world being created, if its level-type is one of
     * TerraForged's presets. Call only for a world that has no level data yet.
     */
    public static void installForNewWorld(Settings<?> properties, boolean safeMode) {
        String levelType = levelType(properties);
        if (!levelType.startsWith(PRESET_PREFIX)) {
            return;
        }

        if (safeMode) {
            TerraForged.LOG.warn("Safe mode loads only vanilla's datapack, so level-type {} cannot be used", levelType);
            return;
        }

        Path dir = worldDatapackDir;
        if (dir == null) {
            TerraForged.LOG.error("Could not find the world's datapacks folder; level-type {} will not resolve", levelType);
            return;
        }

        if (hasTerraForgedPack(dir)) {
            TerraForged.LOG.info("The new world already has a TerraForged datapack in {}", dir);
            return;
        }

        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            TerraForged.LOG.error("Could not create {}; level-type {} will not resolve", dir, levelType, e);
            return;
        }

        DataPackExporter.createWorldDatapack(dir);

        if (Files.exists(dir.resolve(DataPackExporter.PACK_FILE_NAME))) {
            TerraForged.LOG.info("Added TerraForged's datapack to the new world for level-type {}", levelType);
        } else {
            TerraForged.LOG.error("Failed to write TerraForged's datapack to {}; level-type {} will not resolve", dir, levelType);
        }
    }

    /**
     * Writes {@code config/terraforged/default_preset.txt} with an explanation and "Default", if it does
     * not exist yet, so a server owner can find the setting. The file is the same one single-player's
     * "Set As Default" writes.
     */
    public static void writePresetConfigIfMissing() {
        var file = Presets.defaultFile();
        if (Files.exists(file)) {
            return;
        }

        var builtIn = Presets.builtIn().stream().map(Presets.Preset::name).collect(Collectors.joining(", "));
        var lines = List.of(
                "# TerraForged: the settings preset new TerraForged worlds start from.",
                "#",
                "# On a dedicated server it applies when a world is first created with",
                "# level-type=terraforged\\:normal in server.properties. Existing worlds keep the settings",
                "# they were made with, so changing this later does not change them.",
                "#",
                "# Write one preset name on the line below. Names are not case-sensitive, and the",
                "# \"TerraForged - \" prefix can be left out (\"huge biomes\" works).",
                "# Built-in: " + builtIn + ".",
                "# Your own presets are the .json files in config/terraforged/presets: save one from the",
                "# Customize screen in single-player, then copy its file into this server's presets folder.",
                "#",
                "# In single-player, \"Set As Default\" on the Presets page writes this file.",
                Presets.DEFAULT);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, lines);
            TerraForged.LOG.info("Wrote {}; it sets the preset new TerraForged worlds use", file);
        } catch (IOException e) {
            TerraForged.LOG.warn("Could not write {}", file, e);
        }
    }

    /**
     * Applies the preset named in {@code default_preset.txt} to a new world's dimensions, if the world
     * is TerraForged's, the way the create-world screen applies it in single-player. Default needs no
     * change: the world preset already builds the default generator.
     */
    public static WorldDimensions applyDefaultPreset(HolderLookup.Provider registries, WorldDimensions dimensions) {
        if (!(dimensions.overworld() instanceof Generator)) {
            return dimensions;
        }

        var configured = Presets.configuredName();
        if (configured.isEmpty()) {
            return dimensions;
        }

        var preset = Presets.find(configured.get());
        if (preset.isEmpty()) {
            var known = Presets.all().stream().map(Presets.Preset::name).collect(Collectors.joining(", "));
            TerraForged.LOG.warn("No TerraForged preset named \"{}\" (from {}); using Default. Presets: {}",
                    configured.get(), Presets.defaultFile(), known);
            return dimensions;
        }

        var p = preset.get();
        if (p.name().equals(Presets.DEFAULT)) {
            return dimensions;
        }

        var settings = p.settings().copy();
        TerraForged.LOG.info("Creating the world with TerraForged preset \"{}\"", p.name());
        return DimensionOverrides.apply(registries,
                dimensions.replaceOverworldGenerator(registries, GeneratorPreset.build(p.levels(), settings, registries)),
                settings.world.dimensions);
    }

    // Read the way DedicatedServerProperties reads it: lower-cased, defaulting to minecraft:normal.
    private static String levelType(Settings<?> properties) {
        var raw = ((SettingsAccessor) (Object) properties).terraforged$getProperties();
        return raw.getProperty("level-type", "minecraft:normal").toLowerCase(Locale.ROOT);
    }

    // True if some pack in the folder already provides TerraForged's presets, e.g. one copied in by hand
    // the way the README used to describe. Adding a second copy would load the same files twice.
    private static boolean hasTerraForgedPack(Path dir) {
        if (!Files.isDirectory(dir)) {
            return false;
        }

        try (var entries = Files.list(dir)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                if (Files.isDirectory(entry) ? Files.isDirectory(entry.resolve(PRESET_DIR)) : zipHasPresets(entry)) {
                    return true;
                }
            }
        } catch (IOException e) {
            TerraForged.LOG.warn("Could not list {}", dir, e);
        }
        return false;
    }

    private static boolean zipHasPresets(Path file) {
        if (!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            return false;
        }

        try (var zip = new ZipFile(file.toFile())) {
            return zip.stream().anyMatch(entry -> entry.getName().startsWith(PRESET_DIR));
        } catch (IOException e) {
            return false;
        }
    }
}
