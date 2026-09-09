package dev.silver.cobbledollarsplayershops;

import com.mojang.authlib.GameProfile;
import fr.harmex.cobbledollars.common.utils.extensions.PlayerExtensionKt;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Keeps FakePlayer NPC projections synchronized with persistent ShopData. */
public final class ShopNpcManager {
    private static final double PROJECTION_RANGE_SQUARED = 128.0 * 128.0;
    private final MinecraftServer server;
    private final ShopStore store;
    private final Map<UUID, ShopNpcEntity> spawned = new HashMap<>();
    private final Set<ProjectionKey> visibleProjections = new HashSet<>();
    private final List<PendingTabRemoval> pendingTabRemovals = new ArrayList<>();
    private long serverTick;

    public ShopNpcManager(MinecraftServer server, ShopStore store) { this.server = server; this.store = store; }

    public void registerEvents() {
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClient || !(player instanceof ServerPlayerEntity serverPlayer) || !(entity instanceof ShopNpcEntity npc)) return ActionResult.PASS;
            ShopData shop = store.get(npc.shopId());
            if (shop == null) return ActionResult.SUCCESS;
            refreshMirror(npc, shop);
            if (serverPlayer.isSneaking()) {
                PlayerExtensionKt.openBank(serverPlayer, npc);
                return ActionResult.CONSUME;
            }
            PlayerExtensionKt.openShop(serverPlayer, npc, false);
            npc.getTradingPlayers().add(serverPlayer);
            return ActionResult.CONSUME;
        });

        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!(entity instanceof ShopNpcEntity npc)) return ActionResult.PASS;
            if (!world.isClient && player instanceof ServerPlayerEntity serverPlayer) {
                ShopData shop = store.get(npc.shopId());
                if (shop != null) {
                    boolean canConfigure = shop.adminShop ? PermissionCompat.isAdmin(serverPlayer)
                            : shop.ownerUuid.equals(serverPlayer.getUuid()) || PermissionCompat.isAdmin(serverPlayer);
                    if (canConfigure) Menus.openConfig(serverPlayer, shop);
                }
            }
            return ActionResult.SUCCESS;
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> {
            forgetViewer(handler.player.getUuid());
            CobbleDollarsPlayerShops.INSTANCE.service().recoverInterruptedStockEdits(handler.player);
            sendSkinProfiles(handler.player);
        }));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> forgetViewer(handler.player.getUuid()));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            serverTick++;
            processTabRemovals();
            if (serverTick % 20 == 0) maintain();
        });
    }

    public void maintain() {
        Set<UUID> valid = new HashSet<>();
        for (ShopData shop : store.all()) {
            valid.add(shop.id);
            ServerWorld world = world(shop);
            if (world == null) continue;
            BlockPos pos = BlockPos.ofFloored(shop.x, shop.y, shop.z);
            ShopNpcEntity existing = spawned.get(shop.id);
            if (!world.isChunkLoaded(pos)) {
                if (existing != null) { removeProjectionForAll(existing); existing.discard(); spawned.remove(shop.id); }
                continue;
            }
            if (existing == null || existing.isRemoved() || existing.getWorld() != world) {
                if (existing != null && !existing.isRemoved()) { removeProjectionForAll(existing); existing.discard(); }
                ShopNpcEntity npc = createNpc(world, shop);
                if (world.spawnEntity(npc)) {
                    spawned.put(shop.id, npc);
                    syncProjectionVisibility(npc);
                } else CobbleDollarsPlayerShops.LOGGER.warn("Could not spawn NPC projection for shop {}", shop.name);
            } else {
                existing.anchor(shop.x, shop.y, shop.z, shop.yaw, shop.pitch);
                refreshMirror(existing, shop);
                syncProjectionVisibility(existing);
            }
        }
        Iterator<Map.Entry<UUID, ShopNpcEntity>> it = spawned.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (!valid.contains(entry.getKey())) { removeProjectionForAll(entry.getValue()); entry.getValue().discard(); it.remove(); }
        }
        visibleProjections.removeIf(k -> !spawned.values().stream().anyMatch(n -> n.getUuid().equals(k.npcUuid())));
    }

    public ShopNpcEntity getSpawned(UUID shopId) {
        ShopNpcEntity npc = spawned.get(shopId);
        return npc == null || npc.isRemoved() ? null : npc;
    }

    public void despawn(UUID shopId) {
        ShopNpcEntity npc = spawned.remove(shopId);
        if (npc != null) { removeProjectionForAll(npc); npc.discard(); }
    }

    public void refresh(UUID shopId) { despawn(shopId); maintain(); }

    public void syncShop(UUID shopId) {
        ShopData shop = store.get(shopId);
        ShopNpcEntity npc = getSpawned(shopId);
        if (shop == null || npc == null) return;
        refreshMirror(npc, shop);
        for (ServerPlayerEntity viewer : server.getPlayerManager().getPlayerList()) {
            if (shouldProject(viewer, npc)) sendShopSyncReflectively(viewer, npc);
        }
    }

    private void sendShopSyncReflectively(ServerPlayerEntity player, ShopNpcEntity npc) {
        // CobbleDollars packet API changed during 2.0 betas; reflection keeps this bridge optional/version-tolerant.
        try {
            Class<?> packetClass = Class.forName("fr.harmex.cobbledollars.common.network.packets.s2c.SyncShopPacket");
            Object packet = null;
            for (var ctor : packetClass.getConstructors()) {
                if (ctor.getParameterCount() == 5) {
                    try { packet = ctor.newInstance(npc.getShop(), true, npc.getMerchantUUID(), 0, true); break; }
                    catch (Throwable ignored) {}
                }
            }
            if (packet == null) return;
            Class<?> networkClass = Class.forName("fr.harmex.cobbledollars.common.network.CobbleDollarsNetwork");
            Object network = networkClass.getField("INSTANCE").get(null);
            for (var method : networkClass.getMethods()) {
                if (!method.getName().equals("sendPacketToPlayer") || method.getParameterCount() != 2) continue;
                try { method.invoke(network, player, packet); return; } catch (Throwable ignored) {}
            }
        } catch (Throwable e) { CobbleDollarsPlayerShops.LOGGER.debug("Could not send live CobbleDollars shop sync to {}", player.getUuid(), e); }
    }

    public void refreshMirror(ShopNpcEntity npc, ShopData shop) {
        npc.setShop(CobbleShopBridge.build(shop, CobbleDollarsPlayerShops.INSTANCE.service(), server));
        npc.setMerchantUUID(npc.getUuid());
    }

    public boolean canResolveSkin(String name) { return SkinResolver.canResolve(server, name); }

    private ShopNpcEntity createNpc(ServerWorld world, ShopData shop) {
        UUID visualUuid = UUID.nameUUIDFromBytes(("cobbledollars-playershop:" + shop.id).getBytes(StandardCharsets.UTF_8));
        GameProfile skin = resolveSkinProfile(shop);
        GameProfile profile = new GameProfile(visualUuid, sanitizeProfileName(shop.name));
        if (skin != null) skin.getProperties().get("textures").forEach(p -> profile.getProperties().put("textures", p));
        ShopNpcEntity npc = new ShopNpcEntity(world, profile, shop.id, shop.name, shop.x, shop.y, shop.z, shop.yaw, shop.pitch);
        refreshMirror(npc, shop);
        return npc;
    }

    private GameProfile resolveSkinProfile(ShopData shop) { return (GameProfile) SkinResolver.resolve(server, shop); }

    private String sanitizeProfileName(String name) {
        String cleaned = (name == null ? "" : name).replaceAll("[^A-Za-z0-9_]", "_");
        if (cleaned.isBlank()) cleaned = "PlayerShop";
        if (cleaned.length() > 16) cleaned = cleaned.substring(0, 16);
        return cleaned;
    }

    private void sendSkinProfiles(ServerPlayerEntity viewer) {
        for (ShopNpcEntity npc : spawned.values()) if (shouldProject(viewer, npc)) sendSkinProfile(viewer, npc);
    }

    private boolean shouldProject(ServerPlayerEntity viewer, ShopNpcEntity npc) {
        return !npc.isRemoved() && viewer.getWorld() == npc.getWorld() && viewer.squaredDistanceTo(npc) <= PROJECTION_RANGE_SQUARED;
    }

    private void syncProjectionVisibility(ShopNpcEntity npc) {
        for (ServerPlayerEntity viewer : server.getPlayerManager().getPlayerList()) {
            if (shouldProject(viewer, npc)) sendSkinProfile(viewer, npc); else hideProjection(viewer, npc);
        }
    }

    private void sendSkinProfile(ServerPlayerEntity viewer, ShopNpcEntity npc) {
        ProjectionKey key = new ProjectionKey(viewer.getUuid(), npc.getUuid());
        if (!visibleProjections.add(key)) return;

        // Announce profile before the entity is tracked so the client can resolve its skin.
        // A full player-list factory is preferred when available; ADD_PLAYER is the fallback.
        try {
            Object packet = null;
            for (var method : PlayerListS2CPacket.class.getDeclaredMethods()) {
                if (!Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 1
                        || !Collection.class.isAssignableFrom(method.getParameterTypes()[0])
                        || method.getReturnType() != PlayerListS2CPacket.class) continue;
                method.setAccessible(true);
                packet = method.invoke(null, Collections.singleton(npc));
                if (packet != null) break;
            }
            if (packet instanceof PlayerListS2CPacket p) viewer.networkHandler.sendPacket(p);
            else viewer.networkHandler.sendPacket(new PlayerListS2CPacket(PlayerListS2CPacket.Action.ADD_PLAYER, npc));
        } catch (Throwable e) {
            viewer.networkHandler.sendPacket(new PlayerListS2CPacket(PlayerListS2CPacket.Action.ADD_PLAYER, npc));
        }
        npc.forceSkinLayersMetadata();
        pendingTabRemovals.add(new PendingTabRemoval(serverTick + 100, viewer.getUuid(), npc.getUuid()));
    }

    private void hideProjection(ServerPlayerEntity viewer, ShopNpcEntity npc) {
        ProjectionKey key = new ProjectionKey(viewer.getUuid(), npc.getUuid());
        if (!visibleProjections.remove(key)) return;
        pendingTabRemovals.removeIf(p -> p.viewerUuid().equals(viewer.getUuid()) && p.npcUuid().equals(npc.getUuid()));
        viewer.networkHandler.sendPacket(new PlayerRemoveS2CPacket(List.of(npc.getUuid())));
    }

    private void removeProjectionForAll(ShopNpcEntity npc) {
        for (ServerPlayerEntity viewer : server.getPlayerManager().getPlayerList()) hideProjection(viewer, npc);
        visibleProjections.removeIf(k -> k.npcUuid().equals(npc.getUuid()));
        pendingTabRemovals.removeIf(p -> p.npcUuid().equals(npc.getUuid()));
    }

    private void forgetViewer(UUID viewerUuid) {
        visibleProjections.removeIf(k -> k.viewerUuid().equals(viewerUuid));
        pendingTabRemovals.removeIf(p -> p.viewerUuid().equals(viewerUuid));
    }

    private void processTabRemovals() {
        Iterator<PendingTabRemoval> it = pendingTabRemovals.iterator();
        while (it.hasNext()) {
            PendingTabRemoval pending = it.next();
            if (pending.dueTick() > serverTick) continue;
            ServerPlayerEntity viewer = server.getPlayerManager().getPlayer(pending.viewerUuid());
            if (viewer != null) viewer.networkHandler.sendPacket(new PlayerRemoveS2CPacket(List.of(pending.npcUuid())));
            it.remove();
        }
    }

    private ServerWorld world(ShopData shop) {
        try {
            Identifier id = Identifier.of(shop.dimension);
            RegistryKey<net.minecraft.world.World> key = RegistryKey.of(RegistryKeys.WORLD, id);
            return server.getWorld(key);
        } catch (Exception e) { return null; }
    }

    private record PendingTabRemoval(long dueTick, UUID viewerUuid, UUID npcUuid) {}
    private record ProjectionKey(UUID viewerUuid, UUID npcUuid) {}
}
