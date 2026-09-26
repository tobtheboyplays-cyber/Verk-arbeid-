# OWNER DIRECTIVE 2026-08-26 — bindende retning etter 30 svar

**Status:** BINDENDE for totaloverhalingen. Dette dokumentet er ikke et
ferdigstempel; det fastsetter hva som skal bygges og hvordan det skal dømmes.

**Kilde:** Eierintervju innsendt `2026-08-26T21:56:35.428Z`, 30/30 svar.
SHA-256 av den innsendte JSON-filen:
`983E3DC6EC687940415DD30AF5BB149DC20CCB704528443A191548D4C1748622`.

## 1. Styringsordre

Hearthstead skal først og fremst være **kolonioptimalisering med gjentakende
raids**, pakket som survival midcore med et lett forståelig startløp og
hardcore management-dybde etterpå. Spilleren bygger selv, bemanner manuelt,
styrer prioriteringer og overstyringer, og løser reelle flaskehalser i en
fysisk økonomi. Landsbylivet er ikke pynt: det er det synlige språket som gjør
optimaliseringen lesbar og følelsesmessig viktig.

Første komplette leveranse er én **full vertikal loop**:

`grunnlegg → lær gjennom Sagaoppdrag → bygg/valider → bemann → produser →
frakt fysisk → sikre mat → rekrutter via taverna → forbered raid → overlev og
redd → reparer → velg én av tre Blessings → gjør loopen igjen`.

Core gameplay kommer først. UI, teksturer, lyd og animasjon skal samtidig
løfte alle tilstander som faktisk inngår i loopen til premiumstandard før
loopen kan kalles ferdig.

## 2. Prejudikat og faste grenser

Når dette dokumentet og eldre produkttekst svarer forskjellig på samme valg,
gjelder dette dokumentet. Det erstatter særlig de fire ventende A3-pitchene i
`OVERHAUL_PROGRAM.md`: sult, matgate, trekull og øl er nå avgjort.

Følgende permanente invariants ble ikke opphevet av eieren og består:

- Arbeidet fortsetter i eksisterende `hearthstead-neoforge` på NeoForge
  1.21.1; ingen omstart eller ny mod.
- Plaketten er landmåleren. Ingen plakett betyr ingen bygning, og ingen innsatt
  Build Plan betyr ingen plakett-UI. Plaketten er tilgangspunkt, aldri en egen
  sannhetsdatabase.
- Settlere bygger aldri strukturer autonomt. De kan reparere raid-skade og
  oppgradere spillerbygde strukturer.
- Kister er sannheten. Alle varer er fysisk ekte og logistikken må konservere
  dem; ingen skjulte varebuffere kan jukse fram flyt.
- Alle søk og verdensskanninger er avgrenset og budsjettert.
- Ingen stille feil: blokkert arbeid, ugyldige bygg, rekrutteringsstopp og
  logistikkstopp skal ha en synlig, konkret årsak.
- Verden først: plaketter, lys, settlere, gjenstander og HUD forklarer
  øyeblikket; Tingboka gir dybde. Modden skal ikke bli et admin-regneark.
- Ingen emerald-økonomi.
- Hver meningsfulle, spiller-synlige aktivitet og overgang i demoen skal ha
  egen korrekt animasjon. Verktøy, gjenstander, treffpunkt, etterbevegelse og
  synkron lyd må verifiseres i faktisk spill; «ser funky ut» er en feil.
- Alle test- og ferdigpåstander følger `qa/PROTOCOL.md` og går bare gjennom
  `tools/hearthstead-qa`.

## 3. Avklaringer der svarene trekker i flere retninger

### Fredelig standard betyr ikke raid av

**Fredelig** er standardprofilen, men kjernefantasien krever gjentakende raid.
Profilen senker press, fiendestyrke og permanent tap, gir tydeligere varsling
og romsligere rednings-/gjenoppbyggingsmarginer. Den fjerner ikke raidloopen.
Første reelle raidtrussel kommer normalt natt 4–7, varslet 1–2 dager før.
Balansert og Jernvinter skalerer samme system; de er ikke separate spill.

### 40–50 settlere uten å ofre premiumfølelsen

**40–50 aktive settlere** er bindende ytelsesmål. Det er tillatt å redusere
usynlig beregningsfrekvens, bruke tidsbudsjetter, køer, cache, LOD og sjeldnere
nyvurdering på avstand. Det er ikke tillatt å løse kravet ved å fjerne fysisk
vareflyt, synlige jobbhandlinger, egne animasjoner eller viktige behov.
Atferden skal være hendelsesdrevet og tidsdelt, ikke mindre troverdig.

`OVERHAUL_PROGRAM.md` D15 skjerpes derfor fra «25+» til en dokumentert
referansetest med 40 og 50 aktive settlere. Gjennomsnittlig MSPT skal fortsatt
være under 45 på referanseriggen, og p99-spikes samt Hearthsteads egen
tick-kostnad mot samme-seed baseline skal rapporteres.

### Optimalisering og levende landsby

Når produksjonsdybde og ren atmosfære konkurrerer, vinner den målbare
koloniloopen. Atmosfære kuttes likevel ikke fra handlingene: arbeid, transport,
sult, hvile, sosial servering, alarm, skade, redning og reparasjon må vises i
verden. «Levende» er lesbarhet og konsekvens, ikke et separat pyntesystem.

### Checkpoints er leveranseform, ikke lavere ferdigkrav

Eieren skal få spillbare jars ved store milepæler med kort, ærlig status om
hva som er bevist og hva som gjenstår. Et checkpoint kan være ufullstendig.
Ordene «komplett», «ferdig» og «releaseklar» krever fortsatt to grønne
`full`-kjøringer på samme fingerprint, `gate`, relevant film/bilder og alle
gjeldende demokrav.

### Kompatibilitet velges smalt, men arkitekturen skal kunne vokse

Første leveranse får **kuratert støtte**: rent NeoForge-oppsett, JEI/EMI,
Xaero-markører og vanlige ytelsesmoder som allerede er utpekt i designet.
Implementasjonen skal bruke datafiler, tags, registries og avgrensede
integrasjonssømmer der det er naturlig, slik at større modpakke-støtte kan
legges til uten datamodell-omskriving. Bred modpakke-matrise er ikke en
demoblokker.

### Save-frihet

Datamodellen kan forbedres, men lagrede data får eksplisitt skjemaversjon og
praktisk migrering. Migrering testes på representativ gammel save med backup.
En privat verden kan bare kreve reset når migrering faktisk er uforsvarlig;
det skal varsles konkret og aldri skje stille.

## 4. Alle 30 eierbeslutninger

Kolonnen «innsendt valg» gjengir valget fra skjemaet; tolkningen gjør det
operativt uten å endre meningen.

| # | Tema | Innsendt valg | Bindende betydning |
|---:|---|---|---|
| 1 | Kjernefantasi | `kolonioptimalisering med raids som går gjentakende` | Produksjon, bemanning og fysisk logistikk er hovedspillet; raid skaper gjentakende press og mål. |
| 2 | Målgruppe | `survival midcore med harcore managment` | Lav terskel inn, høy systemdybde og krevende optimalisering etter onboarding. |
| 3 | Opplæring | `Sagaoppdrag` | Skippbar, spillbar oppdragskjede lærer kjerneloopen uten ekstern wiki. |
| 4 | Standardnivå | `Fredelig` | Laveste pressprofil er valgt som standard, men beholder den komplette raidloopen. |
| 5 | Død og tap | `Redningsvindu` | Settlere går ned først; mislykket redning kan gi permanent død, grav og sorg. |
| 6 | Sult | `Trinnvis fare` | Sult gir først svekket arbeid, så downed-tilstand og til slutt død hvis redningsvinduet utløper. |
| 7 | Matbuffer | `To døgn` | Rekruttering krever spiseklar mat for omtrent to døgn for populasjonen etter innflytting. |
| 8 | Trekull | `2 enheter` | Ett trekull teller som to enheter i Hearthsteads brenselbudsjett. |
| 9 | Ølservering | `Valgfri bonus` | Fysisk øl kan serveres med cooldown for moral/sosialt liv og liten rekrutteringsbonus; det er aldri driftsgate. |
| 10 | Logistikk | `Prioriter og overstyr` | Couriere finner arbeid autonomt; spilleren setter filtre, hastegrad og eksplisitte prioriteringer/overstyringer. |
| 11 | Fraktløft | `Sekk nå, kjerre senere` | Synlig sekk og reell kapasitet inngår i demo; kjerre utsettes til grunnlogistikken er bevist. |
| 12 | Rekruttfart | `2–4 døgn` | En gyldig, forsynt taverna tiltrekker normalt kandidat innenfor dette vinduet. |
| 13 | Bemanning | `Fullt manuelt` | Spilleren tar endelig yrkesvalg; ingen automatisk flytting eller skjult kandidatrangering. |
| 14 | Progresjon | `Hybridmodell` | Hearth-nivå åpner kapasitet, bygningsnivå forbedrer fag, tradisjoner gir strategiske valg. |
| 15 | Forskning | `Ett aktivt prosjekt` | Ett ressurskrevende prosjekt om gangen; skriveren kan gi langsom passiv framgang. |
| 16 | Raidtempo | `Natt 4–7` | Første raidtrussel blir reell i dette vinduet og telegraferes 1–2 dager før. |
| 17 | Raidledelse | `Kamp og kommandhjul` | Spilleren slåss selv og gir få, tydelige ordrer; vakter beholder autonom AI. |
| 18 | Blessings | `Velg én av tre` | Etter overlevd raid vises tre tydelige kort; spilleren velger én varig effekt. |
| 19 | UI-struktur | `Verden først` | Verden/HUD løser øyeblikket; Tingboka er dybdeverktøyet. |
| 20 | UI-stil | `Jern og mørk eik` | Mørk eik, jern, varme tekstiler og begrenset metallaksent er hovedspråket. |
| 21 | Animasjon | `Denne skal være elite. en animasjon for hver bevegelse. Alt skal være animerrt å gi premium følelse bruk tid på dette. og må aktivt testet så det ikke ser funky ut` | Alle meningsfulle synlige handlinger i demoen får egne, aktivt inspiserte animasjoner og overganger; ingen generisk arbeidsloop godkjennes. |
| 22 | Lydnivå | `Arbeid og varsler` | Egen lyd prioriteres for arbeid, treff, statusvarsler, raidtegn og atmosfære; stemmepass kan vente. |
| 23 | Samarbeid | `Én delt landsby` | 2–4 spillere deler progresjon, data og tillatelser for én landsby. |
| 24 | Ytelsesmål | `40–50 settlere` | Full aktiv landsby testes ved 40 og 50 uten å jukse bort kjernesimuleringen. |
| 25 | Kompatibilitet | `du velger, men muligheter som ikke blir ødelagt om den blir stor` | Kuratert demo-støtte nå; data- og integrasjonssømmer skal tåle senere vekst. |
| 26 | Demoomfang | `Full vertikal loop` | Arbeid, mat, rekruttering, logistikk, raid og Blessing skal bevises i én sammenhengende reise. |
| 27 | Save-frihet | `Versjonert migrering` | Arkitektur kan endres med eksplisitt save-versjon, backup og testet migrering der praktisk. |
| 28 | Visuell egenart | `Egen signatur` | Hearthstead får egne ramper, silhuetter og aksenter innen Minecrafts pikselmål og lesbarhet. |
| 29 | Kutt først | `Sen verden og familie` | Familier, årstider, nabobyer, ekstra fraksjoner og store ekspedisjoner flyttes etter demo før kjerneloopen kuttes. |
| 30 | Nattlevering | `Spillbare checkpoints` | Spillbar jar og kort bevisstatus ved hver stor milepæl; full ferdigpåstand venter på komplett gate. |

## 5. Mekaniske kontrakter som nå er låst

### Mat og sult

- «Spiseklar mat» teller bare varer som settlere faktisk kan konsumere fra
  chest-truth-lageret; råvarer og reserverte varer telles ikke dobbelt.
- To-døgnsgaten beregnes mot populasjonen **etter** at kandidaten blir med.
  Den blokkerer betaling/join på en lesbar måte og oppgir manglende mengde.
- Sulttrappen skal ha separate, synlige faser: svekket arbeid → kritisk →
  downed/redningsvindu → død. Tidsverdier balanseres med målinger, ikke gjetning.

### Rekruttering og taverna

- Gyldig taverna er absolutt attraksjonsgate; en gjest som allerede venter
  følger fortsatt grandfather-regelen i D-TAVERN-2.
- Når taverna, bolig, pris og to-døgns matbuffer er gyldige, skal normal
  kandidatankomst ligge i intervallet 2–4 spilldøgn.
- Øl er en fysisk, valgfri bonus med cooldown. Ingen øl betyr lavere bonus,
  aldri stanset taverna.
- Jobbvalg er fullt manuelt. Systemet kan vise fakta om ferdigheter og behov,
  men skal ikke automatisk rangere, ansette eller flytte folk.

### Logistikk

- Autonom jobbvalg er standard; spilleren styrer unntak via filtre, prioritet
  og hastegrad.
- Synlig sekk og kapasitet er del av første demo. Kjerre må ikke snike seg inn
  før demoens grunnflyt er bevist.
- Hver stoppårsak skal være typet og lesbar i verden. S1 `StopReason` er derfor
  første logistikkarbeid, før ny scoring eller større UI.

### Raid og belønning

- Raid er gjentakende, ikke en engangsfinale. Minst to raid-sykluser må kunne
  gjennomføres i samme save uten reset eller progresjonsbrudd.
- Spilleren kjemper og bruker kommandhjul; vakter må fortsatt handle fornuftig
  uten mikrostyring.
- Downed/redning, chest-true tyveri/tap, materialkrevende reparasjon og valg av
  én av tre Blessings er deler av loopen, ikke valgfrie presentasjonslag.

## 6. Ikke mål for første komplette demo

- Familier, barn/skole, årstider, levende nabobyer, store ekspedisjoner og
  full senverden.
- Andre/tredje fullverdige fiendefraksjon; én komplett raidfraksjon er nok til
  å bevise loopen.
- Courier-kjerre.
- Bred modpakke-kompatibilitet eller støtte for flere separate landsbyer i
  samme demo.
- Automatisk bemanning, meny-først-administrasjon, ferdighus/schematics eller
  settlere som bygger for spilleren.
- Full stemme-/mumblepakke dersom arbeid, treff, varsler og atmosfære fortsatt
  har lydgjeld.
- Visuell polish som ikke støtter en fungerende vertikal loop. Den kan ikke
  fortrenge core, men core-tilstander kan heller ikke leveres med placeholder-
  animasjon eller uleselig UI.

## 7. Bindende akseptkriterier

Disse kommer **i tillegg til** `OVERHAUL_PROGRAM.md` D1–D17. Ved konflikt
gjelder oppdateringene i §3. En leveranse er ikke komplett før:

1. En ny spiller kan gjennomføre Sagaoppdrag fra ildsted til første
   chest-true leveranse uten ekstern wiki eller admin-kommando.
2. Hele vertikalloopen i §1 kan gjennomføres i survival i én save, og raid →
   Blessing-delen kan gjentas minst to ganger.
3. Fredelig er faktisk standard. Første raid kommer natt 4–7, varsles 1–2
   dager før og er mildere enn Balansert uten å slå av systemer.
4. Sult viser og tester alle fire faser; mislykket redningsvindu kan ende i
   permanent død, grav og sorg uten stille tilstandshopp.
5. Rekruttering blokkeres lesbart uten gyldig taverna eller to døgn spiseklar
   mat for post-recruit-populasjonen; gyldig flyt gir normalt kandidat innen
   2–4 døgn.
6. Trekull teller nøyaktig 2 fuel-enheter. Øl gir målelig, cooldown-begrenset
   bonus, men fravær av øl stopper aldri drift eller rekruttering.
7. Spilleren kan prioritere og overstyre logistikk. Hver stans har synlig
   `StopReason`, og alle flyttede varer består konserveringstest mot kister.
8. Bemanning er manuelt styrt, og ett aktivt forskningsprosjekt bruker fysiske
   ressurser. Hybridprogresjonens tre lag vises uten falske låser.
9. Alle demo-relevante skjermer fungerer på guiScale 2/3/4 og følger
   jern/mørk-eik-systemet; kritisk status kan leses i verden/HUD uten å åpne
   Tingboka.
10. Hver demoaktivitet har eget korrekt klipp med synlig redskap/gjenstand,
    riktig retning og synkron arbeids-/trefflyd. Klipp dømmes i live-film med
    lyd; statisk eksport alene er ikke godkjenning.
11. To til fire spillere kan bruke én delt landsby uten doble trekk,
    duplisering, permission-lekkasje eller divergerende progresjon.
12. 40- og 50-settler-scenarier passerer ytelsesgaten i §3; ingen ubundet
    skanning eller pathfinding får skjules bak gjennomsnittet.
13. Save/reload midt i rekruttering, logistikk, raid og Blessing bevarer
    tilstand. Minst én representativ eldre save migreres via versjonert sti.
14. Kuraterte kompatibilitetsscenarier passerer uten at kjerneoppførsel blir
    avhengig av tredjepartsmodene.
15. Hvert checkpoint inneholder spillbar jar, kildefingerprint, kjent-gjeld-
    liste og relevante test-/bilde-/filmbevis. Komplett gate krever `full` ×2
    samme fingerprint og `gate`.

## 8. Første prioriterte vertikale slice

### «Mattrygg rekruttering → lesbar logistikk → første raid → Blessing»

Dette er første checkpoint som skal bygges ferdig. Rekkefølgen er bindende:

1. **Sann baseline:** kjør godkjent QA og etabler reell nåstatus/fingerprint;
   ingen gammel rapport brukes som bevis.
2. **Lesbar logistikk først (S1):** additiv `StopReason`, verdensavlesning og
   plakettstatus. Ingen ny rutealgoritme før spilleren kan se hvorfor dagens
   flyt stopper.
3. **Lås eierens A3-valg:** to-døgns matgate, sulttrapp, trekull = 2 og
   valgfri ølbonus, alle med GameTests og ærlig UI/status.
4. **Knytt loopen:** Sagaoppdrag, fullt manuelt jobbvalg, fysisk produksjon og
   sekkecourier skal føre til gyldig taverna og kandidat innen 2–4 døgn.
5. **Lukk raidenden:** Fredelig første raid natt 4–7 med telegraf, spillerkamp
   + kommandhjul, guard-AI, redningsvindu, chest-true tap, materialreparasjon
   og valg av én av tre Blessings.
6. **Premium-pass på brukt flate:** B1 før fargelagte UI-assets, K1 før
   animasjonsgodkjenning; bare skjermene, teksturene, lydene og klippene som
   denne reisen bruker er checkpoint-blokkere.
7. **Lever checkpoint:** kjør relevant QA-kadens, spill hele reisen, bygg jar
   og lever kort status + film med lyd. Restgjeld navngis; checkpointet kalles
   ikke komplett overhaul.

Slice-gaten er én sammenhengende survival-reise uten kommandoer eller skjulte
varer, pluss automatiske bevis for mat, fuel, rekruttering, logistikk,
raid/Blessing, persistens og ingen stille stopp. Etter dette følger S2–S4,
resten av UI-/tekstur-/animasjonsoverhalingen og 40–50-settler-gaten; sen
verden og familie forblir parkert.
