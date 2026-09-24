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

package com.terraforged.mod.hooks;

import com.terraforged.mod.TerraForged;
import com.terraforged.mod.util.ReflectionUtil;
import com.terraforged.mod.worldgen.datapack.DataPackExporter;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.FolderRepositorySource;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraft.world.level.validation.DirectoryValidator;

import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Gets TerraForged's built-in datapack in front of the world-creation screen, and preselects the
 * TerraForged world preset once it is there.
 */
public class DatapackHook {
    private static final String PACK_FILE_ID = "file/" + DataPackExporter.PACK_FILE_NAME;

    public static RepositorySource[] injectRepositorySource(RepositorySource[] sources) {
        var copy = Arrays.copyOf(sources, sources.length + 1);
        copy[sources.length] = new TerraForgedRepositorySource();
        return copy;
    }

    /**
     * Makes sure TerraForged's datapack exists in the repository and is selected.
     *
     * @return true if the selection changed, i.e. the caller needs to apply the new pack
     *         configuration. Returning false lets callers avoid re-applying (and re-reloading)
     *         a configuration that is already in effect, which would otherwise loop when this is
     *         called from the screen's init.
     */
    public static boolean ensureDatapack(PackRepository repository, Path dir, DirectoryValidator validator) {
        boolean wasSelected = repository.getSelectedIds().contains(PACK_FILE_ID);
        injectDatapack(repository, dir, validator);
        return !wasSelected;
    }

    public static void injectDatapack(PackRepository repository, Path dir, DirectoryValidator validator) {
        if (!repository.isAvailable(PACK_FILE_ID)) {
            // Copy default datapack to world's temp-dir
            DataPackExporter.createWorldDatapack(dir);

            // Scan temp-dir and insert pack entry into repository
            TerraForgedRepositorySource.inject(repository, dir, validator);

            TerraForged.LOG.info("Injected datapack {}", PACK_FILE_ID);
        }

        var selected = repository.getSelectedIds();
        if (!selected.contains(PACK_FILE_ID)) {
            // Make mutable & add the TF datapack id
            selected = new ArrayList<>(selected);
            selected.add(PACK_FILE_ID);

            // Update the repository with new selection
            repository.setSelected(selected);

            TerraForged.LOG.info("Selected datapack {}", PACK_FILE_ID);
        }

        // Confirms the selection actually stuck. If PACK_FILE_ID is absent here, the pack was
        // discovered but rejected (wrong pack_format, unreadable archive), and no amount of
        // re-selecting will help.
        TerraForged.LOG.info("Datapack selection is now: {}", repository.getSelectedIds());
    }

    /**
     * Preselects the TerraForged world preset in the create-world screen.
     *
     * <p>Upstream had to do this by finding the preset {@code CycleButton} among the screen's
     * children and pressing it repeatedly until the selection came round to a TerraForged preset.
     * That screen was rebuilt in 1.19.4: selection state now lives in {@link WorldCreationUiState},
     * so the preset can simply be set, with no dependence on widget layout or ordering.
     */
    public static void selectPreset(Object object) {
        if (!(object instanceof CreateWorldScreen screen)) return;

        var uiState = screen.getUiState();

        var current = uiState.getWorldType();
        if (current != null && isTerraForged(current)) return;

        var presets = uiState.getSettings().worldgenLoadContext().lookupOrThrow(Registries.WORLD_PRESET);
        presets.get(TerraForged.WORLD_PRESET).ifPresentOrElse(preset -> {
            uiState.setWorldType(new WorldCreationUiState.WorldTypeEntry(preset));
            TerraForged.LOG.info("Selected terraforged world_preset");
        }, () -> reportPresets("screen init", uiState));
    }

    /**
     * Logs whether the TerraForged preset is present, and what else is, at a given moment.
     *
     * <p>Called both at screen init and after every settings reload, because those answer different
     * questions: at init the datapack has not been injected yet, so only the post-reload report shows
     * whether selecting the pack actually registered the preset.
     */
    public static void reportPresets(String when, WorldCreationUiState uiState) {
        var presets = uiState.getSettings().worldgenLoadContext().lookupOrThrow(Registries.WORLD_PRESET);
        var ids = presets.listElements().map(entry -> entry.key().identifier().toString()).sorted().toList();

        if (ids.contains(TerraForged.WORLD_PRESET.toString())) {
            TerraForged.LOG.info("[{}] {} is registered ({} presets available)",
                    when, TerraForged.WORLD_PRESET, ids.size());
        } else {
            TerraForged.LOG.warn("[{}] {} is NOT registered. {} presets available: {}",
                    when, TerraForged.WORLD_PRESET, ids.size(), ids);
        }
    }

    private static boolean isTerraForged(WorldCreationUiState.WorldTypeEntry entry) {
        return entry.preset().unwrapKey()
                .filter(key -> key.identifier().getNamespace().equals(TerraForged.MODID))
                .isPresent();
    }

    public static class TerraForgedRepositorySource implements RepositorySource {
        private static final MethodHandle PACK_SOURCES = ReflectionUtil.field(PackRepository.class, Set.class);
        private static final RepositorySource NOOP = consumer -> {};

        protected RepositorySource source = NOOP;

        public void setDir(Path path, DirectoryValidator validator) {
            // PackSource.WORLD, because this pack really is served out of the world's own datapack
            // directory -- it is what vanilla uses for that folder. The explicit setSelected call in
            // injectDatapack means the selection does not depend on the source's auto-add behaviour.
            source = new FolderRepositorySource(path, PackType.SERVER_DATA, PackSource.WORLD, validator);
        }

        @Override
        public void loadPacks(Consumer<Pack> consumer) {
            source.loadPacks(consumer);
        }

        public static void inject(PackRepository repository, Path dir, DirectoryValidator validator) {
            try {
                var set = (Set<?>) PACK_SOURCES.invokeExact(repository);

                for (var object : set) {
                    if (object instanceof TerraForgedRepositorySource source) {
                        source.setDir(dir, validator);
                        // Rediscover, so the pack we just wrote is actually available by the time
                        // injectDatapack calls setSelected -- PackRepository#rebuildSelected silently
                        // drops ids it has not discovered, which would leave the pack unselected.
                        repository.reload();
                        return;
                    }
                }
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }
    }
}
