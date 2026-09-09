package dev.silver.cobbledollarsplayershops;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;

import java.lang.reflect.Method;
import java.util.UUID;

/** Optional LuckPerms bridge without a hard runtime dependency. */
public final class PermissionCompat {
    public static final String ADMIN_NODE = "playershopadmin";
    private PermissionCompat() {}

    public static boolean isAdmin(ServerCommandSource source) {
        if (source.hasPermissionLevel(2)) return true;
        return source.getEntity() instanceof ServerPlayerEntity player && isAdmin(player);
    }

    public static boolean isAdmin(ServerPlayerEntity player) {
        if (player.hasPermissionLevel(2)) return true;
        if (!FabricLoader.getInstance().isModLoaded("luckperms")) return false;
        try {
            Class<?> providerClass = Class.forName("net.luckperms.api.LuckPermsProvider");
            Class<?> luckPermsClass = Class.forName("net.luckperms.api.LuckPerms");
            Class<?> userManagerClass = Class.forName("net.luckperms.api.model.user.UserManager");
            Class<?> userClass = Class.forName("net.luckperms.api.model.user.User");
            Class<?> cachedDataManagerClass = Class.forName("net.luckperms.api.cacheddata.CachedDataManager");
            Class<?> cachedPermissionDataClass = Class.forName("net.luckperms.api.cacheddata.CachedPermissionData");
            Class<?> tristateClass = Class.forName("net.luckperms.api.util.Tristate");

            Object luckPerms = providerClass.getMethod("get").invoke(null);
            Object userManager = luckPermsClass.getMethod("getUserManager").invoke(luckPerms);
            Method getUser = userManagerClass.getMethod("getUser", UUID.class);
            Object user = getUser.invoke(userManager, player.getUuid());
            if (user == null) return false;

            Object cachedData = userClass.getMethod("getCachedData").invoke(user);
            Object permissionData = cachedDataManagerClass.getMethod("getPermissionData").invoke(cachedData);
            Object result = cachedPermissionDataClass.getMethod("checkPermission", String.class).invoke(permissionData, ADMIN_NODE);
            return (boolean) tristateClass.getMethod("asBoolean").invoke(result);
        } catch (Throwable e) {
            CobbleDollarsPlayerShops.LOGGER.debug("LuckPerms permission lookup failed for {}", player.getUuid(), e);
            return false;
        }
    }
}
