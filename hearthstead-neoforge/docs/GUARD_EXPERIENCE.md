# Guard and archer kill experience

## Player contract

- A `GUARD` or `ARCHER` earns combat XP only when the final damage source
  credits that same settler and the dead target implements Minecraft's
  hostile `Enemy` contract.
- Friendly settlers, players, animals, environmental deaths and defenders in
  another profession award nothing.
- Ordinary hostiles award 10 XP, Hearthstead raiders 15 XP and raid captains
  30 XP. The persisted counter saturates at 480 and cannot overflow.
- The five visible combat levels begin at 0, 40, 120, 260 and 480 XP. The
  settler inspection sheet shows the current level and next absolute
  threshold; its tooltip also names the existing ability rank.
- Each valid death also trains the existing progression attribute:
  `STRENGTH` for guards and `DEXTERITY` for archers. `GuardRank` and
  `ArcherRank` therefore remain the single ability/armor authority.
- One short, original two-note cue (`hearthstead:guard_experience`) plays at
  the defender only when that death actually increases XP. A defender at the
  480 cap gets no false "XP gained" cue or hidden attribute training. It is
  synthesized deterministically by
  `tools/gen_sounds.py`; no external or reference-mod audio is used.

## Authority and deduplication

`GuardExperienceEvents` listens to `LivingDeathEvent` at lowest priority on
the server. A canceled death does not reach it. The dying entity receives one
namespaced persistent-data marker immediately before the award, so replaying
the same event is idempotent without a global UUID cache or cleanup loop.
Projectile damage credits `DamageSource#getEntity`, which is the owning archer
(the GameTest uses a real `Arrow` as `getDirectEntity`), while environment
damage has no eligible settler owner.

The bounded value is stored as `GuardExperience` in the settler's entity NBT
and projected through synced entity data. The inspection view rebuilds its
cached combat line only when that integer changes; it does not allocate a new
line every render frame.

## Verification

- `GuardExperienceTest` pins threshold boundaries, tier progress and
  saturating/overflow-safe addition.
- `GuardExperienceGameTests` covers a real guard killing a raider, a real
  archer killing a vanilla hostile, replay deduplication, friendly,
  environmental and civilian rejection, attribute training, NBT round-trip
  and malformed-value clamping.
- `python tools/gen_sounds.py --only guard_experience` verifies 0.32 s,
  Vorbis, 44.1 kHz, mono and non-clipping output.

The complete GameTest batch must still be run in the serial release pass; the
shared working tree cannot safely run concurrent Gradle game servers.
