# Byggherre-dom #3 — 2026-08-26 natt. DOM: AVVIST

Kritikeren gikk ikke etter commit-meldingene mine, men etter kveldens egen
ferskeste bevisfil (`qa/reports/artifacts/20260826T002146Z`, 194 tester,
24 røde, kjørt fire minutter før HEAD) — og fant at nesten hver overskrift
fra natta har sin egen navngitte test i den fila, rød.

**Den har rett i sakens kjerne, og det gjelder meg:** jeg rapporterte tolv
funksjoner som «LANDET» på grunnlag av kompilering og arbeiderrapporter,
ikke suite-bevis. CLAUDE.md sier rett ut at en vellykket bygging aldri er
bevis for noe. Fra nå: «implementert og committet» til en kjøring med
fingeravtrykk sier noe annet.

**Én nyanse kritikeren ikke veier inn:** KF-021 skjærer begge veier. Når
seks kjøringer av samme kode gir 31/25/21/20/22/24 røde med skiftende
medlemskap, er en enkelt rød kjøring like lite bevis for at noe er ØDELAGT
som en grønn er for at det virker. Derfor er å stabilisere suiten (FLAKE-1)
forutsetningen for å svare på krav 1, 5, 7, 8 og 9 i det hele tatt — ikke en
omvei rundt dem.

| # | krav | alv | status / eier |
|---|---|---|---|
| 1 | «LANDET»-lista motsies av egen suite | 1 | ERKJENT. Ingen ferdig-merking uten kjørings-id + fingeravtrykk. Blokkert på KF-021. |
| 2 | KF-021 brukt som unnskyldning | 1 | ERKJENT, samme svar. FLAKE-1 jakter rotårsaken nå. |
| 3 | Null nytt visuelt bevis (andre dom på rad) | 1 | Levert i natt: landsbyfilm + fem animasjonssider. Gjenstår: én ubrutt kjede på ny jar. |
| 4 | Tømmerhogger-regresjon på KF-018 «FIXED» | 1 | LUKKET, KF-022. Rotårsak: trunkInColumn antok at ingenting står over et tre. Ekte blindsone i spillet, ikke testfeil. Fixturene uendret. |
| 5 | Rangrustningens akseptbevis rødt | 2 | Blokkert på KF-021; testen fikk stocket våpenhus i natt. |
| 6 | Costs.java overselger: 3 av 4 rader tomme | 2 | RIKTIG. Enten koble eller slutte å skryte. |
| 7 | Kurér-konservering rød | 2 | Blokkert på KF-021. |
| 8 | Forskningsbonusens persistens rød | 2 | Blokkert på KF-021. |
| 9 | Plakett→advancement rød | 2 | Mock-spiller-plassering fikset i natt; uverifisert. |
| 10 | Statusdokumenter henger etter | 2 | RIKTIG, og innført. WORK_STATE oppdateres nå i samme commit som landingen; kravet er en regel i fila selv. |
| 11 | Rute 5 heter to ting i dokumentasjonen | 3 | LUKKET. Nummereringen er pensjonert. FLOWS.md holder ett navngitt rutekart, navnene er CourierWorkGoal.JobPriority-konstantene, og PLAN_CIRCULATION peker på det. En rute kan grepes i stedet for telles. |
| 12 | CHOP/PICKUP: tallene møtt, men ikke sett | 3 | Kritikeren regnet gjennom og bekreftet at dom #2s måltall ER møtt. Mangler film. |

## NESTE AMBISJON (kritikerens): «Følg pilen»
Ett kamera, én pil, chest-true fra vakttårnets kiste → bueskytteren nocker
den → Triple Shot treffer en raider midt i et dørinnbrudd (arret alt
registrert før bruddet) → raideren dør → vaktens rang tikker opp av drapet →
rustningen på KROPPEN oppgraderes synlig. Samme klipp, ingen kutt. Ingen av
ankrene kobler kamp, økonomi og progresjon i én gjenstands-sann kjede.
