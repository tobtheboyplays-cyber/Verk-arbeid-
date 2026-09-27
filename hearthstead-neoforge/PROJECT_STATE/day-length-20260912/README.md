# Day length — isolated implementation proposal

## Request
Make the visible Overworld day about **1.5x longer** without changing server `gameTime`, merchant cadence, raid/recovery timers, sleep semantics, or command authority.

## Audit facts

| Concern | Current owner | Clock used | Consequence of naïvely slowing `dayTime` |
|---|---|---:|---|
| Settler activity / Tavern | `settlement/DayPhase.java`, `SettlerEntity.dayPhase()` | `level.getDayTime()` | Intended: work, meal and evening last 1.5x longer. |
| Merchant attempts, retry, visit and return | `event/EarlyCoinMerchant.java` | `level.getGameTime()` | Already unaffected by a visual-day change. Its `VISIT_TICKS=24000` and `PERIOD_TICKS=48000` remain real server-tick durations. |
| Raid calendar / roll window | `settlement/raid/RaidDirector.java` | `level.getDayTime()` | **Would be delayed 1.5x** if left as-is. First and recurring scheduling therefore cannot be called unaffected without a small clock split. |
| Raid recovery cooldown | `RaidDirector` | `level.getGameTime()` | Already unaffected. |
| Research daily trickle | `event/ResearchEvents.java`, `settlement/research/Research.java` | `level.getDayTime()` | Naturally follows the longer calendar day. This needs an explicit product decision; do not silently turn it into `gameTime`. |
| Sleep / `/time` / daylight gamerule | Vanilla `ServerLevel` | vanilla day time | Must remain vanilla-authoritative; no custom replacement clock or gamerule. |

No current Hearthstead event writes production `dayTime`. Test fixtures use `setDayTime`, which is not a production timing hook.

## Recommended minimal design

1. Add one Overworld-only `LevelTickEvent.Post` service, e.g. `event/HearthsteadDayLength.java`.
2. It does nothing unless `GameRules.RULE_DAYLIGHT` is true and `level.dimension() == Level.OVERWORLD`.
3. On every third **server `gameTime`** tick, it subtracts one just-applied vanilla daylight tick:
   ```java
   if (level.getGameTime() % 3L == 0L) {
       level.setDayTime(level.getDayTime() - 1L);
   }
   ```
   Vanilla adds 3 daylight ticks while the service subtracts 1: net 2 daylight ticks per 3 server ticks, exactly 1.5x longer. There is no accumulator to save, because the phase is derived from persisted `gameTime`; reloads retain the 2:3 cadence.
4. Detect an external large time jump before applying the correction. Keep a transient last-observed `dayTime` per `ServerLevel`; when the observed advance is not 0/1 (sleep or `/time set/add`), refresh the observation and skip the correction for that tick. Vanilla remains authoritative for the jump.
5. Do not touch Nether/End time. Do not add a replacement gamerule, config, scheduled task, or persistent world data.

## Required raid clock split

To meet “merchant/raid unaffected”, `RaidDirector` needs a **calendar scheduling clock** separate from visible light:

```java
private static long schedulingNight(ServerLevel level) {
    return Math.floorDiv(level.getGameTime(), DAY_LENGTH);
}
```

Use this only for first/recurring schedule identity, warning due checks, and `nightOf` inputs that are persisted as upcoming raid calendar numbers. Keep the *physical attack/night-light gate* on `level.getDayTime()` so attacks still occur at night rather than at an arbitrary daytime. `RaidSleepPolicy` should keep visible `dayTime`, since it answers whether sleep should be blocked tonight.

This is a small but essential addition: changing day length without it makes the current `RaidDirector` schedule one raid calendar every 36,000 real ticks. Merchant code needs no change because it already uses `gameTime` throughout.

## Targeted tests after integration

1. `dayLengthSlowsOnlyOverworldAndHonoursDaylightRule`: 3 server ticks advance Overworld `dayTime` by 2; when daylight cycle is false, it does not mutate; non-Overworld is untouched.
2. `dayLengthSkipsCorrectionAfterExternalTimeJump`: a sleep-style / command-style jump is retained exactly on its tick.
3. Existing merchant test proves its `VISIT_TICKS` and receipt cadence still use `gameTime`.
4. Add a pure `RaidDirector` scheduling-clock test proving one `DAY_LENGTH` of `gameTime` advances scheduling night independently of a slower visible day. Existing night/light tests remain on `dayTime`.

## Deliberate non-change

The existing research daily trickle is currently calendar/daylight based. Under a longer day it fires less often in real minutes. That should remain until explicitly changed: moving it to `gameTime` would silently alter research rewards rather than merely making the day longer.
