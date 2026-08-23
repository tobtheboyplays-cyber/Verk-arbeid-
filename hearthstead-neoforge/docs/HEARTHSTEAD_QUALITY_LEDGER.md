# Hearthstead Quality Ledger

Living quality register per the continuous-completion directive. A requirement
is only PASS with concrete, reproducible evidence. Green-streak rule: two full
review rounds with zero changes required, else streak resets.

**Active phase: A1 — Foundation port** (NeoForge 1.21.1).
Identified from DESIGN.md roadmap: A1 = port of the verified 1.20.1 prototype
core + room detection + homes + modular settler visuals. Logistics (A2) and
raids (A3) are OUT OF SCOPE for this gate — but the ported prototype systems
(settlement, settlers, AI, professions v1, hearth UI) are shared foundations
and therefore IN scope.

**Product decision log:**
- PLAQUE SYSTEM REMOVED (user directive). Building registration = pure
  automatic room detection (interview R2 Q5). The plaque-based refinements
  from R13 Q51/R14 Q54 are superseded: detection is automatic; feedback is
  diegetic (particles/sound/HUD toast); building TYPE is inferred from key
  blocks. Recorded product gap: no manual type-override exists — if
  ambiguous rooms become a real problem, a non-plaque override UI (via
  Tingboka) is the sanctioned future path.
- Loop directive: no completion claims until 2 consecutive green rounds.

## Iteration log

### Iteration 1 (in progress)
| # | Requirement | Status | Evidence / gap |
|---|---|---|---|
| 1 | Clean build from scratch (NeoForge 1.21.1, Java 21, MDG 2.0.144) | PASS | `./gradlew build` → BUILD SUCCESSFUL, `build/libs/hearthstead-0.2.0.jar` |
| 2 | All prototype GameTests pass on 1.21.1 | PASS | `./gradlew runGameTestServer` → "All 9 required tests passed" (founding, profession, farmer, lumberer, guard, eat, alarm/flee, settler NBT round-trip, SavedData round-trip) |
| 3 | 1.21 datapack layout (loot_table/recipe/structure/tags/block singularized, recipe result {id}) | PASS | validator + gametests load structures; recipes rewritten |
| 4 | Networking ported to payloads (OpenSettlerScreenPayload) | PASS (code) / untested client | registered via RegisterPayloadHandlersEvent; client screen open needs client-side check |
| 5 | Capabilities: hearth item handler exposed | PASS (code) | RegisterCapabilitiesEvent registration; no automated test yet — add hopper/gametest check |
| 6 | Room detection engine (no plaque) | FAIL (not built yet) | A1c in progress |
| 7 | Homes: capacity from beds, bed claiming, sleep in own bed | FAIL (not built yet) | A1c |
| 8 | Furnishing quality score → morale | FAIL (not built yet) | A1c |
| 9 | Modular settler visuals | FAIL (not built yet) | A1d |
| 10 | Dedicated-server E2E on NeoForge (boot, found, persist) | FAIL (not run yet) | A1e |
| 11 | UI visual inspection | BLOCKED (headless env) | attempt xvfb+Mesa client; else code-based checks + manual screenshot checklist |
| 12 | Asset validator green on new layout | FAIL (not re-run) | update tools/validate_assets.py paths for 1.21 |
| 13 | No TODO/FIXME/placeholder in active scope | UNVERIFIED | sweep pending |
| 14 | Deprecation warnings triage | OPEN | non-removal deprecations remain; list & fix or justify |

Green streak: 0. Next: A1c room detection, A1d visuals, then full round re-run.
