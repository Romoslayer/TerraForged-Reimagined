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

import com.terraforged.engine.serialization.annotation.Comment;
import com.terraforged.engine.serialization.annotation.Range;
import com.terraforged.mod.worldgen.noise.continent.cell.CellShape;
import com.terraforged.mod.worldgen.noise.continent.cell.CellSource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every generator parameter the customize screen can change, saved with the world.
 *
 * <p>Modelled on 1.16.5's settings, page for page. 0.3.x replaced 0.2.x's continent and river generators,
 * so several old settings (wetlands, branch rivers, macro noise, smoothing, strata, bedrock) had nothing
 * left to drive; each is now backed by a real implementation rather than shown as a slider that does
 * nothing. Where 1.16.5 had a feature switched on that 0.3.x never had, it defaults to off here.
 *
 * <p><b>Every default here reproduces the behaviour that was hard-coded</b> in
 * {@code NoiseGenerator#createContinentNoise}, {@code ContinentConfig}, {@code TerrainBlender} or
 * {@code ErodedNoiseGenerator}, so a world with no saved settings generates exactly as it did before
 * this class existed. That was checked numerically: a fixed-seed sample of height, continent, river
 * and climate values across a 12000-block grid hashed identically before and after. Change a default
 * and existing worlds change with it.
 *
 * <p>Labels come from field names ("continentScale" reads "Continent Scale"), ranges from
 * {@link Range}, and tooltips from {@link Comment} — the same annotations 1.16.5 built its pages
 * from, which is also how those pages stayed in step with the settings.
 */
public class TerraSettings {
    public World world = new World();
    public Climate climate = new Climate();
    public Terrain terrain = new Terrain();
    public Rivers rivers = new Rivers();
    public Filters filters = new Filters();
    public Structures structures = new Structures();
    public Miscellaneous miscellaneous = new Miscellaneous();

    /**
     * Per-structure-set placement overrides, keyed by structure set id, so modded structures appear
     * here alongside vanilla's.
     *
     * <p>Empty by default and entries are only acted on where they differ from the structure set's
     * own placement — an untouched world gets exactly the structure state vanilla would build.
     */
    public static class Structures {
        public Map<String, SpreadEntry> spread = new LinkedHashMap<>();
        public Map<String, RingsEntry> rings = new LinkedHashMap<>();
    }

    /** Grid-placed structures: villages, temples, and most modded structures. */
    public static class SpreadEntry {
        @Range(min = 1, max = 256)
        @Comment("Average distance between attempts, in chunks")
        public int spacing;

        @Range(min = 0, max = 255)
        @Comment("Minimum distance between attempts, in chunks. Always kept below spacing.")
        public int separation;

        @Comment("Changes where this structure is placed without changing the world seed")
        public int salt;

        @Comment("Prevents this structure from generating")
        public boolean disabled;
    }

    /** Ring-placed structures: strongholds. */
    public static class RingsEntry {
        @Range(min = 0, max = 1023)
        @Comment("Distance of the first ring from the world origin, in units of six chunks")
        public int distance;

        @Range(min = 1, max = 1023)
        @Comment("How many structures the innermost ring holds; later rings hold more")
        public int spread;

        @Range(min = 1, max = 4095)
        @Comment("The total number of structures placed")
        public int count;

        @Comment("Changes where this structure is placed without changing the world seed")
        public int salt;

        @Comment("Places these structures only near their preferred biomes, as vanilla does")
        public boolean constrainToBiomes = true;

        @Comment("Prevents this structure from generating")
        public boolean disabled;
    }

    /**
     * The decorators and vanilla features 0.3.x still has. Every default is "on", which is what every
     * world did before these were switches.
     */
    public static class Miscellaneous {
        @Comment("Strips soil from steep slopes, exposing the rock beneath")
        public boolean erosionDecorator = true;

        @Comment("Clears snow from steep faces")
        public boolean naturalSnowDecorator = true;

        @Comment("Generates vanilla water springs")
        public boolean vanillaSprings = true;

        @Comment("Generates vanilla lava lakes, above and below ground")
        public boolean vanillaLavaLakes = true;

        @Comment("Generates vanilla lava springs")
        public boolean vanillaLavaSprings = true;

        @Comment("Smooths snow layers on gentle ground")
        public boolean smoothLayerDecorator = true;

        @Comment("Erosion exposes plain stone rather than the rock found beneath the soil")
        public boolean plainStoneErosion = false;

        @Comment("Uses TerraForged's own tree and plant placement. Off uses vanilla's.")
        public boolean customBiomeFeatures = true;

        @Comment("Replaces underground stone with bands of stone types")
        public boolean strataDecorator = false;

        @Range(min = 100, max = 2000)
        @Comment("The size of regions sharing one pattern of stone bands")
        public int strataRegionSize = 600;

        @Comment("Only uses stone types that ores can still generate in")
        public boolean oreCompatibleStoneOnly = true;

        @Range(min = 0F, max = 1F)
        @Comment("The share of steep mountain ground that uses mountain biomes")
        public float mountainBiomeUsage = 1F;

        @Comment("Adds giant caverns and winding tunnels below TerraForged's own cave layers, linking them to the surface")
        public boolean deepCaves = true;
    }

    public static class World {
        public Continent continent = new Continent();
        public ControlPoints controlPoints = new ControlPoints();
        public Properties properties = new Properties();
        public BedrockLayer bedrockLayer = new BedrockLayer();
        public Dimensions dimensions = new Dimensions();
    }

    public enum SpawnType { CONTINENT_CENTER, WORLD_ORIGIN }

    public enum ContinentType { MULTI, SINGLE }

    public static class Properties {
        @Comment("Where new players first spawn: the centre of the continent nearest the origin, or the origin itself")
        public SpawnType spawnType = SpawnType.CONTINENT_CENTER;
    }

    /**
     * Bedrock at the bottom of the world. Vanilla's own floor is kept while these hold their defaults --
     * changing any of them replaces it -- so existing worlds keep generating the floor they had.
     */
    public static class BedrockLayer {
        @Comment("The block used for the bedrock layer")
        public String material = "minecraft:bedrock";

        @Range(min = 0, max = 10)
        @Comment("How many layers of the material are always placed at the bottom of the world")
        public int minDepth = 1;

        @Range(min = 0, max = 10)
        @Comment("How many extra layers may be placed above the minimum, varying by position")
        public int variance = 4;

        public boolean isVanilla() {
            return "minecraft:bedrock".equals(material) && minDepth == 1 && variance == 4;
        }
    }

    /**
     * Which generator the Nether and End use. "default" takes them from the minecraft:normal world
     * preset, which is where mods such as Incendium and Nullscape install their own.
     */
    public static class Dimensions {
        @Comment("The world preset whose Nether is used. default = minecraft:normal, including mods that change it")
        public String nether = "default";

        @Comment("The world preset whose End is used. default = minecraft:normal, including mods that change it")
        public String end = "default";

        @Comment("Keeps dimensions added by datapacks and mods")
        public boolean includeExtraDimensions = true;
    }

    public static class Continent {
        @Range(min = 100, max = 2000)
        @Comment("Controls the size of continents. Rivers and lakes scale with it.")
        public int continentScale = 400;

        @Range(min = 0F, max = 1F)
        @Comment("How irregularly continents are laid out. Zero is a perfect grid.")
        public float continentJitter = 0.75F;

        @Comment("The grid continents are laid out on")
        public CellShape continentShape = CellShape.SQUARE;

        @Comment("The noise that shapes continent outlines")
        public CellSource continentNoise = CellSource.PERLIN;

        @Comment("MULTI generates many continents; SINGLE keeps only the one nearest the world origin")
        public ContinentType continentType = ContinentType.MULTI;

        @Range(min = 0F, max = 1F)
        @Comment("The share of the continent grid left as ocean. Higher values give fewer, more separated continents.")
        public float continentSkipping = 0.525F;

        @Range(min = 0.02F, max = 1F)
        @Comment("How much continents differ in size from one another")
        public float continentSizeVariance = 0.25F;

        @Range(min = 1, max = 5)
        @Comment("The number of noise octaves distorting continent outlines")
        public int continentNoiseOctaves = 3;

        @Range(min = 0F, max = 1F)
        @Comment("How strongly each successive octave contributes to continent outlines")
        public float continentNoiseGain = 0.3F;

        @Range(min = 1F, max = 5F)
        @Comment("How much finer each successive octave of continent outline noise is")
        public float continentNoiseLacunarity = 2.2F;
    }

    /** Continent noise values at which each band of land and sea begins. Must stay in ascending order. */
    public static class ControlPoints {
        @Range(min = 0F, max = 1F)
        @Comment("Controls the point above which deep oceans transition into shallow oceans")
        public float deepOcean = 0.05F;

        @Range(min = 0F, max = 1F)
        @Comment("Controls the point above which shallow oceans transition to coastal terrain")
        public float shallowOcean = 0.3F;

        @Range(min = 0F, max = 1F)
        @Comment("Controls how much of the coastal terrain is assigned to beach biomes")
        public float beach = 0.45F;

        @Range(min = 0F, max = 1F)
        @Comment("Controls the size of coastal regions")
        public float coast = 0.75F;

        @Range(min = 0F, max = 1F)
        @Comment("Controls the point above which the coast transitions to inland terrain")
        public float inland = 0.8F;
    }

    public static class Climate {
        public RangeValue temperature = new RangeValue(0, 7, 2, 0F, 0.98F, 0.1F);
        public RangeValue moisture = new RangeValue(8461, 6, 1, 0F, 1F, -0.05F);
        public BiomeShape biomeShape = new BiomeShape();
        public BiomeEdgeShape biomeEdgeShape = new BiomeEdgeShape();
    }

    public static class RangeValue {
        @Comment("Shifts this value's noise without changing the world seed")
        public int seedOffset;

        @Range(min = 1, max = 20)
        @Comment("The horizontal scale")
        public int scale;

        @Range(min = 1, max = 10)
        @Comment("How quickly values transition from an extremity")
        public int falloff;

        @Range(min = 0F, max = 1F)
        @Comment("The lower limit of the range")
        public float min;

        @Range(min = 0F, max = 1F)
        @Comment("The upper limit of the range")
        public float max;

        @Range(min = -1F, max = 1F)
        @Comment("The bias towards either end of the range")
        public float bias;

        public RangeValue() {}

        public RangeValue(int seedOffset, int scale, int falloff, float min, float max, float bias) {
            this.seedOffset = seedOffset;
            this.scale = scale;
            this.falloff = falloff;
            this.min = min;
            this.max = max;
            this.bias = bias;
        }
    }

    public static class BiomeShape {
        @Range(min = 50, max = 2000)
        @Comment("Controls the size of individual biomes")
        public int biomeSize = 220;

        @Range(min = 1, max = 500)
        @Comment("Controls the scale of shape distortion for biomes")
        public int biomeWarpScale = 150;

        @Range(min = 1, max = 500)
        @Comment("Controls the strength of shape distortion for biomes")
        public int biomeWarpStrength = 80;

        @Range(min = 0, max = 20)
        @Comment("Groups neighbouring biome cells into larger same-biome regions, in biome cells. 0 turns it off.")
        public int macroNoiseSize = 0;
    }

    public enum EdgeNoise { SIMPLEX, SIMPLEX2, PERLIN, PERLIN2, BILLOW, RIDGE, CUBIC }

    /** Ported from 0.2.x: a finer distortion of biome borders. Strength 0 turns it off; the default is 24. */
    public static class BiomeEdgeShape {
        @Comment("The type of noise distorting biome edges")
        public EdgeNoise type = EdgeNoise.SIMPLEX;

        @Range(min = 1, max = 500)
        @Comment("The horizontal scale of the edge distortion")
        public int scale = 24;

        @Range(min = 1, max = 5)
        @Comment("The number of noise octaves in the edge distortion")
        public int octaves = 2;

        @Range(min = 0F, max = 1F)
        @Comment("How strongly each successive octave contributes")
        public float gain = 0.5F;

        @Range(min = 1F, max = 5F)
        @Comment("How much finer each successive octave is")
        public float lacunarity = 2.65F;

        @Range(min = 0, max = 500)
        @Comment("How far biome edges are pushed, in blocks. 0 turns the distortion off.")
        public int strength = 24;
    }

    public static class Terrain {
        public General general = new General();

        /**
         * Per-terrain overrides, keyed by terrain name. A terrain with no entry keeps the weight its
         * datapack gave it and its noise unscaled, which is what lets a datapack add terrains without
         * this knowing about them. The screen fills entries in from the datapack's own weights, so
         * opening it and pressing Done changes nothing.
         */
        public Map<String, TerrainEntry> terrains = new LinkedHashMap<>();
    }

    public static class General {
        @Range(min = 125, max = 5000)
        @Comment("Controls the size of terrain regions")
        public int terrainRegionSize = 800;

        @Range(min = 0F, max = 1F)
        @Comment("How irregularly terrain regions are laid out")
        public float regionJitter = 0.8F;

        @Range(min = 0F, max = 1F)
        @Comment("How widely neighbouring terrain types blend into one another")
        public float regionBlending = 0.4F;

        @Comment("Shifts terrain-region placement without changing the world seed")
        public int terrainSeedOffset = 0;

        @Range(min = 0.1F, max = 2F)
        @Comment("Scales the height of all land terrain")
        public float globalVerticalScale = 1F;

        @Comment("Includes the ridged mountain variants")
        public boolean fancyMountains = true;

        @Range(min = 1F, max = 6F)
        @Comment("Widens the mountain terrains without lowering them. Higher gives longer, gentler faces.")
        public float mountainWidth = 3.5F;
    }

    public static class TerrainEntry {
        @Range(min = 0F, max = 10F)
        @Comment("How often this terrain appears relative to the others. Zero removes it.")
        public float weight = 1F;

        @Range(min = 0F, max = 2F)
        @Comment("Scales the height of this terrain")
        public float verticalScale = 1F;

        @Range(min = 0.1F, max = 5F)
        @Comment("Scales how stretched this terrain's features are")
        public float horizontalScale = 1F;

        @Range(min = 0F, max = 2F)
        @Comment("Raises or lowers this terrain's base level. 1 leaves it where the datapack put it.")
        public float baseScale = 1F;
    }

    public static class Rivers {
        @Comment("Shifts river placement without changing the world seed")
        public int seedOffset = 0;

        @Range(min = 0F, max = 1F)
        @Comment("How many lakes form along river networks")
        public float lakeDensity = 0.75F;

        @Range(min = 0F, max = 1F)
        @Comment("The share of river sources that become rivers. Lower values give fewer, sparser river networks.")
        public float riverDensity = 1F;

        @Range(min = 0.1F, max = 3F)
        @Comment("The smallest a lake gets, relative to its default size")
        public float lakeSizeMin = 1F;

        @Range(min = 0.1F, max = 3F)
        @Comment("The largest a lake gets, relative to its default size")
        public float lakeSizeMax = 1F;

        public Channel mainRivers = Channel.river();

        /** Headwater rivers: ones with no tributaries of their own. */
        public Channel branchRivers = Channel.river();

        public Channel lakes = Channel.lake();

        public Wetlands wetlands = new Wetlands();
    }

    /** One of 0.3.x's river or lake profiles. Widths are in blocks, before the continent scale. */
    public static class Channel {
        @Range(min = 0F, max = 1F)
        @Comment("How much the channel wears down the terrain around it")
        public float erosion = 0.075F;

        @Range(min = 0F, max = 50F)
        @Comment("The narrowest the water channel gets")
        public float bedWidthMin;

        @Range(min = 0F, max = 50F)
        @Comment("The widest the water channel gets")
        public float bedWidthMax;

        @Range(min = 0F, max = 100F)
        @Comment("The narrowest the banks either side get")
        public float bankWidthMin;

        @Range(min = 0F, max = 100F)
        @Comment("The widest the banks either side get")
        public float bankWidthMax;

        @Range(min = 0F, max = 400F)
        @Comment("The narrowest the valley carved around the channel gets")
        public float valleyWidthMin;

        @Range(min = 0F, max = 400F)
        @Comment("The widest the valley carved around the channel gets")
        public float valleyWidthMax;

        @Range(min = 0F, max = 20F)
        @Comment("The shallowest the water gets")
        public float bedDepthMin;

        @Range(min = 0F, max = 20F)
        @Comment("The deepest the water gets")
        public float bedDepthMax;

        @Range(min = 0F, max = 10F)
        @Comment("The lowest the banks sit below the surrounding land")
        public float bankDepthMin;

        @Range(min = 0F, max = 10F)
        @Comment("The furthest the banks sit below the surrounding land")
        public float bankDepthMax;

        /** Mirrors {@code RiverConfig}'s field defaults. */
        public static Channel river() {
            var c = new Channel();
            c.bedWidthMin = 1F;
            c.bedWidthMax = 7F;
            c.bankWidthMin = 3F;
            c.bankWidthMax = 30F;
            c.valleyWidthMin = 80F;
            c.valleyWidthMax = 200F;
            c.bedDepthMin = 1.25F;
            c.bedDepthMax = 5F;
            c.bankDepthMin = 1.25F;
            c.bankDepthMax = 3F;
            return c;
        }

        /** Mirrors {@code RiverConfig#lake()}. */
        public static Channel lake() {
            var c = new Channel();
            c.bedWidthMin = 8F;
            c.bedWidthMax = 15F;
            c.bankWidthMin = 30F;
            c.bankWidthMax = 45F;
            c.valleyWidthMin = 80F;
            c.valleyWidthMax = 120F;
            c.bedDepthMin = 2F;
            c.bedDepthMax = 8F;
            c.bankDepthMin = 1F;
            c.bankDepthMax = 1.5F;
            return c;
        }
    }

    public static class Filters {
        public Erosion erosion = new Erosion();
        public Smoothing smoothing = new Smoothing();
    }

    /** Ported from the 0.2.x engine's smoothing filter. Off by default: 0.3.x never smoothed. */
    public static class Smoothing {
        @Range(min = 0, max = 5)
        @Comment("Controls the number of smoothing iterations. 0 turns smoothing off.")
        public int iterations = 0;

        @Range(min = 0F, max = 5F)
        @Comment("Controls the smoothing radius")
        public float smoothingRadius = 1.75F;

        @Range(min = 0F, max = 1F)
        @Comment("Controls how strongly smoothing is applied")
        public float smoothingRate = 0.85F;
    }

    /**
     * Where swamps and other wetland biomes may form. 0.3.x has no separate wetland generator: wetland
     * biomes are confined to river valleys and coastal lowland, and these control that gate.
     */
    public static class Wetlands {
        @Range(min = 0F, max = 1F)
        @Comment("The share of eligible low, wet ground that may become wetland")
        public float chance = 1F;

        @Range(min = 0F, max = 1F)
        @Comment("How far from a river wetlands can reach. Higher values give wider wetlands.")
        public float riverSize = 0.75F;

        @Range(min = 0F, max = 1F)
        @Comment("How far inland from the coast wetlands can reach")
        public float coastSize = 0.7F;
    }

    public static class Erosion {
        @Range(min = 0, max = 1000)
        @Comment("Controls the number of simulated water droplets per chunk. Zero turns erosion off.")
        public int dropletsPerChunk = 350;

        @Range(min = 1, max = 50)
        @Comment("Controls the number of iterations that a single water droplet is simulated for")
        public int dropletLifetime = 25;

        @Range(min = 0F, max = 1F)
        @Comment("Controls the starting volume of water that a simulated water droplet carries")
        public float dropletVolume = 0.7F;

        @Range(min = 0.1F, max = 1F)
        @Comment("Controls the starting velocity of the simulated water droplet")
        public float dropletVelocity = 0.7F;

        @Range(min = 0F, max = 1F)
        @Comment("Controls how quickly material dissolves (during erosion)")
        public float erosionRate = 0.5F;

        @Range(min = 0F, max = 1F)
        @Comment("Controls how quickly material is deposited (during erosion)")
        public float depositeRate = 0.5F;
    }

    /** A deep copy, via the serializer, so nothing can share mutable state with the original. */
    public TerraSettings copy() {
        return SettingsSerializer.read(SettingsSerializer.write(this));
    }
}
