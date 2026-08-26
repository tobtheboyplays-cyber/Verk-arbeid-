# WORK_STATE — 2026-08-26 natt (Opus-økt, koordinator)

## Mode
Koordinator + parallelle Sonnet-arbeidere, streng fileierskap. Eieren sover
("Kom så langt som mulig i natt"). BYGGHERREN (.claude/agents/byggherren.md)
taler med eierens stemme: dom #1-#3 i docs/project/BYGGHERRE_DOM_*.md.

## VIKTIG ved oppstart
Containeren er blitt tilbakestilt to ganger. Remote er sannheten:
  git fetch origin claude/hearthstead-settlement-mod-vbdb9n
  git reset --hard FETCH_HEAD
Sjekk `git branch --show-current` — hovedrepoet havnet én gang i detached
HEAD med en commit utenfor grenen (reddet). Bruk `git -C <sti>`, aldri `cd`
inn i worktrees i bakgrunnskommandoer. Bash-cwd overlever ikke mellom kall.

## Testtilstanden — dette er det som teller
**4 røde av 196** (kjøring etter 298fc11). Alle fire i kurér-klyngen:
  courierEntersASealedWarehouseAndDelivers
  courierOpensAClosedDoorToDeliver
  restockConservesItemsAcrossTheFullRoute
  restockDeliversWhenTheOnlyStandableCellIsOutsideTheCraftersBounds
Kveldens bane: 31 → 25 → 21 → 20 → 22 → 24 → 17 → 14 → 11 → 6 → **4**.

Lukkede rotårsaker i natt (alle i docs/project/KNOWN_FAILURES.md):
- KF-019: testene delte én verdensklokke og én verden → batch per testklasse.
  Modden var aldri ødelagt; suiten løy.
- KF-020: CrafterWorkGoal manglet requiresUpdateEveryTick() → ALLE oppskrifter
  gikk i halv fart i ekte spill. Ekte spillfeil, ikke testfeil.
- KF-021: raidere gytte 26-38 blokker ut på en tilfeldig bue og vandret inn i
  nabo-arenaene → én batch per raid-test. Sju tester ble grønne av det alene.
- KF-022: trunkInColumn antok at ingenting står over et tre. Trær under
  utheng, plattformer eller snø var usynlige for hele modden.
- KF-023 (ÅPEN, under arbeid): kuréren leverte én sekk og strandet resten for
  alltid — restock spurte «mangler benken minimum?», og én sekk (8) dekker
  hvert minimum (maks 3) på første tur. Bevis i loggen, ikke i lesning.

## Åpne arbeidere akkurat nå
- COURIER-FIX — eier CourierWorkGoal.java + kurér-testene. Alle fire røde er
  dens. Skal fjerne hver COURIER-DIAG før den er ferdig.
- IDLE-1 — eier SettlerAnimations.java, SettlerModel.java og animasjons-
  regionen i SettlerEntity.java. Yrkesmatchede idle-animasjoner (eierkrav).

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
