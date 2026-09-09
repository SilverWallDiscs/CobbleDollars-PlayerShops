package dev.silver.cobbledollarsplayershops;

import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.math.BigInteger;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class Menus {
    private Menus() {}
    private static CobbleDollarsPlayerShops MOD() { return CobbleDollarsPlayerShops.INSTANCE; }

    private static boolean canManage(ServerPlayerEntity player, ShopData shop) {
        if (shop.adminShop) return PermissionCompat.isAdmin(player);
        return shop.ownerUuid.equals(player.getUuid()) || PermissionCompat.isAdmin(player);
    }

    private static boolean ensureManage(ServerPlayerEntity player, ShopData shop) {
        if (canManage(player, shop)) return true;
        msg(player, "You do not have permission to configure this shop.", false);
        return false;
    }

    public static void openConfig(ServerPlayerEntity player, ShopData shop) {
        if (!ensureManage(player, shop)) return;
        SimpleGui gui = base(player, "Configure: " + shop.name);
        gui.setSlot(10, button(Items.EMERALD, "Shop", List.of("Create and manage listings."), () -> openShopAdmin(player, shop)));
        if (shop.adminShop) {
            gui.setSlot(12, button(Items.ENDER_CHEST, "Infinite Stock", List.of("Admin shops do not consume physical stock."), () -> {}));
            gui.setSlot(14, button(Items.GOLD_BLOCK, "Admin Shop", List.of("No seller checkout is created.", "Sales analytics are still recorded."), () -> {}));
        } else {
            gui.setSlot(12, button(Items.CHEST, "Stock", List.of("108 persistent slots.", "Purchases are paused while restocking."), () -> openStock(player, shop, 0)));
            gui.setSlot(14, button(Items.GOLD_INGOT, "Checkout", List.of("Pending: " + shop.pending() + " CobbleDollars", "Transfer earnings to your balance."), () -> openCheckout(player, shop)));
        }
        gui.setSlot(16, button(Items.WRITABLE_BOOK, "Orders", List.of("View the latest 100 sales."), () -> openOrders(player, shop, 0)));
        gui.setSlot(28, button(Items.COMPARATOR, "Settings", List.of("Open/close, move or change skin."), () -> openSettings(player, shop)));
        gui.setSlot(30, button(Items.SPYGLASS, "Shop Preview", List.of("Preview the published listings."), () -> openPublicShop(player, shop)));
        gui.setSlot(32, button(Items.SHIELD, "Safety & Status", List.of("Revision: " + shop.revision, "Listings: " + shop.listings.size(), "Restock/recovery lock: " + MOD().service().isStockEditing(shop.id)), () -> openStatus(player, shop)));
        gui.setSlot(34, button(Items.EXPERIENCE_BOTTLE, "Analytics", List.of("Lifetime revenue and sales", "for this shop."), () -> openAnalytics(player, shop)));
        gui.open();
    }

    private static void openShopAdmin(ServerPlayerEntity player, ShopData shop) {
        if (!ensureManage(player, shop)) return;
        SimpleGui gui = base(player, "Shop: " + shop.name);
        ServerConfig config = MOD().config();
        gui.setSlot(11, button(Items.LIME_DYE, "Create Listing", List.of("Select an item from your inventory", "then set its price.", "Allowed: " + config.MinPrice + " - " + config.MaxPrice), () -> new CreateListingGui(player, shop).open()));
        gui.setSlot(15, button(Items.BOOK, "Published Listings", List.of("Left click: cancel", "Right click: change price"), () -> openListings(player, shop, 0)));
        gui.setSlot(49, back(() -> openConfig(player, shop)));
        gui.open();
    }

    private static final class CreateListingGui extends SimpleGui {
        private final ShopData shop;
        private ItemStack selected = ItemStack.EMPTY;
        private BigInteger price;

        CreateListingGui(ServerPlayerEntity player, ShopData shop) {
            super(ScreenHandlerType.GENERIC_9X6, player, false);
            this.shop = shop;
            setTitle(Text.literal("Create Listing: " + shop.name));
            setLockPlayerInventory(true);
            render();
        }

        private void render() {
            fillTop(this);
            if (!selected.isEmpty()) setSlot(13, new GuiElementBuilder(selected).setName(Text.literal("Selected Item").formatted(Formatting.AQUA)).build());
            else setSlot(13, new GuiElementBuilder(Items.BARRIER).setName(Text.literal("Click an item in your inventory").formatted(Formatting.RED)).build());
            setSlot(29, back(() -> openShopAdmin(player, shop)));
            setSlot(31, button(Items.NAME_TAG, "Price", List.of(price == null ? "Not set" : price + " CobbleDollars", "Click and type the price in chat."), () -> {
                close();
                MOD().chat().askPrice(player, value -> {
                    price = value;
                    player.getServer().execute(() -> { render(); open(); });
                });
            }));
            setSlot(33, button(Items.LIME_CONCRETE, "Confirm", List.of("Creates the listing.", "Stock may still be 0."), () -> {
                if (selected.isEmpty()) { msg(player, "Select an item first.", false); return; }
                if (price == null) { msg(player, "Set a price first.", false); return; }
                try {
                    MOD().service().addListing(shop, selected, price);
                    msg(player, "Listing created.", true);
                    openListings(player, shop, 0);
                } catch (Exception e) { msg(player, e.getMessage(), false); }
            }));
        }

        @Override public boolean onAnyClick(int index, ClickType type, SlotActionType action) {
            if (index >= getVirtualSize()) {
                Slot slot = getSlotRedirectOrPlayer(index);
                if (slot != null && slot.hasStack()) {
                    selected = ItemCodec.template(slot.getStack());
                    render();
                }
                return false;
            }
            return false;
        }
    }

    private static void openListings(ServerPlayerEntity player, ShopData shop, int page) {
        if (!ensureManage(player, shop)) return;
        SimpleGui gui = base(player, "Listings: " + shop.name);
        int pageSize = 45;
        int pages = Math.max(1, (shop.listings.size() + pageSize - 1) / pageSize);
        int p = Math.max(0, Math.min(page, pages - 1));
        for (int i = 0; i < pageSize; i++) {
            int idx = p * pageSize + i;
            if (idx >= shop.listings.size()) break;
            ShopData.Listing listing = shop.listings.get(idx);
            ItemStack display = ItemCodec.decode(listing.itemSnbt, player.getServer().getRegistryManager());
            int stock = shop.adminShop ? -1 : MOD().service().stockCount(shop, listing);
            boolean priceAllowed = MOD().config().isPriceAllowed(listing.price());
            GuiElementBuilder listingButton = new GuiElementBuilder(display)
                    .addLoreLine(Text.literal("Price: " + listing.price + " CobbleDollars").formatted(priceAllowed ? Formatting.GOLD : Formatting.RED))
                    .addLoreLine(Text.literal(shop.adminShop ? "Stock: Infinite" : "Stock: " + stock).formatted(shop.adminShop || stock > 0 ? Formatting.GREEN : Formatting.RED));
            if (!priceAllowed) {
                listingButton.addLoreLine(Text.literal("Price outside server limits - update required.").formatted(Formatting.RED));
            }
            gui.setSlot(i, listingButton
                    .addLoreLine(Text.literal("Left click: cancel").formatted(Formatting.RED))
                    .addLoreLine(Text.literal("Right click: change price").formatted(Formatting.YELLOW))
                    .setCallback((slot, click, action, source) -> {
                        if (click.isLeft) {
                            MOD().service().cancelListing(shop, listing.id);
                            msg(player, "Listing cancelled. Items remain in Stock.", true);
                            openListings(player, shop, p);
                        } else if (click.isRight) {
                            source.close();
                            MOD().chat().askPrice(player, value -> player.getServer().execute(() -> {
                                try {
                                    MOD().service().updatePrice(shop, listing.id, value);
                                    msg(player, "Price updated.", true);
                                } catch (Exception e) {
                                    msg(player, e.getMessage(), false);
                                }
                                openListings(player, shop, p);
                            }));
                        }
                    }).build());
        }
        nav(gui, p, pages, () -> openListings(player, shop, p - 1), () -> openListings(player, shop, p + 1), () -> openShopAdmin(player, shop));
        gui.open();
    }

    private static void openStock(ServerPlayerEntity player, ShopData shop, int page) {
        if (!ensureManage(player, shop)) return;
        if (shop.adminShop) { msg(player, "Admin shops use infinite stock and do not have a Stock inventory.", false); return; }
        if (!MOD().service().beginStockEdit(shop, player)) {
            msg(player, "Someone is already editing this shop's stock.", false);
            return;
        }
        SimpleInventory inv = MOD().service().createStockInventory(shop);
        try {
            // Establish a durable cursor-empty checkpoint before the first movable slot appears.
            MOD().service().checkpointStockEdit(shop, inv, player);
            new StockGui(player, shop, inv, page).open();
        } catch (Exception e) {
            MOD().service().endStockEdit(shop, player);
            msg(player, "Stock could not be opened safely: " + e.getMessage(), false);
        }
    }

    private static final class StockGui extends SimpleGui {
        private final ShopData shop;
        private final SimpleInventory stock;
        private int page;
        private boolean released;

        StockGui(ServerPlayerEntity player, ShopData shop, SimpleInventory stock, int page) {
            super(ScreenHandlerType.GENERIC_9X6, player, false);
            this.shop = shop; this.stock = stock; this.page = Math.max(0, Math.min(2, page));
            setTitle(Text.literal("Stock: " + shop.name + " (" + (this.page + 1) + "/3)"));
            setLockPlayerInventory(false);
            render();
        }

        private void render() {
            int storageSlots = page == 2 ? 18 : 45;
            for (int i = 0; i < storageSlots; i++) setSlotRedirect(i, new Slot(stock, page * 45 + i, 0, 0));
            // Page 3 only uses backend slots 90-107; the remaining display cells are locked filler.
            for (int i = storageSlots; i < 45; i++) setSlot(i, filler());
            for (int i = 45; i < 54; i++) setSlot(i, filler());
            setSlot(45, button(Items.ARROW, "Previous Page", List.of(), () -> changePage(page - 1)));
            setSlot(49, new GuiElementBuilder(Items.CHEST).setName(Text.literal("Persistent Stock: 108 slots").formatted(Formatting.GOLD))
                    .addLoreLine(Text.literal("Purchases are locked while this menu is open.").formatted(Formatting.YELLOW)).build());
            setSlot(53, button(Items.ARROW, "Next Page", List.of(), () -> changePage(page + 1)));
        }

        private void changePage(int next) {
            if (screenHandler != null && !screenHandler.getCursorStack().isEmpty()) {
                msg(player, "Place the item on your cursor into a slot before changing pages.", false);
                return;
            }
            try {
                MOD().service().checkpointStockEdit(shop, stock, player);
            } catch (Exception e) {
                msg(player, "Stock checkpoint failed: " + e.getMessage(), false);
                close();
                return;
            }
            this.page = Math.max(0, Math.min(2, next));
            setTitle(Text.literal("Stock: " + shop.name + " (" + (this.page + 1) + "/3)"));
            render();
        }

        @Override public void onTick() {
            // A checkpoint is valid only when the cursor is empty, so it represents a complete
            // player-inventory + shop-stock state that can safely be restored after a crash.
            if (player.age % 20 == 0 && screenHandler != null && screenHandler.getCursorStack().isEmpty()) {
                try {
                    MOD().service().checkpointStockEdit(shop, stock, player);
                } catch (Exception e) {
                    msg(player, "Stock checkpoint failed. This shop has been locked for safe recovery.", false);
                    close();
                }
            }
        }

        @Override public void onClose() {
            if (released) return;
            released = true;
            // Let vanilla finish returning any cursor stack first, then persist/flush the final
            // stable state. finishStockEdit keeps the persistent recovery journal until player
            // data has been flushed to disk.
            player.getServer().execute(() -> {
                try {
                    MOD().service().finishStockEdit(shop, stock, player);
                } catch (Exception e) {
                    CobbleDollarsPlayerShops.LOGGER.error("Could not finalize Stock editor for shop {}", shop.name, e);
                    msg(player, "Stock close could not be finalized. Purchases are locked until safe recovery.", false);
                }
            });
        }
    }

    private static void openCheckout(ServerPlayerEntity player, ShopData shop) {
        if (!ensureManage(player, shop)) return;
        if (shop.adminShop) { msg(player, "Admin shops do not create seller payouts.", false); return; }
        SimpleGui gui = base(player, "Checkout: " + shop.name);
        gui.setSlot(22, button(Items.GOLD_BLOCK, "Collect " + shop.pending() + " CobbleDollars", List.of("Uses a persistent payout journal", "to prevent double checkout after crashes."), () -> {
            ShopService.CheckoutResult result = MOD().service().checkout(shop);
            msg(player, result.message(), result.success());
            openConfig(player, shop);
        }));
        gui.setSlot(49, back(() -> openConfig(player, shop)));
        gui.open();
    }

    private static void openOrders(ServerPlayerEntity player, ShopData shop, int page) {
        SimpleGui gui = base(player, "Orders: " + shop.name);
        int pageSize = 45;
        int pages = Math.max(1, (shop.orders.size() + pageSize - 1) / pageSize);
        int p = Math.max(0, Math.min(page, pages - 1));
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
        for (int i = 0; i < pageSize; i++) {
            int idx = p * pageSize + i;
            if (idx >= shop.orders.size()) break;
            ShopData.OrderRecord order = shop.orders.get(idx);
            ItemStack display = ItemCodec.decode(order.itemSnbt, player.getServer().getRegistryManager());
            display.setCount(Math.min(display.getMaxCount(), Math.max(1, order.quantity)));
            gui.setSlot(i, new GuiElementBuilder(display)
                    .addLoreLine(Text.literal("Buyer: " + order.buyerName).formatted(Formatting.AQUA))
                    .addLoreLine(Text.literal("Quantity: " + order.quantity))
                    .addLoreLine(Text.literal("Unit price: " + order.unitPrice))
                    .addLoreLine(Text.literal("Total: " + order.total + " CobbleDollars").formatted(Formatting.GOLD))
                    .addLoreLine(Text.literal(fmt.format(Instant.ofEpochMilli(order.timestamp))).formatted(Formatting.GRAY)).build());
        }
        nav(gui, p, pages, () -> openOrders(player, shop, p - 1), () -> openOrders(player, shop, p + 1), () -> openConfig(player, shop));
        gui.open();
    }

    private static void openSettings(ServerPlayerEntity player, ShopData shop) {
        if (!ensureManage(player, shop)) return;
        SimpleGui gui = base(player, "Settings: " + shop.name);
        gui.setSlot(19, button(shop.open ? Items.LIME_DYE : Items.GRAY_DYE, shop.open ? "Shop: OPEN" : "Shop: CLOSED", List.of("Click to toggle customer purchases."), () -> {
            shop.open = !shop.open; shop.revision++; MOD().store().save(); MOD().npcManager().syncShop(shop.id); openSettings(player, shop);
        }));
        gui.setSlot(21, button(Items.PAPER, "Server Limits", List.of(
                "NPC limit per player: " + MOD().config().Limitnpc,
                "Minimum price: " + MOD().config().MinPrice,
                "Maximum price: " + MOD().config().MaxPrice
        ), () -> {}));
        gui.setSlot(23, button(Items.COMPASS, "Move NPC Here", List.of("Moves the NPC to your exact position."), () -> {
            if (!shop.adminShop && !FlanCompat.canSpawnNpc(player, net.minecraft.util.math.BlockPos.ofFloored(player.getX(), player.getY(), player.getZ()))) {
                msg(player, "Flan does not allow spawning PlayerShop NPCs in this claim.", false);
                return;
            }
            shop.dimension = player.getWorld().getRegistryKey().getValue().toString();
            shop.x = player.getX(); shop.y = player.getY(); shop.z = player.getZ();
            shop.yaw = player.getYaw(); shop.pitch = player.getPitch(); shop.revision++;
            MOD().store().save(); MOD().npcManager().refresh(shop.id);
            msg(player, "NPC moved.", true); openSettings(player, shop);
        }));
        gui.setSlot(25, button(Items.PLAYER_HEAD, "NPC Skin", List.of(
                "Current: " + shop.effectiveSkinName(),
                "Use any valid Minecraft username.",
                "The player does not need to be online."
        ), () -> {
            gui.close();
            MOD().chat().askPlayerName(player, name -> player.getServer().execute(() -> {
                if (!MOD().npcManager().canResolveSkin(name)) {
                    msg(player, "Could not resolve that Minecraft username/skin.", false);
                    openSettings(player, shop);
                    return;
                }
                shop.skinName = name;
                shop.skinTextureName = null;
                shop.skinTextureValue = null;
                shop.skinTextureSignature = null;
                shop.skinTextureUuid = null;
                shop.revision++;
                MOD().store().save();
                MOD().npcManager().refresh(shop.id);
                msg(player, "NPC skin changed to " + name + ".", true);
                openSettings(player, shop);
            }));
        }));
        gui.setSlot(49, back(() -> openConfig(player, shop)));
        gui.open();
    }

    private static void openAnalytics(ServerPlayerEntity player, ShopData shop) {
        SimpleGui gui = base(player, "Analytics: " + shop.name);
        long orders = shop.lifetimeOrders;
        BigInteger revenue = shop.lifetimeRevenue();
        BigInteger average = orders > 0 ? revenue.divide(BigInteger.valueOf(orders)) : BigInteger.ZERO;
        long stockUnits = MOD().service().totalStockUnits(shop);
        gui.setSlot(20, new GuiElementBuilder(Items.GOLD_INGOT)
                .setName(Text.literal("Lifetime Revenue").formatted(Formatting.GOLD))
                .addLoreLine(Text.literal(revenue + " CobbleDollars").formatted(Formatting.YELLOW)).build());
        gui.setSlot(22, new GuiElementBuilder(Items.WRITABLE_BOOK)
                .setName(Text.literal("Lifetime Sales").formatted(Formatting.GOLD))
                .addLoreLine(Text.literal("Orders: " + orders))
                .addLoreLine(Text.literal("Items sold: " + shop.lifetimeItemsSold))
                .addLoreLine(Text.literal("Average order: " + average + " CobbleDollars")).build());
        gui.setSlot(24, new GuiElementBuilder(Items.CHEST)
                .setName(Text.literal("Current Inventory").formatted(Formatting.GOLD))
                .addLoreLine(Text.literal("Active listings: " + shop.listings.size()))
                .addLoreLine(Text.literal("Stock units: " + stockUnits))
                .addLoreLine(Text.literal("Pending checkout: " + shop.pending() + " CobbleDollars")).build());
        gui.setSlot(49, back(() -> openConfig(player, shop)));
        gui.open();
    }

    private static void openStatus(ServerPlayerEntity player, ShopData shop) {
        SimpleGui gui = base(player, "Safety & Status: " + shop.name);
        List<String> lore = new ArrayList<>();
        lore.add("Revision: " + shop.revision);
        lore.add("Persistent stock slots: 108");
        lore.add("Restock/recovery lock: " + MOD().service().isStockEditing(shop.id));
        lore.add("Interrupted Stock recovery: " + MOD().service().hasInterruptedStockEdit(shop.id));
        lore.add("Pending earnings: " + shop.pending());
        lore.add("NPC state is never authoritative.");
        if (shop.payoutJournal != null) {
            lore.add("Last payout: " + shop.payoutJournal.status);
            if (shop.payoutJournal.note != null && !shop.payoutJournal.note.isBlank()) lore.add(shop.payoutJournal.note);
        }
        gui.setSlot(22, button(Items.SHIELD, "Transaction Safety", lore, () -> {}));
        gui.setSlot(49, back(() -> openConfig(player, shop)));
        gui.open();
    }

    public static void openPublicShop(ServerPlayerEntity player, ShopData shop) {
        openPublicShop(player, shop, 0);
    }

    private static void openPublicShop(ServerPlayerEntity player, ShopData shop, int page) {
        SimpleGui gui = base(player, shop.name + " - Player Shop");
        int pageSize = 45;
        int pages = Math.max(1, (shop.listings.size() + pageSize - 1) / pageSize);
        int p = Math.max(0, Math.min(page, pages - 1));
        if (!shop.open) {
            gui.setSlot(22, new GuiElementBuilder(Items.BARRIER).setName(Text.literal("This shop is closed.").formatted(Formatting.RED)).build());
        } else if (!shop.adminShop && MOD().service().isStockEditing(shop.id)) {
            gui.setSlot(22, new GuiElementBuilder(Items.CHEST).setName(Text.literal("Shop inventory locked").formatted(Formatting.YELLOW)).addLoreLine(Text.literal("Restocking or crash recovery is in progress.")).build());
        } else {
            for (int i = 0; i < pageSize; i++) {
                int idx = p * pageSize + i;
                if (idx >= shop.listings.size()) break;
                ShopData.Listing listing = shop.listings.get(idx);
                int stock = shop.adminShop ? Integer.MAX_VALUE : MOD().service().stockCount(shop, listing);
                boolean priceAllowed = MOD().config().isPriceAllowed(listing.price());
                ItemStack display = ItemCodec.decode(listing.itemSnbt, player.getServer().getRegistryManager());
                GuiElementBuilder listingButton = new GuiElementBuilder(display)
                        .addLoreLine(Text.literal("Price: " + listing.price + " CobbleDollars each").formatted(priceAllowed ? Formatting.GOLD : Formatting.RED))
                        .addLoreLine(Text.literal(shop.adminShop ? "Stock: Infinite" : "Stock: " + stock).formatted(stock > 0 ? Formatting.GREEN : Formatting.RED));
                if (!priceAllowed) {
                    listingButton.addLoreLine(Text.literal("Unavailable: price outside server limits.").formatted(Formatting.RED));
                } else {
                    listingButton.addLoreLine(Text.literal(stock > 0 ? "Click to buy" : "Out of stock").formatted(stock > 0 ? Formatting.AQUA : Formatting.DARK_RED));
                }
                gui.setSlot(i, listingButton
                        .setCallback((slot, click, action, source) -> { if (stock > 0 && priceAllowed) openPurchase(player, shop, listing); }).build());
            }
        }
        nav(gui, p, pages, () -> openPublicShop(player, shop, p - 1), () -> openPublicShop(player, shop, p + 1), () -> {});
        gui.open();
    }

    private static void openPurchase(ServerPlayerEntity player, ShopData shop, ShopData.Listing listing) {
        SimpleGui gui = base(player, "Buy from " + shop.name);
        int stock = shop.adminShop ? Integer.MAX_VALUE : MOD().service().stockCount(shop, listing);
        int[] quantities = {1, 8, 16, 32, 64};
        int[] slots = {20, 21, 22, 23, 24};
        for (int i = 0; i < quantities.length; i++) {
            int q = quantities[i];
            BigInteger total = listing.price().multiply(BigInteger.valueOf(q));
            ItemStack icon = ItemCodec.decode(listing.itemSnbt, player.getServer().getRegistryManager());
            icon.setCount(Math.min(icon.getMaxCount(), q));
            gui.setSlot(slots[i], new GuiElementBuilder(icon)
                    .setName(Text.literal("Buy x" + q).formatted(q <= stock ? Formatting.GREEN : Formatting.RED))
                    .addLoreLine(Text.literal("Total: " + total + " CobbleDollars").formatted(Formatting.GOLD))
                    .addLoreLine(Text.literal("Available: " + stock))
                    .setCallback((slot, click, action, source) -> {
                        if (q > stock) { msg(player, "Not enough stock.", false); return; }
                        ShopService.PurchaseResult result = MOD().service().purchase(player, shop, listing.id, q);
                        result.send(player);
                        openPublicShop(player, shop);
                    }).build());
        }
        gui.setSlot(49, back(() -> openPublicShop(player, shop)));
        gui.open();
    }

    private static SimpleGui base(ServerPlayerEntity player, String title) {
        SimpleGui gui = new SimpleGui(ScreenHandlerType.GENERIC_9X6, player, false);
        gui.setTitle(Text.literal(title));
        gui.setLockPlayerInventory(true);
        fillTop(gui);
        return gui;
    }

    private static void fillTop(SimpleGui gui) {
        for (int i = 0; i < 54; i++) gui.setSlot(i, filler());
    }

    private static eu.pb4.sgui.api.elements.GuiElement filler() {
        return new GuiElementBuilder(Items.GRAY_STAINED_GLASS_PANE).setName(Text.empty()).hideTooltip().build();
    }

    private static eu.pb4.sgui.api.elements.GuiElement back(Runnable action) {
        return button(Items.ARROW, "Back", List.of(), action);
    }

    private static eu.pb4.sgui.api.elements.GuiElement button(Item item, String name, List<String> lore, Runnable action) {
        GuiElementBuilder b = new GuiElementBuilder(item).setName(Text.literal(name).formatted(Formatting.GOLD));
        for (String line : lore) b.addLoreLine(Text.literal(line).formatted(Formatting.GRAY));
        return b.setCallback((index, type, slotAction, gui) -> action.run()).build();
    }

    private static void nav(SimpleGui gui, int page, int pages, Runnable prev, Runnable next, Runnable back) {
        for (int i = 45; i < 54; i++) gui.setSlot(i, filler());
        if (page > 0) gui.setSlot(45, button(Items.ARROW, "Previous", List.of(), prev));
        gui.setSlot(49, button(Items.PAPER, "Page " + (page + 1) + "/" + pages, List.of("Back"), back));
        if (page + 1 < pages) gui.setSlot(53, button(Items.ARROW, "Next", List.of(), next));
    }

    private static void msg(ServerPlayerEntity player, String text, boolean good) {
        player.sendMessage(Text.literal(text == null ? "Unknown error" : text).formatted(good ? Formatting.GREEN : Formatting.RED), false);
    }
}
