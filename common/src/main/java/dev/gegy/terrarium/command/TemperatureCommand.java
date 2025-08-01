package dev.gegy.terrarium.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import dev.gegy.terrarium.client.TemperatureHud;
import dev.gegy.terrarium.client.TemperatureService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * Command to display the current temperature at the player's location.
 * Usage: /temperature
 */
public class TemperatureCommand {
    
    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("temperature")
                .requires(source -> source.hasPermission(0)) // Allow all players
                .executes(TemperatureCommand::execute)
                .then(Commands.literal("hud")
                        .executes(TemperatureCommand::toggleHud)
                )
        );

        // Also register shorter alias
        dispatcher.register(Commands.literal("temp")
                .requires(source -> source.hasPermission(0))
                .executes(TemperatureCommand::execute)
                .then(Commands.literal("hud")
                        .executes(TemperatureCommand::toggleHud)
                )
        );
    }
    
    private static int execute(final CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        final Player player = source.getPlayer();
        
        if (player == null) {
            source.sendFailure(Component.literal("This command can only be used by players"));
            return 0;
        }
        
        // Send initial message
        source.sendSuccess(() -> Component.literal("Getting temperature data..."), false);
        
        // Get temperature data asynchronously
        TemperatureService.getTemperatureDataAtPlayer(player).thenAccept(temperatureData -> {
            if (temperatureData == null) {
                source.sendFailure(Component.literal("Temperature data not available at this location"));
                return;
            }
            
            // Create formatted message
            final Component message = Component.literal("")
                    .append(Component.literal("🌡 Temperature: ").withStyle(style -> style.withColor(0x55AAFF)))
                    .append(Component.literal(temperatureData.getBriefDisplayString()).withStyle(style -> style.withColor(getTemperatureColor(temperatureData.meanTemperature()))))
                    .append(Component.literal(" (").withStyle(style -> style.withColor(0xAAAAAA)))
                    .append(Component.literal(temperatureData.getDescription()).withStyle(style -> style.withColor(0xAAAAAA)))
                    .append(Component.literal(")").withStyle(style -> style.withColor(0xAAAAAA)));
            
            source.sendSuccess(() -> message, false);
            
            // Also show detailed info if min temperature is significantly different
            if (Math.abs(temperatureData.meanTemperature() - temperatureData.minTemperature()) > 2.0f) {
                final Component detailMessage = Component.literal("")
                        .append(Component.literal("  Detailed: ").withStyle(style -> style.withColor(0x888888)))
                        .append(Component.literal(temperatureData.getDisplayString()).withStyle(style -> style.withColor(0xCCCCCC)));
                
                source.sendSuccess(() -> detailMessage, false);
            }
        }).exceptionally(throwable -> {
            source.sendFailure(Component.literal("Failed to get temperature data: " + throwable.getMessage()));
            return null;
        });
        
        return 1;
    }

    private static int toggleHud(final CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        final Player player = source.getPlayer();

        if (player == null) {
            source.sendFailure(Component.literal("This command can only be used by players"));
            return 0;
        }

        final boolean newState = TemperatureHud.toggle();

        if (newState) {
            source.sendSuccess(() -> Component.literal("🌡 Temperature HUD enabled"), false);
        } else {
            source.sendSuccess(() -> Component.literal("🌡 Temperature HUD disabled"), false);
        }

        return 1;
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
}
