package dev.gegy.terrarium.world.generator.chunk;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.gegy.terrarium.Terrarium;
import dev.gegy.terrarium.backend.GeoChunk;
import dev.gegy.terrarium.backend.earth.EarthAttachments;
import dev.gegy.terrarium.backend.earth.EarthConfiguration;
import dev.gegy.terrarium.backend.earth.EarthLayers;
import dev.gegy.terrarium.backend.earth.climate.RainfallRaster;
import dev.gegy.terrarium.backend.earth.climate.TemperatureRaster;
import dev.gegy.terrarium.backend.earth.cover.Cover;
import dev.gegy.terrarium.backend.earth.soil.SoilSuborder;
import dev.gegy.terrarium.backend.raster.EnumRaster;
import dev.gegy.terrarium.backend.raster.ShortRaster;
import dev.gegy.terrarium.backend.tile.GuavaTileCache;
import dev.gegy.terrarium.world.GeoProvider;
import dev.gegy.terrarium.world.GeoProviderHolder;
import dev.gegy.terrarium.world.generator.biome.GeoBiomeSource;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.SurfaceRuleData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

public class EarthChunkGenerator extends GeoChunkGenerator {
    public static final MapCodec<EarthChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            GeoBiomeSource.CODEC.fieldOf("biome_source").forGetter(EarthChunkGenerator::getBiomeSource),
            Codec.INT.fieldOf("min_y").forGetter(EarthChunkGenerator::getMinY),
            Codec.INT.fieldOf("height").forGetter(EarthChunkGenerator::getGenDepth),
            EarthConfiguration.CODEC.forGetter(c -> c.configuration)
    ).apply(i, EarthChunkGenerator::new));

    private static final SurfaceRules.RuleSource SURFACE_RULE = SurfaceRuleData.overworld();

    private final int minY;
    private final int height;
    private final int maxY;

    private final EarthConfiguration configuration;
    private final float heightScale;

    private final BlockState fillBlock = Blocks.STONE.defaultBlockState();
    private final BlockState fluidBlock = Blocks.WATER.defaultBlockState();

    public EarthChunkGenerator(final GeoBiomeSource biomeSource, final int minY, final int height, final EarthConfiguration configuration) {
        super(biomeSource);
        this.minY = minY;
        this.height = height;
        maxY = minY + height - 1;

        this.configuration = configuration;
        heightScale = configuration.heightScale() / configuration.projection().idealMetersPerBlock();
    }

    public EarthChunkGenerator withConfiguration(final EarthConfiguration configuration) {
        return new EarthChunkGenerator(getBiomeSource(), minY, height, configuration);
    }

    public EarthConfiguration configuration() {
        return configuration;
    }

    @Override
    public GeoProvider createGeoProvider() {
        return new GeoProvider(EarthLayers.create(
                Terrarium.createTiles(new GuavaTileCache(Duration.ofSeconds(30), 256)),
                configuration.projection(),
                Util.backgroundExecutor()
        ));
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public void applyCarvers(final WorldGenRegion region, final long seed, final RandomState randomState, final BiomeManager biomes, final StructureManager structures, final ChunkAccess chunk) {
    }

    @Override
    public void buildSurface(final WorldGenRegion region, final StructureManager structures, final RandomState randomState, final ChunkAccess chunk) {
        final GeoChunk geoChunk = getGeoChunk(chunk);

        final WorldGenerationContext context = new WorldGenerationContext(this, region);
        final BiomeManager biomeManager = region.getBiomeManager();
        final Registry<Biome> biomeRegistry = region.registryAccess().lookupOrThrow(Registries.BIOME);
        final NoiseChunk noiseChunk = getOrCreateDummyNoiseChunk(randomState, chunk, geoChunk);
        randomState.surfaceSystem().buildSurface(randomState, biomeManager, biomeRegistry, false, context, chunk, noiseChunk, SURFACE_RULE);
    }

    private NoiseChunk getOrCreateDummyNoiseChunk(final RandomState randomState, final ChunkAccess chunk, final GeoChunk geoChunk) {
        final ShortRaster elevation = geoChunk.get(EarthAttachments.ELEVATION);
        final DummyNoiseChunkFactory.SurfaceSampler surfaceSampler;
        if (elevation != null) {
            final ChunkPos chunkPos = chunk.getPos();
            final int minBlockX = chunkPos.getMinBlockX();
            final int minBlockZ = chunkPos.getMinBlockZ();
            surfaceSampler = (x, z) -> {
                // Surface builders query slightly out of range, but it's good enough to fudge and clamp it
                final int relativeX = Mth.clamp(x - minBlockX, 0, SectionPos.SECTION_MAX_INDEX);
                final int relativeZ = Mth.clamp(z - minBlockZ, 0, SectionPos.SECTION_MAX_INDEX);
                return transformElevationToY(elevation.getInt(relativeX, relativeZ));
            };
        } else {
            surfaceSampler = (x, z) -> minY;
        }
        return DummyNoiseChunkFactory.getOrCreate(chunk, randomState, surfaceSampler);
    }

    @Override
    public void spawnOriginalMobs(final WorldGenRegion region) {
    }

    @Override
    public int getGenDepth() {
        return height;
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(final Blender blender, final RandomState randomState, final StructureManager structures, final ChunkAccess chunk) {
        final ShortRaster elevation = getGeoChunk(chunk).get(EarthAttachments.ELEVATION);
        if (elevation != null) {
            fillSurface(chunk, elevation, fillBlock, fluidBlock, getSeaLevel());
        }
        return CompletableFuture.completedFuture(chunk);
    }

    private void fillSurface(final ChunkAccess chunk, final ShortRaster elevationRaster, final BlockState fillBlock, final BlockState fluidBlock, final int seaLevel) {
        final Heightmap oceanFloorHeightmap = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        final Heightmap worldSurfaceHeightmap = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);

        final int[] surfaceMap = new int[SectionPos.SECTION_SIZE * SectionPos.SECTION_SIZE];
        int maxY = seaLevel;
        for (int z = 0; z < SectionPos.SECTION_SIZE; z++) {
            for (int x = 0; x < SectionPos.SECTION_SIZE; x++) {
                final int surfaceY = transformElevationToY(elevationRaster.getInt(x, z));
                surfaceMap[x + z * SectionPos.SECTION_SIZE] = surfaceY;
                oceanFloorHeightmap.update(x, surfaceY, z, fillBlock);
                worldSurfaceHeightmap.update(x, Math.max(surfaceY, seaLevel), z, fluidBlock);
                if (surfaceY > maxY) {
                    maxY = surfaceY;
                }
            }
        }

        final int maxSectionY = SectionPos.blockToSectionCoord(maxY);
        final int minSectionY = chunk.getMinSectionY();
        for (int sectionY = maxSectionY; sectionY >= minSectionY; sectionY--) {
            final int sectionBottomY = SectionPos.sectionToBlockCoord(sectionY);
            final int sectionTopY = SectionPos.sectionToBlockCoord(sectionY, SectionPos.SECTION_MAX_INDEX);
            final LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sectionY));
            for (int z = 0; z < SectionPos.SECTION_SIZE; z++) {
                for (int x = 0; x < SectionPos.SECTION_SIZE; x++) {
                    final int surfaceY = surfaceMap[x + z * SectionPos.SECTION_SIZE];
                    final int topY = Math.max(surfaceY, seaLevel);
                    for (int y = Math.min(topY, sectionTopY); y >= sectionBottomY; y--) {
                        final BlockState block = y > surfaceY ? fluidBlock : fillBlock;
                        section.setBlockState(x, SectionPos.sectionRelative(y), z, block, false);
                    }
                }
            }
        }
    }

    private int transformElevationToY(final int elevation) {
        final int y = Mth.floor((elevation * heightScale) + configuration.heightOffset());
        return Mth.clamp(y, minY, maxY);
    }

    @Override
    public int getSeaLevel() {
        return configuration.heightOffset();
    }

    @Override
    public int getMinY() {
        return minY;
    }

    @Override
    public int getBaseHeight(final int x, final int z, final Heightmap.Types heightmap, final LevelHeightAccessor levelHeight, final RandomState randomState) {
        final int surfaceY = sampleBaseSurfaceY(x, z, randomState);
        if (heightmap.isOpaque().test(fluidBlock)) {
            return Math.max(surfaceY, getSeaLevel()) + 1;
        }
        return surfaceY + 1;
    }

    @Override
    public NoiseColumn getBaseColumn(final int x, final int z, final LevelHeightAccessor levelHeight, final RandomState randomState) {
        final int minY = levelHeight.getMinY();
        final int surfaceY = sampleBaseSurfaceY(x, z, randomState);
        final int topY = Math.max(surfaceY, getSeaLevel());
        final BlockState[] blocks = new BlockState[topY - minY];
        for (int i = 0; i < blocks.length; i++) {
            final int y = i + minY;
            blocks[i] = y > surfaceY ? fluidBlock : fillBlock;
        }
        return new NoiseColumn(minY, blocks);
    }

    private int sampleBaseSurfaceY(final int x, final int z, final RandomState randomState) {
        final GeoProvider geoProvider = GeoProviderHolder.get(randomState);
        if (geoProvider != null) {
            final ChunkPos chunkPos = new ChunkPos(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
            final ShortRaster elevation = geoProvider.getOrLoadSync(chunkPos).get(EarthAttachments.ELEVATION);
            if (elevation != null) {
                return transformElevationToY(elevation.getInt(SectionPos.sectionRelative(x), SectionPos.sectionRelative(z)));
            }
        }
        return minY;
    }

    @Override
    public void addDebugScreenInfo(final List<String> lines, final RandomState randomState, final BlockPos pos) {
        final double lat = configuration.projection().lat(pos.getX(), pos.getZ());
        final double lon = configuration.projection().lon(pos.getX(), pos.getZ());
        lines.add(String.format(Locale.ROOT, "Lat/Lon: %.3f / %.3f", lat, lon));
    }

    @Override
    public ChunkGeneratorStructureState createState(final HolderLookup<StructureSet> structureSetLookup, final RandomState randomState, final long seed) {
        final HolderLookup<StructureSet> filteredLookup = new HolderLookup<>() {
            @Override
            public Optional<Holder.Reference<StructureSet>> get(final ResourceKey<StructureSet> key) {
                return structureSetLookup.get(key).filter(set -> !shouldDropStructureSet(set.value()));
            }

            @Override
            public Optional<HolderSet.Named<StructureSet>> get(final TagKey<StructureSet> tag) {
                return structureSetLookup.get(tag);
            }

            @Override
            public Stream<Holder.Reference<StructureSet>> listElements() {
                return structureSetLookup.listElements().filter(set -> !shouldDropStructureSet(set.value()));
            }

            @Override
            public Stream<HolderSet.Named<StructureSet>> listTags() {
                return structureSetLookup.listTags();
            }
        };
        return ChunkGeneratorStructureState.createForNormal(randomState, seed, biomeSource, filteredLookup);
    }

    // TODO: The Stronghold's placement doesn't make too much sense (and the biome scan is too expensive) - should be replaced with something else
    private static boolean shouldDropStructureSet(final StructureSet set) {
        return set.placement() instanceof ConcentricRingsStructurePlacement;
    }

    @Override
    public void buildLod(final LodOutput output, final GeoChunk geoChunk, final GeoBiomeSource.FlatChunkResolver biomeResolver) {
        final Optional<EarthAttachments> earth = EarthAttachments.from(geoChunk);
        if (earth.isEmpty()) {
            return;
        }

        final ShortRaster elevation = earth.get().elevation();
        final EnumRaster<Cover> landCover = earth.get().landCover();
        final EnumRaster<SoilSuborder> soilSuborder = earth.get().soilSuborder();
        final TemperatureRaster meanTemperature = earth.get().meanTemperature();
        final RainfallRaster annualRainfall = earth.get().annualRainfall();
        final int seaLevel = getSeaLevel();

        // Enhanced LOD generation using land cover, soil, and climate data

        for (int z = 0; z < elevation.height(); z++) {
            for (int x = 0; x < elevation.width(); x++) {
                final Holder<Biome> biome = biomeResolver.get(x, z);
                output.beginColumn(x, z, biome);

                final int surfaceY = transformElevationToY(elevation.getInt(x, z));
                final Cover cover = landCover.get(x, z);
                final SoilSuborder soil = soilSuborder.get(x, z);
                final float temperature = meanTemperature.getTemperature(x, z);
                final float rainfall = annualRainfall.getRainfall(x, z);

                if (surfaceY >= seaLevel) {
                    // Land surface - use appropriate surface material
                    final BlockState surfaceMaterial = getLodSurfaceMaterial(cover, soil, temperature, rainfall, surfaceY);



                    output.addLayerUpTo(surfaceY, surfaceMaterial);
                } else {
                    // Underwater - use appropriate underwater material
                    final BlockState underwaterMaterial = getLodUnderwaterMaterial(cover, soil, temperature);
                    output.addLayerUpTo(surfaceY, underwaterMaterial);
                    output.addLayerUpTo(seaLevel, fluidBlock);
                }

                output.endColumn();
            }
        }
    }

    private BlockState getLodSurfaceMaterial(final Cover landCover, final SoilSuborder soilSuborder, final float temperature, final float rainfall, final int elevation) {
        // Very cold areas get snow (highest priority for visual accuracy)
        if (temperature < -5.0f) {
            return Blocks.SNOW_BLOCK.defaultBlockState();
        }

        // BLOCK-PERFECT: Hot and dry areas get sand (matches vanilla desert surface rules)
        if (temperature > 20.0f && rainfall < 400.0f) {
            return Blocks.SAND.defaultBlockState(); // Use only vanilla sand for all desert types
        }

        // High elevation areas get stone only if EXTREMELY high and not cold
        // Further reduced to minimize stone dominance - only true mountain peaks
        if (elevation > getSeaLevel() + 800 && temperature > 0.0f) {
            return Blocks.STONE.defaultBlockState();
        }

        // Surface material selection based on land cover, climate, and elevation

        return switch (landCover) {
            // BLOCK-PERFECT: Forest types - use only vanilla surface materials
            case BROADLEAF_EVERGREEN -> {
                if (temperature < 5.0f) {
                    yield Blocks.PODZOL.defaultBlockState(); // Cold forest (matches vanilla taiga)
                } else {
                    yield Blocks.GRASS_BLOCK.defaultBlockState(); // Temperate forest (matches vanilla forest)
                }
            }
            case BROADLEAF_DECIDUOUS, BROADLEAF_DECIDUOUS_CLOSED, BROADLEAF_DECIDUOUS_OPEN -> {
                if (temperature < 5.0f) {
                    yield Blocks.PODZOL.defaultBlockState(); // Cold deciduous (matches vanilla taiga)
                } else {
                    yield Blocks.GRASS_BLOCK.defaultBlockState(); // Temperate deciduous (matches vanilla forest)
                }
            }
            case NEEDLE_LEAF_EVERGREEN, NEEDLE_LEAF_EVERGREEN_CLOSED, NEEDLE_LEAF_EVERGREEN_OPEN,
                 NEEDLE_LEAF_DECIDUOUS, NEEDLE_LEAF_DECIDUOUS_CLOSED, NEEDLE_LEAF_DECIDUOUS_OPEN -> {
                if (temperature < -5.0f) {
                    yield Blocks.SNOW_BLOCK.defaultBlockState(); // Arctic (matches vanilla snowy taiga)
                } else {
                    yield Blocks.PODZOL.defaultBlockState(); // Boreal forest (matches vanilla taiga)
                }
            }
            case MIXED_LEAF_TYPE -> {
                if (temperature < 5.0f) {
                    yield Blocks.PODZOL.defaultBlockState(); // Cold mixed forest (matches vanilla taiga)
                } else {
                    yield Blocks.GRASS_BLOCK.defaultBlockState(); // Temperate mixed (matches vanilla forest)
                }
            }

            // BLOCK-PERFECT: Grassland - use only vanilla grassland materials
            case GRASSLAND, HERBACEOUS_COVER, HERBACEOUS_COVER_WITH_TREE_AND_SHRUB -> {
                if (temperature < -5.0f) {
                    yield Blocks.SNOW_BLOCK.defaultBlockState(); // Cold grassland (matches vanilla snowy plains)
                } else {
                    yield Blocks.GRASS_BLOCK.defaultBlockState(); // Normal grassland (matches vanilla plains)
                }
            }

            // BLOCK-PERFECT: Shrubland - use vanilla savanna-equivalent materials
            case SHRUBLAND, SHRUBLAND_EVERGREEN, SHRUBLAND_DECIDUOUS, TREE_AND_SHRUB_WITH_HERBACEOUS_COVER -> {
                if (temperature < -5.0f) {
                    yield Blocks.SNOW_BLOCK.defaultBlockState(); // Cold shrubland
                } else if (temperature > 25.0f && rainfall < 400.0f) {
                    yield Blocks.SAND.defaultBlockState(); // Desert shrubland (matches vanilla desert)
                } else {
                    yield Blocks.GRASS_BLOCK.defaultBlockState(); // Temperate shrubland (matches vanilla savanna)
                }
            }

            // Cropland - brown but should be farmland
            case RAINFED_CROPLAND, IRRIGATED_CROPLAND, CROPLAND_WITH_VEGETATION, VEGETATION_WITH_CROPLAND ->
                Blocks.FARMLAND.defaultBlockState();

            // BLOCK-PERFECT: Sparse vegetation - use vanilla materials
            case SPARSE_VEGETATION, SPARSE_TREE, SPARSE_SHRUB, SPARSE_HERBACEOUS_COVER -> {
                if (temperature > 25.0f && rainfall < 400.0f) {
                    yield Blocks.SAND.defaultBlockState(); // Desert sparse vegetation (matches vanilla desert)
                } else if (temperature < 0.0f) {
                    yield Blocks.SNOW_BLOCK.defaultBlockState(); // Cold sparse vegetation
                } else {
                    yield Blocks.COARSE_DIRT.defaultBlockState(); // Temperate sparse vegetation
                }
            }

            // Lichens and mosses (tundra-like)
            case LICHENS_AND_MOSSES ->
                temperature < 5.0f ? Blocks.SNOW_BLOCK.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState();

            // Bare areas - climate-aware selection with less stone
            case BARE, BARE_CONSOLIDATED -> {
                if (temperature > 30.0f && rainfall < 200.0f) {
                    yield Blocks.SAND.defaultBlockState(); // Hot desert bare areas
                } else if (temperature < -5.0f) {
                    yield Blocks.SNOW_BLOCK.defaultBlockState(); // Cold bare areas
                } else if (elevation > getSeaLevel() + 300) {
                    yield Blocks.STONE.defaultBlockState(); // Only high elevation bare rock
                } else {
                    yield Blocks.COARSE_DIRT.defaultBlockState(); // Lower elevation bare areas
                }
            }
            case BARE_UNCONSOLIDATED -> {
                if (temperature > 20.0f && rainfall < 500.0f) {
                    yield Blocks.SAND.defaultBlockState(); // Desert sand
                } else {
                    yield Blocks.GRAVEL.defaultBlockState(); // Temperate loose material
                }
            }

            // Urban areas - grey
            case URBAN ->
                Blocks.STONE_BRICKS.defaultBlockState();

            // Water areas (shouldn't reach here but just in case)
            case WATER ->
                Blocks.WATER.defaultBlockState();

            // Permanent snow - white
            case PERMANENT_SNOW ->
                Blocks.SNOW_BLOCK.defaultBlockState();

            // Flooded areas - should be green
            case FRESH_FLOODED_FOREST, SALINE_FLOODED_FOREST, FLOODED_VEGETATION ->
                Blocks.GRASS_BLOCK.defaultBlockState();

            // Tree or shrub cover (generic) - should be green
            case TREE_OR_SHRUB_COVER ->
                temperature > 15.0f ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.PODZOL.defaultBlockState();

            // Default fallback - climate-aware with reduced stone
            default -> {
                if (temperature < -5.0f) {
                    yield Blocks.SNOW_BLOCK.defaultBlockState(); // Cold areas
                } else if (temperature > 25.0f && rainfall < 400.0f) {
                    yield Blocks.SAND.defaultBlockState(); // Hot dry areas
                } else if (elevation > getSeaLevel() + 400) {
                    yield Blocks.STONE.defaultBlockState(); // Only very high elevation
                } else {
                    yield Blocks.GRASS_BLOCK.defaultBlockState(); // Default temperate
                }
            }
        };
    }

    private BlockState getLodUnderwaterMaterial(final Cover landCover, final SoilSuborder soilSuborder, final float temperature) {
        // Underwater materials based on what would be there if it wasn't underwater
        return switch (landCover) {
            case BARE_UNCONSOLIDATED, SPARSE_VEGETATION ->
                Blocks.SAND.defaultBlockState();
            case BARE, BARE_CONSOLIDATED, URBAN ->
                Blocks.STONE.defaultBlockState();
            case PERMANENT_SNOW ->
                temperature < -10.0f ? Blocks.PACKED_ICE.defaultBlockState() : Blocks.STONE.defaultBlockState();
            default ->
                Blocks.DIRT.defaultBlockState();
        };
    }
}
