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

## Testtilstanden — KF-021 er LØST
**All 202 required tests passed**, fire kjøringer på rad i FLAKE-2s tre.
Rotårsak: 22 fikstur-filer registrerte en `Building` uten å henge plakett,
og `BuildingManager` oppløste dem korrekt én per 20 tikk — testene slettet
sine egne bygninger. Ett felles sted nå (`GameTestFixtures`) som plasserer
plaketten OG hevder den er der ved oppsett. Netto -153 linjer.
Andre halvdel: `run/world` ble aldri vasket (217 MB tilbake til 23. august).
Kontrolleren vasker den nå før hver kjøring.

**Fortsatt åpent:** én rød i én av fire kjøringer — KF-027, åtte jernbarrer
ikke gjort rede for i kurér-konserveringstesten. Behandles som mulig brudd
på chest truth, ikke som flake. CONSERVE-1 jakter den i eget tre.

## Landet i natt (verifisert, ikke bare kompilert der det står)
- Kurér-klyngen lukket: rekkevidde måles til KISTA, ikke til bygningens boks
  (KF-023). Fire tester grønne.
- KF-021 løst (over).
- 14 yrkesmatchede idle-animasjoner, alle 21 yrker, katalogen §22 skrevet,
  `anim_check` PASS.
- MAYOR_FEAST og REPAIR koblet chest-true; reparasjonsrabatt = «noen arr gror
  gratis», deterministisk teller.
- Våpenhuset: 8 rustningsoppskrifter + ARMOURER-yrket, så kjeden lukker seg
  fra garveri/smeltehytte til vaktens kropp.
- QA-dommeren strammet: full rosterlagring (var `head -5`), kontrolleren inn i
  fingeravtrykket, driftvakt mellom de to fingeravtrykks-implementasjonene
  (testet ved å ødelegges med vilje).
- BALANCE_AUDIT.md: 367 linjer regnestykker, ni funn.

## Åpne tråder
- KF-027 (åtte barrer) — CONSERVE-1.
- FLAKE-2: `full` x2 + statisk vakt mot plakettløse fiksturer.
- Film: side 5 og 6 bygget, venter på at klienten slipper skjerm :99.
- KF-026: ANIMATION_CATALOGUE.md avgjør en dom men er ikke i fingeravtrykket.
- Balansefunn 2 (forskning som ikke gjør noe) — utsatt til suiten er stabil.
- MILITARY-OUT-ruta er fortsatt ubygget.

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

## Regler som ikke bøyes
- All testkjøring gjennom `tools/hearthstead-qa` (rot, ikke moddmappa).
- `playtest` krever at eieren spørres. `full`/`gametest`/`quick` gjør ikke.
- Aldri svekke dommeren: ingen slettede/hoppede/løsnede tester, ingen
  timeout-inflasjon uten diagnose, ingen svelgede unntak, ingen redigerte
  rapporter.
- En vellykket bygging er aldri et bevis. Bare LOCKED betyr ferdig.
