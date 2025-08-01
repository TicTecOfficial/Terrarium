package dev.gegy.terrarium.client;

import dev.gegy.terrarium.backend.earth.EarthAttachments;
import dev.gegy.terrarium.backend.earth.climate.TemperatureRaster;
import dev.gegy.terrarium.world.GeoProvider;
import dev.gegy.terrarium.world.GeoProviderHolder;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * Service for getting real-world temperature data at player locations.
 * Uses Terrarium's climate data to provide accurate temperature readings in Celsius.
 */
public class TemperatureService {
    
    /**
     * Get the current temperature at the player's location in Celsius.
     * 
     * @param player The player to get temperature for
     * @return CompletableFuture containing the temperature in Celsius, or null if not available
     */
    public static CompletableFuture<@Nullable Float> getTemperatureAtPlayer(final Player player) {
        if (!(player.level() instanceof ServerLevel serverLevel)) {
            return CompletableFuture.completedFuture(null);
        }
        
        final GeoProvider geoProvider = GeoProviderHolder.get(serverLevel);
        if (geoProvider == null) {
            return CompletableFuture.completedFuture(null);
        }
        
        final int blockX = (int) player.getX();
        final int blockZ = (int) player.getZ();
        final ChunkPos chunkPos = new ChunkPos(SectionPos.blockToSectionCoord(blockX), SectionPos.blockToSectionCoord(blockZ));
        
        return geoProvider.getOrLoad(chunkPos).thenApply(geoChunk -> {
            final TemperatureRaster meanTemperature = geoChunk.get(EarthAttachments.MEAN_TEMPERATURE);
            if (meanTemperature == null) {
                return null;
            }
            
            final int relativeX = SectionPos.sectionRelative(blockX);
            final int relativeZ = SectionPos.sectionRelative(blockZ);
            
            return meanTemperature.getTemperature(relativeX, relativeZ);
        });
    }
    
    /**
     * Get both mean and minimum temperature at the player's location.
     * 
     * @param player The player to get temperature for
     * @return CompletableFuture containing temperature data, or null if not available
     */
    public static CompletableFuture<@Nullable TemperatureData> getTemperatureDataAtPlayer(final Player player) {
        if (!(player.level() instanceof ServerLevel serverLevel)) {
            return CompletableFuture.completedFuture(null);
        }
        
        final GeoProvider geoProvider = GeoProviderHolder.get(serverLevel);
        if (geoProvider == null) {
            return CompletableFuture.completedFuture(null);
        }
        
        final int blockX = (int) player.getX();
        final int blockZ = (int) player.getZ();
        final ChunkPos chunkPos = new ChunkPos(SectionPos.blockToSectionCoord(blockX), SectionPos.blockToSectionCoord(blockZ));
        
        return geoProvider.getOrLoad(chunkPos).thenApply(geoChunk -> {
            final TemperatureRaster meanTemperature = geoChunk.get(EarthAttachments.MEAN_TEMPERATURE);
            final TemperatureRaster minTemperature = geoChunk.get(EarthAttachments.MIN_TEMPERATURE);
            
            if (meanTemperature == null) {
                return null;
            }
            
            final int relativeX = SectionPos.sectionRelative(blockX);
            final int relativeZ = SectionPos.sectionRelative(blockZ);
            
            final float mean = meanTemperature.getTemperature(relativeX, relativeZ);
            final float min = minTemperature != null ? minTemperature.getTemperature(relativeX, relativeZ) : mean;
            
            return new TemperatureData(mean, min);
        });
    }
    
    /**
     * Format temperature for display with appropriate precision and units.
     * 
     * @param temperatureCelsius Temperature in Celsius
     * @return Formatted temperature string (e.g., "23.5°C")
     */
    public static String formatTemperature(final float temperatureCelsius) {
        return String.format("%.1f°C", temperatureCelsius);
    }
    
    /**
     * Get a descriptive text for the temperature range.
     * 
     * @param temperatureCelsius Temperature in Celsius
     * @return Descriptive text (e.g., "Warm", "Cold", "Freezing")
     */
    public static String getTemperatureDescription(final float temperatureCelsius) {
        if (temperatureCelsius < -20) {
            return "Extremely Cold";
        } else if (temperatureCelsius < -10) {
            return "Very Cold";
        } else if (temperatureCelsius < 0) {
            return "Cold";
        } else if (temperatureCelsius < 10) {
            return "Cool";
        } else if (temperatureCelsius < 20) {
            return "Mild";
        } else if (temperatureCelsius < 30) {
            return "Warm";
        } else if (temperatureCelsius < 40) {
            return "Hot";
        } else {
            return "Extremely Hot";
        }
    }
    
    /**
     * Data class containing temperature information.
     */
    public record TemperatureData(float meanTemperature, float minTemperature) {
        
        /**
         * Get formatted display string for both temperatures.
         * 
         * @return Formatted string (e.g., "Mean: 23.5°C, Min: 18.2°C")
         */
        public String getDisplayString() {
            return String.format("Mean: %.1f°C, Min: %.1f°C", meanTemperature, minTemperature);
        }
        
        /**
         * Get a brief formatted display string.
         * 
         * @return Brief formatted string (e.g., "23.5°C")
         */
        public String getBriefDisplayString() {
            return formatTemperature(meanTemperature);
        }
        
        /**
         * Get temperature description based on mean temperature.
         * 
         * @return Descriptive text
         */
        public String getDescription() {
            return getTemperatureDescription(meanTemperature);
        }
    }
}
