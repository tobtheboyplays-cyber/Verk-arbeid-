# Bannerhold – veikart (høst 2026)

Skrevet 25. sep. 2026. «Bannerhold» er et midlertidig navn (tidligere Hearthstead). Internt modid `hearthstead`, registry-ID-er og klassenavn endres ikke, slik at eksisterende verdener fortsetter å laste.
Fasit for retningen er Tobias' 50 svar (`memory/bannerhold-vision.md`). Bygger på `ui-proposal/*.md` og `handoff/CLAUDE_HANDOFF.md`.

**Størrelser** (grovt, med flere agenter i parallell): **S** = 1–2 dager, **M** = 3–5 dager, **L** = 1–2 uker.
**Bugs:** rapporteres foreløpig direkte til Claude i chat. En Discord-integrasjon (varsler, chat-bro, bug-kommando) kan komme senere.

---

## Fase 0 – «Søndagsbygget» (lør. 26.–søn. 27. sep.)

Målet er Tobias' krav: **«alt føles smooth og nytt og funker».** Det er bedre å levere færre ting som virker enn mange som nesten virker.

### Tidsplan
| Når | Hva |
|---|---|
| Fre. 25.9, kveld | Alle agenter som jobber nå leverer til kopien (`Verk-arbeid-`). Ingen nye systemer startes etter dette. |
| Lør. 26.9, senest kl. 12 | Alt integreres i én gren. Full JUnit og GameTest kjøres. **Soak-testen på 10 dager startes.** 10 spilldager ved 2x døgnlengde tar ca. 6 t 40 min i sanntid. |
| Lør. 26.9, kl. 18 | Go/no-go for hver funksjon (se kutt-regelen). Tobias får en før/etter-video. |
| **Lør. 26.9, kl. 21** | **Kodefrys.** Etter dette er bare blokkerfikser tillatt, og hver av dem krever at testene kjøres på nytt. |
| Søn. 27.9 | Endelig jar lages via den kanoniske QA-ruten (`tools/hearthstead-qa package` + `gametest`). Deretter backup. **Deploy skjer kun etter Tobias' eksplisitte «ja».** Så vennetest. |

### Sjekkliste (alt må være krysset av før deploy)

**Stabilitet**
- [ ] Soak-testen på 10 dager er ferdig med 0 krasj og 0 item-tap eller duplisering (revisjonsloggen i request-ledgeren stemmer).
- [ ] Soak-testen viser stabilt minne og ytelse på mspt median < 30 og p95 < 50. Antall settlere noteres.
- [ ] Watchdogen for arbeidere har 0 uløste tilfeller der en settler står fast i mer enn N minutter.
- [ ] Save/reload midt i en raid, en leveranse og en crafting-runde gir ingen dobbel eller tapt tilstand.
- [ ] Pathing: settlere kommer seg gjennom dører, trapper og stiger og mellom etasjer i et testhus med flere etasjer.
- [ ] En 2-timers co-op-økt med 2 klienter på serveren gir ingen ERROR i `latest.log`.

**Tester**
- [ ] Alle JUnit-tester er grønne (≥ 555).
- [ ] Alle GameTests er grønne (≥ 963 pluss de nye) via den kanoniske ruten, ikke bare `gradlew` i kopien.
- [ ] QA-gjennomspilling i survival fra ny verden fram til første raid (ca. 100–120 min) uten blokkering.

**Animasjon og lyd**
- [ ] Den nye animasjonsmotoren (bøyde albuer og knær, lag, JSON) er **tydelig synlig** på Lumberer og minst tre andre jobber. Hvis en JSON-fil feiler, faller den tilbake til den gamle animasjonen.
- [ ] Blender-klippene for Lumberer ser riktige ut i spillet, uten at armer klipper gjennom kroppen.
- [ ] Lyd: vanilla-remap pluss Sonniss/CC0. Ingen av de gamle syntetiserte lydene er igjen, volumet er jevnt, og det finnes en lisens- og kreditliste.

**UI, kart og Banner**
- [ ] Banner-hovedskjermen følger referansemockupen. Alle faner åpner, og ingenting overlapper på GUI-skala 2–4 ved 1080p og 1440p.
- [ ] Det levende rikskartet viser bygninger, settlere og raid-retning, og det **laster eller genererer ikke chunks**.
- [ ] Banner og stativ: gamle Hearth-blokker konverteres på plass i en kopi av eierverdenen. All spillertekst sier Bannerhold/Banner.
- [ ] Jeger: kadaver og slakting virker, vilt spawner uten å spamme, og nye items finnes i JEI.
- [ ] Settlere regenererer helse når de er godt mett, og tallet synes på settler-arket.

**Backup og rollback**
- [ ] Det finnes en full backup av serververden, `mods` og `config` med tidsstempel. Den er **testet ved gjenoppretting** på en kopi.
- [ ] Forrige jar ligger i `mod-history`, og rollback er prøvd én gang.
- [ ] Hashen til `hearthstead.jar` i `automodpack-content.json` er lik hashen til den nye jaren.
- [ ] Rollback-plan: hvis spillet krasjer ved join eller verdenen blir ødelagt → stopp serveren → legg tilbake gammel jar og verdensbackup → start → gi vennene beskjed.

### Kutt-regel (lør. kl. 18)
En funksjon som ikke er grønn, slås av med config-bryter eller tas ut av bygget. Prioritet når noe må kuttes (høyest først): stabilitet og pathing → Banner og navnebytte → ny UI og kart → animasjon → lyd → Jeger.
**Risiko:** mange samtidige sammenslåinger gir konflikter. Konverteringen fra Hearth til Banner kan skade eierverdenen, så den testes kun på en kopi. Soak-testen tar ca. 7 timer, så den må starte i tide.

---

## Fase 1–6: 12 uker (28. sep.–20. des., uke 40–51)

Hver fase tar 2 uker og ender med en venneoppdatering (se Faste rutiner). Rekkefølgen er: det billige og synlige først, deretter truslene (raid og hendelser), så det som gjør tap overkommelig (helbredelse og forsvar), deretter dybden (økonomi), veksten (folk og rike) og til slutt skala.

### Fase 1 – Grunnmur og liv (uke 40–41)
**Mål:** Søndagsbygget blir solid, og landsbyen føles levende.
| Funksjon | Str. |
|---|---|
| Fikse funn fra søndagstesten (sett av ca. 30 % av tiden) | M |
| Tankebobler for livsbehov (sulten, ingen seng, rystet, vil til tavernaen) og mumlelyder | S–M |
| Config-presets Cozy/Normal/Hard pluss et avansert oppsett | S |
| Raid-grunnfikser: spawn utenfor claim (i dag 26–38 blokker inne), vise hvilken blokker som holder igjen en raid, timeout eller retrett ved daggry | S–M |
| Xaero-minimap: veipunkter for Banner og bygninger, og raid-retning. Valgfri avhengighet | S–M |
| Ytelses-baseline: mspt målt ved 30 og 60 settlere | S |

**Hvorfor nå:** Alt er billig og synlig. Presetene blir justeringsknappen alle senere faser balanseres mot. Raid-fiksene må være på plass før murer har noen mening.
**Risiko:** Xaero har ikke noe stabilt offentlig API. Reserveløsningen er vårt eget rikskart. Boblene kan også bli rotete, så bare én boble vises om gangen.

### Fase 2 – Hendelser og fiender (uke 42–43)
**Mål:** Noe skjer ofte, og det varierer.
| Funksjon | Str. |
|---|---|
| Hendelsesmotor med budsjett, nedkjøling og ingen «innhenting» etter søvn eller reload | M |
| Hendelser: rik handelsmann, tyvebande (bygger på goblin-tyven), vennlige og aggressive utsendinger, og møter med folk i verden (reisende leirer som gir rekrutter) | M |
| Nabofolk-register: navngitte folk med banner og humør, lagret som data. Blir til AI-riker i fase 6 | S |
| Ukentlige raid med 2–3 fraksjoner, hver med eget utseende og egen taktikk (f.eks. Torchbearer, Bowman, Shieldbearer) | L |
| Nemesis-kapteiner: bygger på den eksisterende `RaidCaptain` med nag og tilnavn, og får et svakt punkt som kan leses | M |
| Jakt på tyv med byttesekk: fang ham og få varene tilbake | S |

**Hvorfor nå:** Hendelser er Tobias' viktigste ønske for følelsen i spillet, og raid-motoren finnes allerede. Utsendingene trenger nabofolk, så registeret kommer nå.
**Risiko:** Det kan bli for mye. Innfør én ny rolle per fraksjon om gangen. Dødsreglene forblir som i dag til fase 3.
**Besluttet (25. sep):** raid hver 3.–4. spilldag (ca. 2–2,7 t spilletid).

### Fase 3 – Sår, helbredelse og forsvar (uke 44–45)
**Mål:** Tap betyr noe, men det finnes et forsvar mot dem.
| Funksjon | Str. |
|---|---|
| Permanent død: en settler på 0 HP går **ned**. Uten Infirmary dør settleren; med Infirmary bæres den inn og behandles | M–L |
| Healer i `INFIRMARY`: bygningen finnes allerede og står tom. Healeren samler urter, lager omslag og går runder | M |
| Lett forsvar: palisade, port med porthus (vakt stenger den), tårn med siktlinje som gir tidlig varsel, feller som aldri skader spillere | L |
| Svake punkter: fiender går mot porten eller et hull i muren, og en mørk flanke er en reell svakhet | M |
| Navngitte helter på egen side: veteranvakter får navn og historikk | S–M |

**Hvorfor nå:** Ukentlige raid fra fase 2 gjør tap reelle. Helbredelse og murer må komme rett etter, ellers blir raidene bare straff.
**Risiko:** Ned-tilstanden berører død, BLOD-regelen og lagring, så den trenger mange tester. Raidere kan også sette seg fast utenfor murer, så de trenger en reserveløsning der de bryter gjennom.

### Fase 4 – Produksjon og økonomi (uke 46–47)
**Mål:** Dybde på nivå med MineColonies, uten at noe låser seg fast.
| Funksjon | Str. |
|---|---|
| Crafting-ordre: en mangel sendes videre til riktig verksted og til slutt til spilleren (M1) | M |
| Bygningsnivå 1–3 med materialordre (M2), og **hybridbygging**: spillere bygger, settlere reparerer og oppgraderer | L |
| Minimumslager (M3), prioritet for kurerer (M5) og visningen «Trenger deg» (M6) | M |
| Coins fra skatt, taverna og tjenester, og oppdrag. Fiks Trader-inntekten som blir liggende fast i Trading Post-kister | M |
| Vaktutstyr laget i landsbyen (Smith/Armourer) **eller** kjøpt for Coins | M |
| Bonus for variert mat (siste 5 måltider) og vinterforråd («mat og ved for N dager» på Banner-skjermen) | M |
| Rangstige trinn 1: hamlet → village → town (krever bygningsnivåer og innbyggertall) | S–M |

**Hvorfor nå:** Utstyr og oppgraderinger trenger produksjonskjeder. Coins gir hendelsene fra fase 2 mer mening, som handelsmann og løsepenger.
**Risiko:** Regresjoner i `CourierWorkGoal` (4 600 linjer). Endringene holdes inne i `RequestLedgerService`. Balansen testes ved vanlig spillfart.
**Utsatt (25. sep):** skatt venter – Tobias vil ikke ha skatt nå. Coins fra handel, taverna/tjenester og oppdrag.

### Fase 5 – Folk og vekst (uke 48–49)
**Mål:** Landsbyen vokser av seg selv og får historie.
| Funksjon | Str. |
|---|---|
| Relasjoner og par, familier, barn som vokser opp, går på `SCHOOL` og **arver yrket** | L |
| Redning av fanger: raidere kan ta settlere, og spillerne redder dem fra en leir. Fanger kan også bli rekrutter. Løsepenge-raidet slås på | M–L |
| Utposter (gruve, gård, vakt) knyttet sammen med vogner (Carter). Last flyttes som data når chunken ikke er lastet | L |
| Rangstige trinn 2: town → castle | S |
| Krønike: en kort, datert logg på Banner-skjermen | S–M |

**Hvorfor nå:** Barn trenger stabil mat, senger og skole fra fase 4. Fanger trenger fraksjoner (fase 2) og ned-tilstand (fase 3).
**Risiko:** Befolkningen kan eksplodere, så sengeplasser setter taket. Nye felt i lagringen krever migrering. Vogner mellom chunks krever at ingen chunks lastes med tvang.

### Fase 6 – Rivaler og skala (uke 50–51)
**Mål:** Riket står mot andre riker og tåler 100+ settlere.
| Funksjon | Str. |
|---|---|
| Rivaliserende AI-riker (2–3 fra nabofolk-registeret) med dyp diplomati: allianser, giftemål, spioner og krigserklæring som utløser raid | L |
| Rangstige trinn 3: castle → kingdom (krever diplomati eller en allianse) | S |
| Ytelsesrunde for 100+ settlere: profilering, AI-tick-budsjett, pathing-cache, synk bare det som endrer seg | L |
| Versjonsvurdering (se under) | S |

**Hvorfor nå:** Diplomati bygger på hendelser, fraksjoner, økonomi og familier (giftemål). Ytelse kommer sist fordi Tobias vil ha innhold før ytelse, men hver fase har en ytelsessjekk underveis.
**Risiko:** Diplomati kan vokse uten grenser. Versjon 1 holder rikene utenfor kartet som data. Det er også risiko for at 100+ settlere krever ombygging av pathing.

### Versjonsstrategi
- Vi blir på **1.21.1** til innholdet er solid, det vil si gjennom fase 6.
- Deretter vurderes den nyeste stabile MC-versjonen med NeoForge-støtte. Kriterier: NeoForge er stabil; Xaero, Jade, JEI og AutoModpack finnes for versjonen; kostnaden ved portering er anslått; testsuiten er grønn på et prøveport.
- Beslutningen tas ved flervalg: A) porte nå, B) vente én versjon til, C) bli på 1.21.1 fram til offentlig lansering.

---

## Senere / DLC-lignende utvidelser
- **Hester og kavaleri:** Carter-hest, stall, beredne vakter og kavaleri i raid.
- **Årstider:** Hearthstead-året med såing, høst, vinter og innhøstingsfest. Vinterforrådet fra fase 4 kobles inn her.
- **Beleiring:** rambukk, sappører, kokende bek og skjoldmur. Bygger på porthuset fra fase 3.
- Andre kandidater: markedsdag og handelsruter, ekspedisjoner, festivaler og bryllup, flere yrker (birøkter, lærvarer, kullbrenner, meieri).

---

## Faste rutiner (gjelder hver venneoppdatering)
1. **Soak-test før hver venneoppdatering:** 5 spilldager for små endringer og 10 for store. Kravene er 0 krasj, 0 item-tap og en mspt innenfor budsjett.
2. **Full JUnit og GameTest** via den kanoniske QA-ruten, ikke bare i kopien.
3. **Før/etter-video** og en kort rapport til Tobias. Endringene må synes tydelig.
4. **Spør før serveroppdateringer:** Spør Tobias når vennene skal spille, og deploy kun etter hans eksplisitte «ja».
5. **Backup og rollback ved hver deploy:** verdensbackup, forrige jar i `mod-history` og sjekk av AutoModpack-hashen.
6. **Nye systemer bak config-bryter**, slik at de kan slås av uten ny jar.
7. **Lagringskompatibilitet:** Stabile ID-er, og hver migrering testes på en kopi av eierverdenen.
8. **Ytelsesbudsjett per fase:** mspt måles ved fasens mål for antall settlere.
9. **Kun store beslutninger går til Tobias** (nye systemer, økonomi, deploy), og alltid som flervalg.
10. **Bugs** rapporteres foreløpig direkte til Claude i chat og samles i én liste per fase.
