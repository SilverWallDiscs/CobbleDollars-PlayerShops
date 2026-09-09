# CobbleDollars PlayerShops

Server-side Fabric mod for **Minecraft 1.21.1** that adds persistent player-owned NPC shops powered by **CobbleDollars**.

Players can create a shop NPC, stock it with real items, publish listings with custom prices, choose an NPC skin, and let other players purchase through CobbleDollars' native shop interface.

## Features

- Persistent player-owned NPC shops
- CobbleDollars native merchant UI
- 108-slot physical stock for normal shops
- Infinite-stock admin shops
- Custom listing prices
- Persistent earnings, order history and analytics
- Purchase amount automatically limited by inventory capacity and available stock (no artificial x64 cap)
- Custom NPC skins resolved from valid Minecraft usernames, including offline players
- Signed Mojang texture caching for reconnect/restart persistence
- NPC projection repair across chunk unload/reload and reconnects
- Transaction rollback and stock-edit recovery protections
- Optional LuckPerms support
- Optional Flan claim permission support

## Requirements

- Minecraft **1.21.1**
- Fabric Loader **0.16.5+**
- Fabric API
- CobbleDollars **2.0.0+**

Optional: **Flan**, **LuckPerms**.

## Commands

```text
/shopnpc add <name>
/shopnpc config <name>
/shopnpc remove <name>
/shopnpc move <name>
/shopnpc list
/shopnpc reloadconfig

/shopnpcadmin create <name>
/shopnpcadmin remove <name>
```

`/shopnpcadmin` and admin management require operator level 2 or the LuckPerms node:

```text
playershopadmin
```

## Flan

When Flan is installed, normal shop creation/movement checks:

```text
cobbledollars_playershops:spawn_player_shop_npc
```

The permission appears in Flan as **Allow Spawning Player Shops NPC**.

## Skins

Open a shop's Settings menu, choose the NPC Skin option, and type a valid Minecraft username in chat.

PlayerShops resolves the username using Mojang's Minecraft profile service, obtains the signed `textures` property from the Mojang session server (`unsigned=false`), and stores the signed texture in the shop data. The selected player does not have to be online or have joined the server before.

## Configuration

Generated at:

```text
config/cobbledollars-playershops.json
```

Default:

```json
{
  "Limitnpc": 6,
  "MinPrice": 500,
  "MaxPrice": 100000000
}
```

## Building

Java 21 is required.

```bash
gradle build
```

The tested 1.2.5 server JAR is included under [`dist/`](dist/).

## License

MIT. See [LICENSE](LICENSE).

Maintained by **Silver / SilverWallDiscs**.
