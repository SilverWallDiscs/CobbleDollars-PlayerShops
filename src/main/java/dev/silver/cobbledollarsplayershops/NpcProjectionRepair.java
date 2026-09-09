package dev.silver.cobbledollarsplayershops;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * Small defensive repair hook. ShopNpcManager owns projections; this hook gives it an
 * additional chance to reconcile after unusual chunk/entity tracking transitions.
 */
public final class NpcProjectionRepair {
    private static long ticks;
    private NpcProjectionRepair() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++ticks % 100 != 0) return;
            CobbleDollarsPlayerShops mod = CobbleDollarsPlayerShops.INSTANCE;
            if (mod != null && mod.npcManager() != null) mod.npcManager().maintain();
        });
    }
}
