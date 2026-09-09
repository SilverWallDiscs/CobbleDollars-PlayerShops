package dev.silver.cobbledollarsplayershops;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/** Optional Flan bridge. When Flan is loaded, the custom claim permission is fail-closed. */
public final class FlanCompat {
    public static final Identifier SPAWN_SHOP_NPC = Identifier.of("cobbledollars_playershops", "spawn_player_shop_npc");
    private static boolean warnedMissingPermission;
    private static boolean warnedCheckFailure;
    private FlanCompat() {}

    public static boolean isLoaded() { return FabricLoader.getInstance().isModLoaded("flan"); }

    public static boolean canSpawnNpc(ServerPlayerEntity player, BlockPos pos) {
        if (!isLoaded()) return true;
        try {
            Class<?> permissionManagerClass = Class.forName("io.github.flemmli97.flan.api.permission.PermissionManager");
            Object manager = getPermissionManager(permissionManagerClass);
            if (manager == null) {
                warnMissingPermission("Flan PermissionManager is not ready yet.");
                return false;
            }
            Object permission = permissionManagerClass.getMethod("get", Identifier.class).invoke(manager, SPAWN_SHOP_NPC);
            if (permission == null) {
                warnMissingPermission("Flan did not register the PlayerShops claim permission. Check datapack loading.");
                return false;
            }

            Class<?> claimHandlerClass = Class.forName("io.github.flemmli97.flan.api.ClaimHandler");
            Object result = claimHandlerClass.getMethod("canInteract", ServerPlayerEntity.class, BlockPos.class, Identifier.class)
                    .invoke(null, player, pos, SPAWN_SHOP_NPC);
            return Boolean.TRUE.equals(result);
        } catch (Throwable e) {
            if (!warnedCheckFailure) {
                warnedCheckFailure = true;
                CobbleDollarsPlayerShops.LOGGER.error("Flan permission check failed. PlayerShop spawning is denied to avoid bypassing claim permissions.", e);
            }
            return false;
        }
    }

    private static Object getPermissionManager(Class<?> permissionManagerClass) throws ReflectiveOperationException {
        try { return permissionManagerClass.getMethod("getInstance").invoke(null); }
        catch (NoSuchMethodException ignored) { return permissionManagerClass.getField("INSTANCE").get(null); }
    }

    private static void warnMissingPermission(String detail) {
        if (warnedMissingPermission) return;
        warnedMissingPermission = true;
        CobbleDollarsPlayerShops.LOGGER.error("{} Permission {} is required while Flan is installed.", detail, SPAWN_SHOP_NPC);
    }
}
