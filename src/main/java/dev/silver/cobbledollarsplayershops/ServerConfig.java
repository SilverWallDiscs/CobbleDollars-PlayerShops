package dev.silver.cobbledollarsplayershops;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Global server configuration for CobbleDollars PlayerShops.
 *
 * Stored in config/cobbledollars-playershops.json so the limits are shared by
 * every world hosted by the same server installation.
 */
public final class ServerConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "cobbledollars-playershops.json";

    // Keep the serialized field names exactly as documented for server owners.
    public int Limitnpc = 6;
    public long MinPrice = 500L;
    public long MaxPrice = 100_000_000L;

    public static ServerConfig load() {
        try {
            return loadStrict();
        } catch (Exception e) {
            CobbleDollarsPlayerShops.LOGGER.error("Could not load {}. Using safe defaults for this session; the existing file was left untouched.", path(), e);
            return new ServerConfig();
        }
    }

    /** Loads the file and throws if an existing config is invalid. */
    public static ServerConfig loadStrict() throws IOException {
        Path path = path();
        Files.createDirectories(path.getParent());

        if (!Files.exists(path)) {
            ServerConfig defaults = new ServerConfig();
            defaults.save();
            return defaults;
        }

        try (Reader reader = Files.newBufferedReader(path)) {
            ServerConfig loaded = GSON.fromJson(reader, ServerConfig.class);
            if (loaded == null) throw new IllegalArgumentException("Config file is empty.");
            loaded.validate();
            return loaded;
        } catch (RuntimeException e) {
            throw new IOException("Invalid PlayerShops config: " + e.getMessage(), e);
        }
    }

    public void save() throws IOException {
        validate();
        Path path = path();
        Files.createDirectories(path.getParent());
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temp)) {
            GSON.toJson(this, writer);
        }
        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void validate() {
        if (Limitnpc < 0) throw new IllegalArgumentException("Limitnpc cannot be negative.");
        if (MinPrice < 0) throw new IllegalArgumentException("MinPrice cannot be negative.");
        if (MaxPrice < 0) throw new IllegalArgumentException("MaxPrice cannot be negative.");
        if (MaxPrice < MinPrice) throw new IllegalArgumentException("MaxPrice cannot be lower than MinPrice.");
    }

    public BigInteger minPrice() {
        return BigInteger.valueOf(MinPrice);
    }

    public BigInteger maxPrice() {
        return BigInteger.valueOf(MaxPrice);
    }

    public boolean isPriceAllowed(BigInteger price) {
        return price != null && price.compareTo(minPrice()) >= 0 && price.compareTo(maxPrice()) <= 0;
    }

    public static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }
}
