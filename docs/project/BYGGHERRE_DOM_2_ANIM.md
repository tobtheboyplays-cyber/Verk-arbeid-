# Byggherre-dom #2 — CHOP og PICKUP_STOW, keyframe-nivå. DOM: AVVIST

Dømt analytisk mot committed HEAD (arbeidstreet var under redigering).
Begge defektene er matematisk beviselige fra Java-tallene.

## CHOP — «øksa til siden ser fortsatt jævlig ut» (andre runde)
1. **(alv 1)** Torso og arm topper på SAMME tick (begge 0.50→0.55: torso y
   60°/tick, arm y 158°/tick). Skill §1.5 krever torso-ledelse 2-3 ticks.
   Dette er hele grunnen til at det leser som arm over stiv kropp.
2. **(alv 1)** Null overshoot — recovery går monotont rett til hvile på arm,
   torso og root. Nøyaktig «the canonical bad example» doktrinen navngir.
3. **(alv 2)** torso z = 0 i hver keyframe: ren yaw, ingen roll/vekt.
4. **(alv 2)** Ingen akselerasjonsrampe: 2.33°/tick → 158°/tick i ett steg.
5. **(alv 2)** Beina har 0° delta i hele klippet (eier ba om «steps into it»).
6. **(alv 3)** Impact-hold driver 6°, kravet er ≤3°.
Aksentkontrakt t=0.55 / tick 11 av 20 er LÅST.

## PICKUP_STOW — «den må fikses på»
1. **(alv 1)** INGEN kanal passerer 30°/tick noe sted. Raskeste (16°/tick)
   er returen til idle; grabbet er 5.25, stow-rullen 11.5-12.
2. **(alv 1)** CATMULLROM gjennom begge story-beats — smører ut nøyaktig de
   rykkene som skal bære lesningen. §3 sjekk 7: LINEAR på BEGGE nøkler i
   BEGGE par.
3. **(alv 2)** Fartsprofilen er invertert: returen er raskere enn aksenten.
4. **(alv 2)** Null overshoot på retur (x/y/z lander eksakt på hvile).
5. **(alv 2)** Hold er 2 ticks (16.7%), tuck-holdet driver 4°.

## Aksepttest (begge)
§3-sjekklisten på de nye tallene + rendring/film. Kritikerens ord:
«Hvis svingen fortsatt leser som ett synkronisert rykk, er den avvist
uansett hva tallene sier.»

## Neste ambisjon
La PICKUP_STOW variere håndkraft og hastighet etter HVA som plukkes opp —
samme universelle klipp, forskjellig lesning etter kontekst. Verken
MineColonies eller TekTopia gjør det.
