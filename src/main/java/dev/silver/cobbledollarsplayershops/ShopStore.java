package dev.silver.cobbledollarsplayershops;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class ShopStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<ShopData>>(){}.getType();

    private final MinecraftServer server;
    private final Path dir;
    private final Path file;
    private final Map<UUID, ShopData> byId = new LinkedHashMap<>();

    public ShopStore(MinecraftServer server) {
        this.server = server;
        this.dir = server.getSavePath(WorldSavePath.ROOT).resolve("cobbledollars_playershops");
        this.file = dir.resolve("shops.json");
    }

    public synchronized void load() {
        byId.clear();
        try {
            Files.createDirectories(dir);
            if (!Files.exists(file)) return;
            List<ShopData> shops = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), LIST_TYPE);
            if (shops == null) return;
            for (ShopData shop : shops) {
                if (shop == null || shop.id == null || shop.ownerUuid == null || shop.name == null) continue;
                shop.ensureStockSize();
                if (shop.pendingEarnings == null) shop.pendingEarnings = "0";
                if (shop.lifetimeRevenue == null) shop.lifetimeRevenue = "0";
                if (shop.stockEditJournal != null) shop.stockEditJournal.ensureInventorySize();
                byId.put(shop.id, shop);
            }
            CobbleDollarsPlayerShops.LOGGER.info("Loaded {} player shops", byId.size());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load CobbleDollars PlayerShops data", e);
        }
    }

    /** Temp file + fsync + atomic rename when supported. */
    public synchronized void save() {
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve("shops.json.tmp");
            String json = GSON.toJson(new ArrayList<>(byId.values()), LIST_TYPE);
            Files.writeString(tmp, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist CobbleDollars PlayerShops data", e);
        }
    }

    public synchronized Collection<ShopData> all() {
        return List.copyOf(byId.values());
    }

    public synchronized ShopData get(UUID id) { return byId.get(id); }

    public synchronized Optional<ShopData> findByName(String name) {
        return byId.values().stream().filter(s -> s.name.equalsIgnoreCase(name)).findFirst();
    }

    public synchronized List<ShopData> ownedBy(UUID owner) {
        return byId.values().stream().filter(s -> s.ownerUuid.equals(owner)).toList();
    }

    public synchronized void put(ShopData shop) { byId.put(shop.id, shop); }
    public synchronized ShopData remove(UUID id) { return byId.remove(id); }
    public MinecraftServer server() { return server; }
}
