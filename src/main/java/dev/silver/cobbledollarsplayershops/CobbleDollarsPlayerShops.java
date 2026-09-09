package dev.silver.cobbledollarsplayershops;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CobbleDollarsPlayerShops implements ModInitializer {
    public static final String MOD_ID = "cobbledollars_playershops";
    public static final Logger LOGGER = LoggerFactory.getLogger("CobbleDollars PlayerShops");
    public static CobbleDollarsPlayerShops INSTANCE;

    private ServerConfig config;
    private ShopStore store;
    private ShopService service;
    private ShopNpcManager npcManager;
    private ChatInputManager chat;

    @Override public void onInitialize() {
        INSTANCE = this;
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            ShopNpcCommand.register(dispatcher);
            ShopNpcAdminCommand.register(dispatcher);
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            config = ServerConfig.load();
            store = new ShopStore(server);
            store.load();
            service = new ShopService(server, store);
            chat = new ChatInputManager();
            chat.register();
            npcManager = new ShopNpcManager(server, store);
            npcManager.registerEvents();
            NpcProjectionRepair.register();
            service.reconcilePreparedPayouts();
            npcManager.maintain();
            LOGGER.info("CobbleDollars PlayerShops ready: {} shops loaded | Limitnpc={} | MinPrice={} | MaxPrice={}",
                    store.all().size(), config.Limitnpc, config.MinPrice, config.MaxPrice);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> { if (store != null) store.save(); });
    }

    public ServerConfig config() { return config; }
    public void reloadConfig() throws java.io.IOException { config = ServerConfig.loadStrict(); }
    public ShopStore store() { return store; }
    public ShopService service() { return service; }
    public ShopNpcManager npcManager() { return npcManager; }
    public ChatInputManager chat() { return chat; }
}
