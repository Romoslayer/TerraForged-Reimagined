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

import com.mojang.datafixers.util.Pair;
import com.terraforged.mod.hooks.DatapackHook;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.validation.DirectoryValidator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Desc;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Hooks the create-world screen so TerraForged's built-in datapack is present and its world preset
 * is preselected.
 *
 * <p>Both targets changed shape in the 1.19.4 screen rewrite: {@code getTempDataPackDir} became
 * {@link #getOrCreateTempDataPackDir()}, and {@code tryApplyNewDataPacks} gained a flag and a
 * callback. Neither is a rename of convenience — the temp directory is now created lazily on demand,
 * so asking for it is what brings it into existence.
 *
 * <p>The screen's own {@link DirectoryValidator} is passed through rather than a permissive one, so
 * the injected pack is subject to exactly the same symlink checks as any pack the player selects.
 */
@Mixin(CreateWorldScreen.class)
public abstract class MixinCreateWorldScreen {
    @Shadow
    private DirectoryValidator packValidator;

    // Target is private, so this cannot be abstract -- Mixin discards the body.
    @Shadow
    private Path getOrCreateTempDataPackDir() {
        throw new AssertionError();
    }

    @Shadow
    private Pair<Path, PackRepository> getDataPackSelectionSettings(WorldDataConfiguration dataConfiguration) {
        throw new AssertionError();
    }

    @Shadow
    private void tryApplyNewDataPacks(PackRepository repository, boolean isDataPackScreen,
                                      Consumer<WorldDataConfiguration> onAbort) {
        throw new AssertionError();
    }

    /**
     * Installs and selects TerraForged's datapack as soon as the screen opens.
     *
     * <p>It used to be injected at the head of {@code tryApplyNewDataPacks}, which only runs when the
     * datapack screen is *closed*. That produced a confusing sequence: on a fresh create-world screen
     * TerraForged was not offered as a world type, and opening Datapacks did not list it either —
     * you had to open Datapacks, close it (which finally triggered the injection), and open it again
     * before the pack appeared and the world type became selectable.
     *
     * <p>Doing it here means the pack is present and selected before the player looks at anything.
     * {@code ensureDatapack} reports whether the selection actually changed, and the new configuration
     * is only applied when it did — applying unconditionally would re-enter this method after the
     * reload puts the screen back up, and loop.
     *
     * <p>{@code false} for {@code isDataPackScreen} so a pack requesting experimental features cannot
     * pop a confirmation dialog over a screen the player just opened; that prompt belongs to the
     * datapack screen's own flow.
     */
    @Inject(target = @Desc(value = "init"), at = @At("RETURN"))
    private void onInit(CallbackInfo ci) {
        var screen = (CreateWorldScreen) (Object) this;
        var settings = getDataPackSelectionSettings(screen.getUiState().getSettings().dataConfiguration());

        if (settings != null
                && DatapackHook.ensureDatapack(settings.getSecond(), settings.getFirst(), packValidator)) {
            tryApplyNewDataPacks(settings.getSecond(), false, config -> {});
            return;
        }

        DatapackHook.selectPreset(this);
    }

    @Inject(
            target = @Desc(
                    value = "tryApplyNewDataPacks",
                    args = {PackRepository.class, boolean.class, Consumer.class}
            ),
            at = @At("HEAD")
    )
    private void onTryApplyNewDataPacks(PackRepository repository,
                                        boolean resetToDefault,
                                        Consumer<WorldDataConfiguration> onSuccess,
                                        CallbackInfo ci) {
        DatapackHook.injectDatapack(repository, getOrCreateTempDataPackDir(), packValidator);
    }
}
