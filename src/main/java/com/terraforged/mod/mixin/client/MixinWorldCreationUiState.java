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

package com.terraforged.mod.mixin.client;

import com.terraforged.mod.TerraForged;
import com.terraforged.mod.client.ui.Presets;
import com.terraforged.mod.client.ui.TerraForgedScreen;
import com.terraforged.mod.worldgen.GeneratorPreset;
import com.terraforged.mod.hooks.DatapackHook;
import net.minecraft.client.gui.screens.worldselection.PresetEditor;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Reports the world-preset registry every time the create-world screen's settings are replaced.
 *
 * <p>This is where the datapack reload lands. The check in {@code MixinCreateWorldScreen} runs during
 * {@code init}, which is always *before* TerraForged's datapack has been injected, so it can only ever
 * say "not there yet" — useless for telling apart "the pack was never applied" from "it was applied but
 * the preset did not register". Those look identical from outside and need completely different fixes.
 */
@Mixin(WorldCreationUiState.class)
public abstract class MixinWorldCreationUiState {
    @Inject(method = "setSettings", at = @At("RETURN"))
    private void onSetSettings(WorldCreationContext settings, CallbackInfo ci) {
        DatapackHook.reportPresets("settings reloaded", (WorldCreationUiState) (Object) this);
    }

    /**
     * Applies the player's default preset whenever TerraForged is chosen as the world type.
     *
     * <p>Hooked here, after vanilla's own body, because {@code setWorldType} rebuilds the dimensions
     * from the world preset every time it runs. Applying the default anywhere earlier — when the screen
     * opens, say — would be thrown away the moment the player cycled the world type away and back.
     */
    @Inject(method = "setWorldType", at = @At("RETURN"))
    private void onSetWorldType(WorldCreationUiState.WorldTypeEntry worldType, CallbackInfo ci) {
        var preset = worldType.preset();
        if (preset == null) return;
        if (preset.unwrapKey().filter(key -> key.identifier().equals(TerraForged.WORLD_PRESET)).isEmpty()) return;

        String name = Presets.defaultName();
        if (Presets.DEFAULT.equals(name)) return;

        Presets.get(name).ifPresent(p -> {
            var settings = p.settings().copy();
            ((WorldCreationUiState) (Object) this).updateDimensions((registries, dimensions) ->
                    com.terraforged.mod.worldgen.settings.DimensionOverrides.apply(registries,
                            dimensions.replaceOverworldGenerator(registries, GeneratorPreset.build(p.levels(), settings, registries)),
                            settings.world.dimensions));
        });
    }

    /**
     * Supplies TerraForged's preset editor, which enables the "Customize" button.
     *
     * <p>Vanilla looks the editor up in {@code PresetEditor.EDITORS}, a {@code Map.of(...)} holding
     * only the flat and single-biome presets. Being immutable, it cannot be added to, so the lookup
     * is answered here instead. That also keeps the editor out of vanilla's map entirely, so
     * nothing else that reads it sees a TerraForged entry it did not expect.
     *
     * <p>Only TerraForged's own preset is claimed; every other world type falls through to vanilla
     * and keeps whatever behaviour it had.
     */
    @Inject(method = "getPresetEditor", at = @At("HEAD"), cancellable = true)
    private void onGetPresetEditor(CallbackInfoReturnable<PresetEditor> cir) {
        var preset = ((WorldCreationUiState) (Object) this).getWorldType().preset();
        if (preset == null) return;

        boolean isTerraForged = preset.unwrapKey()
                .filter(key -> key.identifier().equals(TerraForged.WORLD_PRESET))
                .isPresent();

        if (isTerraForged) {
            cir.setReturnValue(TerraForgedScreen.EDITOR);
        }
    }
}
