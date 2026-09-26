# Hearthstead survival reachability — live economy path

**Scope.** This is the current source contract after the 17 September economy
revision. Current-source packaging and all 901 required GameTests pass. It does
not prove an unassisted full player run, native UI readability, multiplayer, or
release readiness.

## Reachable route

| Stage | Player action and spend | Live rule |
| --- | --- | --- |
| 0. Founding | Craft and place a valid Hearth. | Three hireable founders and one dedicated Mayor arrive. |
| 1. First Coins | Sell ordinary Basic logs to the visiting merchant. | Basic timber costs 8 logs per Coin. The first visitor has an 8-Coin shared purse; later visitors have 12. |
| 2. First worker | Buy Timber Rights (2) and Lumberer Emblem (1). | Three Basic timber sales cost 24 logs, so a player can fund the first productive worker in the first market. |
| 3. Transport | Store a real Lumberer log, learn Stores and Roads (2), build a Warehouse and buy Courier (2). | A real delivery unlocks the farming branch. |
| 4. Food and housing | Learn Cultivated Ground (2), buy Farmer (2), then learn Home (2). | Home requires Shelter plus Foundation Ready, not a Farmer crop deposit. |
| 5. Village life | House three settlers, learn Hospitality (4), build a valid Tavern and buy Innkeeper (2). | Tavern service needs its complete Plaque layout, including a connected Ale Tap and barrel. |
| 6. First defense | Learn First Watch (4), buy Guard (4), then meet the real equipment/delivery gate for Archer (4). | Guard, Archer, tools, food, beds and arrows remain physical readiness requirements. |
| 7. Recovery | Resolve the first raid and collect the saved delivery if the Hearth has space. | Current raid reward behavior is still being rebalanced against V1 merchant income; it is not part of the bootstrap calculation. |

## Merchant boundaries

Every Coin sale is a real physical trade and every active visitor has one shared
market budget. Reopening a menu, reloading, replacing the visitor, choosing
another wood species, or adding more players cannot create a new allowance.
When the purse reaches zero, every Coin offer retires.

The first four sales in an active Timber, Field, or Specialty family use their
base quote. The fourth sale makes remaining rows in that family cost one 25%
rounded-up input step. It cannot stack and resets only for the next visitor.

| Quality | Timber / iron / honey | Field goods | Row capacity |
| --- | ---: | ---: | ---: |
| Basic | 8 | 16 | 4 |
| Fine | 7 | 14 | 4 |
| Superior | 6 | 12 | 4 |
| Exceptional | 5 | 10 | 2 |
| Masterwork | 4 | 9 | 2 |
| Legendary | 4 | 8 | 1 |

Quality is committed by completed worker work. It cannot be created by moving,
storing, reconnecting, or reloading a stack. Oak planks retain the same value
as their source log because the plank quote is exactly four times the rounded
log quote.

## Known evidence boundaries

- **Automated server behavior:** current-source package and 901 GameTests pass,
  including market purse, demand, reload and migration behavior.
- **Player-visible merchant clarity:** the screen still needs a server-to-client
  shared-purse and next-visit display before it can be called fully readable.
- **Ordinary survival pacing:** fresh solo and shared-settlement play from
  founding to first defense remain required.
- **Farmer after reload:** the controlled save preserves worker identity and
  inventory, but a spectator-only observer run did not resume crop growth.
  The next diagnosis uses an active nearby observer while preserving all
  conservation checks.
- **No-hireable-resident recovery:** an all-founder loss remains a design branch
  that needs a bounded, persisted recovery route before release.

Historical price audits and old balance notes are retained as history only;
they do not describe the live market policy. For the current source of truth,
read [Economy revision](project/ECONOMY_REBALANCE_2026-09-17.md).
