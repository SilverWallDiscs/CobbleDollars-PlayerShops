# Changelog

## 1.2.5

### Added
- Player-owned persistent NPC shops using CobbleDollars
- Physical stock and persistent sales/earnings
- Infinite-stock admin shops
- LuckPerms `playershopadmin` integration
- Flan claim permission integration
- Mojang username skin lookup and persistent signed texture cache

### Improved
- Removed the artificial 64-item purchase cap
- Purchase amount now clamps to actual player inventory capacity and shop stock
- Improved NPC projection repair after chunk unload/reload and player reconnects
- Full player-profile refresh path for custom NPC skins

### Safety
- Buyer debit/stock/accounting are committed before item delivery
- Failed persistent transactions restore buyer/shop state
- Stock GUI recovery journal protects interrupted restocking sessions
- Invalid or unsigned cached skin textures are rejected and refreshed
