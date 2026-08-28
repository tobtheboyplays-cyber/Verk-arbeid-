# OVERHAUL-PROGRAM — Hearthstead totaloverhaling
*Byggherre-dømt og korrigert (dom 6). Denne teksten er bindende.*

Mandat: BYGGHERRENS_VILJE (26.08): total UI-overhaul, animasjonsoverhaul,
teksturer «100 bedre», core gameplay til playable demo, balansér det vi har.
«Ikke start på nytt» — alt skjer i eksisterende kodebase. Stående ordre:
**core gameplay først**. TAVERN-GATE-slicen er allerede i arbeid; settler-
arkets citizen-card-pass er halvveis (793e05b) — begge fullføres, dupliseres
ikke.

## 1. DEMOKRAVENE (endelige, målbare)

| # | Krav | Verifikasjon |
|---|---|---|
| D1 | Fresh survival: hearth + 3 founders < 10 min, null caving, null wiki | Playtest-harness + film; 0 quit-øyeblikk første 10 min |
| D2 | Første lumberer leverer stokk chest-true < 25 min, uten spillerarbeid etter ansettelse | qa playtest-scenario + gametest + film |
| D3 | «Neste settler»-sjekklisten aldri usann; tavern-blocker rendres FOR progressbaren | GameTest på ContainerData + screenshot |
| D4 | Tavern-gate: uten taverna fylles gaugen aldri; traveler vandrer synlig inn med chat+lyd | RecruitGameTests + film av ankomst |
| D5 | Bell craftbar i survival — porten kan aldri soft-locke (samme commit som porten) | Vokter-test gjennom ekte RecipeManager |
| D6 | Null emeralds i hele økonomien | Ratchet-test i `full` |
| D7 | Rekrutt betales chest-true, eksakt rabattert pris; fullt hus belastes aldri | CostsGameTests + RecruitGameTests |
| D8 | ALLE yrker (eksakt roster telles og fryses i A0 — ingen «+»; inkl. ARMOURER) gjør ekte arbeid via normal hire-og-goal-path | `hearthstead-qa gametest` ×2 samme fingerprint, 0 røde; rosterliste i beviset |
| D9 | Courier: alle 4 ruter ende-til-ende, konservering, aldri shuttle/stranding; vekttabell endrer målbart throughput | Courier-GameTests + live-watch |
| D10 | Matsolvens: landsby på 8 med bakery+mill, 3 spilldøgn, ingen sult < 20 | dedicated_e2e-probe, hunger logget per settler |
| D11 | Første raid natt 4–7, telegrafert; guards engasjerer; repair forbruker ekte materialer | Raid-GameTests + playtest av hel raid-natt + film |
| D12 | Ingen bygning i demo-path validerer og gjør ingenting uten å si det (Well/School/Market/Infirmary merkes «under bygging» eller kuttes) | UI-test + screenshot per bygning |
| D13 | Alle skjermer betjenbare på guiScale 2/3/4 — ingen klippet knapp | Preview-målinger + screenshots på alle tre skalaer |
| D14 | Hvert aktivt klipp på riktig kropp uten summing: CELEBRATING settes, sukkerrør får egne klipp, verktøy synlig i verifikasjonsrender; **hvert impaktklipp (CHOP, HAMMER_ANVIL, MELEE, FARM_TILL) har lyd PÅ slaget, hørbart i filmen** | anim-suite + film med lyd (statisk sjekk aksepteres ikke) |
| D15 | Save/reload midt i loopen (ventende traveler, raid-pressure, ansettelser) taper ingenting; 25+ settlers: modens EGEN tick-kostnad målt mot baseline-verden, p99-spike rapportert, avg MSPT < 45 som absolutt tak | Persistens-GameTests + `hearthstead-qa performance` (baseline + mod, samme seed) |
| D16 | Teksturer «100 bedre» er SYNLIG: før/etter-par per materialfamilie (stein, tre, metall, tekstil, settler-hud) + in-game film; byggherre-dom GODKJENT per par | Regenererte assets + før/etter-screenshots + film; dom per par før B-slicen lukkes |
| D17 | Hver konvertert skjerm dømmes side-om-side mot sin navngitte referanse i §4 (stjelelisten er AKSEPTANKER, ikke inspirasjon); «kjempe stygg»-dommen skal reverseres skjerm for skjerm | Side-om-side-screenshot + byggherre-dom per skjerm før C-slicen lukkes |

## 2. ARBEIDSSTRØMMER — rekkefølge og begrunnelse

**Strøm A — CORE/DEMO (starter først, eierens stående ordre).** Loopen må
være sann før den pyntes; TAVERN-GATE er alt i flight og D1–D12 henger på
den. **A0 (baseline) går aller først — ingen sliceslutt kan måles mot en
rød `full`.**

**Strøm B — TEKSTURFUNDAMENT (starter samtidig med A).** texlib-palettene
er pre-doktrine (24/32 ramper bryter hue-shift-loven) og UI-kitet genereres
fra de fire dårligste rampene. B1 må lande **før** strøm C genererer nye
FARGELAGTE sprites — ellers genereres «kjempe stygg» på nytt i pen layout.

**Strøm C — UI-OVERHAUL.** C1s preview-specs, layout og ikon-SILHUETTER
(form, ikke farge) starter PARALLELT med B1; all fargegenerering og
kit-eksport venter på B1. Systemgrep først (ikoner, portretter, motion,
emptyState), så skjermene i parallell. HUD er ny kode og største
enkeltleveranse.

**Strøm K — ANIMASJON (parallelt med alt; K1 straks).** Rotårsakene er
tooling (item usynlig i render), AI-orientering (CHOP) og prop-sync
(PICKUP) — ikke flere blinde keyframe-retunes. K1 lander først fordi den
låser opp riktig verifikasjon for alt annet. *(Omdøpt fra «strøm D» —
demokravene eier D-navnerommet alene.)*

## 3. SLICES MED DISJUNKT FILEIERSKAP

**SEAM-filer (én eier om gangen, sekvenseres av koordinator):**
`lang/en_us.json`+`lang/nb_no.json`, `HsUi.java`, `tokens.json`,
`gen_ui.py`, `SettlerAnimations.java`, `SettlementManager.java`,
`validate_assets.py`. Ingen slice rører en SEAM-fil den ikke eier i sin
periode.

**A0 — BASELINE-GRØNN `full`** *(aller først, før fan-out)*: KF-001/004/005
lukkes — eller bevises alt lukket av romskanner-arbeidet (bbaa8aa,
273/273-kjøringen 18:36) og KNOWN_FAILURES.md oppdateres deretter;
`full` ×2 grønn på navngitt fingerprint som blir programmets baseline;
yrkes-rosteren telles og fryses i D8. Eier: HearthsteadGameTests-helper
(KF-001), plaque-lang/blockstate (KF-004/005), KNOWN_FAILURES.md.
→ forutsetning for ALL sliceslutt-verifikasjon.

**A1 — TAVERN-GATE-fullføring** *(i arbeid — fullfør etter
PLAN_TAVERN_GATE, kravliste 1–9 bindende)*: gate i
`SettlementManager.tickRecruitment`, `bell.json` (3 gull+2 pinner+1 jern)
samme commit, `recruit_blocked.tavern`-nøkkel, blocker foran progressbar,
decay på seedet progress, RecruitGameTests (d)(e)(f)(h). Eier:
SettlementManager (SEAM), bell.json, RecruitGameTests, lang (SEAM-vindu).
→ D3/D4/D5.

**A2 — Ærlighetsfikser**: G2 (Brewery-Nether-linje i plan-tooltip/handbook),
G6/D12 («under bygging»-merking av Well/School/Market/Infirmary), G8
(KF-025 ulastet mayor), G9 (kapteins-epitet). Eier: plan-tooltips,
PlaqueMenu-tekstpath, RaidDirector-saga. Lang i eget SEAM-vindu etter A1.

**A3 — Balansemålinger + eierpitcher**: kjør D10/D11-probene, stem
raid-quiet-gain KUN mot målt datapunkt (aldri tersklene); hev bloom-jern
til ≥×1.5 — beslutningsnotatet OPPGIR metrikken (effort vs. tick,
BALANCE_AUDIT Q5) og dagens målte tall FØR endring; FuelGameTests-band
omskrives samtidig. Skriv beslutningsnotat til eieren: matflyt-gate
(`foodCache ≥ 2×pop`), charcoal=2 fuel, sult-konsekvens, ALE-servering i
taverna — **valg med kostnad, ikke gjort uten svar**. Eier:
Fuel/Costs-konstanter, e2e-scenarier.

**A4 — MILITARY-OUT + G12** (inn i logistikk-overhaul-slicen som alt er
bestilt): pil-rute smithy→watchtower/barracks, research-materiell via
courier. Eier: courier-rutekode, LogisticsGoals. → D9-utvidelse.

**A5 — KF-015-fiks** (repelled med raiders i live): mekanismeforslag til
koordinator, deretter fiks i RaidDirector. Akseptkriterium: GameTest som
beviser at «repelled» ALDRI kan inntreffe mens en raider står i
live-listen. Eier: RaidDirector/RaidPressure.

**B1 — Palett-revolusjonen**: `make_ramp()` i texlib.py, alle 32 ramper
regenerert etter loven (V-steg 8–11, span ≥32, hue-drift +10..15° varm /
−12..18° kald, chroma-peak stop 1–2, hud-unntak); iron/stone/charcoal
separeres; material-primitiver `metal/wood_grain/fold/worn_edge`;
stone()-tilefix (randomisert kursoffset, 16-periodisk wrap). ramp_audit +
3×3-tiletest inn i validate_assets.py som røde gates. Eier: texlib.py,
validate_assets.py (SEAM). Kalibrer mot Slynyrd/Wayline-referansene.
→ D16-bevis (før/etter-par) produseres løpende, ikke til slutt.

**B2 — Settler-skins** (etter B1): kontrastpass i gen_settler.py —
buksetoner, konsentrert vevstøy, hette/hud-hueseparasjon, én chroma-aksent
per variant; trade-telling-test på alle 26 outfits; turnarounds
regenereres og inspiseres. Eier: gen_settler.py, preview_settler.py. → D16.

**B3 — Blokker/plakett/items** (etter B1): P4-kantregler i
gen_blocks_items.py, hearth_stone-tilebug, gen_plaque brass-løft. Eier:
gen_blocks_items.py, gen_plaque.py. → D16.

**C1 — UI-systemgrep** (specs/silhuetter parallelt med B1; fargeeksport
etter B1; eier HsUi+tokens+gen_ui i sitt vindu): `icon/`-familie (~20
piktogrammer, silhuett-test i preview), `HsUi.iconRow/portrait/emptyState/
titledWindow`, motion-tokens + `HsAnim` (ease-out 100ms slide, count-up
200ms, puls 400ms). Preview-specs for alle nye tilstander FØR Java.
→ alt i C2–C5 avhenger.

**C2 — HearthScreen-konvertering**: riv `hearth_screen.png`, full
kit-konvertering med parchment-inset kun for ledger, emblem-hode,
ikon+verdi-stats, slot-posisjoner bevares. Eier: HearthScreen.java.
Synligste skjøt i modden — først etter C1. → D17-dom mot Manor
Lords-referansen.

**C3 — HUD-layer (ny)**: settler-strip à la RimWorld
(SettlerTextureCache-portretter, morale-tint, jobb-badge, krise-blink),
pinned alert-hjørne (severity-tone, klikk-for-fokus, aldri modal),
raid-target-bar (kun target/skadde). Eier: ny `client/hud/`-pakke +
GUI-layer-registrering. → fikser «monitoring bak menyer»-forbudet.
→ D17-dom mot RimWorld-referansen.

**C4 — Skjermene i parallell** (én arbeider per fil, etter C1):
PlaqueScreen (portretter i kort, fysiske tabs, requirement-rows med ekte
item-ikoner, designede tom-tilstander — «tamt»-dommen); ResearchScreen
(EMI-krav-rader have/need ✔/✘, prosjektikon, progressbar +
D13-guiScale-fiks); SettlerScreen (attributt/needs-ikoner, brass-ramme på
mayor — bygger på citizen-card-passet 793e05b, ikke om igjen);
HandbookScreen (Ponder-illustrasjonssone, kapittelikoner, D13-fiks);
StorageScreen (ikon-hode, tom-tilstand). Eier: hver sin skjermfil;
lang-strenger i sekvenserte SEAM-vinduer. → D17-dom per skjerm mot
referansen §4 navngir.

**C5 — Look-at-overlay** (etter C3): bar-hånd-blikk på
plakett/settler/hearth gir 2–3 linjers verdens-tooltip. Eier: hud-pakken.

**K1 — Item i offline-render** (straks, ren tooling): proxy-verktøy med
vanilla håndtransform i `bb_render.mjs`. Gate: intet kamp/arbeidsklipp
godkjennes uten item-render. Eier: tools/blockbench.

**ANIM-1 — PICKUP_STOW + K2**: ease-out inn i grep, grasp-hold, løft
1,5–2× tregere enn nedbøyning med root-lead, stow mot faktisk sack-del +
SCALE-puff; prop-sync (HandItems på grep-accent, bakke-item fjernes på
eksakt tick, kontraktlinje i anim_check.py). Eier: PICKUP_STOW-blokken i
SettlerAnimations (SEAM-vindu) + pickup-goal.

**ANIM-2 — CHOP + K3**: AI-orientering i LumbererWorkGoal (45–90° på
stammen), orienteringskontrakt i klippkommentar, K1-verifikasjon fra
stammens synsvinkel. **Ingen tredje kurve-retune før dette er sett.**
Lydcue på treffticket (D14). Eier: LumbererWorkGoal.

**ANIM-3 — Guard/kamp**: K1-render av sverdvinkel FØR arm-tall røres
(evt. K4 ItemInHandLayer-subklasse); GUARD_STANCE/PATROL får puste-SCALE +
én beat per loop; SHIELD_BLOCK tremble+pust; MELEE retimes 0.9–1.0s med
K5-skadetick-flytt i SettlerEntity + kontrakttabeller (koordinert commit);
lyd på treffticket (D14). Eier: kampklipp-blokkene +
SettlerEntity.doHurtTarget.

**ANIM-4 — Resten**: EAT (ujevne bitt, levende kropp), WALK-armer (4 keys,
drag, y/z-komponent), audit RUN_PANIC/WALK_LIMP/CREEP_NIGHT/WALK_LADEN mot
firepose-doktrinen, REST lett, hode-dobbeltstyring (SettlerModel:410)
løses, KF-036 sukkerrør-klipp + CELEBRATING-trigger (D14). Friske klipp
kun gjennom K1-regresjon — røres ikke.

## 4. STJELELISTEN → OPPGAVER (akseptanker for D17, ikke inspirasjon)

RimWorld colonist bar→C3-strip · MineColonies citizen-portretter→C1
`portrait()`+C4-Plaque · Banished-piktogrammer→C1 ikonsett · Manor Lords
panelspråk/emblem→C1 titledWindow+C2 · Create Goggles→C5 · Sophisticated
Backpacks-tabs→C4-Plaque · EMI-kjedevisning→C4-Research · Create
Ponder→C4-Handbook · Provi-tilbakeholdenhet→C3-raidbar · Fresh Animations
(levende idles)→ANIM-3/4-målestokk · TekTopia lumberjack-video→ANIM-2-fasit
· AnimationMentor løfte-beats→ANIM-1 · Slynyrd/PMC hue-shift→B1-kalibrering
· Jicklus/Farmer's Delight-tonefamilie→B2/B3-stilanker.

## 5. VERIFIKASJONSKADENS

0. **Baseline (A0, før alt annet)**: `full` ×2 grønn på navngitt
   fingerprint; KNOWN_FAILURES à jour. Ingen sliceslutt måles mot rød
   baseline.
1. **Kontinuerlig**: `hearthstead-qa quick` etter hver commit;
   ramp-audit/tiletest/determinisme-gates i validate_assets på hver
   asset-regenerering; preview-PNG + §11-kritikksjekkliste som gate på
   hver UI-endring FØR Java.
2. **Per integrasjon**: `hearthstead-qa gametest` for hver slice som rører
   entity/AI/menu; anim-kadensen §3-sjekkliste → export+K1-render →
   anim_preview --strict → qa animation.
3. **Sliceslutt**: `full` ×2 på samme fingerprint, 0 røde; SEAM-filens
   vindu lukkes først da.
4. **Til eieren**: film/screenshots per synlig leveranse (eieren VIL se) —
   UI-claims uten inspisert screenshot er BLOCKED; anim-klipp krever
   live-film MED LYD, ikke statisk sjekk; B-leveranser krever
   før/etter-par (D16); C-leveranser krever referanse-side-om-side (D17);
   **jar til eieren ved hver sliceslutt med synlig leveranse** — eieren
   spiller, han leser ikke rapporter.
5. **Demogate**: D1–D17 avkrysses med bevislenke per krav; A0-baselinen er
   forutsetningen, ikke en del av gaten.

## 6. GJØRES IKKE (frys-disiplin)

- **Ingen nye systemer uten eierordre**: ikke sult-straff, ikke
  matflyt-gate, ikke charcoal-buff, ikke ALE-servering — alle fire pitches
  som valg (A3) og venter på svar. Byggherre-agenten kan ikke godkjenne
  dem — kun den ekte eieren.
- **Ingen GeckoLib/JSON-migrering** — bryter anim_check-tooling og «ikke
  start på nytt».
- **Ingen omskriving** av gen_ui-arkitekturen, tokens-pipelinen,
  plakettkost, rekruttpris, gjeste-patience, effort, fed-multipliers
  (unntatt jern-gulvet, med målingen dokumentert i A3-notatet),
  raid-terskler eller vekttabellen — de er landet.
- **Ingen raid-tallendring uten D11-måling**; aldri terskler, kun
  quiet-gain.
- **Ingen retune av friske klipp** (craft-looper, courier-klipp,
  CELEBRATE) — kun K1-regresjonsrender.
- **Ingen parallellskriving i SEAM-filer**; ingen gradle/builds fra
  forskningsagenter; ingen «ferdig»-påstand uten kadensens bevis.
