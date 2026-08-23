# Modular Settler Appearance — Design (phase A1d)

Status: **blueprint, not yet implemented.** Quality-ledger row 9 ("Modular
settler visuals") moves to PASS only when §8 is green through
`tools/hearthstead-qa full`.

Scope: make every settler visually unique and persistent — skin tone × hair
style/colour × face × body build × profession outfit — identical on every
client, stable across save/load, and cheap enough to run 30 of them plus
raiders at 60 fps.

All Minecraft/NeoForge API names below were verified against
`build/moddev/artifacts/neoforge-21.1.248-sources.jar` (NeoForge 21.1.248,
MC 1.21.1, mojmaps). Where a signature matters it is quoted verbatim.

---

## 0. The five decisions

Read this section if you read nothing else. Everything after it is detail.

| # | Decision | Rejected alternative |
|---|---|---|
| D1 | Appearance is **one packed `long`**, resolved once at birth, stored as the authority. | Storing only a seed (faces would silently change the day we add a hairstyle). |
| D2 | Synced with the **vanilla `EntityDataSerializers.LONG`**. | A custom `EntityDataSerializer` in `NeoForgeRegistries.ENTITY_DATA_SERIALIZERS` — unnecessary, `LONG` already exists. |
| D3 | **Runtime texture composition** into a client-side `DynamicTexture` cache: 1 draw call per settler, ~100 source PNGs. | Pre-generated per-combination files (≈50 PB) and naive per-trait `RenderLayer`s (6× draw calls, and cannot express hair colour without 128 files). |
| D4 | Layer art is authored with **ramp keys** (magenta sentinel colours) that the composer resolves against the settler's own palette ramps, applying the same per-face lighting `texlib` uses. One `hair/long_braid.png` covers all 8 hair colours. | One pre-shaded file per style×colour (600+ files). |
| D5 | **Compose identity, layer equipment.** Skin/face/hair/beard/outfit go into the composed sheet. Guard armour progression is a real `RenderLayer` with its own deformed geometry and one shared texture per tier. | Composing armour too (re-composes on every equip; loses cross-settler batching). |

Sheet size goes from **128×64 to 128×128** (§4). This is a deliberate,
one-time break; the existing top half keeps its exact UV offsets so no
current art region moves.

---

## 1. Trait model

### 1.1 The traits

Bit layout of the appearance code, LSB-first. `schema` sits in the low nibble
and is always ≥ 1, so the value `0L` is unambiguously "not resolved yet" and
the common code stays small enough for `VAR_LONG` to encode in 7–8 bytes.

| Bits | Width | Field | Values | Kind | Notes |
|---|---|---|---|---|---|
| 0–3 | 4 | `schema` | 1..15 (now 1) | meta | bump only on a layout change; drives migration |
| 4 | 1 | `sex` | 2 | identity | gates `facialHair`, biases `build`; no gameplay effect |
| 5–7 | 3 | `skinTone` | 8 | palette | ordered ramp, pale → deep |
| 8–9 | 2 | `faceShape` | 4 | texture | nose/mouth/jaw-shading variant |
| 10–12 | 3 | `eyeColour` | 8 | palette | resolved into the eye layer's secondary key |
| 13–14 | 2 | `eyeShape` | 4 | texture | |
| 15–16 | 2 | `browShape` | 4 | texture | brows take the **hair** ramp |
| 17–20 | 4 | `hairStyle` | 16 | texture | index 0 = bald (no file) |
| 21–23 | 3 | `hairColour` | 8 | palette | |
| 24–26 | 3 | `facialHair` | 8 | texture | 0 = none; forced 0 when `sex`=fem or age < YOUTH |
| 27–29 | 3 | `faceMark` | 8 | texture | 0 = none; freckles, moles, weathering, earned scars |
| 30–31 | 2 | `build` | 4 | geometry | bone scale, no texture cost |
| 32–33 | 2 | `heightClass` | 4 | geometry | `Attributes.SCALE` modifier |
| 34–37 | 4 | `tunicDye` | 12 of 16 | palette | restricted per profession |
| 38–40 | 3 | `trimDye` | 8 | palette | |
| 41–43 | 3 | `accessory` | 8 | texture | 0 = none; neckerchief, belt-pouch, earring, arm-rag … |
| 44–46 | 3 | `ageStage` | 5 of 8 | mutable | INFANT/CHILD/YOUTH/ADULT/ELDER |
| 47–49 | 3 | `headgear` | 8 | texture | 0 = "profession default" |
| 50–63 | 14 | reserved | — | — | must be 0; validator asserts it |

50 bits defined, 14 spare. Reserved capacity is earmarked for saga-earned
marks (battle scars, a missing eye), tattoo/warpaint sets, and a second
accessory slot.

### 1.2 Combination count

Identity traits only (excluding `schema`, `ageStage`, reserved):

```
2 × 8 × 4 × 8 × 4 × 4 × 16 × 8 × 8 × 8 × 4 × 4 × 12 × 8 × 8 × 8
  = 6.60 × 10^12 raw combinations
```

Raw counts flatter the design, so here are the two honest numbers:

- **Recognition at conversation range** (the face reads): everything above.
  Constraints (no beards on women or children, dye sets restricted per
  profession) cut the *reachable* set to roughly 2 × 10^11. Irrelevantly
  large either way.
- **Recognition at 20 blocks** (only silhouette and large colour blocks
  read): `hairStyle(16) × facialHair(8) × accessory(8) × headgear(8) ×
  build(4) × heightClass(4)` = **131 072 silhouettes**, times
  `skinTone(8) × hairColour(8) × tunicDye(12) × trimDye(8)` = **6 144
  colourways** → **8.05 × 10^8 classes**.

Birthday-collision probability for a 30-settler village at that stricter
distance metric: `1 − exp(−30·29 / (2·8.05e8)) ≈ 5.4 × 10⁻⁷`. Even blinding
the metric further to just silhouette × skin × hair (8.4 × 10⁶ classes) gives
`≈ 5 × 10⁻⁵`. **Two settlers in one village will never look the same.**

### 1.3 Variety vs. memory cost

The reason this variety is affordable is that traits are **additive layers,
not multiplied files**.

| Layer group | Files (now) | Files (12 professions) |
|---|---|---|
| `skin/base_<tone>` | 8 | 8 |
| `face/base_<shape>` | 4 | 4 |
| `face/eyes_<shape>` | 4 | 4 |
| `face/brow_<shape>` | 4 | 4 |
| `face/mark_<mark>` | 7 | 7 |
| `hair/<style>` | 15 | 15 |
| `beard/<style>` | 7 | 7 |
| `accessory/<name>` | 7 | 7 |
| `headgear/<name>` | 7 | 7 |
| `outfit/<prof>_t<1..3>` | 12 | 36 |
| `settler_unresolved.png` | 1 | 1 |
| **Total** | **76** | **100** |

Costs, measured against the real UV table:

- **Disk.** 128×128 RGBA PNG, mostly transparent, binary alpha → 1–4 KB each.
  100 files ≈ **200 KB** in the jar. (Current fully-painted 128×64 sheets are
  ~3 KB, so this is a like-for-like estimate.)
- **Client heap.** Source layers decoded to `NativeImage`: 128×128×4 = 64 KB
  each. Holding all 100 resident = **6.4 MB**. Acceptable; if it ever isn't,
  crop each layer to its bounding rect and store an offset (documented
  optimisation, not day-one work).
- **VRAM.** One composed 128×128 RGBA texture per *distinct* appearance,
  64 KB, no mipmaps. LRU capped at 96 entries = **6 MB**. A 30-settler
  village uses 1.9 MB.
- **Compose CPU.** Only texels inside a box UV rect are touched: **4 074 of
  16 384** (computed from the UV table in §4.1). Eight layer passes ≈ 33 000
  texel tests, well under 0.1 ms in Java over the native buffer.

Contrast with pre-generated files (§3, option a): 8.05 × 10^8 "distinct at
distance" classes × 64 KB = **51 TB**, and that is the *cheap* metric.

---

## 2. Persistence and sync

### 2.1 Storage model — resolved code is the authority

Two longs live on the entity:

| NBT key | Type | Synced? | Meaning |
|---|---|---|---|
| `Appearance` | `long` | **yes** | the resolved code from §1.1. Authoritative. |
| `AppearanceSeed` | `long` | no | the seed the code was originally rolled from. Server-side only; used for `/hearthstead reroll`, QA reproduction, and debugging. |
| `AgeTicks` | `int` | no | growth clock; the derived `ageStage` lives inside `Appearance` |
| `MotherId` / `FatherId` | `UUID` | no | lineage (present only for village-born settlers) |

**Why store the resolved code and not just the seed.** Hearthstead has named
settlers with families, saga entries and memorial stones. If appearance were
re-derived from a seed on every load, adding a 17th hairstyle in a later
version would silently redraw the face of every existing settler. Storing the
resolved code freezes identity at birth; the seed is kept only so a roll can
be *reproduced*, never so it is *re-run*. The `schema` nibble handles the one
legitimate case for change: a migration that must remap old indices.

### 2.2 Sync — `EntityDataSerializers.LONG`

Verified present in 1.21.1
(`net/minecraft/network/syncher/EntityDataSerializers.java:43`):

```java
public static final EntityDataSerializer<Long> LONG =
    EntityDataSerializer.forValueType(ByteBufCodecs.VAR_LONG);
```

So no custom serializer, no `NeoForgeRegistries.ENTITY_DATA_SERIALIZERS`
registration, no hand-written `StreamCodec`. Following the exact pattern
already in `SettlerEntity.java:60-67`:

```java
private static final EntityDataAccessor<Long> DATA_APPEARANCE =
    SynchedEntityData.defineId(SettlerEntity.class, EntityDataSerializers.LONG);
```

```java
@Override
protected void defineSynchedData(SynchedEntityData.Builder builder) {
    super.defineSynchedData(builder);
    ...
    builder.define(DATA_APPEARANCE, 0L);   // 0 = unresolved
}
```

Accessors, mirroring the existing `getProfession()` / `setActivity()` style:

```java
public long appearanceCode()            { return entityData.get(DATA_APPEARANCE); }
public Appearance appearance()          { /* cached unpack, invalidated on code change */ }
public void setAppearance(long code)    { /* server only; entityData.set + refreshScale() */ }
```

`Appearance` is unpacked lazily and memoised per entity against the last seen
code, because `SettlerRenderer.getTextureLocation` runs every frame per
settler (`LivingEntityRenderer.getRenderType` line 144 calls it).

Bandwidth: `VAR_LONG` over a ≤50-bit value = 7–8 bytes, sent once in the
entity's initial data payload and again only when appearance actually
changes (age stage, an earned scar). Negligible.

`heightClass` and `ageStage` are *not* separately synced — they drive a
`Attributes.SCALE` modifier, and `SCALE` is `setSyncable(true)`
(`Attributes.java:85-87`). `LivingEntity` refreshes both the render scale and
the hitbox automatically:

```java
// LivingEntity.java:2513
float f6 = this.getScale();
if (f6 != this.appliedScale) { this.appliedScale = f6; this.refreshDimensions(); }
// LivingEntityRenderer.java:97
float f8 = p_115308_.getScale();
p_115311_.scale(f8, f8, f8);
```

Applied as (1.21.1 `AttributeModifier` is a record of
`(ResourceLocation id, double amount, Operation)`):

```java
new AttributeModifier(Hearthstead.id("appearance_scale"),
    heightFactor * ageFactor - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
```

### 2.3 NBT round-trip

Mirroring `SettlerEntity.addAdditionalSaveData` / `readAdditionalSaveData`
exactly as they are written today:

```java
// addAdditionalSaveData
tag.putLong("Appearance", appearanceCode());
tag.putLong("AppearanceSeed", appearanceSeed);
tag.putInt("AgeTicks", ageTicks);
if (motherId != null) tag.putUUID("MotherId", motherId);
if (fatherId != null) tag.putUUID("FatherId", fatherId);

// readAdditionalSaveData
long code = tag.getLong("Appearance");
appearanceSeed = tag.getLong("AppearanceSeed");
ageTicks = tag.getInt("AgeTicks");
motherId = tag.hasUUID("MotherId") ? tag.getUUID("MotherId") : null;
fatherId = tag.hasUUID("FatherId") ? tag.getUUID("FatherId") : null;
if (code == 0L || AppearanceCodec.schemaOf(code) == 0) {
    code = AppearanceCodec.fromSeed(stableSeed(), getProfession(), AgeStage.ADULT);
} else if (AppearanceCodec.schemaOf(code) < AppearanceCodec.SCHEMA) {
    code = AppearanceCodec.migrate(code);
}
entityData.set(DATA_APPEARANCE, code);
refreshAppearanceScale();
```

`stableSeed()` = `appearanceSeed != 0 ? appearanceSeed
: (getUUID().getMostSignificantBits() ^ getUUID().getLeastSignificantBits())`,
so even a `/summon`ed settler with no seed gets a look that is stable forever
and identical on every client.

### 2.4 Determinism rules (non-negotiable)

1. `AppearanceCodec.fromSeed(long, Profession, AgeStage)` is a **pure
   function**. No `Level`, no `RandomSource` from the world, no time. It uses
   a locally constructed `java.util.random`-free splitmix over the seed so
   the same seed always yields the same code on any JVM.
2. It is called **only on the logical server** — from
   `SettlementManager.spawnSettler` (which already exists and is the single
   spawn funnel per INV-5) and from `SettlerEntity.finalizeSpawn` as the
   fallback. The client never rolls appearance; `SettlerSkinCache` reads the
   synced code and nothing else. This is what makes every client identical by
   construction rather than by luck.
3. `qa`/validator enforcement: no reference to `AppearanceCodec.fromSeed` or
   `AppearanceCodec.inherit` may appear under `com/hearthstead/client/`
   (§8 check 12).

---

## 3. Rendering strategy

### 3.1 The three options, costed

Village of 30 settlers, plus 6–10 raiders in phase A3.

**(a) Pre-generated composed textures, one file per combination.**

| | |
|---|---|
| Files | 6.6 × 10^12 (or 8 × 10^8 for the "distinct at distance" subset) |
| Disk / VRAM | 51 TB for the *subset* |
| Draw calls | 1 per settler (best) |
| Verdict | **Impossible.** Not a trade-off, an arithmetic wall. Any variant that pre-multiplies traits into files dies the same death; even collapsing to skin×hair×outfit only (8 × 16 × 8 × 12 = 12 288 files) already puts 780 MB of PNG in the jar and still gives no face variety. |

**(b) Layered `RenderLayer`s over a base skin (vanilla villager / armour
pattern).**

Minimum viable layer set: base skin, face, hair, beard, outfit-dyed,
outfit-undyed → **6 draw calls per settler** (each distinct texture is a
distinct `RenderType`, hence its own buffer and draw in `MultiBufferSource`).

| | |
|---|---|
| Files | ~76, same as composition (the source art is identical) |
| Draw calls | 6 × 30 = **180/frame** for settlers alone, before name tags, held items and armour |
| VRAM | ~5 MB (same source art resident on GPU) |
| Fatal flaw | Colour. `renderColoredCutoutModel(model, tex, pose, buffers, light, entity, int color)` tints the **whole layer**. Tinting the hair layer by hair colour is fine; tinting the outfit layer by dye also tints its leather trim and iron buckle. So dye variety costs either a 2× draw-call split per tinted region, or one pre-shaded file per colour (600+ files). Both are worse than composition on the axis they were supposed to win. |
| Verdict | Right pattern for the wrong problem. Keep it for equipment (§6.3). |

**(c) Runtime composition into a client-side cache. — CHOSEN**

| | |
|---|---|
| Files | 76 now, ~100 at 12 professions, ~200 KB on disk |
| Draw calls | **1 per settler**, exactly as today |
| VRAM | 64 KB per distinct appearance; LRU 96 = 6 MB ceiling |
| CPU | ~33 000 texel tests per compose, < 0.1 ms, once per settler per session |
| Extra win | The composed sheet is the same asset the Tingboka portrait card needs — one implementation serves both the world and the UI |
| Cost | ~250 lines of cache/composer code, a reload listener, and a throttle |

Modding convention: composition is not exotic. Vanilla itself composes player
skins from downloaded images, and `DynamicTexture` + `TextureManager.register`
is a first-class, stable API (`TextureManager.java:62`,
`DynamicTexture.java:22`). Texture-pack authors keep full control — they
override the *layer sources*, which is finer-grained than overriding a
monolithic skin, and the composer picks up their edits on the next resource
reload. The one thing composition loses versus layering is the ability for a
third-party mod to bolt a new visual on without touching our registry; that
is exactly what §6.3's `RenderLayer` seam is for.

### 3.2 Classes to add

**Common** (`com.hearthstead.entity.appearance`, no client imports — INV-6):

| Class | Responsibility |
|---|---|
| `Appearance` | `record Appearance(int schema, Sex sex, SkinTone skin, …)` with `long pack()` and `static Appearance unpack(long)`. All fields are enums, never raw ints, so an out-of-range index is impossible past the codec boundary. |
| `AppearanceCodec` | Bit offsets/widths as `public static final int` pairs; `pack`, `unpack`, `schemaOf`, `migrate`, `fromSeed(long, Profession, AgeStage)`, `inherit(Appearance, Appearance, long)`, `withAgeStage(long, AgeStage)`. Pure, no MC types except `Profession`. |
| `SkinTone`, `HairStyle`, `HairColour`, `EyeColour`, `EyeShape`, `BrowShape`, `FaceShape`, `FacialHair`, `FaceMark`, `Build`, `HeightClass`, `Dye`, `Accessory`, `Headgear`, `AgeStage`, `Sex` | Enums. Each has `key()` (the filename fragment) and, for palette enums, `ramp()` returning the five-stop ARGB ramp that mirrors `texlib.PALETTES`. |

**Client** (`com.hearthstead.client.render`):

| Class | Responsibility |
|---|---|
| `SettlerLayerSources` | Loads layer PNGs on demand via `Minecraft.getInstance().getResourceManager().getResource(loc)` → `NativeImage.read(resource.open())` (`NativeImage.java:100`). Holds them in a `Map<ResourceLocation, NativeImage>`. Registered through `RegisterClientReloadListenersEvent`; on reload it closes every `NativeImage` and flushes `SettlerSkinCache`. |
| `SettlerSkinCache` | The composer + LRU. `ResourceLocation get(SettlerEntity)`. Key = `appearanceCode ^ ((long) professionId << 56) ^ ((long) outfitTier << 52)`. Backed by `Long2ObjectLinkedOpenHashMap` (fastutil is on the classpath). On miss: enqueue, return `settler_unresolved.png`. |
| `SettlerSkinComposer` | The pure pixel work: `NativeImage compose(Appearance, Profession, int tier)`. Iterates the UV rect list, applies layers bottom-to-top, resolves ramp keys, applies the face-light map. |
| `SettlerFaceLight` | Builds, once at class-init from `SettlerUV`, a `float[128*128]` giving each texel its `FACE_LIGHT` factor (1.0 outside any box). |
| `layer.SettlerArmourLayer` | `extends RenderLayer<SettlerEntity, SettlerModel>` — phase B1, see §6.3. |

**Client, model** (`com.hearthstead.client.model`):

| Class | Responsibility |
|---|---|
| `SettlerUV` | **New.** The single UV table, as `public static final int[] HEAD = {0,0,8,8,8};` etc. `SettlerModel.createBodyLayer()` must consume it instead of literals, `SettlerFaceLight` consumes it, and `tools/settler_uv.py` mirrors it under validator enforcement (§8 check 1). This retires the "must mirror by hand" comment that currently sits at the top of both files. |

### 3.3 Composition mechanics — the parts that bite

**Pixel format.** `NativeImage.getPixelRGBA` / `setPixelRGBA`
(`NativeImage.java:209, 223`) read and write a raw little-endian int, i.e.
**ABGR**, not ARGB. Use `net.minecraft.util.FastColor.ABGR32` (verified
present, with `alpha/red/green/blue/color`). Getting this backwards produces
blue-skinned settlers and is the single most likely first bug.

**Blending.** Every generated layer uses **binary alpha (0 or 255 only)** —
enforced by validator check 3. So composition is `if (ABGR32.alpha(src) != 0)
dst = src;`, which is exact and faster than `NativeImage.blendPixel`. It also
keeps the composed sheet binary-alpha, which is what
`RenderType.entityCutoutNoCull` wants — `HierarchicalModel`'s default render
type (`HierarchicalModel.java:22-24`), already what the settler uses today.
Partial alpha in a cutout render type produces hard, ugly clipping; do not
introduce it.

**Thread discipline.** `new DynamicTexture(NativeImage)` calls
`TextureUtil.prepareImage` and needs the render thread (it defers via
`RenderSystem.recordRenderCall` otherwise). Compose *and* upload on the
render thread, throttled to **at most 2 compositions per frame**; anything
beyond that waits and renders `settler_unresolved.png` for a frame or two.
At < 0.1 ms per compose this throttle will essentially never engage, but it
converts a pathological case (a 30-settler chunk loading at once) from a
120 ms hitch into nothing. The throttle counter is exported for QA (§8).

**Texture registration.**

```java
ResourceLocation id = Hearthstead.id("dynamic/settler/" + Long.toHexString(key));
DynamicTexture tex = new DynamicTexture(composed);
tex.setFilter(false, false);                       // AbstractTexture.java:20 — hard pixels
Minecraft.getInstance().getTextureManager().register(id, tex);   // TextureManager.java:62
```

Eviction calls `textureManager.release(id)` (`TextureManager.java:158`),
which closes the GL handle. Never release a texture the renderer might still
be holding this frame — evict at the *start* of a frame, not mid-render.

**Lifetime.** The cache is flushed on resource reload, on world unload, and
on disconnect. It is *not* flushed when a settler dies — another settler may
share the code, and the LRU handles it.

---

## 4. Texture atlas / UV plan

### 4.1 Sheet layout

`LayerDefinition.create(mesh, 128, 64)` becomes
`LayerDefinition.create(mesh, 128, 128)`.

Top half: **unchanged offsets**, except that `hat_brim` vacates `(64,44)`.

| Box | `texOffs` | w×h×d | Occupies | Texels |
|---|---|---|---|---|
| `head` | (0,0) | 8×8×8 | x 0–32, y 0–16 | 384 |
| `hood` | (32,0) | 8×8×8 | x 32–64, y 0–16 | 384 |
| `torso` | (64,0) | 10×12×5 | x 64–94, y 0–17 | 460 |
| `backpack` | (96,0) | 6×7×3 | x 96–114, y 0–10 | 162 |
| `belt` | (96,20) | 10×2×5 | x 96–126, y 20–27 | 160 |
| `right_arm` | (0,32) | 4×12×4 | x 0–16, y 32–48 | 224 |
| `left_arm` | (16,32) | 4×12×4 | x 16–32, y 32–48 | 224 |
| `right_leg` | (32,32) | 4×12×4 | x 32–48, y 32–48 | 224 |
| `left_leg` | (48,32) | 4×12×4 | x 48–64, y 32–48 | 224 |
| `cloak` | (64,32) | 11×4×6 | x 64–98, y 32–42 | 268 |
| **`hair`** *(new)* | **(0,64)** | 8×8×8 | x 0–32, y 64–80 | 384 |
| **`hat_crown`** *(new)* | **(32,64)** | 10×5×10 | x 32–72, y 64–79 | 400 |
| **`hat_brim`** *(moved, resized)* | **(0,80)** | 16×1×16 | x 0–64, y 80–97 | 576 |
| free | — | — | x 64–128 y 44–64; x 72–128 y 64–128 | — |

**Total painted texels: 4 074** of 16 384. The reserved block
(x 64–128, y 64–128, plus x 64–112 y 44–60) is earmarked for the armour
layer's helm/chest/greave boxes in phase B1 — those live on a *separate*
model and sheet, so this space is spare capacity, not a commitment.

Face rectangles come from `texlib.box_faces(u,v,w,h,d)`, unchanged:
`top (u+d, v, w, d)`, `bottom (u+d+w, v, w, d)`, `right (u, v+d, d, h)`,
`front (u+d, v+d, w, h)`, `left (u+d+w, v+d, d, h)`,
`back (u+d+w+d, v+d, w, h)`.

The face rect for eyes/brows/nose/mouth is therefore **head-front =
(8, 8, 8, 8)**, and every layer other than `skin/`, `face/` must be fully
transparent inside it (validator check 5).

`left_arm` and `left_leg` use `.mirror()` in `createBodyLayer`, so their UV
faces read reversed at render time. The generator already paints them as
independent regions and must continue to; the composer never mirrors.

### 4.2 Layer → region ownership

| Layer group | May write to | Must be transparent everywhere else |
|---|---|---|
| `skin/base_*` | every body box (head, torso, arms, legs) — **fully opaque** | hood, hair, hat_crown, hat_brim, belt, backpack, cloak |
| `face/base_*`, `face/eyes_*`, `face/brow_*`, `face/mark_*` | head-front `(8,8,8,8)` and head-side rows 5–7 (jawline marks only) | everything else |
| `hair/*` | `hair` box only | everything else |
| `beard/*` | `hair` box only (front rows 5–7, side rows 5–6) | everything else |
| `outfit/*` | torso, arms, legs, belt, backpack, cloak | head, hair, hood, hat_* |
| `accessory/*` | torso, arms, belt, cloak | head, hair, hood, hat_* |
| `headgear/*` | hood **or** hat_crown + hat_brim | everything else |

Composite order, bottom to top (fixed, for determinism — most groups occupy
disjoint regions so order rarely matters, but it is pinned anyway):

```
skin → face.base → face.eyes → face.brow → face.mark
     → outfit → accessory
     → hair → beard
     → headgear
```

### 4.3 Transparency

- Binary alpha only, everywhere, always. Validator-enforced.
- Every texel of every **body box** rect must be opaque after the `skin`
  layer. A transparent texel there renders as a hole straight through the
  settler under `entityCutoutNoCull`. Validator check 4.
- Boxes that are *optionally* invisible (`hood`, `hat_crown`, `hat_brim`)
  are hidden via `ModelPart.visible` in `setupAnim`, not via transparency —
  transparent quads still cost vertices.
- The `hair` box is **always visible**, even for a bald, beardless settler.
  Six fully transparent quads are cheaper than mutating model state per
  entity, and it keeps `setupAnim` branch-free on that axis.

### 4.4 Hair on the head box, and the straw-hat clipping problem

This is the geometry that A1d exists to get right.

**Deformation ladder** (a `CubeDeformation` inflates a box uniformly; two
boxes at the same deformation z-fight, so every value here is distinct):

| Box | Base geometry | Deformation | Effective half-extent (x/z) |
|---|---|---|---|
| `head` | `addBox(-4,-8,-4, 8,8,8)` | 0 | 4.00 |
| `hair` | same box, own UV | **0.25** | 4.25 |
| `hood` | same box, own UV | 0.60 (unchanged) | 4.60 |
| `hat_crown` | `addBox(-5,-10,-5, 10,5,10)` | 0 | 5.00 |
| `hat_brim` | `addBox(-8,-5,-8, 16,1,16)` | 0 | 8.00 |

**Hair fits the head box** by being a 0.25-inflated copy of it, carrying both
hair *and* beard in one box. Why one box: a beard authored 0.25 proud of the
skin reads as volume without new geometry, and the hair box's lower-front and
lower-side faces are exactly the jaw and chin. Hair colour, beard colour and
brow colour all resolve from the same `hairColour` ramp, so they stay
consistent for free.

**Hood** at 0.60 fully encloses hair at 0.25 — no interaction to manage. The
hood's front face is open (the current generator already paints only a rim),
so the fringe and beard read through the opening. That is the desired look
and needs no special case in the composer.

**Straw hat**, the case that must not clip:

- `hat_crown` occupies x/z ∈ [−5, 5], y ∈ [−10, −5].
- `hat_brim` occupies x/z ∈ [−8, 8], y ∈ [−5, −4], with the **central 10×10
  texels of its top and bottom faces fully transparent** (a ±5 hole),
  leaving a 3-texel straw ring.
- Hair occupies x/z ∈ [−4.25, 4.25].

Therefore:

1. The brim's horizontal slab is pierced by the hair box at |x| = 4.25 and
   |z| = 4.25 — **inside the ±5 hole**, where the brim has no texels at all.
   Opaque hair never intersects opaque brim. No clipping, by construction,
   and it is checkable arithmetically rather than by eye.
2. The crown's outer wall sits at exactly |x| = 5, i.e. flush with the hole
   edge. Perpendicular planes meeting at an edge — a clean joint, no
   coplanarity, no z-fighting.
3. The crown's bottom face (y = −5, 10×10) is exactly the brim's hole, so
   the brim's top face has no texels there either. Two coplanar faces that
   never both have a texel at the same (x,z). It is also permanently
   interior — author it transparent.
4. Hair below y = −5 (fringe, sideburns, nape) stays fully visible under the
   brim. This is the whole point: a farmer in a straw hat still reads as
   *that* farmer.

The old 12×1×12 brim at y −5..−4 is deleted. It was 4 texels narrower than
the head is wide once hair is added, which is precisely why the current
generator paints straw directly onto the head texture instead of using
geometry — a hack that modular appearance cannot keep.

`setupAnim` visibility, replacing the current profession switch in
`SettlerModel.java:113-115`:

```java
Headgear hg = entity.appearance().headgear(entity.getProfession());
hood.visible      = hg == Headgear.HOOD || hg == Headgear.HELM || hg == Headgear.HEADSCARF;
hatCrown.visible  = hg == Headgear.STRAW_HAT;
hatBrim.visible   = hg == Headgear.STRAW_HAT;
// hair is never hidden — hood/helm geometry encloses it
```

### 4.5 Build and height without extra geometry

`createBodyLayer()` is baked once per `ModelLayerLocation`, so per-entity
geometry cannot come from it. Two mechanisms instead:

- **`build`** → `ModelPart.xScale/yScale/zScale` (public fields,
  `ModelPart.java:27-29`), set in `setupAnim` after `resetPose()`:

  | Build | `torso.xScale` | `torso.zScale` | `arm.xScale/zScale` | `leg.xScale/zScale` |
  |---|---|---|---|---|
  | SLIGHT | 0.94 | 0.94 | 0.92 | 0.94 |
  | LEAN | 0.98 | 0.97 | 0.96 | 0.98 |
  | STURDY | 1.00 | 1.00 | 1.00 | 1.00 |
  | BROAD | 1.06 | 1.05 | 1.08 | 1.04 |

  ±6 % texel stretch on pixel art at entity scale is invisible; four extra
  baked model layers to avoid it would not be.

- **`heightClass`** → the `Attributes.SCALE` modifier from §2.2:
  0.94 / 0.98 / 1.02 / 1.06. This scales the *hitbox* too, which is correct
  — a short settler should be a short target.

---

## 5. Generator plan

### 5.1 A bug to fix first

`tools/gen_settler.py:464` seeds its RNG with Python's `hash()` of a string:

```python
rng = random.Random(hash(prof_key) & 0xFFFF | 1420)
```

`hash()` on `str` is salted per process unless `PYTHONHASHSEED` is set, so
**the current pipeline is not reproducible** — running `gen_settler.py` twice
in different processes produces different noise, in direct violation of the
"run the generator twice, get identical bytes" rule in the `hearthstead-art`
skill. Every generator seed must become `zlib.crc32(key.encode())` (or a
truncated `hashlib.sha256`). Validator check 9 (§8) locks this shut
permanently by running the generator twice into temp dirs and diffing.

### 5.2 Module structure

| File | Status | Contents |
|---|---|---|
| `tools/settler_uv.py` | **new** | `SHEET = (128,128)`; the `UV` dict from §4.1; `face_light_map()`; `face_of(x,y)`; `rects()` returning every `(box, face, x, y, w, h)`. The single source of UV truth on the Python side, cross-checked against `SettlerUV.java`. |
| `tools/texlib.py` | extend | new palette ramps (5 more skin tones, 4 more hair colours, 12 dyes); the ramp-key constants and `resolve_keys()`. No behaviour change to existing helpers. |
| `tools/gen_settler.py` | **rewritten in place** | emits the layer set. Keeps its filename so `qa/PROTOCOL.md` routing and the art skill stay valid. |
| `tools/preview_settler.py` | **rewritten in place** | composes and renders review sheets (§5.5). |

### 5.3 Ramp keys

Three key families, five ramp indices each. Key colours are chosen in a
magenta band that never appears in the medieval palette, so an unresolved key
is instantly obvious both in a diff and in-game:

```python
# tools/texlib.py
KEY_PRIMARY   = [(255, 0, 240 + i, 255) for i in range(5)]   # #FF00F0..#FF00F4
KEY_SECONDARY = [(255, 1, 240 + i, 255) for i in range(5)]   # #FF01F0..#FF01F4
KEY_TERTIARY  = [(255, 2, 240 + i, 255) for i in range(5)]   # #FF02F0..#FF02F4

def key(family: int, idx: int) -> tuple[int, int, int, int]: ...
```

Family binding per layer group — this is the table the Java composer and the
Python composer must agree on:

| Layer group | primary | secondary | tertiary |
|---|---|---|---|
| `skin/` | skin ramp | — | — |
| `face/base` | skin ramp | — | — |
| `face/eyes` | sclera (fixed off-white) | **eye colour** | — |
| `face/brow` | — | — | **hair ramp** |
| `face/mark` | skin ramp (darkened) | — | — |
| `hair/`, `beard/` | **hair ramp** | hair ramp, shifted −1 | skin ramp (scalp slivers) |
| `outfit/` | **tunic dye** | **trim dye** | skin ramp (rarely; bare skin normally uses transparency) |
| `accessory/` | tunic dye | trim dye | — |
| `headgear/` | literal colours (straw, iron) | trim dye (cords, liners) | — |

Resolution rule: a texel matching an exact key colour is replaced by
`shade(ramp[idx], FACE_LIGHT[face_of(x, y)])`; any other opaque texel is
copied verbatim. Mixing keyed and literal colours in one layer is allowed and
expected (a straw hat's straw is literal, its chin cord is `trimDye`).

Face lighting is applied **by the composer**, not baked into the layer PNG,
because the same keyed texel appears on faces with different light factors.
This reproduces exactly what `gen_settler.py`'s `lit()` does today.

### 5.4 `gen_settler.py` — module list and signatures

```python
LAYER_ROOT = ".../assets/hearthstead/textures/entity/settler"

def canvas() -> Image                      # 128x128 RGBA, fully transparent
def rng_for(name: str) -> random.Random    # zlib.crc32 seeded — deterministic

# One emitter per layer group. Each writes exactly one PNG and returns nothing.
def emit_skin(tone: str) -> None                 # skin/base_<tone>.png
def emit_face_base(shape: str) -> None           # face/base_<shape>.png
def emit_face_eyes(shape: str) -> None           # face/eyes_<shape>.png
def emit_face_brow(shape: str) -> None           # face/brow_<shape>.png
def emit_face_mark(mark: str) -> None            # face/mark_<mark>.png
def emit_hair(style: str) -> None                # hair/<style>.png
def emit_beard(style: str) -> None               # beard/<style>.png
def emit_outfit(profession: str, tier: int) -> None   # outfit/<prof>_t<tier>.png
def emit_accessory(name: str) -> None            # accessory/<name>.png
def emit_headgear(name: str) -> None             # headgear/<name>.png
def emit_unresolved() -> None                    # settler_unresolved.png (flat, fully resolved)

# Registries — these lists ARE the spec; validate_assets.py compares them
# against the Java enums in both directions.
SKIN_TONES, HAIR_STYLES, HAIR_COLOURS, EYE_COLOURS, EYE_SHAPES, BROW_SHAPES,
FACE_SHAPES, BEARDS, FACE_MARKS, DYES, ACCESSORIES, HEADGEAR, PROFESSIONS

def main() -> None    # iterates every registry; idempotent; prints a count
```

Painting helpers move from ad-hoc inline loops to region-addressed calls:

```python
def paint(img, box: str, face: str, painter) -> None
    """painter(x, y, w, h, put) over that box-face's rect from settler_uv.UV."""
```

The current `fill_faces` / `face_rect` helpers survive, retargeted at
`settler_uv.UV`.

Output paths (all under `textures/entity/settler/`):

```
skin/base_pale.png … base_deep.png                  (8)
face/base_broad.png … ; eyes_wide.png … ;
face/brow_heavy.png … ; mark_freckles.png …         (19)
hair/cropped.png … long_braid.png                   (15)
beard/stubble.png … forked.png                      (7)
accessory/neckerchief.png …                         (7)
headgear/hood.png, straw_hat.png, helm_iron.png …   (7)
outfit/farmer_t1.png … guard_t3.png                 (12)
settler_unresolved.png                              (1)
```

The four legacy sheets `settler_{none,farmer,lumberer,guard}.png` are deleted;
`SettlerRenderer`'s four `ResourceLocation` constants go with them, replaced by
`SettlerSkinCache.get(entity)` and the single `settler_unresolved.png`
fallback.

### 5.5 `preview_settler.py` — the review surface

The preview tool becomes the **Python mirror of the Java composer**, which
buys the single strongest headless QA lever available: a byte-for-byte
golden-image cross-check between the two implementations (§8 check 10).

```python
def compose(code: int, profession: str) -> Image
    """Mirror of SettlerSkinComposer: same layer order, same key families,
       same face-light table. Returns the 128x128 composed sheet."""

def turnaround(sheet: Image, code: int, profession: str) -> Image
    """Front | side | back strip, using the existing orthographic paste
       routine extended for the hair, hat_crown and hat_brim boxes."""

def grid(entries: list[tuple[int, str]], cols: int = 6,
         caption=lambda c: f"{c:012x}") -> Image

def sample_village(seed: int, n: int = 24) -> list[tuple[int, str]]
def sample_trait_ladder(base_code: int) -> list[tuple[int, str]]
def sample_family(seed: int) -> list[tuple[int, str]]
def sample_ages(seed: int) -> list[tuple[int, str]]

def main(argv) -> int    # --village --traits --family --ages --all (default)
```

Four review sheets, all written next to the tool and all inspected before any
appearance claim is made:

| Sheet | Question it answers |
|---|---|
| `preview_settlers.png` — 24 sampled settlers, mixed professions, turnarounds | Do 24 people read as 24 people? |
| `preview_traits.png` — one row per trait axis, everything else held fixed | Are all 8 skin tones / 16 hair styles actually distinguishable, at 1× as well as 6×? |
| `preview_family.png` — 2 parents + 4 sampled children | Do the children read as *these* parents' children? |
| `preview_ages.png` — one settler through 5 age stages | Does the growth curve read as growth rather than as shrinking? |

`sample_*` must use the same bit layout as `AppearanceCodec` (mirrored in
`settler_uv.py`'s sibling `settler_codec.py`, or inline in the preview tool —
either way the layout is validator-checked against the Java in check 8).

---

## 6. Profession outfits

### 6.1 The overlay contract

The outfit layer is *clothing*, not a costume that replaces the person. Three
rules make individuality survive it:

1. **The outfit never touches the head.** No texel in `outfit/*` or
   `accessory/*` may be opaque inside the head, hair, hood, `hat_crown` or
   `hat_brim` rects. Face, hair colour, beard and skin tone are therefore
   untouchable by profession. Validator check 5.
2. **Bare skin is transparency, not paint.** Where a sleeve ends, the outfit
   layer is simply transparent and the `skin/base_<tone>` layer beneath shows
   through. One `outfit/farmer_t2.png` therefore works for all 8 skin tones,
   with zero extra files and zero composer special-casing. Ramp-key family
   *tertiary* (skin) exists only for the rare case where the outfit needs a
   skin-*derived* colour that is not already underneath — e.g. a rolled cuff
   shading the forearm.
3. **The outfit is authored entirely in ramp keys.** Primary = `tunicDye`,
   secondary = `trimDye`. A single file yields 12 × 8 = 96 looks.

### 6.2 Keeping the profession readable

Individual variation must not destroy "that's a farmer" at 20 blocks. The
split:

- **Profession carries the silhouette**: headgear (straw hat / hood / helm),
  apron, bracers, backpack, gauntlets. These are geometry and shape — they do
  not vary per individual.
- **Profession carries the dye *family***: `Profession.dyePalette()` returns
  the allowed subset of the 12 dyes. Farmers get earth and linen; guards get
  the settlement's livery hues; lumberers get burgundy and forest. Variety
  lives inside the family.
- **The individual carries everything else**: exact dye within the family,
  trim, wear tier, accessory, and of course the person underneath.

Wear tier `t1/t2/t3` (patched / plain / fine) is a *third* axis, driven by
building tier and talent rank, not by identity. It shares the UV layout and
differs only in cloth detail. Changing tier invalidates that settler's cache
entry and nothing else — it is not part of the appearance code, it is part of
the cache key (§3.2).

### 6.3 Equipment layering (guard armour progression, phase B1)

Armour is the case where composition is the *wrong* tool and `RenderLayer` is
the right one, for three reasons: it changes at runtime on every equip; it is
geometrically separate (deformed boxes over the body, not coplanar repaints);
and 30 guards in the same tier share one texture, so the whole squad costs
**one** extra draw call rather than one each.

```java
public class SettlerArmourLayer extends RenderLayer<SettlerEntity, SettlerModel> {
    private final SettlerArmourModel model;   // its own baked ModelLayerLocation

    @Override
    public void render(PoseStack pose, MultiBufferSource buffers, int packedLight,
                       SettlerEntity entity, float limbSwing, float limbSwingAmount,
                       float partialTick, float ageInTicks, float netHeadYaw,
                       float headPitch) {
        ArmourTier tier = entity.armourTier();
        if (tier == ArmourTier.NONE) return;
        coloredCutoutModelCopyLayerRender(getParentModel(), model,
            tier.texture(), pose, buffers, packedLight, entity,
            limbSwing, limbSwingAmount, partialTick, ageInTicks,
            netHeadYaw, headPitch, -1);
    }
}
```

(`coloredCutoutModelCopyLayerRender` and `renderColoredCutoutModel` are the
1.21.1 helpers on `RenderLayer`; both take a trailing `int` ARGB colour,
`-1` for untinted.)

Its deformation ladder must not collide with §4.4:

| Armour piece | Base box | Deformation | Sits above |
|---|---|---|---|
| helm | head box | 0.90 | hood 0.60, hair 0.25 |
| cuirass | torso box | 0.75 | outfit (composed, deformation 0) |
| greaves | leg boxes | 0.50 | outfit |
| pauldrons | arm boxes | 0.60 | outfit |

A guard in a full helm still shows their beard through the helm's open front
face — the helm geometry encloses the hair box rather than replacing it.

---

## 7. Family resemblance and ageing

### 7.1 Inheritance

```java
public static Appearance inherit(Appearance mother, Appearance father, long seed)
```

Pure function of the two parent codes and the child's own stored
`AppearanceSeed`. No world state, so it is fully reproducible and
GameTest-assertable.

Four inheritance classes, chosen per trait:

| Class | Traits | Rule |
|---|---|---|
| **Blended ordinal** | `skinTone`, `heightClass` | `mid = (m + f) / 2` rounded; then jitter drawn from `{−1, 0, 0, 0, +1}` (60 % exact midpoint) and clamped to range. These ramps are *ordered*, so a midpoint is meaningful. |
| **Mendelian categorical** | `hairColour`, `eyeColour` | p = 0.45 copy mother, p = 0.45 copy father, p = 0.10 draw from `RECESSIVE[parentPair]` — a fixed table mapping each colour to one *lighter neighbour*. Two dark-haired parents can produce a lighter child; they can never produce an unrelated colour. |
| **Trait mosaic** | `faceShape`, `eyeShape`, `browShape`, `build`, `sex`-independent shape traits | Each trait independently copies one parent at 50/50. This mosaic — mother's brow, father's jaw — is what actually makes a child read as related to *both*, and it is the single highest-value rule here. |
| **Not inherited** | `hairStyle`, `facialHair`, `accessory`, `tunicDye`, `trimDye`, `headgear` | Rolled fresh from the child's seed. Style is culture, not genetics. Deliberate: siblings who share a face but not a haircut read as siblings; siblings who share both read as a rendering bug. |

Special cases:

- `faceMark`: freckles and moles inherit with p = 0.5 from either parent.
  **Scars never inherit** — they are earned in the Saga and must stay
  meaningful.
- `sex`: 50/50, independent of parents.
- `facialHair` is forced to `NONE` while `ageStage < YOUTH` or `sex == FEM`;
  the stored value is still rolled and kept, so it appears on schedule at
  YOUTH rather than being lost.

Lineage is stored as `MotherId` / `FatherId` on the child (§2.1) and as a
`ChildIds` list on each parent, needed by the family system regardless. The
parents' resolved `Appearance` codes are also copied into the
`Settlement.SettlerRecord` so a dead parent's face can still be drawn on a
memorial stone or in the Saga chronicle.

### 7.2 Ageing

Stages advance on `AgeTicks`, 5–8 in-game days per stage (DESIGN R21). The
server ticks it in `SettlerEntity.aiStep`'s existing `tickCount % 20 == 0`
block, alongside `tickNeeds()`.

| Stage | id | SCALE | Model (`setupAnim`) | Texture |
|---|---|---|---|---|
| INFANT | 0 | 0.45 | `head` scale 1.35; limbs `yScale` 0.60; `torso` 0.85 | `face/*_infant` variants (larger eyes, minimal brows); hair limited to 3 wisp styles; no beard; `outfit/child_t1` |
| CHILD | 1 | 0.60 | `head` 1.25; limbs `yScale` 0.78 | child hair subset; `outfit/child_t1` |
| YOUTH | 2 | 0.82 | `head` 1.10 | full adult face set; beard forced NONE; profession outfit at tier 1 (apprentice) |
| ADULT | 3 | 1.00 | — | full set |
| ELDER | 4 | 0.97 | `torso.xRot += 0.06` baked into the idle pose (a stoop) | composer substitutes the **grey** hair ramp; `mark_weathered` forced on |

The elder rule is deliberate: `hairColour` in the stored code is **not**
rewritten. The *composer* substitutes the grey ramp when
`ageStage == ELDER`. Identity stays stable, greying stays tunable, and a
Blessing that restores youth is a one-line change instead of a data
migration.

Stage transition, server-side:

```java
if (AgeStage.forTicks(ageTicks) != appearance().ageStage()) {
    setAppearance(AppearanceCodec.withAgeStage(appearanceCode(), next));
    refreshAppearanceScale();      // updates the SCALE modifier → refreshDimensions()
    level().broadcastEntityEvent(this, EV_GREW);   // diegetic: chime + happy particles
}
```

The synced long changes → every client's cache key changes → every client
re-composes automatically. No extra packet, no invalidation message. This is
the payoff for making the appearance code the single synced value.

---

## 8. QA

Everything below runs through `tools/hearthstead-qa`. Per `qa/PROTOCOL.md`,
routing puts this work in `assets animation build client visual behavior
gametest dedicated` — effectively `full`.

### 8.1 `tools/validate_assets.py` additions

Twelve new checks, in the existing `check(category, ok, message)` style:

| # | Check | Catches |
|---|---|---|
| 1 | **UV mirror.** Parse `SettlerUV.java`'s constants; compare to `tools/settler_uv.py::UV` and `SHEET`. Also assert `SettlerModel.createBodyLayer()` contains no numeric `texOffs(` literals (it must go through `SettlerUV`). | The oldest hazard in this codebase — the "must mirror by hand" comment at the top of `gen_settler.py` and `SettlerModel.java`. |
| 2 | **Sheet size.** Set `TEXTURE_CONFIG["entity_default_size"] = (128,128)`; every PNG under `textures/entity/settler/**` must match. | Half-migrated art. |
| 3 | **Binary alpha.** Every settler layer PNG has alpha ∈ {0, 255}. | Soft edges that `entityCutoutNoCull` will clip into jagged garbage. |
| 4 | **Base opacity.** `skin/base_*.png` is fully opaque across every body-box rect and fully transparent outside them. | See-through settlers. |
| 5 | **Region discipline.** Per the §4.2 table, in both directions (must-write and must-not-write). | An outfit that paints over someone's face; a hair layer bleeding onto the torso. |
| 6 | **Ramp-key legality.** Any texel in the magenta band must be an *exact* key colour; no off-band magenta anywhere. | Off-by-one keys silently rendering as literal magenta in-game. |
| 7 | **Enum ↔ file parity, both directions.** Every value of `SkinTone`/`HairStyle`/`FacialHair`/`FaceMark`/`Accessory`/`Headgear`/`Dye` has a PNG, and every PNG has an enum value. | Drift — the failure mode that actually happens. |
| 8 | **Bit-layout sanity.** Parse `AppearanceCodec`'s offset/width constants: Σ ≤ 64, no overlaps, reserved region declared, each width ≥ `ceil(log2(enum size))`, `schema` in bits 0–3. Compare against the Python mirror. | Silent trait truncation. |
| 9 | **Generator determinism.** Run `gen_settler.py` into two temp dirs in separate processes; every output must have an identical sha256. | The `hash()` salting bug of §5.1, permanently. |
| 10 | **Golden composite.** For ~8 fixed appearance codes, `preview_settler.compose()` must match checked-in `tools/golden/settler_<code>.png` byte for byte. | Any drift between the Python composer and the checked-in art. Combined with GameTest 8.2.6, it transitively pins the Java composer. |
| 11 | **Lang parity** for new trait keys (`hearthstead.trait.hair.long_braid`, …) — the existing en_us ↔ nb_no check covers them once they exist. | Missing Norwegian. |
| 12 | **Client purity.** No occurrence of `AppearanceCodec.fromSeed` or `AppearanceCodec.inherit` under `src/main/java/com/hearthstead/client/`. | Client-side appearance rolls → desync. |

### 8.2 GameTests (server-side, `HearthsteadGameTests`)

All cheap, all headless, all using the existing `buildArena` / `makeSettlement`
/ `boundSettler` helpers.

1. `appearanceAssignedOnSpawn` — a settler from `SettlementManager.spawnSettler`
   has `appearanceCode() != 0`, `schemaOf(code) == SCHEMA`, reserved bits are
   zero, and `Appearance.unpack` succeeds with every enum index in range.
2. `appearanceSurvivesNbtRoundTrip` — mirrors the existing settler NBT test:
   write to `CompoundTag`, read into a second settler, assert `Appearance` and
   `AppearanceSeed` are equal and `Appearance.unpack` is field-for-field equal.
3. `appearanceStableAcrossReload` — extend the existing SavedData persistence
   test: discard the entity, re-add from NBT, assert the code is unchanged.
   This is the "looks the same after reload" acceptance criterion.
4. `appearanceDeterministicFromSeed` — `AppearanceCodec.fromSeed(0xC0FFEEL,
   Profession.FARMER, AgeStage.ADULT)` equals a hard-coded expected `long`.
   **This is the regression lock:** any change to derivation rules fails this
   test and must be recorded as a specification correction in the quality
   ledger (INV-10), never quietly re-baselined.
5. `appearanceUniqueInVillage` — found a settlement, spawn 30 settlers, assert
   all 30 codes distinct and that no `(skinTone, hairStyle, hairColour,
   facialHair)` tuple repeats more than twice.
6. `composerLayerPlanMatchesGolden` — server-side, no GL: call the *layer
   selection* half of the composer (which layer paths, in which order, with
   which ramps, for a given code) and compare against a checked-in expected
   list. Pins the Java side of check 10 without needing a GL context.
7. `childInheritsFromBothParents` — build two parents with hand-set codes;
   assert `skinTone ∈ [min−1, max+1]`; `hairColour ∈ {m, f} ∪ recessive(m,f)`;
   each of `faceShape`/`eyeShape`/`browShape`/`build` equals one parent's;
   `hairStyle` is *not* constrained to a parent's; scars do not inherit; and
   same seed → identical child.
8. `ageStageAdvancesAndRescales` — set `AgeTicks` just below a boundary, tick,
   assert the stage advanced, `appearanceCode()` changed, `getScale()` changed
   and `getBbHeight()` changed (proving the SCALE modifier is actually wired,
   not just stored).
9. `appearanceNotRolledOnClient` — covered statically by validator check 12
   plus the existing `dedicated` suite's no-client-classloading assertion,
   with `SettlerSkinCache` / `SettlerSkinComposer` / `SettlerLayerSources`
   added to the client-only class list.

### 8.3 Visual verification (mandatory — a claim without an inspected screenshot is BLOCKED)

Preview sheets, regenerated and *looked at* before any completion claim:

- `preview_settlers.png` — 24 sampled settlers. Do 24 people read as 24?
- `preview_traits.png` — trait ladders at 1× and 6×. Are 8 skin tones really
  8 tones, or 4 tones and 4 near-duplicates? This sheet is where the trait
  *value ranges* in §1.1 get tuned or trimmed.
- `preview_family.png` — parents + children. Do they read as a family?
- `preview_ages.png` — 5 stages. Does it read as growing up?

In-engine, under Xvfb via `tools/hearthstead-qa client` / `visual`:

- Six settlers of mixed profession at 6 blocks and at 20 blocks — the second
  distance is the real test of §6.2's readability split.
- **The clipping case, explicitly**: a straw-hatted farmer with the longest
  hair style, orbited at 3 distances. Any shimmer at the crown/brim seam, or
  any hair poking through the brim, is a FAIL, not a nit.
- A hooded settler with long hair and a full beard — fringe and beard must
  read through the hood opening.
- A helmed guard — beard visible through the open front, no hair poking
  through the helm shell.
- A night shot — composed `DynamicTexture`s must respond to the lightmap
  exactly like a normal entity texture (catches a wrong render type or a
  `setFilter` mistake).
- A child and an elder beside an adult, same frame.

Performance, added to `client`/`visual`:

- `SettlerSkinCache` exports compose count, total compose ms, peak cached
  entries, and throttle engagements. Fail if any single frame composes more
  than 2 skins, or if composing 30 distinct settlers costs more than 30 ms in
  total, or if the LRU exceeds its 96-entry cap.
- The existing `performance` suite (30 settlers, dedicated server) is
  unaffected — composition is client-only — but it must still show no MSPT
  regression from the appearance tick and the SCALE modifier.

### 8.4 Ledger

Row 9 ("Modular settler visuals") moves FAIL → PASS only when §8.1 (12 checks),
§8.2 (9 GameTests) and §8.3 (all sheets and in-engine shots inspected) are
green under one `tools/hearthstead-qa full` run, with `green_streak ≥ 2`.

---

## 9. Explicitly rejected

Recorded so they are not re-proposed:

- **Pre-generated per-combination textures.** 51 TB for the *narrow* metric.
- **Naive per-trait `RenderLayer`s.** 6× draw calls, and whole-layer tinting
  cannot express dye without either doubling the layers again or exploding
  the file count.
- **Storing only the seed.** Adding a hairstyle would redraw every existing
  settler's face. Unacceptable in a mod with named characters, families and
  memorial stones.
- **A custom `EntityDataSerializer`** registered in
  `NeoForgeRegistries.ENTITY_DATA_SERIALIZERS`. Vanilla `LONG` exists
  (`EntityDataSerializers.java:43`); a custom serializer adds a synced
  registry entry and a `StreamCodec` for zero benefit.
- **`EntityDataSerializers.STRING` or `COMPOUND_TAG`** for appearance. Bigger
  packets, no gain, and invites unstructured growth.
- **`NativeImage.blendPixel` for compositing.** Binary alpha makes
  copy-if-opaque exact and faster, and blending would introduce partial alpha
  that `entityCutoutNoCull` then clips harshly.
- **Extra baked `ModelLayerLocation`s for body build.** `ModelPart.xScale` at
  ±6 % is free and invisible.
- **Any client-side appearance mutation** ("local flavour", randomised idle
  looks). Guaranteed desync; the appearance code is the only truth.
- **Restoring hair painted onto the head texture** (the current straw-hat
  hack). It is exactly what makes hats clip and what modular appearance
  exists to remove.

---

## 10. Implementation order

1. `SettlerUV` + `tools/settler_uv.py` + validator check 1. Retire the
   hand-mirroring comment. *(No visual change; pure safety net.)*
2. Fix the `hash()` seeding bug; add validator check 9.
3. Sheet 128×64 → 128×128; add `hair`, `hat_crown`, new `hat_brim`; delete the
   old brim. Regenerate the four legacy sheets on the new layout so the mod
   still renders. Screenshot to confirm no regression.
4. `Appearance` / `AppearanceCodec` / the trait enums + GameTests 8.2.1–8.2.5.
   Still no visual change — the code is stored and synced but unused.
5. `gen_settler.py` rewrite: ramp keys, layer emitters, `settler_unresolved`.
   `preview_settler.py` rewrite: `compose()`, the four sample sheets, goldens.
   Validator checks 2–8, 10.
6. `SettlerLayerSources` + `SettlerSkinComposer` + `SettlerSkinCache`;
   `SettlerRenderer.getTextureLocation` switches over. First in-engine
   screenshots. **This is the moment appearance goes live.**
7. `SettlerModel` build/age bone scales; `Attributes.SCALE` modifier;
   GameTest 8.2.8.
8. `inherit()` + lineage NBT + GameTest 8.2.7 + `preview_family.png`.
9. Ageing tick, stage transitions, `EV_GREW` feedback, `preview_ages.png`.
10. Full QA round; ledger row 9; second round for `green_streak ≥ 2`.

Steps 1–4 are non-visual and independently shippable; step 6 is the single
risky commit and should land alone.
