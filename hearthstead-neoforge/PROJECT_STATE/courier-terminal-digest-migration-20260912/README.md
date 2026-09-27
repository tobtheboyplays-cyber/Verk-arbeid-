# Courier ledger: terminal historic digest migration

## Live evidence — 2026-09-12, read-only

Source snapshot: `C:\Users\tobia\Hearthstead-Server\Hearthstead-Survival\data\hearthstead_request_ledger.dat`, timestamp `2026-09-12 17:09:20`, 4,163 bytes.

The saved root and Wilmot's settlement ledger both declare `Quarantined=0`.

| Bucket | Rows | Obsolete digest rows | Relevant state |
|---|---:|---:|---|
| Active | 1 | 1 | `OUTPUT_PICKUP`, `BLOCKED <- RESERVED`, `FINGERPRINT_MISMATCH`, moved/delivered `0/0` |
| Terminal | 26 | 13 | 12 completed `SATISFIED` output rows, 1 `EXPIRED` zero-cargo output row |

The active row is request UUID ints `[-1709275711,-555335511,-2096189455,-1366863150]`, owned by Wilmot, for four quality-1 oak logs. Its exact receipt remains on Wilmot with `Expected=0`, `Clock=0`, `Ack=false`, `Pending=false`, and physical `Bag=[]`.

Current code only permits the obsolete fingerprint format while loading the special **active** zero-cargo row. It loads every terminal row with the strict public decoder. The first historic terminal row with digest `67f3c9e52d7cf4343cdaf999d366139d0423a148a62223ad2cea955b56ff5f49` therefore returns null, making the entire settlement ledger `malformed_terminal_row` in memory. `CourierSourceBagSession.retireExpiredZeroCargoOutput` then exits at `ledger.quarantined()` and cannot remove Wilmot's exact empty receipt; `CourierWorkGoal` sees that active receipt but no active request and returns false before normal route selection.

## Patch contract

The patch keeps `RequestRecord.readNbt` strict and public. The only fallback is package-private and is used solely from `RequestLedger`'s terminal-history loop. It requires:

- current request data version;
- a state which is already terminal;
- a strict fingerprint decode failure plus a valid legacy parsed item/prototype;
- the existing full `RequestRecord` trace, counts, settlement identity, source/target, and state invariants to validate unchanged.

It cannot create an active request, reserve a source, add/remove an item, or affect a row that owns cargo. A blocked-to-expired transition also clears `blockedFrom`, and the replay validator treats the terminal edge as closing the interruption, so the migrated active zero-cargo row round-trips as valid terminal history on the next restart.

## Suggested future regression fixture

Construct one ledger NBT snapshot with:

1. this exact active legacy zero-cargo blocked row;
2. one terminal `SATISFIED` OUTPUT row using the same obsolete digest and a complete physical trace;
3. one terminal `EXPIRED` zero-cargo row using the same digest.

Load must be non-quarantined, retain all terminal rows, move only the active zero-cargo row to `EXPIRED`, and round-trip through `writeNbt`/strict reload. A malformed or nonterminal legacy-digest row must still quarantine.

No test/build was run, per current user instruction.