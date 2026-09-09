package dev.silver.cobbledollarsplayershops;

import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.math.BigInteger;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class ChatInputManager {
    private final Map<UUID, InputSession> sessions = new ConcurrentHashMap<>();

    @FunctionalInterface
    private interface InputSession {
        boolean handle(ServerPlayerEntity sender, String input);
    }

    public void register() {
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            InputSession session = sessions.get(sender.getUuid());
            if (session == null) return true;

            String input = message.getContent().getString().trim();
            if (input.equalsIgnoreCase("cancel")) {
                sessions.remove(sender.getUuid());
                sender.sendMessage(Text.literal("Input cancelled.").formatted(Formatting.YELLOW), false);
                return false;
            }

            if (session.handle(sender, input)) sessions.remove(sender.getUuid());
            return false;
        });
    }

    public void askPrice(ServerPlayerEntity player, Consumer<BigInteger> callback) {
        sessions.put(player.getUuid(), (sender, input) -> {
            if (!input.matches("[0-9]+")) {
                sender.sendMessage(Text.literal("Invalid price. Use a whole non-negative number only, or type cancel.").formatted(Formatting.RED), false);
                return false;
            }
            try {
                BigInteger value = new BigInteger(input);
                ServerConfig config = CobbleDollarsPlayerShops.INSTANCE.config();
                if (!config.isPriceAllowed(value)) {
                    sender.sendMessage(Text.literal("Price must be between " + config.MinPrice + " and " + config.MaxPrice + " CobbleDollars.").formatted(Formatting.RED), false);
                    return false;
                }
                callback.accept(value);
                return true;
            } catch (Exception e) {
                sender.sendMessage(Text.literal("Invalid price. Use a whole non-negative number only.").formatted(Formatting.RED), false);
                return false;
            }
        });

        ServerConfig config = CobbleDollarsPlayerShops.INSTANCE.config();
        player.sendMessage(Text.literal("Type the price in chat (" + config.MinPrice + " - " + config.MaxPrice + " CobbleDollars). Whole numbers only. Type cancel to abort.").formatted(Formatting.GOLD), false);
    }

    public void askPlayerName(ServerPlayerEntity player, Consumer<String> callback) {
        sessions.put(player.getUuid(), (sender, input) -> {
            if (!input.matches("[A-Za-z0-9_]{1,16}")) {
                sender.sendMessage(Text.literal("Invalid Minecraft username. Use 1-16 letters, numbers or underscores, or type cancel.").formatted(Formatting.RED), false);
                return false;
            }
            callback.accept(input);
            return true;
        });
        player.sendMessage(Text.literal("Type the Minecraft username whose skin this NPC should use. Type cancel to abort.").formatted(Formatting.GOLD), false);
    }
}
