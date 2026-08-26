# WORK_STATE — 2026-08-26 natt (Opus-økt, koordinator)

## Mode
Koordinator + parallelle Sonnet-arbeidere, streng fileierskap. Eieren sover
("Kom så langt som mulig i natt"). BYGGHERREN (.claude/agents/byggherren.md)
taler med eierens stemme: dom #1-#3 i docs/project/BYGGHERRE_DOM_*.md.

## VIKTIG ved oppstart — containeren nullstilles
Containeren er nullstilt TRE ganger i natt. Remote er den eneste sannheten:
  git fetch origin claude/hearthstead-settlement-mod-vbdb9n
  git reset --hard FETCH_HEAD
Sjekk `git branch --show-current` — hovedrepoet havnet én gang i detached
HEAD med en commit utenfor grenen (reddet). Bruk `git -C <sti>`, aldri `cd`
inn i worktrees i bakgrunnskommandoer. Bash-cwd overlever ikke mellom kall.

**Dyrekjøpt regel (04:15Z):** nullstillingen slettet TO TIMER med ferdig
arbeiderarbeid — kurér-fiksen, idle-animasjonene, Costs-koblingen og hele
flake-jakten — fordi ingenting av det var committet. Arbeidere leverer nå i
biter, og koordinatoren committer ved HVER rapport. Ingenting ligger
ucommittet over tid. Worktrees under scratchpad/ overlever ikke; bare
remote gjør det.

## Testtilstanden (07:30Z, runde 1)
**213 tester, alle grønne unntatt to kjente** — `ahiredhunterhuntsbutnever
breaksthefloor` og `ahiredfisheractuallyfishes`, begge eid av TRADES-1.
KF-021 er løst (fiksturene oppløste sine egne bygninger); vakta mot
plakettløse fiksturer har alt fanget én ekte regresjon etter det.

## Åpne arbeidere (runde 1)
- SPILLER-1 — survival-gjennomspilling på jar fra fe889ed, med kamera.
  Har alt funnet KF-030: kamera-hjelperen drepte spilleren i survival.
- TRADES-1 — hvorfor jegeren og fiskeren nekter å starte (egne porter,
  ikke arbeidsløkkene). Eget tre.
- ECON-1 — regner om de fôrede rutene mot DAGSVERK etter at tikk-målingen
  viste seg å måle feil akse. Eget tre.
- RAID-1 — jaktmål for blodraid + vaktenes sivile-først-prioritering.
  Løsepenge-løgnen (KF-031) og epitetene er alt avvæpnet.
- Gjennomgang av nattas 6590-linjers diff: fem linser, tre skeptikere per
  funn, og et spørsmål til slutt om hva ingen av linsene så på.

## Landet i runde 1
- Tre nye yrker: gjeter, fisker, jeger — egne klipp, ekte tilbakeholdenhet
  (artsgulv, vann-minimum, flokk-gulv).
- Fire vegger revet: sykestua mistet Nether, mølla lager papir, markedets
  emerald byttet, tre døde varer fikk bruk.
- Forskningen ble ekte: kutter dagsverk, ikke bare et tikk-tak ingen når.
- Rustningskjeden lukket: oppskrifter + ARMOURER-yrket + ansettelse.
- Raiderne: to bygg, sju klipp, skumle teksturer, vold skopet til eget raid.
- Kurér-klyngen lukket: rekkevidde måles til kista, ikke bygningens boks.
- Dommeren strammet tre ganger: full roster, kontrolleren fingeravtrykket,
  statisk vakt mot plakettløse fiksturer.
- Weight.java skrevet (logistikkens grunnstein, ikke koblet ennå med vilje).

## Viktigste lærdom i runde 1
**Sømmene mellom parallelle arbeidere er der feilene bor.** ECON-1 målte
fôrede ruter i tikk mens RESEARCH-1 i parallell fikset nettopp det at tikk
ikke binder noe. Ingen av dem gjorde feil i sin egen fil. Dokumentet begge
leste målte feil akse. Derfor gjennomgangen med fem linser — og derfor
spørsmålet «hva så ingen på?» til slutt.

## Merk om oppgavelisten
Verktøyets task-liste nullstilles sammen med containeren og har gjort det
flere ganger. **Denne fila er sannheten**, ikke task-lista. Ikke bruk tid på
å synkronisere dem.

## Implementert og pushet i natt (IKKE «landet» — dom #3 krav 1)
Ferdig-merking krever kjørings-id og fingeravtrykk. Ingenting under er
suite-bevist ennå.
Brenselsøkonomi (Fuel.java, kull-kaldstart, bloom x1.67) · kurér-rute 5 (mat
til peis + brensel) · synlig rangrustning (SettlerArmorLayer + gen_armor.py)
· rustning KJØPT fra våpenhuset (ARMOURY-1) · bueskytteren med DEX-stige,
Power/Triple Shot og chest-true piler · Profession.martial() ·
reparasjonsdugnaden · raidere som bryter dører og stjeler (arr FØR
ødeleggelse) · forskningsbonusene koblet · 33 survival-oppskrifter med
ratchet · Costs.java med navngitte rabatter · håndbok: 6 kapitler + 2
advancements + 466 nøkler i paritet · yrkesnavn alltid på skiltet ·
Production.ready() behovsstyrt med WORKING_RESERVE · polermester: CHOP og
PICKUP_STOW ombygget, lavgarde-sverdholdning, nye ansikter/hår, fire antrekk.


## Balansefunn og rekkefølgebeslutning (05:30Z)
BALANCE-1 leverte docs/project/BALANCE_AUDIT.md — 367 linjer regnestykker,
ni funn, to rangert BROKEN.

1. **Vaktenes rustning har ingen produsent.** GuardRank tar sju rustnings-
   deler ut av ekte kister, men `Production.of(ARMOURY)` er TOM — bekreftet:
   ARMOURY finnes ikke i Production.java i det hele tatt. Landsbyen kan ikke
   bevæpne seg selv; bare spilleren kan legge rustning i en kiste for hånd.
   ARMOURY-2 fikser dette nå.
2. **Dagsverket binder, ikke tikkene — så fire av seks forskningsprosjekter
   gjør ingenting.** Håndverk koster 2 dagsverk per batch mot en kapasitet
   på 20 + STAMINA/5, altså 10-20 batcher per dag. Selv den treigeste
   oppskriften (300 tikk) tillater 30 batcher per dag på tid alene. De fire
   prosjektene som kutter tikk med 15% flytter et tak ingen når fra før.

**Beslutning: funn 2 lander IKKE nå.** Å endre dagsverk eller tikkost mens
FLAKE-2 stabiliserer dommeren ville flytte timingen i titalls tester
samtidig som vi prøver å finne ut hvilke som er ustabile. Rekkefølgen er:
suiten grønn og reproduserbar først, DERETTER balanseendringer, slik at
enhver bevegelse i tallene etterpå har én forklaring og ikke to.
Funn 1 lander nå fordi det legger til en manglende produsent uten å røre
noen eksisterende rate.

## Neste, i rekkefølge
1. COURIER-FIX lander → `tools/hearthstead-qa gametest` → forventet 0 av 196.
2. IDLE-1 lander → kompilering + gametest i samme kjøring der det går.
3. GATE-1: `tools/hearthstead-qa full` x2, green_streak >= 2, samme
   fingeravtrykk → DA først kan noe merkes som landet.
4. Film (eieren har bedt to ganger): SHOWCASE_PLAN-scenene 15-18 + «følg
   brødskiva» (åker→peis→lager→mølle→bakeri→lager→peis→munn) + byggherrens
   «følg pilen». Live-økta kjører på nattens jar.
5. REVIEW-ALL: gjennomgang av alle ~45 klipp/teksturer/skjermer med de tre
   v2-ferdighetene.
6. Byggherre-dom #3 åpne krav: krav 3 (visuelt bevis, reist to ganger),
   krav 10 (WORK_STATE oppdateres i samme commit som landinger), krav 11
   (rute 5 het to forskjellige ting i dokumentene).


## Eierens instrukser (06:50Z, våken igjen)
1. **Video når prosjektene rundes av** — filmes i det øyeblikk FLAKE-2s
   full-kjøring nr. 2 slipper skjerm :99: side 5+6 (yrkes-idle), landsby,
   scenene i SHOWCASE_PLAN. Raidernes egen film kommer når RAIDER-bølgen
   lander.
2. **Videre på core gameplay og en raid.**
3. **Fiender skal se unike ut og være skumle med syke animasjoner** —
   RAIDER-ANIM (modell/klipp/trigger + anim_check-utvidelse) og RAIDER-ART
   (gen_raider.py, teksturmatrise, renderer-valg) kjører nå. Kontrakten
   Variant {SKIRMISHER, BRUTE} + kaptein/saga-roller er committet (d2cb27c).
4. **Logistikk er koordinatorens personlige ansvar** — docs/project/LOGISTICS.md
   er kontrakten: vektklasser gjør plassering viktig, kurértimer/dag gjør
   kostnaden synlig, Fraktboka + plakett-anslag gjør den lærbar. KF-027 først.

## Eierens ordre 07:05Z: FULL SURVIVAL-GJENNOMSPILLING
Verbatim: «Kjør en full gameplay test. Spill igjennom alt. Få en lumberjack.
altså uten creative. så jobb deg opp prøv å research og jobb deg til de andre
bygningene. Dette skal jo tross alt være en survival opplevelse.» + «Test
hele loopen.» Deretter full patch-runde på alt som ikke funker/er ubalansert.
**Denne meldingen ER playtest-autorisasjonen** (stående regel krever spørring
først — eieren har nå beordret den).

Plan i rekkefølge:
1. SURVIVAL-AUDIT (kjører) — statisk: kan man i det hele tatt NÅ alt fra
   bare hender? Tier-tabell, WALL/GRIND/SMOOTH, hva live-testen skal knekke.
2. FLAKE-2s full-kjøring 2 slipper maskinen (green_streak=2 på db7fd1e).
3. SPILLER-1: ekte survival-gjennomspilling på ferskt bygd jar, filmer
   underveis (dekker også eierens videoønske), dokumenterer hvert
   friksjonspunkt. ALDRI creative for å komme forbi en vegg — veggen ER
   funnet da.
4. PATCH-RUNDE: fleet på funnene fra SURVIVAL-AUDIT + SPILLER-1 +
   BALANCE_AUDIT (nå inkludert de utsatte balansefunnene — forskning som
   ikke gjør noe, døde varer, mat-underskudd).
5. Ny full x2 + film til eieren.


## Patch-runde-backlog (fra SURVIVAL_AUDIT + BALANCE_AUDIT, 07:45Z)
Koordinatorens beslutninger, tas i patch-runden ETTER gjennomspillingen
(unntatt der spillingen ikke kan nå området likevel):

**VEGGER:**
- PASTURE/FISHERY/HUNTERS_LODGE: yrkene finnes ikke (22 trades, ingen
  gjeter/fisker/jeger). BYGGES — Ring-1-fullføring per PLAN_CIRCULATION
  (HERDER: ekte dyr i paddock, chest-true ull/egg/slakt; FISHER: fisk fra
  tilstøtende vann; HUNTER: tilsvarende). Venter på at RAIDER-ANIM slipper
  anim_check.py + ANIMATION_CATALOGUE.md (kollisjon ellers).
- INFIRMARY mister Nether-veggen (helbredelse er kjerneloop; brewing_stand →
  gryte/urtekrav). BREWERY beholder brewing_stand (flavor) MEN ale må få en
  forbruker (vertshus-servering/moral) — død vare i dag.
- MARKET: emerald (seed-avhengig) byttes til gull + varer.

**GRINDS:**
- To ambolter = 62 jern for hånd: vurder én delt/alternativt krav for
  armoury (smithing table?) etter spilltest-følelsen.
- LIBRARY 81 papir: en trade får papiroppskrift (mølla, av sukkerrør).
- Rekrutt-vs-mat-dalen (pop 5-8, dag 1-3): demp rekrutt-tempo eller øk
  start-spiskammer; spilltesten avgjør hvilken.

**FRA KF-027 (lukket):**
- RaiderEntity angriper ENHVER settler, ikke bare raidets eget mål —
  kryss-test-drap i suiten i dag, feil aggro mot NPC-nabolandsbyer i B2.
  Skop målvalget til raidets settlement (hurt-by-gjengjeldelse forblir
  universell). Venter på at RAIDER-ANIM slipper RaiderEntity.java.

**INERTE SYSTEMER (BALANCE_AUDIT):**
- 4 av 6 forskningsprosjekter kutter tikk ingen når: bytt bonusen fra
  tikk-kutt til DAGSVERK-kutt (effort er det som binder) — da blir de ekte
  uten nye systemer.
- Forsknings-ærendet leveres aldri av kurér (CourierWorkGoal:1597) — inn i
  MILITARY-OUT/rute-arbeidet.
- Døde varer: ALE (over), WOOL_BOLT/BANNER (vever→?), BARREL (fed-path gir
  null ekstra).

**DOKUMENT-RÅTE FUNNET:** COSTS.md sa forskning ukoblet — koden krget. Fikset.


## RUNDESTRUKTUREN (eierens ordre: «deretter ta fler runder»)
En RUNDE er: spill → funn → patch-bølge → suiten grønn → spill igjen.
Ikke ferdig før spilleren kommer gjennom hele loopen uten å bli stoppet.

- **Runde 1 (pågår):** SPILLER-1 på jar fra fe889ed. Har alt funnet en
  harness-VEGG som gjorde survival-spilling umulig (safe_regrab
  teleporterte til Y=300, som er gratis i creative og DØDELIG i survival —
  spilleren ble sparket to ganger og døde i fallet tredje gang). Fikset i
  live.sh: bare creative får teleporten; survival ser rett opp på stedet og
  hopper over klikket helt hvis det ikke er fri himmel over.
- **Mellom hver runde:** hele suiten grønn før neste spilling starter.
  Ellers måler runde N+1 en blanding av gammelt og nytt.
- **Hver runde bygger sitt eget jar** fra et fastlåst, byte-rent tre, slik
  at funnene tilhører en commit og ikke et bevegelig tre.
- **Rundene stopper ikke på grønn suite.** De stopper når en spiller kommer
  fra bar mark til raid uten å møte en VEGG, og friksjonslista er kort nok
  til at den øverste posten ikke er pinlig.


## Regler som ikke bøyes
- All testkjøring gjennom `tools/hearthstead-qa` (rot, ikke moddmappa).
- `playtest` krever at eieren spørres. `full`/`gametest`/`quick` gjør ikke.
- Aldri svekke dommeren: ingen slettede/hoppede/løsnede tester, ingen
  timeout-inflasjon uten diagnose, ingen svelgede unntak, ingen redigerte
  rapporter.
- En vellykket bygging er aldri et bevis. Bare LOCKED betyr ferdig.
