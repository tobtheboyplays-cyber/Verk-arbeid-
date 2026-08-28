# Masterplan: Spillbart og meningsfylt — med det vi HAR

*Eierens bestilling 19:50: «Lag en master plan for å gjøre det vi har mer
playable og gir mening. Alle blokkene fra survival og gir mening. Se hvordan
TekTopia og MineColonies gjør det. Og hvordan får fler settlers. Finn den
beste måten.» Innenfor frysen: dette er å få EKSISTERENDE systemer til å
lande hos en spiller, ikke nye systemer. Dommen tas av byggherren.*

## Hvordan ankrene løser det

**MineColonies:** ett startobjekt (Supply Camp, billig, én gang) gir Town
Hall → alle hytteblokker craftes av vanlige materialer i stigende tier →
borgere SPAWNER automatisk når sengekapasitet finnes — veksten er synlig i
Town Hall-UI som «housing 4/6». Spilleren vet alltid hva neste settler
koster: en seng til.

**TekTopia:** landsbyen ER arkitekturen din (vårt eget DNA). Nye villagers
kommer som NOMADER som fysisk VANDRER INN når landsbyen har ledige senger —
du SER rekrutteringen skje som en hendelse i verden, ikke som et tall.
Yrker tildeles med gjenstander du allerede har. Nesten ingen spesialblokker.

**Fellesnevneren:** (1) første bygning koster tre-/stein-tier, aldri
jern; (2) vekst er SYNLIG og prisen på neste settler er alltid lesbar;
(3) spesialblokker er få og billige — verdien ligger i det du bygger selv.

## Hvor vi bryter med dette i dag — og planen

### A. Første bygning krever gruvedrift (BRUDD, viktigst)
Plaketten koster 5 jern + 1 kobber. Hearth → 3 settlere fungerer på
minutt 5, men FØRSTE bygning ligger 20+ minutter med caving unna — mot
ankrenes tre/stein-tier. Eieren traff dette selv i går (måtte grave etter
jern i sin første økt).

**Forslag (eierbeslutning, én av):**
1. **Kobber-plakett** — 5 kobber + 1 jern + 3 planker. Kobber ligger i
   dagen overalt i 1.21; jern-innslaget beholder følelsen av verdi.
   Minst endring, anbefalt.
2. Tre-tier førsteplakett (planker+jern-nugget) som oppgraderes.
3. Hearthen gir 2 blanke plaketter som startpakke (MineColonies-modellen).

### B. Rekruttering er usynlig (BRUDD)
I dag: traveler-systemet finnes (tavern, foodCache ≥ 8, morale ≥ 60,
kapasitet 3+senger) men skjer i det stille — eieren fikk settler nr. 4 uten
å vite hvorfor, og vet ikke hva nr. 5 krever.

**Plan (ren kommunikasjon, ingen ny mekanikk):**
- Hearth-skjermens Settlement-fane får en «Neste settler»-sjekkliste av
  data som allerede finnes: ✔ senger (4/5) · ✔ mat (12/8) · ✘ moral
  (54/60) — MineColonies-lesbarhet på vårt eksisterende system.
- Traveleren som kommer skal SES: den vandrer allerede fysisk inn
  (TekTopia-arven vår) — legg en chat-linje + lydsignal når en traveler
  ankommer tavernaen, samme idiom som plakett-skanneren fikk i kveld.
- Handbook-kapittelet «Recruiting» skriver sjekklisten eksplisitt.

### C. Survival-vei til alle gjenstander (REVISJON, håndhevbar)
Nytt valideringsskript (samme familie som check_fixture_plaques):
**hver registrert blokk/item skal ha (a) en oppskrift, (b) en produsent i
Production, eller (c) en dokumentert unntaksliste med begrunnelse.**
Kjent status: hearth ✔ (shapeless nå), plakett ✔ (kost = A), byggeplaner ✔,
handbook ✔, kjedevarer produseres ✔. Skriptet fanger fremtidige brudd —
det er slik «alle blokkene fra survival» blir en invariant og ikke et løfte.

### D. «Gir mening» — hver bygning svarer på «hva får jeg?»
Plakettens skjerm viser i dag KRAV, men aldri GEVINST. Én linje per
bygningstype (data finnes i Employment/Production): «Lumber Camp — ansett
en tømmerhogger; stokker havner i kista». Vises i plakett-UI når planen
settes inn, og i Handbook. Ingen ny mekanikk — bare at spillet sier hva
det allerede gjør.

### E. Læringssløyfen valideres av nybegynneren
Red team-agenten (ekstrem noob, sløret regel: får ikke bruke kunnskap
spillet ikke gir) spiller første økt etter hver av A–D og leverer
quit-øyeblikk-listen. Målet: null quit-øyeblikk i de ti første minuttene.

## Rekkefølge og kost

| # | tiltak | kost | krever suite |
|---|---|---|---|
| 1 | B: «Neste settler»-sjekkliste + ankomst-signal | liten | ja (UI+lyd) |
| 2 | A: plakettkost (eieren velger variant) | én oppskrift | ja |
| 3 | D: gevinstlinje per bygning | liten | ja |
| 4 | C: obtainability-skriptet | middels | nei (validator) |
| 5 | E: nybegynner-økt | maskin-tid | nei |

Alt over er frys-kompatibelt: ingen nye systemer, kun synliggjøring,
kostjustering og håndheving av det som finnes. Arkitekt-/plankjøpssystemet
(TekTopia-design, eierens ønske fra filmen) står PARKERT som første slice
etter frysen og er bevisst IKKE i denne planen.
