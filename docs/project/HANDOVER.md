# HANDOVER — Hearthstead-koordinator (2026-08-26 ~20:30)

Gi dette til en ny chat. Alt annet den trenger står i repoet.

## Hvem du er
Koordinator for Hearthstead (NeoForge 1.21.1, levende-landsby-mod) i
/home/user/Verk-arbeid-, branch `claude/hearthstead-settlement-mod-vbdb9n`,
draft PR #1. Du dirigerer parallelle arbeidere med strengt disjunkt
fileierskap; KUN koordinatoren committer/pusher. Les CLAUDE.md først —
QA-reglene der er absolutte (all testkjøring via tools/hearthstead-qa,
aldri sviekk dommeren, aldri suite mens noen redigerer).

## Eierens stående ordrer (i kraft)
1. **FRYS:** ingen nye systemer. Alt som finnes skal bli VELDIG bra.
2. Premium er standarden; han vil SE ting (film/skjermbilder), ikke løfter.
3. Eieren spiller live og gir tilbakemelding løpende, ofte på norsk, ofte
   midt i turen. Alt han nevner er arbeidsliste.
4. Video-tilbakemeldingssløyfe: han filmer med stemme → hans Google Drive
   (connector koblet, mappe «Siviliasjon mods»; fil må link-deles) →
   `python3 qa/scripts/analyze_video.py ingest <fil>` (~61 sek: transkript
   m/tidsstempler + kontaktark + stillbilder). LES TRANSKRIPTET FØRST — det
   ER tilbakemeldingen; skjermbildene er bevis. Diagnoser fra skjermbilde,
   ALDRI fra logg-teori (tre feildiagnoser i dag ble alle løst av ett bilde).
5. Jar-levering: bygg → send jar-en i chatten (SendUserFile) → eieren
   dobbelklikker `hearthstead-oppdater.bat` (har den; flytter fra
   Nedlastinger til CurseForge-instansens mods og rydder gamle).

## Ekspertbenken (registrert i .claude/agents/, spawn via Agent-tool)
- **animasjonsmester** — 17 år; eier alle keyframes; MÅ rendre eget arbeid.
- **teksturmester** — 17 år; texturer via generatorene + all UI; MÅ
  forhåndsvise. ⚠ EN INSTANS KJØRER MULIGENS ENNÅ på settler-arket
  («veldig stygg, ingen tydelige buffs») — sjekk git status for
  SettlerScreen/HsUi/ui-specs-endringer før du rører de filene.
- **nybegynneren** — red-team noob m/slørregel; finner quit-øyeblikk.
- **byggherren** — eier-kritiker, 17 år, evidens-først; dømmer alle planer
  og integrasjoner. Fire dommer levert; les BYGGHERRE_DOM-filene.
- Vanlige arbeidere: sonnet-builder m/eksplisitt fileierskap i prompten.

## Tilstand akkurat nå
- Alt committet OG pushet t.o.m. `docs/project/ANKER_ANALYSE.md`-commiten,
  UNNTATT teksturmesterens in-flight settler-ark-filer (ukommittert).
- Suite: 266+ GameTests var grønne før kveldens bølge; ETTER bølgen er
  gametest IKKE kjørt (ventet på teksturmesteren). NESTE HANDLING:
  når treet er stille → `tools/hearthstead-qa gametest` → fiks rødt →
  commit → bygg jar → send til eieren → `full` x2 + `gate` i bakgrunnen
  (green_streak ≥ 2 kreves; en full kjøring SLETTER qa/reports/BLOCKED
  ved start — gjenopprett/oppdater den ærlig hvis kjøringene ryker).
- Kjent flakete: playtest-suitens input-steg (plakettinnsetting) — 4
  grønne/3 røde i dag; historikk og neste mistenkte står i BLOCKED-teksten
  i git-historikken (commit 077a43d).

## Kveldens leveranser (alt pushet)
Lyd: chop = ekte smell. Navneskilt: designet plate, 24m fade. Settler-bag
i arket. Tre-klaim (to hoggere deler aldri tre). Romskanner: «No room
found» navngir lekkasje-cellen (ekte scanner-bug fikset: nærmeste-celle,
ikke sprawl). Archer: OUT_OF_AMMO-aktivitet + melding (målvalget var aldri
feil — pilkassen var tom og stum). CHOP: sidesving m/vekt (topp-ned-bevis).
WALK: bob var baklengs, 4-pose-syklus nå. Hearth-skjerm: Seat-panelet over
labels + scrim. Kobber-plakett (5 cu+1 fe+3 planker). Gevinstlinje per
bygning (33, begge språk) + ærlig «Ikke i drift»-merke på SCHOOL/MARKET/
WELL/INFIRMARY. Rekrutteringsstripe viser blokkeringsgrunn m/tall.
Videoverktøyet: 13 min → 61 sek.

## Nøkkeldokumenter (les ved behov, ikke alle)
- `docs/project/MASTERPLAN_PLAYABLE.md` + byggherre-dom #4 (i transkript/
  BYGGHERRE_DOM-fil): batch 2 = ferdig (B), C=obtainability-skript GJENSTÅR,
  E=nybegynner-økt GJENSTÅR (stoppeklokke hearth→første grønne plakett).
- `docs/project/ANKER_ANALYSE.md`: TekTopias kropp + MineColonies' hjerne;
  dødssyndene som forbud. Styrer prioritering.
- `docs/project/PLAN_ETTER_DEMO.md`: parkert bak frysen (arkitekt-system,
  sult-konsekvens, ALE-forbruker, Brønnen, forskning på courier-rute).
- `KNOWN_ISSUES.md` (eiervendt), `docs/project/KNOWN_FAILURES.md` (KF-er),
  `qa/PROTOCOL.md` + `qa/QUICKSTART.md` (QA-loven).

## Maskinregler (lært på den harde måten)
15 GB RAM: ALDRI suite + Minecraft-klient samtidig (OOM-drap, exit 137).
Én suite om gangen, aldri under redigering. Bruk absolutte stier (cwd
driftes). Bakgrunnskjøringer for alt >5 min. Eksperter kjører ALDRI
full/gametest/playtest — kun `quick` og offline-verktøyene sine.
