package dev.silver.cobbledollarsplayershops;

import com.mojang.authlib.GameProfile;
import fr.harmex.cobbledollars.common.world.item.trading.CobbleDollarsShopHolder;
import fr.harmex.cobbledollars.common.world.item.trading.shop.Shop;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Visual/interactive projection only. ShopStore remains authoritative. */
public final class ShopNpcEntity extends FakePlayer implements CobbleDollarsShopHolder {
    private final UUID shopId;
    private double anchorX, anchorY, anchorZ;
    private float anchorYaw, anchorPitch;
    private Shop cobbleShop = new Shop();
    private UUID merchantUUID;
    private Set<PlayerEntity> tradingPlayers = new LinkedHashSet<>();

    public ShopNpcEntity(ServerWorld world, GameProfile profile, UUID shopId, String displayName,
                         double x, double y, double z, float yaw, float pitch) {
        super(world, profile);
        this.shopId = shopId;
        this.merchantUUID = getUuid();
        anchor(x, y, z, yaw, pitch);
        setCustomName(Text.literal(displayName));
        setCustomNameVisible(true);
        setInvulnerable(true);
        setNoGravity(true);
        setSilent(true);
        noClip = true;
        forceSkinLayersMetadata();
    }

    public UUID shopId() { return shopId; }

    public void forceSkinLayersMetadata() {
        // Vanilla skin-layer metadata byte: all seven outer layers enabled.
        getDataTracker().set(PLAYER_MODEL_PARTS, (byte) 0x7F, true);
    }

    public void anchor(double x, double y, double z, float yaw, float pitch) {
        anchorX = x; anchorY = y; anchorZ = z; anchorYaw = yaw; anchorPitch = pitch;
        refreshPositionAndAngles(x, y, z, yaw, pitch);
        setHeadYaw(yaw);
        setVelocity(Vec3d.ZERO);
    }

    @Override public Shop getShop() { return cobbleShop; }
    @Override public void setShop(Shop shop) { this.cobbleShop = shop == null ? new Shop() : shop; }
    @Override public UUID getMerchantUUID() { return merchantUUID; }
    @Override public void setMerchantUUID(UUID uuid) { merchantUUID = uuid == null ? getUuid() : uuid; }
    @Override public Set<PlayerEntity> getTradingPlayers() { return tradingPlayers; }
    @Override public void setTradingPlayers(Set<PlayerEntity> players) { tradingPlayers = players == null ? new LinkedHashSet<>() : players; }

    @Override public void tick() {
        super.tick();
        refreshPositionAndAngles(anchorX, anchorY, anchorZ, anchorYaw, anchorPitch);
        setHeadYaw(anchorYaw);
        setVelocity(Vec3d.ZERO);
    }
    @Override public boolean isPushable() { return false; }
    @Override public void pushAwayFrom(Entity entity) {}
    @Override public boolean damage(DamageSource source, float amount) { return false; }
    @Override public boolean shouldSave() { return false; }
}
