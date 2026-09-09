# CobbleDollars PlayerShops

Server-side Fabric mod for **Minecraft 1.21.1** that adds persistent player-owned NPC shops powered by **CobbleDollars**.

Players can create a shop NPC, stock it with real items, publish listings with custom prices, choose an NPC skin, and let other players purchase through CobbleDollars' native shop interface.

<img width="271" height="523" alt="imagen" src="https://github.com/user-attachments/assets/8cc99478-c83e-4371-bf02-593d4aa9ad6a" />
<img width="624" height="476" alt="imagen" src="https://github.com/user-attachments/assets/865ab0cf-22bc-47d7-8084-e2e310c46d9c" />
<img width="359" height="453" alt="imagen" src="https://github.com/user-attachments/assets/656013fe-1288-4712-81c0-28f881c6d43d" />


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


<img width="1381" height="581" alt="imagen" src="https://github.com/user-attachments/assets/d965027d-dc51-47f7-8484-96f91d3ac480" />
<img width="1340" height="554" alt="imagen" src="https://github.com/user-attachments/assets/bd9d270c-be99-4eac-a525-b4a2c09fd720" />
<img width="727" height="483" alt="imagen" src="https://github.com/user-attachments/assets/10652ecb-164c-4af3-82c1-de5b27100e34" />


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
