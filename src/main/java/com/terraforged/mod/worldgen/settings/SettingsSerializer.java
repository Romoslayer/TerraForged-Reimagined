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

package com.terraforged.mod.worldgen.settings;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;
import com.terraforged.engine.serialization.annotation.Range;
import com.terraforged.mod.TerraForged;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes {@link TerraSettings} as JSON by reflection.
 *
 * <p>Reflective rather than a hand-written {@code RecordCodecBuilder} because there are over sixty
 * fields, and a codec per field is sixty chances to get a key, a default or a getter out of step
 * with the class. It also gives two properties a strict codec would not:
 *
 * <ul>
 *   <li><b>Missing keys keep their defaults.</b> Reading starts from a fresh {@code TerraSettings}
 *       and overlays whatever the JSON has, so a world saved before a field existed still loads, and
 *       loads with that field at the value it generated with.
 *   <li><b>Unknown keys are ignored</b>, so a world saved by a newer version does not fail to load in
 *       an older one.
 *   <li><b>Numbers are pinned to their {@code @Range}.</b> The settings screen already keeps values in
 *       range, so this only ever reaches a hand-edited or corrupted preset -- which, without it, could
 *       carry a zero into a division and generate a world of NaN.
 * </ul>
 *
 * <p>Handles public instance fields of primitive, {@code String}, enum, nested settings object and
 * {@code Map<String, settings object>} types — exactly what {@code TerraSettings} uses.
 */
public final class SettingsSerializer {
    /**
     * Stored through a passthrough so the world's own ops (NBT, in a save) round-trip it; the JSON
     * form is only the internal representation.
     */
    public static final Codec<TerraSettings> CODEC = Codec.PASSTHROUGH.xmap(
            dynamic -> read(dynamic.convert(JsonOps.INSTANCE).getValue()),
            settings -> new Dynamic<>(JsonOps.INSTANCE, write(settings)));

    private SettingsSerializer() {}

    public static JsonObject write(TerraSettings settings) {
        return writeObject(settings);
    }

    public static TerraSettings read(JsonElement json) {
        var settings = new TerraSettings();
        if (json != null && json.isJsonObject()) {
            readInto(settings, json.getAsJsonObject());
        }
        return settings;
    }

    /** The fields this serializer and the settings screen both walk, in declaration order. */
    public static List<Field> fields(Class<?> type) {
        return java.util.Arrays.stream(type.getFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()) && !Modifier.isTransient(f.getModifiers()))
                .sorted(java.util.Comparator.comparingInt(f -> declarationIndex(type, f)))
                .toList();
    }

    /**
     * {@code getFields} makes no ordering promise, and the screen lays sections out in field order,
     * so order is recovered from {@code getDeclaredFields}, which in practice follows the source.
     */
    private static int declarationIndex(Class<?> type, Field field) {
        var declared = type.getDeclaredFields();
        for (int i = 0; i < declared.length; i++) {
            if (declared[i].equals(field)) return i;
        }
        return Integer.MAX_VALUE;
    }

    public static boolean isLeaf(Class<?> type) {
        return type.isPrimitive() || type == String.class || type.isEnum()
                || Number.class.isAssignableFrom(type) || type == Boolean.class;
    }

    private static JsonObject writeObject(Object object) {
        var json = new JsonObject();

        for (var field : fields(object.getClass())) {
            try {
                var value = field.get(object);
                if (value == null) continue;
                json.add(field.getName(), writeValue(value));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        return json;
    }

    private static JsonElement writeValue(Object value) {
        if (value instanceof Boolean b) return new JsonPrimitive(b);
        if (value instanceof Number n) return new JsonPrimitive(n);
        if (value instanceof String s) return new JsonPrimitive(s);
        if (value instanceof Enum<?> e) return new JsonPrimitive(e.name());

        if (value instanceof Map<?, ?> map) {
            var json = new JsonObject();
            for (var entry : map.entrySet()) {
                json.add(String.valueOf(entry.getKey()), writeValue(entry.getValue()));
            }
            return json;
        }

        return writeObject(value);
    }

    private static void readInto(Object target, JsonObject json) {
        for (var field : fields(target.getClass())) {
            var element = json.get(field.getName());
            if (element == null || element.isJsonNull()) continue;

            try {
                readField(target, field, element);
            } catch (Exception e) {
                // One bad value must not discard the rest of a world's settings; that field just keeps
                // its default. Logged, because silently generating different terrain from what was
                // saved is the kind of thing someone will eventually need to explain.
                TerraForged.LOG.warn("Ignoring invalid setting {}={}: {}", field.getName(), element, e.toString());
            }
        }
    }

    private static void readField(Object target, Field field, JsonElement element) throws Exception {
        var type = field.getType();

        if (isLeaf(type)) {
            field.set(target, clampToRange(field, readLeaf(type, element)));
            return;
        }

        if (Map.class.isAssignableFrom(type)) {
            var valueType = (Class<?>) ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[1];
            var map = new LinkedHashMap<String, Object>();

            for (var entry : element.getAsJsonObject().entrySet()) {
                var value = valueType.getConstructor().newInstance();
                readInto(value, entry.getValue().getAsJsonObject());
                map.put(entry.getKey(), value);
            }

            field.set(target, map);
            return;
        }

        // Nested settings: read into the existing instance so fields absent from the JSON keep the
        // defaults that instance was constructed with.
        var nested = field.get(target);
        if (nested == null) {
            nested = type.getConstructor().newInstance();
            field.set(target, nested);
        }
        readInto(nested, element.getAsJsonObject());
    }

    /**
     * Pins a number to its field's {@code @Range}, returned in the field's own type.
     *
     * <p>A value already in range is returned <b>untouched</b>, not re-derived through a double, so a
     * valid setting round-trips bit for bit and a world reloads with exactly the settings it was created
     * with. Every shipped default was checked to sit inside its own range before this went in -- if one
     * did not, clamping would have silently changed every existing world on reload, since the world
     * stores every field.
     *
     * <p>Integral fields clamp to the whole numbers inside the range. NaN is rejected rather than pinned
     * to either end: the throw reaches {@link #readInto}, which logs it and keeps the default.
     */
    private static Object clampToRange(Field field, Object value) {
        var range = field.getAnnotation(Range.class);
        if (range == null || !(value instanceof Number number)) return value;

        var type = field.getType();
        boolean integral = type == int.class || type == Integer.class || type == long.class || type == Long.class;
        double v = number.doubleValue();
        if (Double.isNaN(v)) throw new IllegalArgumentException("NaN");

        double min = integral ? Math.ceil(range.min()) : range.min();
        double max = integral ? Math.floor(range.max()) : range.max();
        if (v >= min && v <= max) return value;

        double pinned = Math.max(min, Math.min(max, v));
        TerraForged.LOG.warn("Setting {}={} is outside {}..{}; using {}", field.getName(), value, min, max, pinned);

        if (type == int.class || type == Integer.class) return (int) pinned;
        if (type == long.class || type == Long.class) return (long) pinned;
        if (type == float.class || type == Float.class) return (float) pinned;
        return pinned;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object readLeaf(Class<?> type, JsonElement element) {
        if (type == int.class || type == Integer.class) return element.getAsInt();
        if (type == float.class || type == Float.class) return element.getAsFloat();
        if (type == double.class || type == Double.class) return element.getAsDouble();
        if (type == long.class || type == Long.class) return element.getAsLong();
        if (type == boolean.class || type == Boolean.class) return element.getAsBoolean();
        if (type == String.class) return element.getAsString();
        if (type.isEnum()) return Enum.valueOf((Class) type, element.getAsString());
        throw new IllegalArgumentException("Unsupported setting type " + type);
    }
}
