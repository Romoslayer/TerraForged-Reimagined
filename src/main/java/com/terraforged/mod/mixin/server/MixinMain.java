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

package com.terraforged.mod.mixin.server;

import com.mojang.serialization.Dynamic;
import com.terraforged.mod.hooks.ServerDatapackHook;
import net.minecraft.server.Main;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.dedicated.DedicatedServerProperties;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Dedicated-server startup: puts TerraForged's datapack into a new world whose {@code level-type} is
 * TerraForged's, before vanilla first configures the world's datapacks. See {@link ServerDatapackHook}.
 *
 * <p>{@code main} opens the world, builds its pack repository from it, then calls
 * {@code loadOrCreateConfig}, whose {@code levelDataTag} is null exactly when the world is new. The
 * repository is only scanned later, in {@code MinecraftServer.configurePackRepository}, so a pack
 * written at the head of {@code loadOrCreateConfig} is found and, being in the world's own folder,
 * enabled automatically.
 */
@Mixin(Main.class)
public class MixinMain {
    @ModifyArg(
            method = "main",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/packs/repository/ServerPacksSource;createPackRepository(Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;)Lnet/minecraft/server/packs/repository/PackRepository;"
            )
    )
    private static LevelStorageSource.LevelStorageAccess terraforged$rememberWorld(LevelStorageSource.LevelStorageAccess access) {
        ServerDatapackHook.setWorldDatapackDir(access.getLevelPath(LevelResource.DATAPACK_DIR));
        return access;
    }

    @Inject(method = "loadOrCreateConfig", at = @At("HEAD"))
    private static void terraforged$installDatapack(
            DedicatedServerProperties properties,
            @Nullable Dynamic<?> levelDataTag,
            boolean safeMode,
            PackRepository packRepository,
            CallbackInfoReturnable<WorldLoader.InitConfig> cir
    ) {
        if (levelDataTag == null) {
            ServerDatapackHook.installForNewWorld(properties, safeMode);
        }
    }
}
