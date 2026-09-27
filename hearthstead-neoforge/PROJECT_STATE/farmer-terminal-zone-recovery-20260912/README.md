# Terminal Farm harvest collection after zone reconfirmation — staged only

A physical, transit-stamped crop can be harvested under Farm zone revision N and remain in the world while the same valid Farmhouse is reconfirmed to revision N+1. The active collection gate required `action.zone().equals(expectedZone)`, so the Farmer could not pick the exact owned crop; the later same-Farmhouse deposit recovery could never be reached.

The staged patch permits that mismatch only when all of the following remain true:

- the current `expectedZone` is the live valid zone of the same Farmhouse and worker;
- the item transit already names the same settlement, Farmhouse, worker, and dimension;
- the action is a terminal `FARM_HARVEST`, its exact source was resolved, and its persisted old zone is a structurally valid Farm zone for that same Farmhouse and contains the source;
- both source and item position are loaded; the existing bounded current-zone recovery envelope and produced-count check still pass.

It cannot author planting/new harvesting, admit a nonterminal action, transfer foreign transit, use a replaced employer, or expand Lumber behavior.

The existing `terminalHarvestSurvivesSameFarmhouseZoneReconfirmation` GameTest gains an assertion that the exact carrot passes collection authority immediately after the revision change, before its existing one-time physical deposit assertion.

`git apply --check` passed. No source was changed and no tests were run.
