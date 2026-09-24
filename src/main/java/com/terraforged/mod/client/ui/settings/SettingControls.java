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

package com.terraforged.mod.client.ui.settings;

import com.terraforged.engine.serialization.annotation.Comment;
import com.terraforged.engine.serialization.annotation.Range;
import com.terraforged.mod.worldgen.settings.SettingsSerializer;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * Builds settings controls — by reflection for {@code TerraSettings}, by hand for anything else.
 *
 * <p>Reflection is how 1.16.5 built its pages, and it is what keeps the pages honest: a control
 * exists because a field does, its range is the field's {@link Range}, and its tooltip is the field's
 * {@link Comment}. Nothing can be shown that the generator does not read, and nothing the generator
 * reads can be left off.
 */
public final class SettingControls {
    private SettingControls() {}

    /** "continentScale" reads "Continent Scale". */
    public static Component label(String fieldName) {
        var out = new StringBuilder();
        for (int i = 0; i < fieldName.length(); i++) {
            char c = fieldName.charAt(i);
            if (i == 0) {
                out.append(Character.toUpperCase(c));
            } else if (Character.isUpperCase(c) || (c == '_')) {
                out.append(' ');
                if (c != '_') out.append(c);
            } else {
                out.append(c);
            }
        }
        return Component.literal(out.toString());
    }

    /** "mountain_chain" or "hills_1" reads "Mountain Chain" or "Hills 1". */
    public static Component title(String id) {
        var words = id.replace('/', ' ').replace('_', ' ').split(" ");
        var out = new StringBuilder();
        for (var word : words) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return Component.literal(out.toString());
    }

    /**
     * Adds every field of a settings object: its own values first, then each nested object under a
     * heading of its field name. Maps are skipped — the only one, the terrain overrides, needs the
     * registry to know its keys and is laid out by the screen.
     */
    public static void addObject(SettingsList list, Object target, boolean tooltips, Runnable onChange) {
        addObject(list, target, tooltips, onChange, java.util.Set.of());
    }

    /** @param skip names of nested sections the caller lays out itself */
    public static void addObject(SettingsList list, Object target, boolean tooltips, Runnable onChange,
                                 java.util.Set<String> skip) {
        var fields = SettingsSerializer.fields(target.getClass());

        for (var field : fields) {
            if (SettingsSerializer.isLeaf(field.getType())) {
                list.addControl(forField(target, field, tooltips, onChange));
            }
        }

        for (var field : fields) {
            var type = field.getType();
            if (SettingsSerializer.isLeaf(type) || Map.class.isAssignableFrom(type)) continue;
            if (skip.contains(field.getName())) continue;

            list.addHeader(label(field.getName()));
            addObject(list, get(target, field), tooltips, onChange);
        }
    }

    public static AbstractWidget forField(Object target, Field field, boolean tooltips, Runnable onChange) {
        var type = field.getType();
        var label = label(field.getName());
        var comment = field.getAnnotation(Comment.class);
        var tooltip = tooltips && comment != null ? String.join(" ", comment.value()) : null;
        var range = field.getAnnotation(Range.class);

        AbstractWidget widget;

        if (type == String.class) {
            // Free text, as 1.16.5's Bedrock Layer material box was. Written back on every keystroke;
            // anything invalid is dealt with where the value is used.
            var box = new net.minecraft.client.gui.components.EditBox(
                    net.minecraft.client.Minecraft.getInstance().font, 150, 20, label);
            box.setMaxLength(256);
            box.setValue(String.valueOf(get(target, field)));
            box.setResponder(value -> {
                set(target, field, value);
                onChange.run();
            });
            widget = box;
        } else if (type == boolean.class) {
            widget = toggle(label, (boolean) get(target, field), value -> {
                set(target, field, value);
                onChange.run();
            });
        } else if (type.isEnum()) {
            widget = cycle(label, type.getEnumConstants(), get(target, field), value -> {
                set(target, field, value);
                onChange.run();
            });
        } else if (type == int.class && range == null) {
            // An unranged int is a seed offset. 1.16.5 made these a button that rolls a new value,
            // because a slider over two billion values is useless.
            widget = seed(label, () -> (int) get(target, field), value -> {
                set(target, field, value);
                onChange.run();
            });
        } else if (type == int.class) {
            widget = slider(label, range.min(), range.max(), true,
                    () -> (int) get(target, field),
                    value -> {
                        set(target, field, (int) Math.round(value));
                        onChange.run();
                    });
        } else if (type == float.class) {
            float min = range != null ? range.min() : 0F;
            float max = range != null ? range.max() : 1F;
            widget = slider(label, min, max, false,
                    () -> (float) get(target, field),
                    value -> {
                        set(target, field, (float) value);
                        onChange.run();
                    });
        } else {
            throw new IllegalArgumentException("No control for " + field);
        }

        if (tooltip != null) widget.setTooltip(Tooltip.create(Component.literal(tooltip)));
        return widget;
    }

    public static AbstractWidget slider(Component label, float min, float max, boolean integer,
                                        DoubleSupplier getter, DoubleConsumer setter) {
        return new Slider(label, min, max, integer, getter, setter);
    }

    public static AbstractWidget toggle(Component label, boolean value, Consumer<Boolean> setter) {
        return CycleButton.onOffBuilder(value).create(0, 0, 150, 20, label, (button, v) -> setter.accept(v));
    }

    public static <T> AbstractWidget cycle(Component label, T[] values, T value, Consumer<T> setter) {
        return CycleButton.<T>builder(v -> Component.literal(((Enum<?>) v).name()), value)
                .withValues(Arrays.asList(values))
                .create(0, 0, 150, 20, label, (button, v) -> setter.accept(v));
    }

    public static AbstractWidget seed(Component label, java.util.function.IntSupplier getter,
                                      java.util.function.IntConsumer setter) {
        var holder = new Button[1];
        holder[0] = Button.builder(CommonComponents.optionNameValue(label, Component.literal(String.valueOf(getter.getAsInt()))),
                button -> {
                    setter.accept(ThreadLocalRandom.current().nextInt(100000));
                    button.setMessage(CommonComponents.optionNameValue(label,
                            Component.literal(String.valueOf(getter.getAsInt()))));
                }).width(150).build();
        return holder[0];
    }

    private static Object get(Object target, Field field) {
        try {
            return field.get(target);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void set(Object target, Field field, @Nullable Object value) {
        try {
            field.set(target, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A slider over a real range, showing the value as 1.16.5 did: integers plain, floats to three
     * places, and snapping floats to those three places so the value saved is the value shown.
     */
    private static final class Slider extends AbstractSliderButton {
        private final Component label;
        private final float min;
        private final float max;
        private final boolean integer;
        private final DoubleSupplier getter;
        private final DoubleConsumer setter;

        private Slider(Component label, float min, float max, boolean integer, DoubleSupplier getter, DoubleConsumer setter) {
            super(0, 0, 150, 20, Component.empty(), normalize(getter.getAsDouble(), min, max));
            this.label = label;
            this.min = min;
            this.max = max;
            this.integer = integer;
            this.getter = getter;
            this.setter = setter;
            updateMessage();
        }

        private static double normalize(double value, float min, float max) {
            if (max <= min) return 0;
            return Math.max(0, Math.min(1, (value - min) / (max - min)));
        }

        private double current() {
            double raw = min + value * (max - min);
            return integer ? Math.round(raw) : Math.round(raw * 1000.0) / 1000.0;
        }

        @Override
        protected void updateMessage() {
            double v = current();
            String text = integer ? Long.toString(Math.round(v)) : String.format(Locale.ROOT, "%.3f", v);
            setMessage(CommonComponents.optionNameValue(label, Component.literal(text)));
        }

        @Override
        protected void applyValue() {
            if (current() != getter.getAsDouble()) setter.accept(current());
        }
    }
}
