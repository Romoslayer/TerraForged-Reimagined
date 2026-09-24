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

package com.terraforged.engine.world.terrain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The registry of known {@link Terrain}s, and the built-in set every world starts with.
 *
 * <p>In the engine's earlier API this name belonged to an enum of terrain behaviours; that enum is
 * now {@link TerrainCategory}, and this class holds named {@code Terrain} instances instead. The
 * constants below are {@code Terrain}s, not categories, and are passed wherever a {@code Terrain}
 * is expected.
 *
 * <p>Registration order is preserved, because {@link #forEach(Consumer)} is used to mirror these
 * into a Minecraft registry and that needs to be deterministic.
 */
public class TerrainType {

    private static final Map<String, Terrain> REGISTRY = Collections.synchronizedMap(new LinkedHashMap<>());

    public static final Terrain NONE = register("none", TerrainCategory.NONE);
    public static final Terrain DEEP_OCEAN = register("deep_ocean", TerrainCategory.DEEP_OCEAN);
    public static final Terrain SHALLOW_OCEAN = register("shallow_ocean", TerrainCategory.SHALLOW_OCEAN);
    public static final Terrain COAST = register("coast", TerrainCategory.COAST);
    public static final Terrain BEACH = register("beach", TerrainCategory.BEACH);
    public static final Terrain RIVER = register("river", TerrainCategory.RIVER);
    public static final Terrain LAKE = register("lake", TerrainCategory.LAKE);
    public static final Terrain WETLAND = register("wetland", TerrainCategory.WETLAND);
    public static final Terrain FLATS = register("flats", TerrainCategory.FLATLAND);
    public static final Terrain HILLS = register("hills", TerrainCategory.LOWLAND);
    public static final Terrain BADLANDS = register("badlands", TerrainCategory.LOWLAND);
    public static final Terrain PLATEAU = register("plateau", TerrainCategory.HIGHLAND);
    public static final Terrain MOUNTAINS = register("mountains", TerrainCategory.HIGHLAND);

    private TerrainType() {}

    /**
     * Returns the terrain registered under {@code name}, or {@link #NONE} if there is none.
     */
    public static Terrain get(String name) {
        return REGISTRY.getOrDefault(name, NONE);
    }

    /**
     * Returns the terrain registered under {@code name}, registering a new one deriving its
     * behaviour from {@code parent} if it does not exist yet.
     *
     * <p>Calling this repeatedly with the same name always returns the same instance, so callers
     * can use it as an idempotent "declare this terrain" operation.
     */
    public static Terrain getOrCreate(String name, Terrain parent) {
        synchronized (REGISTRY) {
            Terrain existing = REGISTRY.get(name);
            if (existing != null) {
                return existing;
            }
            Terrain terrain = new Terrain(name, parent.getWeight(), parent);
            REGISTRY.put(name, terrain);
            return terrain;
        }
    }

    /**
     * Visits every registered terrain in registration order.
     */
    public static void forEach(Consumer<Terrain> consumer) {
        synchronized (REGISTRY) {
            REGISTRY.values().forEach(consumer);
        }
    }

    private static Terrain register(String name, TerrainCategory category) {
        Terrain terrain = new Terrain(name, category);
        REGISTRY.put(name, terrain);
        return terrain;
    }
}
