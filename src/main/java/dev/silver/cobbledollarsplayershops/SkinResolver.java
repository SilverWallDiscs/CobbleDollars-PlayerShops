package dev.silver.cobbledollarsplayershops;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.server.MinecraftServer;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Mojang profile/skin resolver used by both normal and admin shops. */
public final class SkinResolver {
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern ID_PATTERN = Pattern.compile("[0-9a-fA-F]{32}");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private static final Map<String, GameProfile> PROFILE_CACHE = new ConcurrentHashMap<>();
    private SkinResolver() {}

    public static boolean canResolve(Object server, String name) {
        try { return resolveByName(normalize(name)) != null; }
        catch (Exception e) { debug("Skin lookup failed for " + name, e); return false; }
    }

    public static Object resolve(Object serverObject, Object shopObject) {
        MinecraftServer server = (MinecraftServer) serverObject;
        ShopData shop = (ShopData) shopObject;
        String wanted = normalize(shop.effectiveSkinName());
        if (wanted == null) return fallbackOwner(server, shop);

        try {
            GameProfile persisted = profileFromPersistedTexture(shop, wanted);
            if (persisted != null) return persisted;

            GameProfile resolved = resolveByName(wanted);
            if (resolved != null && hasSignedTextures(resolved)) {
                persistTexture(shop, resolved, wanted);
                saveStore();
                return resolved;
            }
        } catch (Exception e) {
            debug("Could not resolve signed Mojang skin for " + wanted, e);
        }
        return fallbackOwner(server, shop);
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String name = value.trim();
        return USERNAME.matcher(name).matches() ? name : null;
    }

    private static GameProfile resolveByName(String name) throws Exception {
        if (name == null) return null;
        String key = name.toLowerCase(Locale.ROOT);
        GameProfile cached = PROFILE_CACHE.get(key);
        if (cached != null && hasSignedTextures(cached)) return cached;

        UUID uuid = lookupMojangUuid(name);
        if (uuid == null) return null;
        GameProfile profile = fetchSignedProfile(uuid);
        if (profile != null && hasSignedTextures(profile)) PROFILE_CACHE.put(key, profile);
        return profile;
    }

    private static UUID lookupMojangUuid(String name) throws Exception {
        String url = "https://api.minecraftservices.com/minecraft/profile/lookup/name/" + URLEncoder.encode(name, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return null;
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!json.has("id")) return null;
        return parseUuid(json.get("id").getAsString());
    }

    private static UUID parseUuid(String raw) {
        if (raw == null) return null;
        String clean = raw.replace("-", "");
        if (!ID_PATTERN.matcher(clean).matches()) return null;
        String dashed = clean.substring(0, 8) + "-" + clean.substring(8, 12) + "-" + clean.substring(12, 16)
                + "-" + clean.substring(16, 20) + "-" + clean.substring(20);
        return UUID.fromString(dashed);
    }

    private static GameProfile fetchSignedProfile(UUID uuid) throws Exception {
        String id = uuid.toString().replace("-", "");
        String url = "https://sessionserver.mojang.com/session/minecraft/profile/" + id + "?unsigned=false";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("Mojang session profile lookup returned HTTP " + response.statusCode());

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        UUID profileId = json.has("id") ? parseUuid(json.get("id").getAsString()) : uuid;
        String profileName = json.has("name") ? json.get("name").getAsString() : "PlayerShop";
        GameProfile profile = new GameProfile(profileId == null ? uuid : profileId, profileName);
        JsonArray props = json.has("properties") ? json.getAsJsonArray("properties") : new JsonArray();
        for (var element : props) {
            JsonObject p = element.getAsJsonObject();
            if (!p.has("name") || !p.has("value")) continue;
            String propertyName = p.get("name").getAsString();
            String value = p.get("value").getAsString();
            String signature = p.has("signature") ? p.get("signature").getAsString() : null;
            profile.getProperties().put(propertyName,
                    signature == null || signature.isBlank() ? new Property(propertyName, value) : new Property(propertyName, value, signature));
        }
        return profile;
    }

    private static boolean hasSignedTextures(GameProfile profile) {
        if (profile == null) return false;
        for (Property property : profile.getProperties().get("textures")) {
            if (property.value() != null && !property.value().isBlank()
                    && property.signature() != null && !property.signature().isBlank()) return true;
        }
        return false;
    }

    private static Property firstTextureProperty(GameProfile profile) {
        for (Property property : profile.getProperties().get("textures")) return property;
        return null;
    }

    private static void persistTexture(ShopData shop, GameProfile profile, String requestedName) {
        Property texture = firstTextureProperty(profile);
        if (texture == null || texture.signature() == null || texture.signature().isBlank()) return;
        shop.skinTextureName = requestedName;
        shop.skinTextureValue = texture.value();
        shop.skinTextureSignature = texture.signature();
        shop.skinTextureUuid = profile.getId() == null ? "" : profile.getId().toString();
    }

    private static GameProfile profileFromPersistedTexture(ShopData shop, String requestedName) {
        if (shop.skinTextureName == null || !shop.skinTextureName.equalsIgnoreCase(requestedName)) return null;
        if (shop.skinTextureValue == null || shop.skinTextureValue.isBlank()) return null;
        // Old 1.2.3/1.2.4 cache entries without a signature are deliberately invalidated.
        if (shop.skinTextureSignature == null || shop.skinTextureSignature.isBlank()) return null;
        UUID uuid;
        try { uuid = UUID.fromString(shop.skinTextureUuid); }
        catch (Exception ignored) { return null; }
        GameProfile profile = new GameProfile(uuid, requestedName);
        profile.getProperties().put("textures", new Property("textures", shop.skinTextureValue, shop.skinTextureSignature));
        return profile;
    }

    private static GameProfile fallbackOwner(MinecraftServer server, ShopData shop) {
        try {
            if (shop.ownerUuid != null) {
                var online = server.getPlayerManager().getPlayer(shop.ownerUuid);
                if (online != null && hasSignedTextures(online.getGameProfile())) return online.getGameProfile();
            }
            String owner = normalize(shop.ownerName);
            if (owner != null) {
                GameProfile profile = resolveByName(owner);
                if (profile != null) return profile;
            }
        } catch (Exception e) { debug("Owner skin fallback failed", e); }
        UUID uuid = shop.ownerUuid == null ? UUID.randomUUID() : shop.ownerUuid;
        String name = shop.ownerName == null || shop.ownerName.isBlank() ? "PlayerShop" : shop.ownerName;
        return new GameProfile(uuid, name.substring(0, Math.min(16, name.length())));
    }

    private static void saveStore() {
        try {
            if (CobbleDollarsPlayerShops.INSTANCE != null && CobbleDollarsPlayerShops.INSTANCE.store() != null)
                CobbleDollarsPlayerShops.INSTANCE.store().save();
        } catch (Throwable e) { debug("Could not persist skin cache", e); }
    }

    private static void debug(String message, Throwable e) {
        CobbleDollarsPlayerShops.LOGGER.debug(message, e);
    }
}
