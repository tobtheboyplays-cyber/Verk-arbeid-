# Goblin thievery: first complete system

Owner approved 11 September 2026. Friendly-comic thief pressure that rewards guards, not a destructive raid. In-game text English.

## Progression and cadence
Owner update: first natural visit becomes eligible as soon as a registered village chest contains at least one physical Coin, with a valid Hearth and a living player nearby. No First Watch requirement or initial grace period. Keep one active thief and30 eligible minutes between subsequent visits. Offline/unloaded time does not consume cooldown. Persist next eligibility/active thief per settlement, including unloaded thieves. Failed path/spawn checks retry at most once per minute without charging a completed visit. No visit during an active raid; peaceful difficulty disables natural thieves. One active thief per settlement, never replenish merely because an entity is unloaded.

## Physical encounter
Choose a real Coin container inside a valid registered settlement building using existing authoritative storage APIs. Never inspect or debit player inventories. Spawn on loaded, dry, collision-free terrain out of nearby players' sight,8-40 blocks from chosen container; validate complete safe route before publication. Do not force-load chunks or alter terrain. Open ordinary wooden doors; cannot unlock iron doors, break blocks, phase through walls or teleport.
Approach -> three-second continuous-contact stealing telegraph -> flee. If contact is interrupted, restart telegraph. At transfer tick move actual stack into existing Raider loot authority. Amount min(3,max(1,floor(current Coins/10))), no second theft by same actor. Loss bounded, goods quality irrelevant to currency. Concurrent player spending uses actual current count. No minted recovery reward.

## Detection and recovery
One visible alert and short surprised sound when a player or defender actually sees thief. Guards use normal hostile acquisition and interception, no invisible global teleport targeting. Civilians retain existing danger response. Goblin flees rather than fights;12health, quick small steps. Killing it releases its exact cargo once. A thief that reaches original escape point remains physically recoverable for this first version; no silent despawn of money. Natural director does not create another while this actor is outstanding. If a no-cargo thief is permanently unable to path, retreat and expire safely after bounded timeout. Persist explicit death/removal outcomes; unload is never death. Unknown missing actor holds slot rather than risking duplicate cargo/visits.

## Presentation
Original block model: expressive eyebrows, pupils, small asymmetric smirk, stepped pouch with tie, hood and broad ears. SNEAK: short steps and occasional shoulder checks. STEAL: planted feet, forward latch reach, hand at Coins transfer. FLEE: pouch clutch, brief startled reaction, short rapid steps. Smooth actor-local transitions; no shared renderer mutable phase. Synced state/phase must be authoritative for both players. Sparse amusing sounds; avoid normal raid roar/horn and repeated sound spam. Initial cues reuse licensed vanilla sounds; custom voice work remains separate.

## Verification and rollout
Test natural eligibility/grace/reload, one-active lock, no unloaded replacement, physical routing, continuous contact, exact loss/recovery conservation, two-client presentation state, ordinary raid regression. Preview actual model and movement; native visual/audio approval remains separate from compilation or serialized data assertions. Preserve current world; install only after tests, with offline backup and restart. Manual demo command remains for controlled testing. Document every unimplemented rule instead of implying completion.
