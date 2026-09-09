# Transaction safety notes

PlayerShops treats the persistent `ShopData` as authoritative. The NPC entity is only a visual/interactive projection.

## Purchase order

1. Validate shop/listing/price.
2. Clamp the requested quantity to actual inventory capacity and available stock.
3. Simulate item delivery before charging.
4. Validate the CobbleDollars balance.
5. Debit the buyer.
6. Remove physical stock for normal shops and update seller/order accounting.
7. Persist the shop state.
8. Deliver the purchased stacks.

If persistence fails after the debit, PlayerShops restores the stock/accounting snapshot and attempts to restore the buyer balance before returning a failure.

Admin shops deliberately skip physical stock removal and pending seller payout because their stock is infinite.

## Stock editing

Normal shop stock uses a durable edit journal containing the matching player inventory snapshot. The journal remains until player data has been flushed. On reconnect an interrupted journal can restore the matching inventory state before the shop is unlocked again.

This design is intended to fail closed: an ambiguous transaction/recovery state should block further mutation instead of guessing and risking duplication.
