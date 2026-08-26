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

## Testtilstanden — dette er det som teller
**14 røde av 196** (kjøring 20260826T041906Z, commit a6638eb, ren worktree
på pushet HEAD). **Ingen av dem er kurér-tester.** Hele kurér-klyngen er
lukket: rekkevidde ble målt mot bygningens boks i stedet for mot kista, så
en kurér seks blokker unna og utenfor veggen telte som framme.

De 14 som står igjen har én form: **en ansatt arbeider som ikke arbeider.**
Smelter, koker, murer, garver, snekker, baker, gruvearbeider (x2), bonde,
skriver, forskningsfullføring, kull-kaldstart, jern-hovedbok, reparasjon.
Ingen kurér, ingen raid, ingen plakett. FLAKE-2 eier dette.

**Tallet er fortsatt ikke en sammenligning.** Samme commit har gitt 4, 10,
14 og 20 røde i natt i ulike trær. Tre-kjørings-baselinen kommer først; alt
etter den måles mot den.

## Åpne arbeidere akkurat nå (04:45Z)
- FLAKE-2 — egen worktree (scratchpad/flake-tree). Eier KF-021. Har allerede
  levert nattens skarpeste måling: 12 røde, så 1 rød, rett etter hverandre i
  SAMME tre på SAMME commit uten at noe ble rørt. Det avliver
  «trærne måler forskjellig» og peker mot noe som BÆRES OVER mellom
  kjøringer, ikke et terningkast. Styrt mot batch-rekkefølge og gjenbrukt
  verden før attributter.
- IDLE-1 — SettlerAnimations, SettlerModel, animasjonsregionen i
  SettlerEntity. Yrkesmatchede idle-klipp (eierkrav).
- COSTS-2 — Costs, Mayor, RepairWorkGoal + testene deres og COSTS.md.
  Kobler MAYOR_FEAST og REPAIR (dom #3 krav 6). Beslutning tatt:
  reparasjonsrabatt = «noen arr gror gratis», deterministisk teller, ingen
  terning (suiten sliter allerede med ikke-determinisme).
- BALANCE-1 — LESER bare. Skriver én ny fil, docs/project/BALANCE_AUDIT.md.
  Regner ut om landsbyen brødfør seg selv, om kjedene lukker seg, om
  brenselet er solvent, om dagsverket binder, og om prisene betyr noe.

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

## Regler som ikke bøyes
- All testkjøring gjennom `tools/hearthstead-qa` (rot, ikke moddmappa).
- `playtest` krever at eieren spørres. `full`/`gametest`/`quick` gjør ikke.
- Aldri svekke dommeren: ingen slettede/hoppede/løsnede tester, ingen
  timeout-inflasjon uten diagnose, ingen svelgede unntak, ingen redigerte
  rapporter.
- En vellykket bygging er aldri et bevis. Bare LOCKED betyr ferdig.
