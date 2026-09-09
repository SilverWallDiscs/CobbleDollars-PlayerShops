package dev.silver.cobbledollarsplayershops.mixin;

import dev.silver.cobbledollarsplayershops.*;
import fr.harmex.cobbledollars.common.network.handlers.server.BuyHandler;
import fr.harmex.cobbledollars.common.network.packets.c2s.BuyPacket;
import fr.harmex.cobbledollars.common.world.item.trading.shop.Category;
import fr.harmex.cobbledollars.common.world.item.trading.shop.Offer;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = BuyHandler.class, remap = false)
public abstract class BuyHandlerMixin {
    @Inject(method = "handle", at = @At("HEAD"), cancellable = true, remap = false)
    private void playerShops$securePurchase(BuyPacket packet, MinecraftServer server, ServerPlayerEntity player, CallbackInfo ci) {
        if (packet == null || packet.getMerchantUUID() == null) return;
        ServerWorld world = server.getWorld(player.getWorld().getRegistryKey());
        if (world == null) return;
        Entity entity = world.getEntity(packet.getMerchantUUID());
        if (!(entity instanceof ShopNpcEntity npc)) return;

        ci.cancel();
        CobbleDollarsPlayerShops mod = CobbleDollarsPlayerShops.INSTANCE;
        ShopData shop = mod.store().get(npc.shopId());
        if (shop == null) { fail(player, "This player shop no longer exists."); return; }
        mod.npcManager().refreshMirror(npc, shop);

        if (!packet.getHasMerchant() || packet.getCategoryIndex() != 0) {
            fail(player, "Invalid player shop request."); mod.npcManager().syncShop(shop.id); return;
        }
        int offerIndex = packet.getOfferIndex();
        if (offerIndex < 0 || offerIndex >= shop.listings.size()) {
            fail(player, "This listing changed. Please try again."); mod.npcManager().syncShop(shop.id); return;
        }

        try {
            if (npc.getShop().isEmpty() || offerIndex >= ((Category) npc.getShop().get(0)).getOffers().size()) {
                fail(player, "This listing changed. Please try again."); mod.npcManager().syncShop(shop.id); return;
            }
            Offer expected = ((Category) npc.getShop().get(0)).getOffers().get(offerIndex);
            if (!expected.equals(packet.getOffer())) {
                fail(player, "The shop stock or price changed. Please try again."); mod.npcManager().syncShop(shop.id); return;
            }
        } catch (Exception e) {
            fail(player, "The shop could not be synchronized safely."); mod.npcManager().syncShop(shop.id); return;
        }

        ShopData.Listing listing = shop.listings.get(offerIndex);
        ShopService.PurchaseResult result = mod.service().purchase(player, shop, listing.id, packet.getAmount());
        result.send(player);
        mod.npcManager().syncShop(shop.id);
    }

    private static void fail(ServerPlayerEntity player, String message) {
        player.sendMessage(Text.literal(message).formatted(Formatting.RED), false);
    }
}
