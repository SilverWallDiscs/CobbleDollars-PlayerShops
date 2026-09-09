package dev.silver.cobbledollarsplayershops;

import fr.harmex.cobbledollars.common.world.item.trading.shop.Category;
import fr.harmex.cobbledollars.common.world.item.trading.shop.Offer;
import fr.harmex.cobbledollars.common.world.item.trading.shop.Shop;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;

import java.math.BigInteger;
import java.util.ArrayList;

/** Converts persistent PlayerShop data to the CobbleDollars native merchant model. */
public final class CobbleShopBridge {
    private CobbleShopBridge() {}

    public static Shop build(ShopData data, ShopService service, MinecraftServer server) {
        ArrayList<Offer> offers = new ArrayList<>();
        boolean purchasable = data.open && (data.adminShop || !service.isStockEditing(data.id));
        ServerConfig config = CobbleDollarsPlayerShops.INSTANCE.config();

        for (ShopData.Listing listing : data.listings) {
            ItemStack item = ItemCodec.decode(listing.itemSnbt, server.getRegistryManager());
            if (item.isEmpty()) item = new ItemStack(Items.BARRIER);
            item = ItemCodec.template(item);
            BigInteger price = listing.price();
            int stock = 0;
            if (purchasable && config.isPriceAllowed(price)) {
                stock = data.adminShop ? -1 : Math.max(0, service.stockCount(data, listing));
            }
            offers.add(new Offer(item, price, stock));
        }

        Shop shop = new Shop();
        shop.add(new Category(data.name == null || data.name.isBlank() ? "Shop" : data.name, offers));
        return shop;
    }
}
