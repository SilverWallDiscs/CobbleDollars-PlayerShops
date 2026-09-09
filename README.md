# CobbleDollars PlayerShops

A server-side Fabric mod for **Minecraft 1.21.1** that adds persistent player-owned shop NPCs backed by **CobbleDollars**.

PlayerShops keeps money, stock and listings in persistent server data while using a FakePlayer only as the visual NPC projection. The NPC opens CobbleDollars' native shop interface, so buying feels consistent with the rest of the mod.

## Features

- Persistent player shop NPCs
- CobbleDollars native shop UI
- 108-slot persistent stock storage for normal shops
- Infinite-stock admin shops
- Safe purchases with balance/stock rollback protection
- No artificial 64-item purchase limit — purchases are capped by actual inventory space and available stock
- Persistent order history and earnings
- Configurable per-player NPC limit and price range
- Optional **LuckPerms** admin permission
- Optional **Flan** claim permission integration
- Custom NPC skins from any valid Mojang/Minecraft username, even if that player is offline or has never joined the server
- Signed Mojang skin textures are cached in shop data so skins survive reconnects and server restarts
- NPC projection repair when chunks unload/reload

## Requirements

- Minecraft **1.21.1**
- Fabric Loader **0.16.5+**
- Fabric API
- CobbleDollars **2.0.0+**

Optional:

- Flan
- LuckPerms

SGUI is included in the built mod JAR.

## Commands

### Player shops

```text
/shopnpc add <name>
/shopnpc config <name>
/shopnpc remove <name>
/shopnpc move <name>
/shopnpc list
/shopnpc reloadconfig
```

`/shopnpc reloadconfig` requires admin permission.

### Admin shops

```text
/shopnpcadmin create <name>
/shopnpcadmin remove <name>
```

Admin shops have unlimited stock and are not counted against the normal per-player NPC limit.

## Permissions

### LuckPerms

The admin permission node is:

```text
playershopadmin
```

Vanilla operators with permission level 2 or higher are also treated as PlayerShops admins.

Example:

```text
/lp user <player> permission set playershopadmin true
```

### Flan

When Flan is installed, PlayerShops registers this claim permission:

```text
cobbledollars_playershops:spawn_player_shop_npc
```

It appears in Flan as **Allow Spawning Player Shops NPC**. Creating or moving a normal PlayerShop inside a claim requires this permission. The integration fails closed if Flan is installed but the custom permission cannot be resolved.

## NPC skins

Open a shop's **Settings** menu and choose **NPC Skin**, then type a Minecraft username in chat.

The target player does **not** need to be online. PlayerShops resolves the account through Mojang's official profile services and stores the signed `textures` property in the shop data. Cached unsigned/invalid textures are rejected instead of silently falling back to them.

## Configuration

On first startup the mod creates:

```text
config/cobbledollars-playershops.json
```

Default configuration:

```json
{
  "Limitnpc": 6,
  "MinPrice": 500,
  "MaxPrice": 100000000
}
```

- `Limitnpc`: maximum normal PlayerShops per player
- `MinPrice`: minimum listing price
- `MaxPrice`: maximum listing price

Admin shops are not included in `Limitnpc`.

## Transaction safety

The persistent shop data is authoritative; the NPC entity is only a projection.

For a normal purchase the server validates the request, clamps the amount to inventory capacity and stock, verifies the buyer balance, debits the buyer, persists stock/accounting changes, and only then delivers items. If the persistent transaction fails, the balance/accounting state is restored before any items are delivered.

Stock editing also keeps a recovery journal so an interrupted server session does not silently duplicate or delete the player's inventory/stock state.

See [ANTI_DUPING.md](ANTI_DUPING.md) for more implementation notes.

## Building

Java 21 is required.

```bash
gradle build
```

The compiled mod will be created under `build/libs/`.

The tested **1.2.5** JAR is also included in [`dist/`](dist/).

## Project layout

```text
src/main/java/                         Java source
src/main/resources/                    Fabric metadata, mixins and Flan data
config-example/                        Example server config
dist/                                  Tested release JAR
ANTI_DUPING.md                         Transaction/recovery notes
CHANGELOG.md                            Version history
```

## License

MIT License. See [LICENSE](LICENSE).

Maintained by **Silver / SilverWallDiscs**.
