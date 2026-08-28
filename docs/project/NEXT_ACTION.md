# Next action

One action. After a restart or a compaction, start exactly here — do not redo
proven work, and do not start a different slice because it looks easier.

## Do this

Les `.claude/WORK_STATE.md` (2026-08-26 ~18:50) — den er sannheten om hva
som er aktivt. Kort: TAVERN-GATE-arbeideren og overhaul-masterplan-workflowen
kjører; når de lander er koordinatorens løkke:

1. Review TAVERN-GATE mot byggherrens kravliste (PLAN_TAVERN_GATE.md, 1-9)
   → `tools/hearthstead-qa gametest` → fiks rødt → commit/push → jar til
   eieren (SendUserFile; han har hearthstead-oppdater.bat).
2. Skriv `docs/project/OVERHAUL_PROGRAM.md` fra byggherre-dommen over
   masterplanen → start de tre første slicene som parallelle arbeidere med
   disjunkt fileierskap (lang-filene + HsUi er SEAM: sekvenser dem).
3. Settler-arket (793e05b, halvferdig) ferdigstilles i UI-strømmen med
   offline-preview som bevis.
4. Ved integrasjonsslutt: `full` x2 + `gate` (green_streak ≥ 2), deretter
   film/skjermbilder til eieren.

Historisk HARNESS-1-innhold som sto her før: utført/foreldet — se
git-historikken til denne filen om detaljene trengs.
