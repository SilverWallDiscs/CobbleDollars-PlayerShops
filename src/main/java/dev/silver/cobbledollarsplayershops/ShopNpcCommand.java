package dev.silver.cobbledollarsplayershops;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class ShopNpcCommand {
    private ShopNpcCommand() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("shopnpc")
                .then(literal("add").then(argument("name", StringArgumentType.greedyString()).executes(ctx -> createInternal(ctx.getSource(), StringArgumentType.getString(ctx, "name"), false))))
                .then(literal("config").then(argument("name", StringArgumentType.greedyString()).executes(ctx -> config(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
                .then(literal("remove").then(argument("name", StringArgumentType.greedyString()).executes(ctx -> remove(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
                .then(literal("move").then(argument("name", StringArgumentType.greedyString()).executes(ctx -> move(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
                .then(literal("list").executes(ctx -> list(ctx.getSource())))
                .then(literal("reloadconfig").requires(PermissionCompat::isAdmin).executes(ctx -> reloadConfig(ctx.getSource()))));
    }

    private static ServerPlayerEntity player(ServerCommandSource source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return source.getPlayerOrThrow();
    }

    static int createInternal(ServerCommandSource source, String rawName, boolean adminShop) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity p = player(source);
        String name = rawName.trim();
        if (name.isBlank() || name.length() > 32) { source.sendError(Text.literal("NPC names must be 1-32 characters.")); return 0; }
        if (CobbleDollarsPlayerShops.INSTANCE.store().findByName(name).isPresent()) { source.sendError(Text.literal("A shop NPC with that name already exists.")); return 0; }

        if (!adminShop) {
            int owned = (int) CobbleDollarsPlayerShops.INSTANCE.store().ownedBy(p.getUuid()).stream().filter(s -> !s.adminShop).count();
            int limit = CobbleDollarsPlayerShops.INSTANCE.config().Limitnpc;
            if (owned >= limit) { source.sendError(Text.literal("You have reached the server shop NPC limit (" + limit + ").")); return 0; }
            BlockPos target = BlockPos.ofFloored(p.getX(), p.getY(), p.getZ());
            if (!FlanCompat.canSpawnNpc(p, target)) { source.sendError(Text.literal("Flan does not allow spawning PlayerShop NPCs in this claim.")); return 0; }
        }

        ShopData shop = new ShopData();
        shop.id = java.util.UUID.randomUUID();
        shop.name = name;
        shop.ownerUuid = p.getUuid();
        shop.ownerName = p.getGameProfile().getName();
        shop.skinName = p.getGameProfile().getName();
        shop.adminShop = adminShop;
        shop.dimension = p.getWorld().getRegistryKey().getValue().toString();
        shop.x = p.getX(); shop.y = p.getY(); shop.z = p.getZ();
        shop.yaw = p.getYaw(); shop.pitch = p.getPitch();
        CobbleDollarsPlayerShops.INSTANCE.store().put(shop);
        CobbleDollarsPlayerShops.INSTANCE.store().save();
        CobbleDollarsPlayerShops.INSTANCE.npcManager().refresh(shop.id);
        source.sendFeedback(() -> Text.literal((adminShop ? "Created unlimited admin shop NPC '" : "Created player shop NPC '") + name + "'. Use /shopnpc config " + name).formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int config(ServerCommandSource source, String name) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity p = player(source);
        ShopData shop = ownOrAdmin(source, name);
        if (shop == null) return 0;
        Menus.openConfig(p, shop);
        return 1;
    }

    private static int remove(ServerCommandSource source, String name) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        player(source);
        ShopData shop = ownOrAdmin(source, name);
        if (shop == null) return 0;
        if (shop.adminShop) { source.sendError(Text.literal("Admin shops must be removed with /shopnpcadmin remove <name>.")); return 0; }
        if (CobbleDollarsPlayerShops.INSTANCE.service().isStockEditing(shop.id)) { source.sendError(Text.literal("Close the Stock editor before removing this shop.")); return 0; }
        if (shop.pending().signum() > 0) { source.sendError(Text.literal("Checkout pending earnings before removing this shop.")); return 0; }
        if (shop.stock.stream().anyMatch(entry -> entry != null && !entry.isBlank())) { source.sendError(Text.literal("Empty the Stock inventory before removing this shop.")); return 0; }
        CobbleDollarsPlayerShops.INSTANCE.npcManager().despawn(shop.id);
        CobbleDollarsPlayerShops.INSTANCE.store().remove(shop.id);
        CobbleDollarsPlayerShops.INSTANCE.store().save();
        source.sendFeedback(() -> Text.literal("Removed shop '" + shop.name + "'.").formatted(Formatting.YELLOW), false);
        return 1;
    }

    private static int move(ServerCommandSource source, String name) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity p = player(source);
        ShopData shop = ownOrAdmin(source, name);
        if (shop == null) return 0;
        if (!shop.adminShop && !FlanCompat.canSpawnNpc(p, BlockPos.ofFloored(p.getX(), p.getY(), p.getZ()))) {
            source.sendError(Text.literal("Flan does not allow spawning PlayerShop NPCs in this claim.")); return 0;
        }
        shop.dimension = p.getWorld().getRegistryKey().getValue().toString();
        shop.x = p.getX(); shop.y = p.getY(); shop.z = p.getZ(); shop.yaw = p.getYaw(); shop.pitch = p.getPitch(); shop.revision++;
        CobbleDollarsPlayerShops.INSTANCE.store().save();
        CobbleDollarsPlayerShops.INSTANCE.npcManager().refresh(shop.id);
        source.sendFeedback(() -> Text.literal("Moved shop NPC here.").formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int list(ServerCommandSource source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity p = player(source);
        var shops = CobbleDollarsPlayerShops.INSTANCE.store().ownedBy(p.getUuid()).stream().filter(s -> !s.adminShop).toList();
        if (shops.isEmpty()) source.sendFeedback(() -> Text.literal("You do not own any player shops."), false);
        else source.sendFeedback(() -> Text.literal("Your shops: " + String.join(", ", shops.stream().map(s -> s.name).toList())).formatted(Formatting.AQUA), false);
        return shops.size();
    }

    private static int reloadConfig(ServerCommandSource source) {
        try {
            CobbleDollarsPlayerShops.INSTANCE.reloadConfig();
            ServerConfig config = CobbleDollarsPlayerShops.INSTANCE.config();
            source.sendFeedback(() -> Text.literal("PlayerShops config reloaded. Limitnpc=" + config.Limitnpc + ", MinPrice=" + config.MinPrice + ", MaxPrice=" + config.MaxPrice).formatted(Formatting.GREEN), false);
            return 1;
        } catch (Exception e) { source.sendError(Text.literal("Could not reload PlayerShops config. Check the server log.")); return 0; }
    }

    private static ShopData ownOrAdmin(ServerCommandSource source, String name) {
        ShopData shop = CobbleDollarsPlayerShops.INSTANCE.store().findByName(name.trim()).orElse(null);
        if (shop == null) { source.sendError(Text.literal("Shop not found.")); return null; }
        if (shop.adminShop) {
            if (!PermissionCompat.isAdmin(source)) { source.sendError(Text.literal("This is an admin shop. Permission playershopadmin is required.")); return null; }
            return shop;
        }
        ServerPlayerEntity p = source.getEntity() instanceof ServerPlayerEntity sp ? sp : null;
        boolean owner = p != null && shop.ownerUuid.equals(p.getUuid());
        if (!owner && !PermissionCompat.isAdmin(source)) { source.sendError(Text.literal("You do not own this shop.")); return null; }
        return shop;
    }
}
