# OWNER DIRECTIVE 2026-08-27 — TECH TREE OG JOBB-EMBLEMS

**Status:** BINDENDE PRODUKTENDRING  
**Kilde:** Tobias under checkpoint-planleggingen 2026-08-27  
**Eierens ord:** «for å åpne bygninger så må vi ha et tech tree. Slik at
bygningene kommer i naturlig rekkefølge. Så må man handle emblems fra mayoren
for å gi andre folk jobb. Emblems er jobbene.»

Denne beslutningen overstyrer alle eldre tekster som avviser token-/emblem-
ansettelse eller sier at gratis Appoint/Hire i plaque-skjermen er den endelige
spillerflyten. Den opphever ikke chest-truth, ingen emerald-økonomi, manuelt
jobbvalg, serverautoritet, save-migrering eller «ingen stille feil».

## 1. Bindende betydning

1. Bygningstyper åpnes gjennom ett delt settlement-tech-tree i en naturlig
   progresjon.
2. En låst bygning kan ikke registreres som aktiv bare fordi spilleren skaffer
   et gammelt eller gitt Build Plan-item.
3. Tech-treeet viser forutsetninger, fysisk kostnad, hvilke bygninger som åpnes
   og hvilke jobb-emblems Mayoren deretter kan utstede.
4. Jobb-emblems handles/utstedes gjennom Mayoren mot fysiske varer. Emeralds
   brukes ikke som abstrakt valuta.
5. Spilleren velger fortsatt personen manuelt. Systemet skal aldri auto-ansette
   eller rangere fram et skjult «beste» valg.
6. Emblemet er den fysiske representasjonen av yrket. En vellykket utnevnelse
   binder emblemet til settleren; en arbeidsplass er en separat, eksplisitt
   relasjon.
7. En settler kan ha et yrke uten en gyldig post og skal da vise en konkret
   stoppårsak, for eksempel «Lumberer — mangler ledig Lumber Camp».
8. Tap av eller ugyldig arbeidsbygning fjerner ikke yrkesidentiteten. Spilleren
   kan tildele en ny kompatibel post eller avsette/omskolere settleren.
9. Ved avsettelse returneres emblemet fysisk gjennom en trygg leveringsstige.
   Ved omskolering brukes et nytt emblem i én atomisk transaksjon.
10. Feil mål, låst tech, manglende permission, fullt bygg, feil settlement,
    gammel revision eller annen avvist handling forbruker aldri emblemet.
11. Hver bygning har én kort, lokaliserbar beskrivelse fra en felles katalog.
    Den samme teksten vises på Build Plan og Development-node; Plaque og
    Handbook kan vise mer detaljer fra samme katalog, men får ikke egne
    motstridende kopier.
12. Tech-treeet starter som én felles læringsvei, splitter etter første raid i
    tre doktriner og åpner senere fem spesialiseringer. Valgene skal være
    strategiske og ha synlige fordeler, kostnader og ulemper.

## 2. Normal spillerflyt

1. Spilleren åpner development/tech-treeet ved Hearth eller Mayor.
2. Spilleren låser opp en node med fysiske ressurser og oppfylte
   forutsetninger.
3. Bygningens Build Plan blir tilgjengelig gjennom den vedtatte planflyten.
4. Den tilsvarende jobbtypen blir tilgjengelig i Mayorens emblem-katalog.
5. Spilleren handler emblemet mot de viste fysiske varene.
6. Spilleren holder emblemet og Shift-høyreklikker ønsket settler.
7. Dersom nøyaktig én kompatibel post er ledig, kan serveren fullføre direkte.
   Ved flere kompatible poster må spilleren velge eksplisitt; ingen skjult
   auto-assignment.
8. Serveren validerer tech, spiller, settlement, permission, hånd, item,
   settler, post, kapasitet og revision.
9. Først etter autoritativ `APPLIED` flyttes ett emblem fra hånden til settlerens
   yrkestilstand og arbeidsrelasjonen oppdateres.
10. UI, nameplate og inspection viser både yrke og eventuell blokkert/manglende
    arbeidsplass.

## 3. Naturlig progresjon: én stamme → tre doktriner → fem spesialiseringer

Den eksakte kostbalansen må måles, men topologien og produktprinsippet er
bindende. Bygg som ikke har ekte gameplay skal ikke vises som lovende noder.

### 3.1 Felles stamme — tutorialen til første raid

Alle nye settlements går først gjennom samme forståelige kjede. Ingen
strategisk doktrine kan brukes til å hoppe over denne fysiske grunnmuren.

1. **Settlement Charter** — Hearth, tre founders og Mayor-funksjonen.
2. **Shelter** — House og synlig population/capacity. Lodging blir den større
   boligvarianten når House-loopen er forstått.
3. **Timber Rights** — Lumber Camp, Lumberer Emblem, Work Scepter og første
   ekte tool-request/workplace-output.
4. **Cultivated Ground** — Farmhouse, Farmer Emblem, felt, seeds og matbuffer.
5. **Stores and Roads** — Warehouse, Courier Emblem, persistent Request Ledger
   og fysisk workplace → backpack → Warehouse-flyt.
6. **Hospitality** — Lodging/Tavern, Innkeeper Emblem og synlig
   rekrutteringssjekkliste.
7. **First Watch** — Barracks, Guard Emblem, fysisk våpenrequest og Command
   Staff med Stand/Patrol.
8. **First Raid Aftermath** — rapporten etter første raid åpner den første
   doktrinebeslutningen.

Fellesstammen gir bare det som trengs for å lære og bevise loopen. Watchtower,
Archer, avansert industri og dypere samfunnsbygg holdes til de strategiske
grenene.

### 3.2 Første splitt — tre aktive bosetningsdoktriner

Spilleren velger én aktiv doktrine hos Mayor. Tallene under er retning, ikke
ferdig balanse; de fryses først etter måling.

#### A. Shield Doctrine — forsvar først

- åpner den raskeste veien til Watchtower, Fletcher og militære forbedringer;
- bedre Guard readiness, responstid, postdisiplin og forsvarsstruktur;
- reell ulempe: høyere mat-/equipment-upkeep og lavere sivil throughput eller
  tregere worker-opplæring;
- skal føles som «gode vakter, dyrere/svakere arbeidsøkonomi», ikke gratis
  kampkraft.

#### B. Guild Doctrine — produksjon først

- åpner den raskeste veien til Sawmill, Mill, Mine og verkstedkjeder;
- bedre worker throughput, durability-bruk, logistikk og foredlingsutbytte;
- reell ulempe: tregere militær mobilisering, dyrere forsvarsoppgraderinger
  eller svakere raidberedskap før spilleren investerer i den;
- skal belønne planlegging og forsyningslinjer, ikke skape items fra luft.

#### C. Hearth Doctrine — samfunn og handel først

- åpner den raskeste veien til Tavern-, bolig-, lærings- og handelsforbedringer;
- bedre morale, traveler attraction, rekruttering og samfunnsrecovery;
- reell ulempe: lavere rå produksjonsbonus og mindre direkte militær effekt;
- skal gjøre settlementet attraktivt og robust, ikke omgå fysisk pris/mat.

### 3.3 Andre splitt — fem senere spesialiseringer

Når doktrinesystemet er lært, blir fem langsiktige spesialiseringer synlige.
Noen er rene endepunkter; andre har krysskrav mellom nabodoktriner.

1. **Fortification** — Barracks, Watchtower, Armoury, shield/formation og
   sterkeste statiske forsvar. Rot: Shield.
2. **Border Wardens** — Archer, Fletcher, patrol, Hunter's Lodge, Fishery og
   kontroll av ytterområder. Rot: Shield med produksjonskryss.
3. **Land and Harvest** — Farmhouse, Pasture, Mill, Bakery, Butcher og stabil
   mat-/råvareøkonomi. Rot: Guild.
4. **Craft and Industry** — Sawmill, Carpenter, Mine, Mason, Smelter, Smithy,
   Tannery, Weaver og fysisk produksjonskjede. Rot: Guild med samfunnskryss.
5. **Hall and Learning** — Tavern, Kitchen, Dining Hall, Brewery, Architect's
   Study, Library og senere Market/School/Infirmary når de har ekte gameplay.
   Rot: Hearth.

Spilleren skal ikke kunne ha alle capstone-bonusene aktive samtidig. Første
versjon bruker én aktiv doctrine og et begrenset antall aktive specialization-
slots. Å bytte skjer hos Mayor med synlig fysisk pris og cooldown. Allerede
kjøpte permanente building-unlocks slettes aldri, men de aktive bonusene og
ulempene følger nåværende valg. Dermed er prioriteringen reell uten at ett
tidlig feilvalg ødelegger en lang save permanent.

### 3.4 Node- og bygningspresentasjon

Hver Development-node viser før kjøp:

- kort bygningsbeskrivelse fra felles NB/EN-katalog;
- hvilken konkret spillerloop bygningen åpner;
- Job Emblem som blir tilgjengelig;
- fysiske kostnader og prerequisites;
- aktiv fordel, aktiv ulempe og hvilke veier valget utsetter;
- om nodeeffekten er permanent unlock eller bare aktiv doctrinebonus.

Build Plan viser minst samme korte beskrivelse, settlementkrav og låst/åpen
status i tooltip. Det er ikke nok at oppskriften finnes i recipe book.

### 3.5 Parkert til faktisk implementert

- School
- Infirmary
- Market
- andre bygg som validerer, men ennå ikke har en ekte aktiv loop

## 4. Datamodell og migrering

- Tech-unlocks lagres per settlement med eksplisitt skjemaversjon og stabile
  streng-/wire-ID-er, aldri enum-ordinal alene.
- Profession-ID-er beholder sine eksisterende stabile wireverdier.
- Yrkesemblem og workplace-id lagres separat.
- Eksisterende saves migreres uten å avbemanne noen:
  - hver gyldige gammel worker-relasjon gir tilsvarende bundet emblem;
  - eksisterende bygg og deres forutsetninger grandfather-unlockes;
  - gamle worlds relockes aldri stille;
  - konflikt, duplikat eller ukjent data feiler lukket og logges.
- Friske worlds starter i Trinn 0 og lærer første emblemhandel gjennom
  Founding Journey/Sagaoppdrag.
- Eksisterende Build Plans i gamle worlds håndteres gjennom den dokumenterte
  migreringsregelen; de skal verken duplisere unlocks eller forsvinne stille.

## 5. Produkt- og sikkerhetskrav

- Mayor-katalogen viser bare emblems som settlementets tech faktisk har åpnet.
- Pris er synlig før handel, og betaling/utstedelse er én atomisk transaksjon.
- Full inventory bruker hand → inventory → kontrollert drop/settlement recovery;
  ingen emblem slettes stille.
- Plaque-skjermens gamle gratis `Appoint`-vei kan vise kandidater og poststatus,
  men kan ikke omgå emblemkravet i normal survival.
- Admin-/testkommandoer merkes som testveier og teller aldri som gameplaybevis.
- Samtidig kjøp eller binding fra to spillere gir nøyaktig én gyldig vinner per
  item/transaksjon og konvergerende klienttilstand.
- Tech-tree og emblem-system skal være eventdrevet, uten globale tick-søk.
- Alle emblems får et konsistent, premium visuelt system med unik yrkesrune og
  lesbar tooltip, men kan dele en effektiv atlas-/genereringspipeline.

## 6. Releasebevis

En tech-/emblem-slice er ikke ferdig før:

1. Frisk survival viser riktig naturlig rekkefølge.
2. Låste, åpne og ferdige noder er serverautoritative og visuelt tydelige.
3. Hver aktiv profession har riktig emblem og bare kompatible poster.
4. Alle 25 aktive professions følger normal emblemflyt eller er eksplisitt
   parkert fordi gameplayet deres ikke er releaseklart.
5. Kjøp, feilkjøp, full inventory, dismiss, retrain, building dissolve, death,
   reconnect, chunk unload og to rene restarts er testet.
6. Gamle saves beholder arbeidere, profesjoner, bygninger og inventory.
7. To til fire spillere kan handle og tildele uten dupe, tap eller stale
   suksess.
8. Founding Journey bruker en ekte Lumberer-emblemhandel og -binding.
9. UI består EN/NB, guiScale 2/3/4 og begge referanseoppløsninger.
10. Hele den første vertikale loopen består etter at direkte hire-bypassen er
    fjernet fra normal gameplay.
11. Fellesstammen kan gjennomføres i én naturlig rekkefølge til første raid;
    tre doktriner og fem spesialiseringer har ingen dead node, sirkel eller
    skjult prerequisite.
12. Doctrinebytte viser og tar riktig fysisk pris én gang, håndhever cooldown og
    sletter aldri permanente building-unlocks.
13. Alle aktive BuildingType-er har én felles kort NB/EN-beskrivelse som vises
    ukuttet på Build Plan og Development-node.
