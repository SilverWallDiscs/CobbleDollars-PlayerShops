package dev.silver.cobbledollarsplayershops;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Persistent, entity-independent shop state. */
public final class ShopData {
    public UUID id;
    public String name;
    public UUID ownerUuid;
    public String ownerName;
    public String skinName;
    public boolean adminShop = false;
    public String dimension;
    public double x;
    public double y;
    public double z;
    public float yaw;
    public float pitch;
    public boolean open = true;
    public long revision = 0L;
    public String pendingEarnings = "0";
    public String lifetimeRevenue = "0";
    public long lifetimeOrders = 0L;
    public long lifetimeItemsSold = 0L;
    public final List<String> stock = new ArrayList<>();
    public final List<Listing> listings = new ArrayList<>();
    public final List<OrderRecord> orders = new ArrayList<>();
    public PayoutJournal payoutJournal;
    public StockEditJournal stockEditJournal;

    /** Cached signed Mojang skin property. */
    public String skinTextureName;
    public String skinTextureValue;
    public String skinTextureSignature;
    public String skinTextureUuid;

    public ShopData() { ensureStockSize(); }

    public void ensureStockSize() {
        while (stock.size() < 108) stock.add("");
        while (stock.size() > 108) stock.remove(stock.size() - 1);
    }

    public String effectiveSkinName() {
        if (skinName != null && !skinName.isBlank()) return skinName;
        return ownerName == null ? "" : ownerName;
    }

    public BigInteger pending() {
        try { return new BigInteger(pendingEarnings == null ? "0" : pendingEarnings); }
        catch (NumberFormatException ignored) { return BigInteger.ZERO; }
    }
    public void setPending(BigInteger amount) { pendingEarnings = amount.max(BigInteger.ZERO).toString(); }
    public BigInteger lifetimeRevenue() {
        try { return new BigInteger(lifetimeRevenue == null ? "0" : lifetimeRevenue); }
        catch (NumberFormatException ignored) { return BigInteger.ZERO; }
    }
    public void setLifetimeRevenue(BigInteger amount) { lifetimeRevenue = amount.max(BigInteger.ZERO).toString(); }

    public static final class Listing {
        public UUID id = UUID.randomUUID();
        public String itemSnbt = "";
        public String price = "0";
        public long createdAt = System.currentTimeMillis();
        public BigInteger price() {
            try { return new BigInteger(price); }
            catch (NumberFormatException ignored) { return BigInteger.ZERO; }
        }
    }

    public static final class OrderRecord {
        public UUID transactionId;
        public UUID buyerUuid;
        public String buyerName;
        public UUID listingId;
        public String itemSnbt;
        public int quantity;
        public String unitPrice;
        public String total;
        public long timestamp;
    }

    public static final class StockEditJournal {
        public UUID id = UUID.randomUUID();
        public UUID editorUuid;
        public String editorName = "";
        public final List<String> playerInventory = new ArrayList<>();
        public long checkpointAt = System.currentTimeMillis();
        public void ensureInventorySize() {
            while (playerInventory.size() < 36) playerInventory.add("");
            while (playerInventory.size() > 36) playerInventory.remove(playerInventory.size() - 1);
        }
    }

    public static final class PayoutJournal {
        public enum Status { PREPARED, COMPLETED, REVIEW_REQUIRED }
        public UUID id = UUID.randomUUID();
        public Status status = Status.PREPARED;
        public String amount = "0";
        public String balanceBefore = "0";
        public long preparedAt = System.currentTimeMillis();
        public long completedAt;
        public String note = "";
        public BigInteger amount() { return new BigInteger(amount); }
        public BigInteger balanceBefore() { return new BigInteger(balanceBefore); }
    }
}
