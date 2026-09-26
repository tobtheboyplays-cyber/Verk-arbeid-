# Economy revision — earned, predictable scarcity

Status: **V1 is live in the current source.** The source packages successfully
and all 901 required GameTests passed on 17 September. That verifies the
server-side contract below; it is not a native-playtest, UI, FPS, or release
claim.

Owner direction: Coins should require real work without alternating between a
trivial windfall and a starvation loop. This policy replaces historical price
notes and applies to future Coin sources, Coin sinks, job gates, and trading
mechanics.

## Live V1 merchant contract

Every normal visitor posts reliable manual outlets for one timber family and
one field-good family. A player can earn the first worker without a paid job,
rare tool, iron trip, or random third offer.

| Rule | Live behavior |
| --- | --- |
| First visitor purse | 8 Coins total, shared by every row and player |
| Later visitor purse | 12 Coins total, shared by every row and player |
| Cadence | The same settlement can receive its next eligible visitor every 24,000 ticks (20 minutes at 20 TPS) |
| Basic / Fine / Superior row stock | 4 Coin sales |
| Exceptional / Masterwork row stock | 2 Coin sales |
| Legendary row stock | 1 Coin sale |
| Duplicate callbacks | A completed sale debits the shared purse once only |
| Reload / replacement | A published visitor keeps its own stock and purse; it is never refilled by reopening, reload, or replacement |

When the shared purse reaches zero, every Coin row retires. The row counter is
therefore only local capacity; the purse is the actual visitor-wide budget.
Pre-policy published markets are deliberately retired with no new purse rather
than being repriced or refilled. This preserves old saves without leaving a
cheap legacy Coin mint.

## Physical quotes and quality

The merchant always consumes real matching goods. Timber, iron and honey use
the timber quote; wheat, carrots, potatoes, beetroot and pumpkins use the
field quote. Oak planks are always four times the matching rounded log quote,
so vanilla's one-log-to-four-planks conversion cannot create Coin value.

| Quality | Timber / iron / honey per Coin | Field goods per Coin | Typical source |
| --- | ---: | ---: | --- |
| Basic | 8 | 16 | Player-gathered ordinary goods |
| Fine | 7 | 14 | Ordinary completed worker output |
| Superior | 6 | 12 | Skill 20+ with an iron-or-better paid tool |
| Exceptional | 5 | 10 | Skill 30+, high-tier paid tool, valid workplace, rare result |
| Masterwork | 4 | 9 | Skill 35+, high-tier paid tool, valid workplace, rare result |
| Legendary | 4 | 8 | Skill 40+, netherite paid tool, valid workplace, 1-in-100 result |

Quality comes from the completed physical work receipt. Carrying, storing,
transferring, reconnecting, or reloading a stack never upgrades it. Quality is
a modest efficiency benefit, not a second currency printer: Legendary is at
most twice as material-efficient as Basic and has one sale of row capacity.

## One readable demand rule

Each active visitor tracks three families:

| Family | Goods |
| --- | --- |
| Timber | All supported logs and oak planks |
| Field | Wheat, carrot, potato, beetroot and pumpkin |
| Specialty | Iron ingot and honey bottle |

The first four Coin sales in a family use the displayed base quote. The fourth
sale applies one +25% rounded-up input step to every remaining live row in that
family. The step cannot stack, wood species cannot bypass it, and it disappears
with the next visitor. This replaces the old overlapping long-lived
per-item-pressure and per-row-use penalties.

## Economic intent

The ordinary first productive worker costs 3 Coins: Timber Rights costs 2 and
the Lumberer Emblem costs 1. Three Basic log shipments cost 24 logs, so the
first visit can fund it without waiting for a second visitor. A fully sold
sequence of visits at minute 0, 20, 40 and 60 has an upper bound of 44 Coins;
that is capacity after gathering and transport, not free income.

Existing sinks remain the baseline until ordinary-speed play says otherwise:
development 2 / 4 / 6 Coins, civilian emblems 1 / 2, military emblems 4, and
recruitment 4 / 6 / 8 before any visible fixed discount. The six-role and first
defense route was source-costed at roughly 41 Coins with the Innkeeper discount
and roughly 43 without it. Those are planning figures, not a completed
player-time measurement.

Tavern income remains supplemental because each visitor carries a finite purse
and a Coin is paid only for completed service. Raid rewards and goblin theft
are a separate next slice: they must support recovery and tension without
becoming the best Coin farm or locking a new settlement out of its first worker.

## Policy for every future mechanic

- New Coin income must have a physical source, an explicit finite or paced
  budget, server-owned persistence, and an exact-once conservation test.
- New Coin costs must show their live price before commitment and leave a
  recoverable path to earn the required amount.
- A craft or conversion must remain value-neutral unless it consumes additional
  material, completed labour, risk, or time that the player can understand.
- Quality may improve efficiency, but cannot multiply payout faster than the
  source's capped market capacity.
- A new event must state its cooldown, grace period, loss/reward range,
  recovery path, and multiplayer ownership before it is considered complete.
- UI copy must describe the live source of truth. A hidden global budget or
  invisible price change is a quality failure.

## Verification performed

- Current-source package: PASS.
- Current-source GameTest suite: PASS, 901 required tests.
- Focused coverage includes quote values, quality caps, shared purse depletion,
  duplicate-sale idempotency, family surcharge, reload, and legacy-market
  retirement.

Still required before a player-balance or release claim:

1. Fresh ordinary-speed solo play from founding through first defense.
2. A shared-settlement playtest that exercises concurrent trading, storage and
   save/rejoin.
3. A merchant UI snapshot that exposes the shared purse and next-visit state.
4. Matched measurements of manual gathering, production, Tavern service, raid
   rewards and goblin losses.

