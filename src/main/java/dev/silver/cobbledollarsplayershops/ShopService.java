package dev.silver.cobbledollarsplayershops;

import fr.harmex.cobbledollars.common.utils.extensions.PlayerExtensionKt;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public final class ShopService {
    public static final int STOCK_SIZE = 108;

    private final MinecraftServer server;
    private final ShopStore store;
    private final CobbleDollarsEconomy economy;
    private final Map<UUID, ReentrantLock> transactionLocks = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> stockEditors = new ConcurrentHashMap<>(); // shop -> player
    private final Map<UUID, UUID> editorShops = new ConcurrentHashMap<>(); // player -> shop

    public ShopService(MinecraftServer server, ShopStore store) {
        this.server = server;
        this.store = store;
        this.economy = new CobbleDollarsEconomy(server);
    }

    private ReentrantLock lock(UUID shopId) {
        return transactionLocks.computeIfAbsent(shopId, k -> new ReentrantLock());
    }

    public int stockCount(ShopData shop, ShopData.Listing listing) {
        ItemStack template = ItemCodec.decode(listing.itemSnbt, server.getRegistryManager());
        int count = 0;
        for (String snbt : shop.stock) {
            ItemStack stack = ItemCodec.decode(snbt, server.getRegistryManager());
            if (ItemCodec.same(template, stack)) count += stack.getCount();
        }
        return count;
    }

    public long totalStockUnits(ShopData shop) {
        long total = 0L;
        for (String snbt : shop.stock) {
            ItemStack stack = ItemCodec.decode(snbt, server.getRegistryManager());
            if (!stack.isEmpty()) total += stack.getCount();
        }
        return total;
    }

    public boolean beginStockEdit(ShopData shop, ServerPlayerEntity player) {
        ReentrantLock lock = lock(shop.id);
        lock.lock();
        try {
            // An interrupted session must be reconciled before anyone can edit this shop again.
            if (shop.stockEditJournal != null) return false;
            if (editorShops.putIfAbsent(player.getUuid(), shop.id) != null) return false;
            if (stockEditors.putIfAbsent(shop.id, player.getUuid()) != null) {
                editorShops.remove(player.getUuid(), shop.id);
                return false;
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    public boolean isStockEditing(UUID shopId) {
        ShopData shop = store.get(shopId);
        return stockEditors.containsKey(shopId) || (shop != null && shop.stockEditJournal != null);
    }

    public boolean hasInterruptedStockEdit(UUID shopId) {
        ShopData shop = store.get(shopId);
        return shop != null && shop.stockEditJournal != null;
    }

    public void endStockEdit(ShopData shop, ServerPlayerEntity player) {
        stockEditors.remove(shop.id, player.getUuid());
        editorShops.remove(player.getUuid(), shop.id);
    }

    public SimpleInventory createStockInventory(ShopData shop) {
        SimpleInventory inv = new SimpleInventory(STOCK_SIZE);
        shop.ensureStockSize();
        for (int i = 0; i < STOCK_SIZE; i++) {
            inv.setStack(i, ItemCodec.decode(shop.stock.get(i), server.getRegistryManager()).copy());
        }
        return inv;
    }

    /**
     * Persists a stable Stock GUI checkpoint. The same atomic shops.json write contains both
     * the authoritative 108 stock slots and the matching 36-slot player inventory snapshot.
     * If the process dies later during a cursor move, recovery restores this checkpoint.
     */
    public void checkpointStockEdit(ShopData shop, SimpleInventory inv, ServerPlayerEntity player) {
        ReentrantLock lock = lock(shop.id);
        lock.lock();
        try {
            UUID activeEditor = stockEditors.get(shop.id);
            if (!player.getUuid().equals(activeEditor)) {
                throw new IllegalStateException("This Stock session is no longer the active editor.");
            }

            List<String> newStock = captureStock(inv);
            List<String> newPlayerInventory = capturePlayerInventory(player);
            ShopData.StockEditJournal journal = shop.stockEditJournal;
            boolean newJournal = false;
            if (journal == null) {
                journal = new ShopData.StockEditJournal();
                journal.editorUuid = player.getUuid();
                journal.editorName = player.getGameProfile().getName();
                journal.ensureInventorySize();
                shop.stockEditJournal = journal;
                newJournal = true;
            }
            if (!player.getUuid().equals(journal.editorUuid)) {
                throw new IllegalStateException("Stock recovery journal belongs to another editor.");
            }

            journal.ensureInventorySize();
            boolean changed = newJournal || !shop.stock.equals(newStock) || !journal.playerInventory.equals(newPlayerInventory);
            if (!changed) return;

            shop.stock.clear();
            shop.stock.addAll(newStock);
            journal.playerInventory.clear();
            journal.playerInventory.addAll(newPlayerInventory);
            journal.checkpointAt = System.currentTimeMillis();
            shop.revision++;
            store.save();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Finalizes a normal Stock close. The journal stays durable until vanilla player data has
     * been flushed. Clearing it is the last step, so a crash at any earlier point is recoverable.
     */
    public void finishStockEdit(ShopData shop, SimpleInventory inv, ServerPlayerEntity player) {
        ReentrantLock lock = lock(shop.id);
        lock.lock();
        try {
            checkpointStockEdit(shop, inv, player);
            server.getPlayerManager().saveAllPlayerData();
            shop.stockEditJournal = null;
            shop.revision++;
            store.save();
        } finally {
            endStockEdit(shop, player);
            lock.unlock();
        }
    }

    /**
     * Reconciles any interrupted stock session for this player before they can edit again.
     * The stock side was already persisted atomically with this inventory snapshot.
     */
    public int recoverInterruptedStockEdits(ServerPlayerEntity player) {
        int recovered = 0;
        for (ShopData shop : store.all()) {
            ShopData.StockEditJournal journal = shop.stockEditJournal;
            if (journal == null || !player.getUuid().equals(journal.editorUuid)) continue;

            ReentrantLock lock = lock(shop.id);
            lock.lock();
            try {
                journal = shop.stockEditJournal;
                if (journal == null || !player.getUuid().equals(journal.editorUuid)) continue;
                journal.ensureInventorySize();
                for (int i = 0; i < 36; i++) {
                    player.getInventory().setStack(i,
                            ItemCodec.decode(journal.playerInventory.get(i), server.getRegistryManager()).copy());
                }
                player.getInventory().markDirty();

                // Flush the repaired inventory before removing the recovery marker.
                server.getPlayerManager().saveAllPlayerData();
                shop.stockEditJournal = null;
                shop.revision++;
                store.save();
                recovered++;
            } catch (Exception e) {
                CobbleDollarsPlayerShops.LOGGER.error("Could not reconcile interrupted Stock session for shop {}", shop.name, e);
                // Journal intentionally remains: purchases/stock editing stay fail-closed.
            } finally {
                lock.unlock();
            }
        }
        if (recovered > 0) {
            player.sendMessage(Text.literal("Recovered " + recovered + " interrupted PlayerShop Stock session(s) safely.")
                    .formatted(Formatting.YELLOW), false);
        }
        return recovered;
    }

    private List<String> captureStock(SimpleInventory inv) {
        List<String> out = new ArrayList<>(STOCK_SIZE);
        for (int i = 0; i < STOCK_SIZE; i++) {
            out.add(ItemCodec.encode(inv.getStack(i).copy(), server.getRegistryManager()));
        }
        return out;
    }

    private List<String> capturePlayerInventory(ServerPlayerEntity player) {
        List<String> out = new ArrayList<>(36);
        for (int i = 0; i < 36; i++) {
            out.add(ItemCodec.encode(player.getInventory().getStack(i).copy(), server.getRegistryManager()));
        }
        return out;
    }

    public ShopData.Listing addListing(ShopData shop, ItemStack template, BigInteger price) {
        validatePrice(price);
        ItemStack normalized = ItemCodec.template(template);
        if (normalized.isEmpty()) throw new IllegalArgumentException("Select a valid item first.");

        ReentrantLock lock = lock(shop.id);
        lock.lock();
        try {
            for (ShopData.Listing existing : shop.listings) {
                ItemStack other = ItemCodec.decode(existing.itemSnbt, server.getRegistryManager());
                if (ItemCodec.same(normalized, other)) throw new IllegalStateException("That exact item is already listed.");
            }
            ShopData.Listing listing = new ShopData.Listing();
            listing.itemSnbt = ItemCodec.encode(normalized, server.getRegistryManager());
            listing.price = price.toString();
            shop.listings.add(listing);
            shop.revision++;
            store.save();
            return listing;
        } finally {
            lock.unlock();
        }
    }

    public void cancelListing(ShopData shop, UUID listingId) {
        ReentrantLock lock = lock(shop.id);
        lock.lock();
        try {
            shop.listings.removeIf(l -> l.id.equals(listingId));
            shop.revision++;
            store.save();
        } finally {
            lock.unlock();
        }
    }

    public void updatePrice(ShopData shop, UUID listingId, BigInteger price) {
        validatePrice(price);
        ReentrantLock lock = lock(shop.id);
        lock.lock();
        try {
            ShopData.Listing listing = shop.listings.stream().filter(l -> l.id.equals(listingId)).findFirst().orElseThrow();
            listing.price = price.toString();
            shop.revision++;
            store.save();
        } finally {
            lock.unlock();
        }
    }

    private void validatePrice(BigInteger price) {
        ServerConfig config = CobbleDollarsPlayerShops.INSTANCE.config();
        if (price == null || price.signum() < 0) {
            throw new IllegalArgumentException("Price must be a non-negative whole number.");
        }
        if (price.compareTo(config.minPrice()) < 0 || price.compareTo(config.maxPrice()) > 0) {
            throw new IllegalArgumentException("Price must be between " + config.MinPrice + " and " + config.MaxPrice + " CobbleDollars.");
        }
    }

    public PurchaseResult purchase(ServerPlayerEntity buyer, ShopData shop, UUID listingId, int quantity) {
        if (quantity <= 0) return PurchaseResult.fail("Purchase quantity must be at least 1.");
        if (!shop.adminShop && buyer.getUuid().equals(shop.ownerUuid)) return PurchaseResult.fail("You cannot buy from your own shop.");
        if (!shop.open) return PurchaseResult.fail("This shop is currently closed.");
        if (!shop.adminShop && isStockEditing(shop.id)) return PurchaseResult.fail("This shop is being restocked or recovered. Try again in a moment.");

        ReentrantLock lock = lock(shop.id);
        lock.lock();
        try {
            if (!shop.adminShop && isStockEditing(shop.id)) return PurchaseResult.fail("This shop is being restocked or recovered. Try again in a moment.");
            ShopData.Listing listing = shop.listings.stream().filter(l -> l.id.equals(listingId)).findFirst().orElse(null);
            if (listing == null) return PurchaseResult.fail("This listing no longer exists.");
            ServerConfig config = CobbleDollarsPlayerShops.INSTANCE.config();
            if (!config.isPriceAllowed(listing.price())) return PurchaseResult.fail("This listing is outside the server price limits. The owner must update its price.");

            ItemStack template = ItemCodec.decode(listing.itemSnbt, server.getRegistryManager());
            if (template.isEmpty()) return PurchaseResult.fail("This listing is invalid.");

            // Match CobbleDollars' native behavior: requested amount is limited by actual
            // inventory capacity rather than an arbitrary x64 cap.
            quantity = Math.min(quantity, Math.max(1, PlayerExtensionKt.getMaxAmountObtainable(buyer, template)));
            if (!shop.adminShop) {
                int available = stockCount(shop, listing);
                quantity = Math.min(quantity, Math.max(1, available));
                if (available < quantity) return PurchaseResult.fail("Not enough stock.");
            }

            List<ItemStack> delivery = makeDelivery(template, quantity);
            if (!canFit(buyer, delivery)) return PurchaseResult.fail("You do not have enough inventory space.");

            BigInteger total = listing.price().multiply(BigInteger.valueOf(quantity));
            BigInteger balanceBefore = economy.get(buyer.getUuid());
            if (balanceBefore.compareTo(total) < 0) return PurchaseResult.fail("You do not have enough CobbleDollars.");

            BigInteger balanceAfter = balanceBefore.subtract(total);
            List<String> stockBackup = new ArrayList<>(shop.stock);
            BigInteger pendingBefore = shop.pending();
            BigInteger revenueBefore = shop.lifetimeRevenue();
            long lifetimeOrdersBefore = shop.lifetimeOrders;
            long lifetimeItemsBefore = shop.lifetimeItemsSold;
            int ordersBefore = shop.orders.size();
            try {
                // Fail closed: charge + durable accounting happens before item delivery.
                economy.setOnline(buyer, balanceAfter);
                if (!shop.adminShop) {
                    removeStock(shop, template, quantity);
                    shop.setPending(pendingBefore.add(total));
                }
                shop.setLifetimeRevenue(revenueBefore.add(total));
                shop.lifetimeOrders++;
                shop.lifetimeItemsSold += quantity;

                ShopData.OrderRecord order = new ShopData.OrderRecord();
                order.transactionId = UUID.randomUUID();
                order.buyerUuid = buyer.getUuid();
                order.buyerName = buyer.getGameProfile().getName();
                order.listingId = listing.id;
                order.itemSnbt = listing.itemSnbt;
                order.quantity = quantity;
                order.unitPrice = listing.price;
                order.total = total.toString();
                order.timestamp = System.currentTimeMillis();
                shop.orders.add(0, order);
                while (shop.orders.size() > 100) shop.orders.remove(shop.orders.size() - 1);
                shop.revision++;
                store.save();
            } catch (Exception e) {
                if (!shop.adminShop) {
                    shop.stock.clear();
                    shop.stock.addAll(stockBackup);
                    shop.setPending(pendingBefore);
                }
                shop.setLifetimeRevenue(revenueBefore);
                shop.lifetimeOrders = lifetimeOrdersBefore;
                shop.lifetimeItemsSold = lifetimeItemsBefore;
                while (shop.orders.size() > ordersBefore) shop.orders.remove(0);
                try { economy.setOnline(buyer, balanceBefore); }
                catch (Exception rollback) { CobbleDollarsPlayerShops.LOGGER.error("CRITICAL: purchase balance rollback failed", rollback); }
                CobbleDollarsPlayerShops.LOGGER.error("Purchase transaction failed", e);
                return PurchaseResult.fail("The transaction could not be completed safely.");
            }

            for (ItemStack stack : delivery) {
                ItemStack remaining = stack.copy();
                if (!buyer.getInventory().insertStack(remaining) || !remaining.isEmpty()) {
                    CobbleDollarsPlayerShops.LOGGER.error("Validated delivery unexpectedly failed for transaction in shop {}", shop.name);
                }
            }
            syncShopQuietly(shop.id);
            return PurchaseResult.ok("Purchase complete. Paid " + total + " CobbleDollars.");
        } finally { lock.unlock(); }
    }

    private void removeStock(ShopData shop, ItemStack template, int quantity) {
        int left = quantity;
        for (int i = 0; i < shop.stock.size() && left > 0; i++) {
            ItemStack stack = ItemCodec.decode(shop.stock.get(i), server.getRegistryManager());
            if (!ItemCodec.same(template, stack)) continue;
            int take = Math.min(left, stack.getCount());
            stack.decrement(take);
            left -= take;
            shop.stock.set(i, ItemCodec.encode(stack, server.getRegistryManager()));
        }
        if (left != 0) throw new IllegalStateException("Stock changed during locked transaction");
    }

    private static List<ItemStack> makeDelivery(ItemStack template, int quantity) {
        List<ItemStack> result = new ArrayList<>();
        int left = quantity;
        while (left > 0) {
            int amount = Math.min(left, template.getMaxCount());
            ItemStack stack = template.copy();
            stack.setCount(amount);
            result.add(stack);
            left -= amount;
        }
        return result;
    }

    private static boolean canFit(ServerPlayerEntity player, List<ItemStack> delivery) {
        List<ItemStack> simulated = new ArrayList<>(36);
        for (int i = 0; i < 36; i++) simulated.add(player.getInventory().getStack(i).copy());
        for (ItemStack incomingOriginal : delivery) {
            ItemStack incoming = incomingOriginal.copy();
            if (incoming.isStackable()) {
                for (ItemStack slot : simulated) {
                    if (incoming.isEmpty()) break;
                    if (!slot.isEmpty() && ItemStack.areItemsAndComponentsEqual(slot, incoming)) {
                        int room = Math.min(slot.getMaxCount(), incoming.getMaxCount()) - slot.getCount();
                        if (room > 0) {
                            int move = Math.min(room, incoming.getCount());
                            slot.increment(move);
                            incoming.decrement(move);
                        }
                    }
                }
            }
            for (int i = 0; i < simulated.size() && !incoming.isEmpty(); i++) {
                if (simulated.get(i).isEmpty()) {
                    int move = Math.min(incoming.getCount(), incoming.getMaxCount());
                    ItemStack placed = incoming.copy();
                    placed.setCount(move);
                    simulated.set(i, placed);
                    incoming.decrement(move);
                }
            }
            if (!incoming.isEmpty()) return false;
        }
        return true;
    }

    public CheckoutResult checkout(ShopData shop) {
        ReentrantLock lock = lock(shop.id);
        lock.lock();
        try {
            if (shop.payoutJournal != null && shop.payoutJournal.status != ShopData.PayoutJournal.Status.COMPLETED) {
                return CheckoutResult.fail("A previous payout requires reconciliation before another checkout.");
            }
            BigInteger amount = shop.pending();
            if (amount.signum() <= 0) return CheckoutResult.fail("There are no earnings to collect.");
            BigInteger before = economy.get(shop.ownerUuid);

            ShopData.PayoutJournal journal = new ShopData.PayoutJournal();
            journal.amount = amount.toString();
            journal.balanceBefore = before.toString();
            shop.payoutJournal = journal;
            shop.setPending(BigInteger.ZERO);
            shop.revision++;
            store.save(); // PREPARED is durable before touching CobbleDollars.

            if (!economy.add(shop.ownerUuid, amount)) {
                journal.status = ShopData.PayoutJournal.Status.REVIEW_REQUIRED;
                journal.note = "CobbleDollars offline add returned false.";
                store.save();
                return CheckoutResult.fail("CobbleDollars rejected the payout. No automatic retry will be made to prevent duplication.");
            }

            journal.status = ShopData.PayoutJournal.Status.COMPLETED;
            journal.completedAt = System.currentTimeMillis();
            store.save();
            return CheckoutResult.ok(amount);
        } finally {
            lock.unlock();
        }
    }

    public void reconcilePreparedPayouts() {
        for (ShopData shop : store.all()) {
            ReentrantLock lock = lock(shop.id);
            lock.lock();
            try {
                ShopData.PayoutJournal j = shop.payoutJournal;
                if (j == null || j.status != ShopData.PayoutJournal.Status.PREPARED) continue;
                BigInteger current = economy.get(shop.ownerUuid);
                BigInteger before = j.balanceBefore();
                BigInteger expected = before.add(j.amount());
                if (current.compareTo(expected) >= 0) {
                    j.status = ShopData.PayoutJournal.Status.COMPLETED;
                    j.completedAt = System.currentTimeMillis();
                    j.note = "Recovered: balance already included the payout.";
                } else if (current.equals(before)) {
                    if (economy.add(shop.ownerUuid, j.amount())) {
                        j.status = ShopData.PayoutJournal.Status.COMPLETED;
                        j.completedAt = System.currentTimeMillis();
                        j.note = "Recovered: payout applied after restart.";
                    } else {
                        j.status = ShopData.PayoutJournal.Status.REVIEW_REQUIRED;
                        j.note = "Recovery add failed.";
                    }
                } else {
                    j.status = ShopData.PayoutJournal.Status.REVIEW_REQUIRED;
                    j.note = "Ambiguous balance after restart; automatic payout blocked to prevent duplicate funds.";
                }
                store.save();
            } finally {
                lock.unlock();
            }
        }
    }

    private void syncShopQuietly(UUID shopId) {
        try {
            if (CobbleDollarsPlayerShops.INSTANCE.npcManager() != null)
                CobbleDollarsPlayerShops.INSTANCE.npcManager().syncShop(shopId);
        } catch (Throwable e) {
            CobbleDollarsPlayerShops.LOGGER.debug("Could not sync shop mirror {}", shopId, e);
        }
    }

    public record PurchaseResult(boolean success, String message) {
        static PurchaseResult ok(String m) { return new PurchaseResult(true, m); }
        static PurchaseResult fail(String m) { return new PurchaseResult(false, m); }
        public void send(ServerPlayerEntity p) {
            p.sendMessage(Text.literal(message).formatted(success ? Formatting.GREEN : Formatting.RED), false);
        }
    }

    public record CheckoutResult(boolean success, String message, BigInteger amount) {
        static CheckoutResult ok(BigInteger a) { return new CheckoutResult(true, "Collected " + a + " CobbleDollars.", a); }
        static CheckoutResult fail(String m) { return new CheckoutResult(false, m, BigInteger.ZERO); }
    }
}
