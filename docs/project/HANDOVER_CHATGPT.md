# OVERLEVERING — til ChatGPT (eller en hvilken som helst ny assistent)

Skrevet 2026-08-26 ~19:25 av den avtroppende koordinatoren. Denne filen er
selvstendig: du trenger ikke chat-historikken, bare repoet.

---

## 1. Hva dette er

**Hearthstead** — en original «levende landsby»-mod for Minecraft, NeoForge
1.21.1, i `hearthstead-neoforge/`. Eieren (norsk, spiller selv og gir
tilbakemelding løpende) bygger den mot to ankermoder:

- **TekTopias kropp**: DIN egen arkitektur (ingen ferdighus/schematics),
  synlig liv, diegetisk styring.
- **MineColonies' hjerne**: ekte logistikk, tette borgerkort, progresjon.

`hearthstead/` er en FROSSET 1.20.1-prototype — utvikle aldri der.
Andre mapper (`extension/`, `server/`) er et urelatert prosjekt.

**Branch:** `claude/hearthstead-settlement-mod-vbdb9n` (draft PR #1).
Alt arbeid skjer her. Alt er committet og pushet per nå.

---

## 2. Les disse først, i denne rekkefølgen

1. `CLAUDE.md` — repo-lov. QA-reglene der er ABSOLUTTE.
2. `qa/QUICKSTART.md` — hele testarbeidsflyten på én side.
3. `docs/project/BYGGHERRENS_VILJE.md` — ALT eieren har sagt, ordrett.
   Dette er kravspesifikasjonen. Les den før du tar en eneste beslutning.
4. `docs/project/OVERHAUL_PROGRAM.md` — det bindende programmet du skal
   fullføre (demokrav D1–D17, arbeidsstrømmer, slices, fileierskap).
5. `.claude/WORK_STATE.md` — hvor arbeidet står akkurat nå.
6. `docs/project/ANKER_ANALYSE.md` — doktrinen som styrer prioritering.

---

## 3. Eierens stående ordrer (brytes aldri)

- **«bare premium er standaren»** — kvalitet foran hastighet, alltid.
- **«core gameplay først»** — loopen skal være SANN før den pyntes.
- **«ikke start på nytt»** — alt skjer i eksisterende kodebase. Ingen
  omskriving, ingen ny mod, ingen migrering av animasjonssystemet.
- **Han vil SE ting**, ikke lese påstander: film, skjermbilder, og jar-en
  i chatten (han dobbeltklikker `hearthstead-oppdater.bat` selv).
- **Nåværende leveransemodus (viktig!):** «jeg venter til hele overhaulen
  er good» — han vil IKKE ha delleveranser. Neste kontakt er når helheten
  står: D1–D17 avkrysset med bevis, `full` grønn to ganger, jar + film.
  Eneste unntak: de fire balansespørsmålene som krever HANS avgjørelse
  (sult-konsekvens, matflyt-krav, kull som brensel, øl-servering) — de
  legges fram som valg med kostnad, ikke som åpne spørsmål.

---

## 4. QA-loven (den viktigste tekniske regelen)

**Eneste godkjente testinngang er `tools/hearthstead-qa`.** Kjør ALDRI
`gradlew runGameTestServer/runServer/runClient` direkte.

| Når | Kommando | Tid |
|---|---|---|
| Etter hver endring | `tools/hearthstead-qa quick` | ~1-2 min |
| Før du går videre fra en feature | `tools/hearthstead-qa fast` | ~50 s |
| Ved integrasjon | `tools/hearthstead-qa gametest` | ~5-10 min |
| Før «ferdig» påstås | `tools/hearthstead-qa full` ×2 + `gate` | ~10 min hver |

Harde regler:
- **Aldri to suiter samtidig.** Aldri suite mens filer redigeres.
- **En vellykket bygging er ALDRI bevis** for noe som helst.
- **Svekk aldri dommeren**: ingen sletting/hopping/oppmykning av tester,
  ingen timeout-inflasjon uten diagnose, ingen redigering av rapporter.
- **Diagnostiser fra bevis** (`qa/reports/artifacts/`), aldri fra teori.
  Fasit fra i dag: loggbaserte diagnoser bommet tre ganger; ett
  skjermbilde løste alle tre.
- Maskinen har **15 GB RAM**: aldri suite + Minecraft-klient samtidig.
- **Pillow må være installert** for assets-validatoren
  (`pip install Pillow`) — reinstalleres i ny container.

---

## 5. Produktinvarianter (aldri brytes)

- **Plaketten er landmåleren.** En bygning finnes fordi spilleren hang en
  plakett og rommet rundt oppfylte kravene. Ingen plakett = ingen bygning.
  Ingen innsatt Build Plan = ingen plakett-UI.
- Plaketten er et ACCESS POINT, aldri en andre sannhetskilde.
- **Chest truth**: hver gjenstand er fysisk ekte; logistikken konserverer
  items. Aldri teleportering, aldri duplisering, aldri sluk.
- All verdensskanning er budsjettert. Ingen ubundet per-tick-arbeid.
- Settlere bygger ALDRI selv (de reparerer og oppgraderer).
- Hver oppgave har sitt EGET keyframe-klipp — aldri delte generiske løkker.
- **Aldri stille feil.** Hver blokkering skal kunne leses i verden, med
  tall og grunn. Dette er ankermodenes dødssynd og vårt viktigste forbud.
- **Aldri meny-først.** Verden først; menyer er oppslagsverk.
- **Aldri emeralds** i økonomien (eierordre, håndhevet av vokter-test).

---

## 6. Hva som er GJORT og GRØNT (denne økten)

Alt committet og pushet. `gametest` 278/278 PASS (bevis:
`qa/reports/artifacts/20260826T190321Z`). `quick`: build PASS,
assets 878/878 PASS, animation PASS.

1. **Romskanner-fikser** (`bbaa8aa`): barriere teller som himmel, aldri
   tak (GameTest-arenaens skall gjorde takhull usynlige); og en
   plakett-kandidat vinner kun med et rom som oppfyller planens krav
   (plakettens egen luftlomme vant tidligere over det ekte rommet under
   bakken — eierens filmede feil).
2. **Nybegynner-fikser** (`fbd3125`): `DEMO_README` hadde plakett-
   oppskriften SPEILVENDT (sa 5 jern + 1 kobber; koden krever 5 kobber +
   1 jern) — rettet. 7 nye recipe-unlock-advancements (ingen oppskrift
   dukket opp i oppskriftsboken før). Milepæler toaster og annonserer nå.
3. **TAVERN-GATE** (`c3ed4d6`): tavernaen er porten for nye settlers.
   Uten gyldig taverna fylles aldri gaugen; stripen viser blokkeringen
   FØR den påstår at noen er på vei; `bell.json` gjør bell craftbar så
   porten aldri soft-locker; vokter-test forbyr emeralds i alle build
   plans og alle Costs-linjer. Plan: `docs/project/PLAN_TAVERN_GATE.md`.
4. **CHOP-redesign** (`b1b4e9f`+): ekte anticipation (REST → COCK 0.40 →
   CONTACT 0.55 → follow-through), rendret som bevis i
   `hearthstead-neoforge/qa/reports/artifacts/anim-pro/`.
5. **Baselinen ryddet** (`75a0c91`): KF-001/004/005 var ALLEREDE lukket —
   feillisten hadde aldri tatt igjen virkeligheten. Yrkes-rosteren frosset
   på **25** (D8).
6. **To programdokumenter** skrevet og byggherre-dømt:
   `OVERHAUL_PROGRAM.md` og `PLAN_LOGISTICS_OVERHAUL.md`.

---

## 7. Hva som GJENSTÅR (din arbeidsliste)

Fem arbeidere ble stoppet midt i arbeidet. **Deres ufullførte arbeid er
IKKE i repoet** (bortsett fra animasjonen, som ble sikret). Start disse på
nytt fra programmet:

| Slice | Innhold | Eier disse filene |
|---|---|---|
| **B1** | Palett-revolusjonen: `make_ramp()` etter rampe-loven (V-steg 8-11, span ≥32, hue-drift), separer iron/stone/charcoal, material-primitiver, stone()-tiling, ramp-gates i validatoren | `tools/texlib.py`, `tools/validate_assets.py` |
| **C1** | UI-systemgrep: ~20 ikoner (silhuett-testet), `HsUi.iconRow/portrait/emptyState/titledWindow`, motion-tokens | `client/ui/HsUi.java`, `tools/ui/`, `tools/gen_ui.py` |
| **S1** | Lesbar logistikk-stans: `StopReason`-enum (ADDITIV!), courier-avlesning i verden, plakett-statuslampe | `CourierWorkGoal`, `SettlerEntity`, ny `logistics/`-pakke |
| **A2/A5** | Ærlighetsfikser: Brewery-linjen, «under bygging»-merking (D12), KF-025, kaptein-epiteter; + KF-015 «repelled» med levende raidere | `RaidDirector`, `RaidPressure`, `BuildPlanItem` (plan-tooltip), `PlaqueMenu` |
| **A3** | Balansemålinger + de fire eierpitchene som valg med kostnad | Fuel/Costs-konstanter, e2e-scenarier |

Deretter: **B2/B3** (skins, blokker — etter B1), **K1** (item synlig i
offline-render — låser opp riktig anim-verifikasjon), **ANIM-1..4**,
**C2–C5** (Hearth, HUD, skjermene, look-at), **S2–S4** (jobbvalg med
score, teater+kjerre, fraktbok+fraktkasse).

**Rekkefølge-lov fra programmet:** B1 må lande FØR C1 genererer farger
(ellers genereres «kjempe stygg» på nytt i pent layout). K1 før
anim-slicene. S1 før S2.

**SEAM-filer** (kun én eier om gangen — sekvenser dem selv):
`lang/en_us.json` + `lang/nb_no.json` (full nøkkelparitet håndheves!),
`HsUi.java`, `tokens.json`, `gen_ui.py`, `SettlerAnimations.java`,
`SettlementManager.java`, `validate_assets.py`.

---

## 8. Fallgruver som har kostet oss tid

- **Ukommittert arbeid dør.** Containeren har ødelagt arbeid to ganger på
  én dag. Commit ofte, med ærlig melding om det er halvferdig.
- **`full` sletter `qa/reports/BLOCKED`** når den starter — by design.
- Bruk **absolutte stier**; arbeidskatalogen driver.
- Kjør tunge ting i bakgrunnen; ikke la en suite blokkere deg.
- **KF-037** (åpen, ufarlig): gulvlaget i takhull-fixturen inneholder 16
  steinblokker ingen fixture skriver. Grønn i dag, ordensavhengig i
  morgen. Se `docs/project/KNOWN_FAILURES.md` for neste diagnosesteg.
- Eieren er norsk og skriver ofte midt i en tur — alt han nevner er
  arbeidsliste, også når det kommer som en bisetning.

---

## 9. Slik avslutter du jobben

Når alle slicene står:

1. `tools/hearthstead-qa full` ×2 på samme fingerprint (green_streak ≥ 2),
   deretter `tools/hearthstead-qa gate`.
2. Kryss av D1–D17 i `OVERHAUL_PROGRAM.md` med bevislenke per krav.
3. Lag showcase-film (`tools/hearthstead-qa live film`) — eieren VIL SE
   det, med lyd på slagene.
4. Bygg jar og send den i chatten.

Først da kontaktes eieren.
