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

package com.terraforged.mod.platform.forge;

import com.mojang.serialization.Codec;
import com.terraforged.mod.TerraForged;
import com.terraforged.mod.command.TFCommands;
import com.terraforged.mod.lifecycle.CommonSetup;
import com.terraforged.mod.platform.forge.client.TFForgeClient;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.registries.DataPackRegistryEvent;
import net.minecraftforge.registries.RegisterEvent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The Forge entrypoint, the counterpart of the Fabric build's {@code TFMain} and the NeoForge build's
 * {@code TFNeoForge}. Everything it sets up is shared with the other builds; this class only routes it
 * through Forge's events.
 */
@Mod(TerraForged.MODID)
public class TFForge extends TerraForged {
    // Static on purpose: TerraForged's constructor declares the datapack registries (ModSetup) through
    // registerDataRegistry below, which runs before this subclass's instance fields are initialised.
    private static final List<Consumer<DataPackRegistryEvent.NewRegistry>> DATA_REGISTRIES = new ArrayList<>();
    private static final List<Consumer<RegisterEvent>> BUILT_INS = new ArrayList<>();

    public TFForge(FMLJavaModLoadingContext context) {
        super(TFForge::getRootPath);
        var modBus = context.getModEventBus();
        modBus.addListener(TFForge::onRegister);
        modBus.addListener(TFForge::onNewDataRegistries);
        MinecraftForge.EVENT_BUS.addListener(TFForge::onRegisterCommands);
        CommonSetup.STAGE.run();

        if (FMLEnvironment.dist == Dist.CLIENT) {
            TFForgeClient.init();
        }
    }

    /** Forge freezes the built-in registries outside {@link RegisterEvent}, so entries wait for it. */
    @Override
    public <T> void registerBuiltIn(Registry<T> registry, ResourceLocation id, T value) {
        var key = registry.key();
        BUILT_INS.add(event -> event.register(key, id, () -> value));
    }

    @Override
    public <T> void registerDataRegistry(ResourceKey<Registry<T>> key, Codec<T> codec) {
        DATA_REGISTRIES.add(event -> event.dataPackRegistry(key, codec));
    }

    /**
     * Forge's getter rather than the field: like NeoForge, Forge redirects every read of
     * {@code climateSettings} to it with a coremod (field_to_method.js), so the field cannot be
     * access-transformed the way the Fabric build widens it.
     */
    @Override
    public float getDownfall(Biome biome) {
        return biome.getModifiedClimateSettings().downfall();
    }

    private static void onRegister(RegisterEvent event) {
        // RegisterEvent fires once per registry, and register() ignores entries for other registries.
        BUILT_INS.forEach(entry -> entry.accept(event));
    }

    private static void onNewDataRegistries(DataPackRegistryEvent.NewRegistry event) {
        DATA_REGISTRIES.forEach(entry -> entry.accept(event));
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        TFCommands.register(event.getDispatcher());
    }

    /**
     * The root the bundled datapack is copied from: the jar itself in a built mod, the merged output
     * folder in a dev run.
     */
    private static Path getRootPath() {
        var file = ModList.get().getModFileById(MODID).getFile();
        var defaults = file.findResource("default");
        return defaults.getParent() != null ? defaults.getParent() : file.getFilePath();
    }
}
