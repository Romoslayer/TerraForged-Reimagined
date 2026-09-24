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

package com.terraforged.mod.worldgen.noise.continent;

import com.terraforged.engine.world.GeneratorContext;
import com.terraforged.engine.world.heightmap.ControlPoints;
import com.terraforged.mod.worldgen.noise.IContinentNoise;
import com.terraforged.mod.worldgen.noise.NoiseLevels;
import com.terraforged.mod.worldgen.noise.NoiseSample;
import com.terraforged.mod.worldgen.noise.continent.config.ContinentConfig;
import com.terraforged.mod.worldgen.noise.continent.config.FloatRange;
import com.terraforged.mod.worldgen.noise.continent.config.RiverConfig;
import com.terraforged.mod.worldgen.settings.TerraSettings;
import com.terraforged.mod.worldgen.terrain.TerrainLevels;
import com.terraforged.noise.Source;
import com.terraforged.noise.domain.Domain;

public class ContinentNoise implements IContinentNoise {
    protected final TerrainLevels levels;
    protected final GeneratorContext context;
    protected final ControlPoints controlPoints;
    protected final ContinentGenerator generator;

    protected final Domain warp;
    protected final float frequency;

    public ContinentNoise(TerrainLevels levels, GeneratorContext context) {
        this(levels, context, new TerraSettings());
    }

    public ContinentNoise(TerrainLevels levels, GeneratorContext context, TerraSettings settings) {
        this.levels = levels;
        this.context = context;
        this.controlPoints = new ControlPoints(context.settings.world.controlPoints);
        this.generator = createContinent(context, controlPoints, levels.noiseLevels, settings);

        this.frequency = 1F / context.settings.world.continent.continentScale;

        double strength = 0.2;

        // Continent Noise Octaves / Gain / Lacunarity. These were the fixed 3 / 0.3 / 2.2 until they were
        // settings, and those remain the defaults.
        var outline = settings.world.continent;
        var builder = Source.builder()
                .octaves(outline.continentNoiseOctaves)
                .lacunarity(outline.continentNoiseLacunarity)
                .frequency(3)
                .gain(outline.continentNoiseGain);

        this.warp = Domain.warp(
                builder.seed(context.seed.next()).perlin2(),
                builder.seed(context.seed.next()).perlin2(),
                Source.constant(strength)
        );
    }

    @Override
    public void sampleContinent(int seed, float x, float y, NoiseSample sample) {
        x *= frequency;
        y *= frequency;

        float px = warp.getX(seed, x, y);
        float py = warp.getY(seed, x, y);

        var offset = generator.getWorldOffset(seed);
        px += offset.x;
        py += offset.y;

        generator.shapeGenerator.sample(seed, px, py, sample);

        sample.terrainType = ContinentPoints.getTerrainType(sample.continentNoise);
    }

    @Override
    public void sampleRiver(int seed, float x, float y, NoiseSample sample) {
        x *= frequency;
        y *= frequency;

        float px = warp.getX(seed, x, y);
        float py = warp.getY(seed, x, y);

        var offset = generator.getWorldOffset(seed);
        px += offset.x;
        py += offset.y;

        generator.riverGenerator.sample(seed, px, py, sample);
    }

    @Override
    public GeneratorContext getContext() {
        return context;
    }

    @Override
    public ControlPoints getControlPoints() {
        return controlPoints;
    }

    /**
     * Builds the continent generator's config from the world's settings.
     *
     * <p>{@code ContinentConfig}'s own field defaults were what every world used until settings were
     * configurable; {@code TerraSettings}' defaults are copies of them, so an unconfigured world is
     * unchanged. The seeds are still drawn from the context in the same order — drawing them later,
     * or in between other reads, would shift every continent.
     */
    protected static ContinentGenerator createContinent(GeneratorContext context, ControlPoints controlPoints,
                                                        NoiseLevels levels, TerraSettings settings) {
        var config = new ContinentConfig();
        config.shape.scale = context.settings.world.continent.continentScale;
        config.shape.seed0 = context.seed.next();
        config.shape.seed1 = context.seed.next();

        var continent = settings.world.continent;
        config.shape.jitter = continent.continentJitter;
        config.shape.cellShape = continent.continentShape;
        config.shape.cellSource = continent.continentNoise;
        config.shape.threshold = continent.continentSkipping;
        config.shape.baseFalloffMax = Math.max(config.shape.baseFalloffMin + 0.01F, continent.continentSizeVariance);
        config.shape.singleContinent = continent.continentType == TerraSettings.ContinentType.SINGLE;
        config.shape.centreOnContinent = settings.world.properties.spawnType == TerraSettings.SpawnType.CONTINENT_CENTER;

        var rivers = settings.rivers;
        config.rivers.seed = rivers.seedOffset;
        config.rivers.lakeDensity = rivers.lakeDensity;
        apply(rivers.mainRivers, config.rivers.rivers);
        apply(rivers.lakes, config.rivers.lakes);
        apply(rivers.branchRivers, config.rivers.branchRivers);
        // Compared through the serializer so every channel field counts, including any added later.
        var riversJson = com.terraforged.mod.worldgen.settings.SettingsSerializer.write(settings)
                .getAsJsonObject("rivers");
        config.rivers.branchRiversDiffer = !riversJson.get("mainRivers").equals(riversJson.get("branchRivers"));
        config.rivers.riverDensity = rivers.riverDensity;
        config.rivers.lakeSizeMin = Math.min(rivers.lakeSizeMin, rivers.lakeSizeMax);
        config.rivers.lakeSizeMax = Math.max(rivers.lakeSizeMin, rivers.lakeSizeMax);

        return new ContinentGenerator(config, levels, controlPoints);
    }

    private static void apply(TerraSettings.Channel from, RiverConfig to) {
        to.erosion = from.erosion;
        set(to.bedWidth, from.bedWidthMin, from.bedWidthMax);
        set(to.bankWidth, from.bankWidthMin, from.bankWidthMax);
        set(to.valleyWidth, from.valleyWidthMin, from.valleyWidthMax);
        set(to.bedDepth, from.bedDepthMin, from.bedDepthMax);
        set(to.bankDepth, from.bankDepthMin, from.bankDepthMax);
    }

    /** Min and max are sliders the player moves independently, so either can end up above the other. */
    private static void set(FloatRange range, float a, float b) {
        range.min = Math.min(a, b);
        range.max = Math.max(a, b);
    }
}
