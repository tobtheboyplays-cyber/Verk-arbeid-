# PLAYTEST REVIEW — 2026-08-27 19:56

**Status:** TRIAGERT EIERFEEDBACK  
**Opptak:**

- `Base Profile 2026.08.27 - 19.56.06.29.mp4` — 31,28 s, 2560×1440, 60 FPS
- `Base Profile 2026.08.27 - 19.56.58.30.mp4` — 21,97 s, 2560×1440, 60 FPS

Opptakenes ekstreme farge-/chromaavvik er uttrykkelig avvist av Tobias som en
capture-feil og er ikke en modbug. Det skal ikke brukes arbeidstid på dette.

## Kort dom

Checkpointet viser at settler-, courier- og storage-systemer er synlige, men
spilleren mangler en tydelig styrings- og progresjonskontrakt. Flere aktive
animasjoner og lyder er dessuten under kvalitetskravet. Første release-scope
smalnes derfor til én komplett, bevist loop:

`House/Lodging -> Lumberer -> Farmer/food -> Warehouse/Courier -> Tavern ->
flere settlers -> Guard controls -> first raid`.

## Registrerte funn

### PT-2026-08-27-02 — Courier bærer foran kroppen

**Prioritet:** P1 i første vertikale slice  
**Bevis:** Andre opptak, omtrent 1–3 s. Courieren har sack/pack på ryggen, men
`CARRYING`-posen låser begge armene ut foran brystet som om en usynlig kasse
holdes der.

**Forventet:** Lasten ligger i den virkelige bag-inventoryen og visualiseres i
ryggsekken. Under transport brukes normal/weighted locomotion, eventuelt én
hånd på skulderstroppen. Stacken tas bare ut i en kort pickup-/handoff-sekvens.

**Ferdigbevis:** Side-, front- og bakfilm med tom, halvfull og full sekk; ingen
arm låses foran kroppen og sack-fill matcher fysisk inventory.

### PT-2026-08-27-03 — Pickup-animasjonen er avvist

**Prioritet:** P1 i første vertikale slice  
**Eierdom:** «plukk opp animasjonen er drit».

**Forventet:** Ny pickup skal ha anticipation, tydelig håndkontakt, faktisk
stack/prop, løft med vekt, stow i riktig inventory og recovery til locomotion.
Den må ikke teleportere itemet visuelt eller spille en lyd før kontakten.

**Ferdigbevis:** 50 repetisjoner fra front/side uten arm-through-torso, stuck
pose, dobbel lyd eller mismatch mellom fysisk item-transfer og kontaktframe.

### PT-2026-08-27-04 — Lumbererens carry-walk er avvist

**Prioritet:** P1 i første vertikale slice  
**Eierdom:** «gå animasjonen når lumber mannen bærer er dårlig».

**Forventet:** Logs går i Lumbererens bag/bundle eller bæres med en eksplisitt,
synlig sann prop. Vanlig produksjonsoutput skal normalt stowes i bag og leveres
til Lumber Camp storage. Gangen får lastavhengig steg, tyngdepunkt og armsving,
ikke en generisk stiv pose.

**Ferdigbevis:** Tom/lastet sammenligning, reell logg fra tre til workplace
storage og ingen renderer-only cargo.

### PT-2026-08-27-05 — Guards kan ikke styres

**Prioritet:** P1 før første raid  
**Eierdom:** Spilleren har null kontroll og trenger patrol-/tower-/stand-tool.

**Forventet første scope:**

- `AUTONOMOUS DEFENCE` — standard forsvar innen settlementet;
- `STAND POST` — hold én gyldig post med begrenset engagement-radius;
- `TOWER POST` — bind Guard/Archer til kompatibelt Watchtower/Barracks-punkt;
- `PATROL ROUTE` — 2–8 eksplisitte waypoints med loop/ping-pong;
- `RECALL` — trygg retur til post.

En fysisk Guard Scepter/Command Staff velger guard/squad og verdenspunkt.
Ordre er serverautoritative, persistente, permission-sjekket og kan aldri
force-loade chunks eller overstyre flee/downed/lifesaving behavior.

**Ferdigbevis:** Ordrene består save/restart, vises i verden/UI og to spillere
kan ikke skape divergerende rute. En guard ved ståpost, en archer i tårn og en
to-punkts patrulje må fungere gjennom første raid.

### PT-2026-08-27-06 — Veien til flere settlers er uklar

**Prioritet:** P1 onboarding  
**Eierdom:** Spilleren forstår fortsatt ikke hvordan flere settlers skaffes.

**Forventet:** Journey/Hearth viser en komplett checklist med housing,
Tavern-tech, fysisk matbuffer, traveler-timer, recruit cost og handling. Ingen
wiki eller skjult regel er nødvendig.

### PT-2026-08-27-07 — UI er visuelt avvist

**Prioritet:** P1 presentasjon for første loop  
**Eierdom:** «UI ser stygg ut.»

**Forventet:** Første UI-pass begrenses til skjermer som trengs i den første
loopen: Hearth/Journey, Mayor Development/Emblems, settler profile/inventory,
workplace storage, requests, Tavern/recruitment og guard command. Alle bygges
som komplette previews før runtime-endring og godkjennes av Tobias i klient.

### PT-2026-08-27-08 — Lydpakken er avvist

**Prioritet:** P1 premium-presentasjon  
**Eierdom:** «lydene er skikkelig dårlig».

**Forventet:** Aktiv lyd i første loop får semantisk riktig kilde, variasjon,
kontakt-synk, konsistent loudness/distance og crowd-budget. Dokumenterte
stand-ins og interne transientforsinkelser må erstattes, ikke bare mikses om.

## Bindende scopeendring

Følgende er første prioritet og skal bli komplett før bred yrkespolish:

1. House/Lodging og synlig population capacity.
2. Tech/Major/Job Emblem for Lumberer og Farmer.
3. Settler inventory og fysisk utstyrsrequest.
4. Lumberer work zone, pickup, carry, workplace delivery og lyd.
5. Farmer field/food-loop, workplace delivery og requests.
6. Warehouse/Courier request list, storage routes og backpack transport.
7. Tavern/traveler/recruitment med eksakte blockers.
8. Guard stand/tower/patrol/recall og ærlig equipment/ammo.
9. Full Journey/tutorial som lærer alt over fram til og gjennom første raid.

Andre yrker beholdes kompatible, men får ikke lov til å trekke fokus fra dette
vertikale releasebeviset.

## Bevismateriale

Kontaktark og detaljframes ligger under:

`qa/reports/playtest/20260827T195600/motion/`

De er observasjonsbevis, ikke visuell godkjenning. Ny klientfilm med normal
capture, gameplaylyd og samme frosne fingerprint kreves etter fiks.
