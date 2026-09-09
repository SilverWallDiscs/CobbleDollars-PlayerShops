package dev.silver.cobbledollarsplayershops;

import fr.harmex.cobbledollars.common.utils.extensions.PlayerExtensionKt;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.math.BigInteger;
import java.util.UUID;

public final class CobbleDollarsEconomy {
    private final MinecraftServer server;

    public CobbleDollarsEconomy(MinecraftServer server) { this.server = server; }

    public BigInteger get(UUID playerId) {
        ServerPlayerEntity online = server.getPlayerManager().getPlayer(playerId);
        if (online != null) return PlayerExtensionKt.getCobbleDollars(online);
        BigInteger value = PlayerExtensionKt.getOfflineCobbleDollars(playerId, server);
        return value == null ? BigInteger.ZERO : value;
    }

    public void setOnline(ServerPlayerEntity player, BigInteger value) {
        if (value.signum() < 0) throw new IllegalArgumentException("Negative balance");
        PlayerExtensionKt.setCobbleDollars(player, value);
        PlayerExtensionKt.updateCobbleDollarsAccount(player);
    }

    public boolean add(UUID playerId, BigInteger amount) {
        if (amount.signum() < 0) throw new IllegalArgumentException("Negative add");
        ServerPlayerEntity online = server.getPlayerManager().getPlayer(playerId);
        if (online != null) {
            setOnline(online, PlayerExtensionKt.getCobbleDollars(online).add(amount));
            return true;
        }
        return PlayerExtensionKt.addOfflineCobbleDollars(playerId, server, amount);
    }
}
