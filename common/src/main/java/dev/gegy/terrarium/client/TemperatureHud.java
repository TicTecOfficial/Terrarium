package dev.gegy.terrarium.client;

import dev.gegy.terrarium.client.TemperatureService.TemperatureData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Optional HUD overlay for displaying temperature information.
 * This can be used to show real-time temperature data on the player's screen.
 */
public class TemperatureHud {
    private static boolean enabled = false;
    private static @Nullable TemperatureData lastTemperatureData;
    private static long lastUpdateTime = 0;
    private static final long UPDATE_INTERVAL_MS = 5000; // Update every 5 seconds
    
    /**
     * Enable or disable the temperature HUD.
     * 
     * @param enabled Whether to show the temperature HUD
     */
    public static void setEnabled(final boolean enabled) {
        TemperatureHud.enabled = enabled;
        if (!enabled) {
            lastTemperatureData = null;
        }
    }
    
    /**
     * Check if the temperature HUD is enabled.
     * 
     * @return True if the HUD is enabled
     */
    public static boolean isEnabled() {
        return enabled;
    }
    
    /**
     * Render the temperature HUD on the screen.
     * This should be called from a client-side rendering event.
     * 
     * @param guiGraphics The GUI graphics context
     * @param screenWidth Screen width in pixels
     * @param screenHeight Screen height in pixels
     */
    public static void render(final GuiGraphics guiGraphics, final int screenWidth, final int screenHeight) {
        if (!enabled) {
            return;
        }
        
        final Minecraft minecraft = Minecraft.getInstance();
        final Player player = minecraft.player;
        
        if (player == null) {
            return;
        }
        
        // Update temperature data periodically
        final long currentTime = System.currentTimeMillis();
        if (currentTime - lastUpdateTime > UPDATE_INTERVAL_MS || lastTemperatureData == null) {
            updateTemperatureData(player);
            lastUpdateTime = currentTime;
        }
        
        // Render temperature display
        if (lastTemperatureData != null) {
            renderTemperatureDisplay(guiGraphics, screenWidth, screenHeight, lastTemperatureData);
        }
    }
    
    /**
     * Update temperature data asynchronously.
     * 
     * @param player The player to get temperature for
     */
    private static void updateTemperatureData(final Player player) {
        TemperatureService.getTemperatureDataAtPlayer(player).thenAccept(temperatureData -> {
            lastTemperatureData = temperatureData;
        }).exceptionally(throwable -> {
            // Silently handle errors - temperature data is optional
            return null;
        });
    }
    
    /**
     * Render the temperature display on screen.
     * 
     * @param guiGraphics The GUI graphics context
     * @param screenWidth Screen width
     * @param screenHeight Screen height
     * @param temperatureData Temperature data to display
     */
    private static void renderTemperatureDisplay(final GuiGraphics guiGraphics, final int screenWidth, final int screenHeight, final TemperatureData temperatureData) {
        final Minecraft minecraft = Minecraft.getInstance();
        
        // Position in top-right corner
        final String temperatureText = "🌡 " + temperatureData.getBriefDisplayString();
        final int textWidth = minecraft.font.width(temperatureText);
        final int x = screenWidth - textWidth - 10;
        final int y = 10;
        
        // Draw background
        guiGraphics.fill(x - 4, y - 2, x + textWidth + 4, y + minecraft.font.lineHeight + 2, 0x80000000);
        
        // Draw temperature text with color based on temperature
        final int color = getTemperatureColor(temperatureData.meanTemperature());
        guiGraphics.drawString(minecraft.font, temperatureText, x, y, color);
        
        // Draw description below if there's space
        final String description = temperatureData.getDescription();
        if (description != null && !description.isEmpty()) {
            final int descY = y + minecraft.font.lineHeight + 2;
            final int descWidth = minecraft.font.width(description);
            final int descX = screenWidth - descWidth - 10;
            
            guiGraphics.fill(descX - 4, descY - 2, descX + descWidth + 4, descY + minecraft.font.lineHeight + 2, 0x60000000);
            guiGraphics.drawString(minecraft.font, description, descX, descY, 0xAAAAAA);
        }
    }
    
    /**
     * Get color for temperature display based on temperature value.
     * 
     * @param temperature Temperature in Celsius
     * @return Color as RGB integer
     */
    private static int getTemperatureColor(final float temperature) {
        if (temperature < -20) {
            return 0x00FFFF; // Cyan - extremely cold
        } else if (temperature < -10) {
            return 0x55AAFF; // Light blue - very cold
        } else if (temperature < 0) {
            return 0x88CCFF; // Pale blue - cold
        } else if (temperature < 10) {
            return 0xAADDFF; // Very pale blue - cool
        } else if (temperature < 20) {
            return 0xFFFFFF; // White - mild
        } else if (temperature < 30) {
            return 0xFFDD88; // Light orange - warm
        } else if (temperature < 40) {
            return 0xFF8844; // Orange - hot
        } else {
            return 0xFF4444; // Red - extremely hot
        }
    }
    
    /**
     * Toggle the temperature HUD on/off.
     * 
     * @return New enabled state
     */
    public static boolean toggle() {
        enabled = !enabled;
        if (!enabled) {
            lastTemperatureData = null;
        }
        return enabled;
    }
}
