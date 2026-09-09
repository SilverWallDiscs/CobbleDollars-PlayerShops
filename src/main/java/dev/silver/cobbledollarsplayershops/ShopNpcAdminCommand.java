package dev.silver.cobbledollarsplayershops;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class ShopNpcAdminCommand {
    private ShopNpcAdminCommand() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("shopnpcadmin")
                .requires(PermissionCompat::isAdmin)
                .then(literal("create").then(argument("name", StringArgumentType.greedyString())
                        .executes(ctx -> ShopNpcCommand.createInternal(ctx.getSource(), StringArgumentType.getString(ctx, "name"), true))))
                .then(literal("remove").then(argument("name", StringArgumentType.greedyString())
                        .executes(ctx -> forceRemove(ctx.getSource(), StringArgumentType.getString(ctx, "name"))))));
    }

    private static int forceRemove(ServerCommandSource source, String rawName) {
        ShopData shop = CobbleDollarsPlayerShops.INSTANCE.store().findByName(rawName.trim()).orElse(null);
        if (shop == null) { source.sendError(Text.literal("Shop not found.")); return 0; }
        if (CobbleDollarsPlayerShops.INSTANCE.service().isStockEditing(shop.id)) {
            source.sendError(Text.literal("This shop has an active or interrupted Stock session. Reconcile/close Stock before force-removing it."));
            return 0;
        }
        CobbleDollarsPlayerShops.INSTANCE.npcManager().despawn(shop.id);
        CobbleDollarsPlayerShops.INSTANCE.store().remove(shop.id);
        CobbleDollarsPlayerShops.INSTANCE.store().save();
        source.sendFeedback(() -> Text.literal("Removed shop '" + shop.name + "'.").formatted(Formatting.YELLOW), false);
        return 1;
    }
}
