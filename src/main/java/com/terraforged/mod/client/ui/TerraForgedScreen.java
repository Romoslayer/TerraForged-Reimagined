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

import com.terraforged.mod.TerraForged;
import com.terraforged.mod.client.ui.preview.RenderMode;
import com.terraforged.mod.client.ui.settings.SettingControls;
import com.terraforged.mod.client.ui.settings.SettingsList;
import com.terraforged.mod.worldgen.GeneratorPreset;
import com.terraforged.mod.worldgen.asset.TerrainNoise;
import com.terraforged.mod.worldgen.settings.TerraSettings;
import com.terraforged.mod.worldgen.terrain.TerrainLevels;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.PresetEditor;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.levelgen.WorldOptions;
import org.jspecify.annotations.Nullable;


import java.util.concurrent.ThreadLocalRandom;

/**
 * TerraForged's world settings screen, laid out after 1.16.5's: a page of settings on the left, the
 * preview map and its controls on the right, and {@code << Cancel Done >>} along the bottom.
 *
 * <p>The pages are Presets, World, Climate, Terrain, Rivers and Filters. Every control except the
 * World page's Properties block is built by reflection from {@link TerraSettings}; see
 * {@link SettingControls} for why.
 *
 * <p>Nothing touches the world until Done. The screen edits its own copy of the settings, and only
 * Done builds a generator from them and hands it to the create-world screen.
 *
 * <p><b>Lifecycle, because getting it wrong crashed the game once already.</b> The preview owns a
 * native texture. {@code init} runs again on every resize and page change and must reuse the widget;
 * {@code removed} runs whenever this screen stops being shown and must close it and clear the field,
 * so that a later {@code init} builds a fresh one rather than drawing into freed memory.
 */
public class TerraForgedScreen extends Screen {
    public static final PresetEditor EDITOR = TerraForgedScreen::new;

    private static final String KEY = "terraforged.screen.";

    private enum Page {
        PRESETS, WORLD, CLIMATE, TERRAIN, RIVERS, FILTERS, STRUCTURES, MISCELLANEOUS;

        Component title() {
            return Component.translatable(KEY + "page." + name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    // Session-wide, as in 1.16.5: toggles and preview framing survive closing and reopening the screen.
    private static boolean showTooltips = true;
    private static boolean showCoords = false;
    private static RenderMode renderMode = RenderMode.BIOME_TYPE;
    private static int zoom = 1;

    private static final int MARGIN = 10;
    private static final int GAP = 4;
    private static final int BUTTON_HEIGHT = 20;

    private final CreateWorldScreen parent;
    private final WorldCreationContext context;
    private final int dimensionMinY;
    private final int dimensionMaxY;

    private Page page = Page.PRESETS;
    private TerraSettings settings;
    private long seed;

    // TerrainLevels is immutable, so its fields are edited here and a new one is built when needed.
    private boolean autoScale;
    private float horizontalScale;
    private int minY;
    private int maxY;
    private int baseHeight;
    private int seaLevel;
    private int seaFloor;

    private @Nullable PreviewWidget preview;
    private @Nullable SettingsList settingsList;
    private @Nullable PresetList presetList;
    private @Nullable EditBox presetName;
    private @Nullable String selectedPreset;

    private int leftX, leftWidth, rightX, rightWidth, contentTop, contentBottom;

    public TerraForgedScreen(CreateWorldScreen parent, WorldCreationContext context) {
        super(Component.translatable(KEY + "title"));
        this.parent = parent;
        this.context = context;

        var dimensionType = context.worldgenLoadContext()
                .lookupOrThrow(Registries.DIMENSION_TYPE)
                .getOrThrow(BuiltinDimensionTypes.OVERWORLD)
                .value();
        this.dimensionMinY = dimensionType.minY();
        this.dimensionMaxY = dimensionType.minY() + dimensionType.height();

        // Start from the world's current generator if it is TerraForged's -- the player may be coming
        // back to adjust -- and from defaults otherwise.
        var generator = GeneratorPreset.getGenerator(context.selectedDimensions().overworld());
        readLevels(generator != null ? generator.getLevels() : TerrainLevels.DEFAULT.get());
        this.settings = generator != null ? generator.getSettings().copy() : new TerraSettings();

        String typed = parent.getUiState().getSeed();
        this.seed = WorldOptions.parseSeed(typed).orElse(context.options().seed());
    }

    // ---------------------------------------------------------------------------------------------
    // Levels

    private void readLevels(TerrainLevels levels) {
        autoScale = levels.noiseLevels.auto;
        // Stored as a frequency multiplier (coordinates are multiplied by it), so larger values make
        // terrain SMALLER. The control shows the inverse so that bigger means bigger, as the name says.
        horizontalScale = levels.noiseLevels.scale <= 0 ? 1F : 1F / levels.noiseLevels.scale;
        minY = levels.minY;
        maxY = levels.maxY;
        baseHeight = levels.baseHeight;
        seaLevel = levels.seaLevel;
        seaFloor = levels.seaFloor;
    }

    private TerrainLevels levels() {
        float scale = horizontalScale <= 0 ? 1F : 1F / horizontalScale;
        return new TerrainLevels(autoScale, scale, minY, maxY, baseHeight, seaLevel, seaFloor);
    }

    // ---------------------------------------------------------------------------------------------
    // Layout

    @Override
    protected void init() {
        leftX = MARGIN;
        leftWidth = (int) (width * 0.68F) - MARGIN;
        rightX = leftX + leftWidth + 8;
        rightWidth = width - MARGIN - rightX;
        contentTop = 30;
        contentBottom = height - 34;

        if (preview == null) {
            preview = new PreviewWidget(context.worldgenLoadContext());
        }
        preview.showCoords = showCoords;

        addTopButtons();
        addFooter();

        if (page == Page.PRESETS) {
            addPresetsPage();
        } else {
            addSettingsPage();
        }

        refreshPreview();
    }

    private void addTopButtons() {
        int w = 60;
        addRenderableWidget(Button.builder(Component.translatable(KEY + "coords"), b -> {
            showCoords = !showCoords;
            if (preview != null) preview.showCoords = showCoords;
        }).bounds(width - MARGIN - w * 2 - GAP, 6, w, BUTTON_HEIGHT).build());

        addRenderableWidget(Button.builder(Component.translatable(KEY + "tooltips"), b -> {
            showTooltips = !showTooltips;
            rebuildWidgets();
        }).bounds(width - MARGIN - w, 6, w, BUTTON_HEIGHT).build());
    }

    private void addFooter() {
        int arrow = 40, main = 70;
        int total = arrow * 2 + main * 2 + GAP * 3;
        int x = (width - total) / 2;
        int y = height - 26;

        var back = addRenderableWidget(Button.builder(Component.literal("<<"), b -> changePage(-1))
                .bounds(x, y, arrow, BUTTON_HEIGHT).build());
        x += arrow + GAP;
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(x, y, main, BUTTON_HEIGHT).build());
        x += main + GAP;
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> done())
                .bounds(x, y, main, BUTTON_HEIGHT).build());
        x += main + GAP;
        var next = addRenderableWidget(Button.builder(Component.literal(">>"), b -> changePage(1))
                .bounds(x, y, arrow, BUTTON_HEIGHT).build());

        back.active = page.ordinal() > 0;
        next.active = page.ordinal() < Page.values().length - 1;
    }

    private void changePage(int direction) {
        int index = Math.max(0, Math.min(Page.values().length - 1, page.ordinal() + direction));
        page = Page.values()[index];
        rebuildWidgets();
    }

    // ---------------------------------------------------------------------------------------------
    // Settings pages

    private void addSettingsPage() {
        settingsList = new SettingsList(minecraft, leftX, contentTop, leftWidth, contentBottom - contentTop);
        Runnable changed = this::refreshPreview;

        switch (page) {
            case WORLD -> {
                // Laid out in 1.16.5's order: Continent, Control Points, Properties, Bedrock Layer,
                // Dimensions. The last three mix TerraSettings with things it does not hold (the world's
                // TerrainLevels, the preset registry), so they are built here rather than reflectively.
                SettingControls.addObject(settingsList, settings.world, showTooltips, changed,
                        java.util.Set.of("properties", "bedrockLayer", "dimensions"));
                addProperties(settingsList);

                settingsList.addHeader(SettingControls.label("bedrockLayer"));
                SettingControls.addObject(settingsList, settings.world.bedrockLayer, showTooltips, () -> {});

                addDimensions(settingsList);
            }
            case CLIMATE -> SettingControls.addObject(settingsList, settings.climate, showTooltips, changed);
            case TERRAIN -> addTerrain(settingsList);
            case RIVERS -> SettingControls.addObject(settingsList, settings.rivers, showTooltips, changed);
            case FILTERS -> SettingControls.addObject(settingsList, settings.filters, showTooltips, changed);
            case STRUCTURES -> addStructures(settingsList);
            // Decorators run after terrain and do not show on the map either.
            case MISCELLANEOUS -> SettingControls.addObject(settingsList, settings.miscellaneous, showTooltips, () -> {});
            default -> {}
        }

        addRenderableWidget(settingsList);
        addPreviewControls();
    }

    /** World height and sea level live on {@link TerrainLevels}, not {@link TerraSettings}. */
    /** Nether and End choose from the world presets that define them; see {@code DimensionOverrides}. */
    private void addDimensions(SettingsList list) {
        var dims = settings.world.dimensions;
        var registries = context.worldgenLoadContext();
        list.addHeader(SettingControls.label("dimensions"));

        var netherOptions = com.terraforged.mod.worldgen.settings.DimensionOverrides.options(registries,
                net.minecraft.world.level.dimension.LevelStem.NETHER);
        var endOptions = com.terraforged.mod.worldgen.settings.DimensionOverrides.options(registries,
                net.minecraft.world.level.dimension.LevelStem.END);

        list.addControl(tipText(stringCycle(SettingControls.label("nether"), netherOptions, dims.nether,
                v -> dims.nether = v), "The world preset whose Nether is used. default keeps whatever the world gets, including mods that replace it."));
        list.addControl(tipText(stringCycle(SettingControls.label("end"), endOptions, dims.end,
                v -> dims.end = v), "The world preset whose End is used. default keeps whatever the world gets, including mods that replace it."));

        try {
            var field = TerraSettings.Dimensions.class.getField("includeExtraDimensions");
            list.addControl(SettingControls.forField(dims, field, showTooltips, () -> {}));
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }

    private static net.minecraft.client.gui.components.AbstractWidget stringCycle(Component label, java.util.List<String> options,
                                                                                 String current, java.util.function.Consumer<String> setter) {
        String value = options.contains(current) ? current : options.get(0);
        return net.minecraft.client.gui.components.CycleButton.<String>builder(Component::literal, value)
                .withValues(options)
                .create(0, 0, 150, 20, label, (button, v) -> setter.accept(v));
    }

    private <T extends net.minecraft.client.gui.components.AbstractWidget> T tipText(T widget, String text) {
        if (showTooltips) widget.setTooltip(Tooltip.create(Component.literal(text)));
        return widget;
    }

    private void addProperties(SettingsList list) {
        list.addHeader(Component.translatable(KEY + "properties"));

        try {
            var spawnType = TerraSettings.Properties.class.getField("spawnType");
            list.addControl(SettingControls.forField(settings.world.properties, spawnType, showTooltips, this::refreshPreview));
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }

        list.addControl(tip(SettingControls.slider(Component.translatable(KEY + "world_top"),
                TerrainLevels.Limits.MIN_MAX_Y, dimensionMaxY, true, () -> maxY, v -> { maxY = (int) v; refreshPreview(); }),
                "world_top"));
        list.addControl(tip(SettingControls.slider(Component.translatable(KEY + "world_bottom"),
                dimensionMinY, 0, true, () -> minY, v -> { minY = (int) v; refreshPreview(); }),
                "world_bottom"));
        list.addControl(tip(SettingControls.slider(Component.translatable(KEY + "sea_level"),
                TerrainLevels.Limits.MIN_SEA_LEVEL, 256, true, () -> seaLevel, v -> { seaLevel = (int) v; refreshPreview(); }),
                "sea_level"));
        list.addControl(tip(SettingControls.slider(Component.translatable(KEY + "sea_floor"),
                TerrainLevels.Limits.MIN_SEA_FLOOR, 128, true, () -> seaFloor, v -> { seaFloor = (int) v; refreshPreview(); }),
                "sea_floor"));
        list.addControl(tip(SettingControls.slider(Component.translatable(KEY + "base_height"),
                TerrainLevels.Limits.MIN_SEA_LEVEL, dimensionMaxY, true, () -> baseHeight, v -> { baseHeight = (int) v; refreshPreview(); }),
                "base_height"));
    }

    private void addTerrain(SettingsList list) {
        Runnable changed = this::refreshPreview;

        list.addHeader(Component.translatable(KEY + "general"));
        SettingControls.addObject(list, settings.terrain.general, showTooltips, changed);

        list.addControl(tip(SettingControls.toggle(Component.translatable(KEY + "auto_scale"), autoScale,
                v -> { autoScale = v; refreshPreview(); }), "auto_scale"));
        list.addControl(tip(SettingControls.slider(Component.translatable(KEY + "horizontal_scale"),
                0.1F, 5F, false, () -> horizontalScale, v -> { horizontalScale = (float) v; refreshPreview(); }),
                "horizontal_scale"));

        // One section per terrain the datapack registers. Entries are filled in from the datapack's own
        // weights, so leaving the page untouched saves weights equal to the ones already in effect.
        // Keyed by registry id, not terrain type -- several terrains share a type. See
        // GeneratorPreset#applyTerrainOverrides.
        var registries = context.worldgenLoadContext();
        var terrains = TerraForged.TERRAINS.entries(registries, TerrainNoise[]::new);
        var ids = TerraForged.TERRAINS.entryIds(registries);

        for (int i = 0; i < terrains.length; i++) {
            var terrain = terrains[i];
            String name = ids.get(i);

            var entry = settings.terrain.terrains.computeIfAbsent(name, n -> {
                var e = new TerraSettings.TerrainEntry();
                e.weight = terrain.weight();
                return e;
            });

            list.addHeader(SettingControls.title(name));
            SettingControls.addObject(list, entry, showTooltips, changed);
        }
    }

    /**
     * One section per structure set in the registry, which is what makes modded structures appear here.
     *
     * <p>Entries are filled from each set's own placement, so opening the page and pressing Done
     * changes nothing: {@code StructureOverrides} only replaces a set whose values actually differ.
     * Sets using a placement type other than vanilla's two are left out, since there is no general way
     * to know what their numbers mean.
     */
    private void addStructures(SettingsList list) {
        // Structures do not appear on the map, so edits here must not rebuild the preview generator.
        Runnable changed = () -> {};
        var sets = context.worldgenLoadContext().lookupOrThrow(Registries.STRUCTURE_SET).listElements()
                .sorted(java.util.Comparator.comparing(holder -> holder.key().identifier()))
                .toList();

        // Ring-placed sets (strongholds) first, as 1.16.5 put the stronghold at the top.
        for (var holder : sets) {
            if (!(holder.value().placement() instanceof net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement rings)) continue;

            String id = holder.key().identifier().toString();
            var entry = settings.structures.rings.computeIfAbsent(id, k -> {
                var e = new TerraSettings.RingsEntry();
                e.distance = rings.distance();
                e.spread = rings.spread();
                e.count = rings.count();
                e.salt = rings.salt();
                return e;
            });

            list.addHeader(structureTitle(holder.key().identifier()));
            SettingControls.addObject(list, entry, showTooltips, changed);
        }

        for (var holder : sets) {
            if (!(holder.value().placement() instanceof net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement spread)) continue;

            String id = holder.key().identifier().toString();
            var entry = settings.structures.spread.computeIfAbsent(id, k -> {
                var e = new TerraSettings.SpreadEntry();
                e.spacing = spread.spacing();
                e.separation = spread.separation();
                e.salt = spread.salt();
                return e;
            });

            list.addHeader(structureTitle(holder.key().identifier()));
            SettingControls.addObject(list, entry, showTooltips, changed);
        }
    }

    /** Vanilla's sets read as titles ("Villages"); anyone else's keep their namespace, as 1.16.5 showed them. */
    private static Component structureTitle(net.minecraft.resources.Identifier id) {
        return id.getNamespace().equals("minecraft") ? SettingControls.title(id.getPath()) : Component.literal(id.toString());
    }

    private <T extends net.minecraft.client.gui.components.AbstractWidget> T tip(T widget, String key) {
        if (showTooltips) widget.setTooltip(Tooltip.create(Component.translatable(KEY + key + ".tooltip")));
        return widget;
    }

    /** Zoom, render mode and New Seed above the map, as on 1.16.5's settings pages. */
    private void addPreviewControls() {
        int x = rightX + 6, w = rightWidth - 12, y = contentTop + 6;

        var zoomSlider = SettingControls.slider(Component.translatable(KEY + "zoom"),
                PreviewWidget.MIN_ZOOM, PreviewWidget.MAX_ZOOM, true, () -> zoom,
                v -> { zoom = (int) v; refreshPreview(); });
        zoomSlider.setRectangle(w, BUTTON_HEIGHT, x, y);
        addRenderableWidget(zoomSlider);
        y += BUTTON_HEIGHT + GAP;

        addRenderableWidget(Button.builder(Component.literal(renderMode.name()), b -> {
            renderMode = renderMode.next();
            b.setMessage(Component.literal(renderMode.name()));
            refreshPreview();
        }).bounds(x, y, w, BUTTON_HEIGHT).build());
        y += BUTTON_HEIGHT + GAP;

        addRenderableWidget(Button.builder(Component.translatable(KEY + "new_seed"), b -> {
            seed = ThreadLocalRandom.current().nextLong();
            // Written back to the create-world screen so the world is generated from the seed the map
            // is showing.
            parent.getUiState().setSeed(Long.toString(seed));
            refreshPreview();
        }).bounds(x, y, w, BUTTON_HEIGHT).build());
        y += BUTTON_HEIGHT + GAP;

        placePreview(x, y, w);
    }

    private void placePreview(int x, int y, int w) {
        if (preview == null) return;
        int size = Math.max(40, Math.min(w, contentBottom - 6 - y));
        preview.setRectangle(size, size, x + (w - size) / 2, y);
        addRenderableWidget(preview);
    }

    // ---------------------------------------------------------------------------------------------
    // Presets page

    private void addPresetsPage() {
        presetButtons.clear();
        presetList = new PresetList();
        addRenderableWidget(presetList);

        int x = rightX + 6, w = rightWidth - 12, y = contentTop + 6;

        presetName = new EditBox(font, x, y, w, BUTTON_HEIGHT, Component.translatable(KEY + "preset_name"));
        presetName.setHint(Component.translatable(KEY + "preset_name").withStyle(EditBox.SEARCH_HINT_STYLE));
        addRenderableWidget(presetName);
        y += BUTTON_HEIGHT + GAP;

        y = presetButton("create", x, y, w, true, this::createPreset);
        y = presetButton("load", x, y, w, false, this::loadPreset);
        y = presetButton("save", x, y, w, false, this::savePreset);
        y = presetButton("reset", x, y, w, true, this::resetSettings);
        y = presetButton("delete", x, y, w, false, this::deletePreset);
        y = presetButton("set_default", x, y, w, false, this::setDefaultPreset);

        placePreview(x, y, w);
        updatePresetButtons();
    }

    private final java.util.Map<String, Button> presetButtons = new java.util.HashMap<>();

    private int presetButton(String key, int x, int y, int w, boolean alwaysActive, Runnable action) {
        var button = Button.builder(Component.translatable(KEY + key), b -> action.run())
                .bounds(x, y, w, BUTTON_HEIGHT).build();
        if (!alwaysActive) presetButtons.put(key, button);
        addRenderableWidget(button);
        return y + BUTTON_HEIGHT + GAP;
    }

    private void updatePresetButtons() {
        var selected = selectedPreset == null ? null : Presets.get(selectedPreset).orElse(null);
        boolean any = selected != null;
        boolean editable = any && !selected.builtIn();

        setActive("load", any);
        setActive("save", editable);
        setActive("delete", editable);
        setActive("set_default", any);
    }

    private void setActive(String key, boolean active) {
        var button = presetButtons.get(key);
        if (button != null) button.active = active;
    }

    private void createPreset() {
        if (presetName == null) return;
        String name = presetName.getValue().trim();
        if (name.isEmpty()) return;

        if (Presets.save(name, levels(), settings)) {
            selectedPreset = Presets.sanitize(name);
            presetName.setValue("");
            rebuildWidgets();
        }
    }

    private void savePreset() {
        if (selectedPreset != null && Presets.save(selectedPreset, levels(), settings)) {
            rebuildWidgets();
        }
    }

    private void loadPreset() {
        if (selectedPreset == null) return;
        Presets.get(selectedPreset).ifPresent(preset -> {
            readLevels(preset.levels());
            settings = preset.settings().copy();
            refreshPreview();
        });
    }

    private void resetSettings() {
        readLevels(TerrainLevels.DEFAULT.get());
        settings = new TerraSettings();
        refreshPreview();
    }

    private void deletePreset() {
        if (selectedPreset != null && Presets.delete(selectedPreset)) {
            selectedPreset = null;
            rebuildWidgets();
        }
    }

    private void setDefaultPreset() {
        if (selectedPreset != null) {
            Presets.setDefault(selectedPreset);
            rebuildWidgets();
        }
    }

    private class PresetList extends ObjectSelectionList<PresetList.Entry> {
        private PresetList() {
            super(TerraForgedScreen.this.minecraft, leftWidth, contentBottom - contentTop, contentTop, 20);
            setX(leftX);

            String defaultName = Presets.defaultName();
            for (var preset : Presets.all()) {
                var entry = new Entry(preset, preset.name().equals(defaultName));
                addEntry(entry);
                if (preset.name().equals(selectedPreset)) super.setSelected(entry);
            }
        }

        @Override
        public int getRowWidth() {
            return (int) (width * 0.7F);
        }

        @Override
        public int getRowLeft() {
            return getX() + (int) (width * 0.15F);
        }

        @Override
        protected int scrollBarX() {
            return getX() + width - 6;
        }

        @Override
        protected void extractListBackground(GuiGraphicsExtractor graphics) {}

        @Override
        protected void extractListSeparators(GuiGraphicsExtractor graphics) {}

        @Override
        public void setSelected(@Nullable Entry entry) {
            super.setSelected(entry);
            selectedPreset = entry == null ? null : entry.preset.name();
            updatePresetButtons();
        }

        private class Entry extends ObjectSelectionList.Entry<Entry> {
            private final Presets.Preset preset;
            private final Component label;

            private Entry(Presets.Preset preset, boolean isDefault) {
                this.preset = preset;
                this.label = isDefault
                        ? Component.literal(preset.name()).append(Component.translatable(KEY + "is_default"))
                        : Component.literal(preset.name());
            }

            @Override
            public Component getNarration() {
                return Component.translatable("narrator.select", preset.name());
            }

            @Override
            public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
                graphics.text(font, label, getContentX() + 4, getContentY() + 5, 0xFFFFFFFF, true);
            }

            @Override
            public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
                PresetList.this.setSelected(this);
                if (doubleClick) loadPreset();
                return super.mouseClicked(event, doubleClick);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Preview, rendering, lifecycle

    private void refreshPreview() {
        if (preview != null) preview.update(levels(), settings, seed, renderMode, zoom);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);

        // The two dark panels behind the page and the preview column.
        graphics.fill(leftX, contentTop, leftX + leftWidth, contentBottom, 0xA0000000);
        graphics.fill(rightX, contentTop, rightX + rightWidth, contentBottom, 0xA0000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.text(font, page.title(), leftX + 2, 12, 0xFFFFFFFF, true);
    }

    private void done() {
        var levels = levels();
        var finalSettings = settings.copy();
        parent.getUiState().updateDimensions((registries, dimensions) -> {
            var withGenerator = dimensions.replaceOverworldGenerator(registries, GeneratorPreset.build(levels, finalSettings, registries));
            return com.terraforged.mod.worldgen.settings.DimensionOverrides.apply(registries, withGenerator,
                    finalSettings.world.dimensions);
        });
        onClose();
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    /**
     * Closes the preview's texture and forgets the widget. {@code removed} also runs when a dialog is
     * opened over this screen, so a later {@code init} must be able to build a new one — which is why
     * the field is cleared, not just the widget closed.
     */
    @Override
    public void removed() {
        if (preview != null) {
            preview.close();
            preview = null;
        }
    }
}
