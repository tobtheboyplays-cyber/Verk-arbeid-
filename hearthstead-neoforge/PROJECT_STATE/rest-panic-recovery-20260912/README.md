# Rest and Mayor/Panic live diagnosis — staged only

Status: no active source edits, no live mutation, no QA run.

## Wilmot: confirmed RestAtNightGoal deadlock

Live facts supplied:
- energy 0; resting goal stationary at \`115.188,72.5,-124.399\`
- saved claim is bed \`115,76,-127\`, with no physical upstairs route
- the old empty SourceBag receipt is unrelated to this goal's MOVE ownership

The production path confirms the deadlock:
1. \`canUse\` stays true while energy is below 12, and \`canContinueToUse\` stays true below 60.
2. A still-valid \`BedBlock\` skips the broken-bed release branch.
3. \`path()\` calls navigation without reading success.
4. The goal repeats that failed bed route every 60 ticks. It never enters the no-bed Hearth-rest branch, so energy remains zero.

## Staged fix

\`rest-at-night-unreachable-bed.patch\` changes only \`RestAtNightGoal\` plus one focused GameTest.

- It asks navigation for the same feet target used by the old direct \`moveTo\`.
- If a claimed, still-intact bed has no reachable path, it releases that exact bed claim, records \`rest_bed_unreachable\`, waits 600 ticks before another automatic bed claim, and follows the existing Hearth rough-rest route.
- It does not remove a bed block, alter any inventory, create a home, move a settler, or change valid/reachable bed sleeping.
- The regression seals every physical approach to a real bed block. A zero-energy settler must release the claim and enter existing \`RESTING\` at the Hearth, without sleeping.

The patch passes \`git apply --check\` against the current source snapshot. It needs normal build/GameTest verification after the current freeze.

## Eira: no production change is justified

A Mayor having no ordinary employment is intentional: Mayor authority is outside building worker lists. \`panic_home_unavailable\` is a bounded historic route failure: \`SettlerPanicGoal.failHome\` stops navigation and ends its trip; its \`stop()\` yields MOVE. It does not persist an active panic lock.

At energy 75 and an idle work posting, Eira is eligible for normal post/work scheduling. The live note alone does not prove a current Mayor or panic recovery bug. A goal-selector snapshot and current \`panicShelterUntil\` are needed before changing Mayor behavior.

