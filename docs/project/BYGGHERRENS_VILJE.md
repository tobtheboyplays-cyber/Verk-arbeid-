# BYGGHERRENS VILJE — eierens samlede direktiver, ordrett

Byggherre-agenten LESER DENNE FØRST, hver instans, hver gang. Dette er alt
eieren faktisk har sagt (kronologisk, denne æraen). Dommene skal høres slik
ut, ville dette, og kreve dette. Der to direktiver drar hver sin vei gjelder
det nyeste + «core gameplay først».

## Kjernedirektiver (gjentatt og stående)
- «Alt skal være premium så høy standard på alt» / «bare premium er standaren»
- «Viktigste er core gameplay så vi får opp en fungerende demo så pynter vi
  og legger til senere» / «Du skal ha stål fokus på å få loopen playable
  core gameplay er det kritiske»
- «Jobb overtime fiks alt og få denne modden opp å nikke»
- «Ultra code forblir på så go all inn push deg selv til limiten uten å
  miste kvalitet»
- «fortsett arbeidet å følg din lang tids plan videre få laget en
  velfungerende mod med alt balansert å funker»
- Vil SE ting: «jeg vil se animasjonene», «når du har tid vil jeg se video
  av alt», «gi meg en showcase video igjen når du er klar var veldig nice»

## Arbeidsform eieren har valgt
- Masse bots i parallell, koordinator styrer: «vær en super orchestartor
  styr rundt 20 bots», «lag fler oppgaver til bots», «lag nye bots til
  videre oppgaver etter det igjen tenk fram i tid»
- Koordinatoren spiller/tester og lager en bot per problem: «jeg vil at du
  tester det de lager så gir du tilbakemelding til botten så fortsetter du
  slik helt til modden funker. Så spiller du, ser et problem eller noe vi
  må legge til, lager en bot til det»
- Selvstyrende: «når du ikke har noe å gjøre da lager du oppgaver til deg
  selv ... lager din egen schedule»
- Lær håndverket som varige skills: «bli veldig god på animations så søk opp
  gode toturials med advanced animations å lær det som skill», «samme med
  textures og UI», «bruk beste animasjon toolen å se over alle»

## Design-beslutninger eieren har tatt (bindende)
- Jobblimiter: «jeg vil ikke at farmeren kan farme evig, jeg vil ha en limit
  på alle jobbene så finn en logisk løsning» + farmer: «3x3 først så hvis du
  har fler så kan du ha større eller at den blir større med hvor mye skill»
- Hogst: «på hogging animasjonen så vil jeg han hogger fra siden som i real
  life»
- Pickup: «alle settlers av noe slag skal ha en animasjon for å plukke opp
  ting og putter det i sin lille sekk. Vil også at du ser nøye på
  animasjonen og ser på det som er funky og er kritisk til det for å så
  fikse det»
- Summon: «summone en worker til workplacen er en fin mechanic ... Han må
  også lyse opp når det skjer»
- Flows: «fable 5 skal lage de perfekte flows som knytter alle bygningene
  sammen ... mest at de er avhengig av hverandre men samtidig ikke»
- Vakter: «guards skal ikke kunne ha bra armor før de oppgraderes så de
  trenger erfaring, vil også ha en kaptein som folk må hilse til»
- Archer: «vil også ha en archer, med kule abilities power shot og triple
  shot osv over tid»
- Priser: «researche og fikse hva alt skal koste ... Finn opp priser som
  virker naturlig ... villagen hjelper med å få ned kostnaden»
- Forskning: «Lag også research systemet lag 9 forslag deretter lager du den
  som gir mest mening ... UI må se bra ut»
- Referanser: «bruk minecolonies og tektopia å se om vi mangler noen viktige
  systemer»
- Plaketter: «det som er inni plaques er veldig tamt. Pynt opp det mye mer»
- Grounded items: «kan vi ha items som ligger på bakken faktisk ligger på
  bakken? og at den ikke flyter»
- Navneskilt: «navn over med yrke hadde vært nice» → «tydeligere for jobben»
- Idles: «vil også ha idle animations som matcher jobben»
- Vaktstance: «vakten holder sverdet rart» → «han skal virke selvsikker og
  kontrolert»
- Skins: «skinsa kan bli enda bedre også»

## Ferske dommer fra eieren selv (2026-08-25 kveld, etter filmvisning)
- PICKUP-animasjonen: «Om det var plukk opp animasjoner noen av de. Den må
  fikses på.»
- CHOP side-swing: «Øksa til siden ser fortsatt jævlig ut.» (Etter én
  retune-runde — den er altså fortsatt ikke god nok. Alvorlighet 1 på
  presentasjonssiden.)

## Ferske ordrer (2026-08-26 kveld, ny sesjon — gjelder foran frysen der de
## eksplisitt bestiller noe)
- Anker-mekanikk, ordrett: «vil ha tektopia sin måte å få bygniger å jobber
  på med det skal ikke koste emeralds. Så vil jeg ha minecolonies sin måte
  å kjøpe settlers på. Så tavern er kritisk for å få nye settlers»
  → TAVERN-GATE-slicen (PLAN_TAVERN_GATE.md): tavernaen er porten for nye
  settlers; ingenting koster emeralds, noensinne (vokter-test).
- Total-overhaul-bestillingen, ordrett: «Vil at du skal planlegge og fikse
  alle problemene jeg har. Vil at du gir en TOTAL UI OVERHAUL grunnet den
  er kjempe stygg. Finn noe fine referanser ute på interenett å ta fra de.
  ANIMASJON OVERHAUL. FInn en god løsning der. Deretter CORE GAMPLAY finn
  en god løsning slik at det er en god playable demo. Sett kravene til en
  god demo og sett igang å balance og fiks det vi har til nå»
- «Textures også må bli 100 bedre»
- «ikke start på nytt» — alt overhaul-arbeid skjer i den eksisterende
  hearthstead-neoforge-kodebasen. Aldri omstart, aldri ny mod.
- Bekreftet at koordinatoren har referansetilgang: prototypen i repoet,
  TekTopia/MineColonies åpen kildekode, internettreferanser for UI.

## Ta-over-mandatet (natt/fravær)
Eieren: «Få mini mi til å ta over å lese alt jeg har skrevet før slik den
vet hva jeg vil ha.» Når eieren er borte taler Byggherren med eierens
stemme overfor koordinatoren: dommer og kravlister behandles som
eier-tilbakemelding og omsettes i fikse-arbeid uten å vente. GRENSER som
IKKE følger med: Byggherren kan ikke godkjenne playtest-suite-kjøringer
(krever ekte eier), ikke endre modell/budsjett/rutiner, ikke oppheve
QA-protokollen eller invariantene, og ikke senke kravene. Den kan bare
kreve MER, aldri mindre.
