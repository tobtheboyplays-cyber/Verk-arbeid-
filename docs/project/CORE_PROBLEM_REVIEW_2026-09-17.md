# Core problem review — 17 September 2026

Scope: source and existing-evidence review requested after the AI-tool acceptance work. No gameplay source edits, new runtime tests, installation or release approval in this review. Prioritize a playable work -> transport -> food/economy -> defense -> saved aftermath loop.

## 1. Farmer continuation after reload — unresolved observed failure

The earlier controlled loop harvested, replanted and deposited wheat. Its exact-candidate reload preserved the actor and inventory and completed residual unloading, but did not produce the next harvest within 180 seconds. Crop age remained 1. A spectator-only random-ticking issue is a hypothesis, not a proved Farmer AI defect.

Evidence: quality-ledger opening entry; `qa/reports/artifacts/playtest/20260917T105827.522380713Z-650.4VKW6l/` and the failed `20260917T110603.888066987Z-651.UmDjvz` reload; scenarios in `Temp/resume-20260917/farmer-loop/`.

The existing checkpoint is bound to **bbf395c18f87f726af12**, while the current packaged candidate is **8e1d648beacc0e1bce81**. The checkpoint contract rejects changed candidates/source fingerprints. Do not simply resume/relabel the old checkpoint as current evidence.

Next acceptance: establish a comparable disposable fixture on the current candidate with an active nearby observer, verify an autonomous harvest/replant/deposit, save a fresh exact-candidate checkpoint, then reload that same candidate and prove the next complete cycle. Preserve the 20 TPS / 180-second observation, identity and item-conservation checks. No grants, forced crop ages, manual harvest, worker relocation or extended timeout to manufacture a pass. Assisted setup/growth acceleration remain separate from normal survival pacing. Diagnose the observer condition before changing worker code.

## 2. Merchant UI does not expose the controlling budget — confirmed source gap

`CoinMerchantScreen.renderLabels` displays `selected.getMaxUses() - selected.getUses()` and calls it "remaining". Actual sales are additionally limited by the merchant-wide purse. `GoldCoinTrades.remainingOwnedPurse` is read by tests but has no production client reader in the inspected source. The UI can therefore describe a row without exposing the shared limit controlling all rows and players.

Source: `hearthstead-neoforge/src/main/java/com/hearthstead/client/screen/CoinMerchantScreen.java:128` and `.../event/GoldCoinTrades.java:185`. The quickstart and new Founding text explain the rule but cannot substitute for live state.

Next acceptance: server-owned snapshot of remaining purse and next-visit eligibility, visible distinction between row stock and shared purse, an actionable sold-out reason, and updates after another player's sale. Test depletion, reopening and reconnect, then inspect the actual screen on the exact candidate. Do not invent a client-side purse calculation.

## 3. Early economy has little measured recovery margin — balance risk, not a proved exploit

V1 merchant capacity across visits at minutes 0/20/40/60 is at most 44 Coins. The design's estimated six-role/first-defense route costs about 41–43. This leaves only 1–3 Coins of theoretical merchant headroom by minute 60 if every visit is fully used; it is not a measured earned-income rate. Other legitimate income, visit arrival delays, real gathering time, purchases and losses need to be measured together.

Current source gives first raid victory 8 Coins and recurring victory 4. Theft grace is 12,000 eligible ticks (10 minutes at 20 TPS), followed by 36,000 eligible ticks cooldown after completion. Eligibility includes a player/container with at least one Coin; `theftLimit(1)` returns 1. Thus the inspected rules can take the last Coin from that source. This does not prove permanent bankruptcy, because earning/recovery may remain available, but it must be tested against mandatory purchases and the merchant wait.

Source: `RaidCoinRewards.java:14`, `GoblinTheftSavedData.java:13`, `GoblinTheftDirector.java:80` and `:132`, `GoblinThiefDemo.java:308`; numerical planning baseline in `ECONOMY_REBALANCE_2026-09-17.md`.

Next acceptance: normal-speed founding through the first worker and first defense, including a missed visitor and a theft near a mandatory purchase; record actual earned/spent/lost/recovered Coins and delay. Test event receipts across reload and two players. Change prices/rewards only from this evidence; neither easy windfalls nor an invisible compulsory waiting loop meets the owner contract.

## 4. Tavern and all six jobs together lack current end-to-end acceptance

Do not label the Tavern universally broken: the ledger records real two-meal service and conserved actor-specific transport on an older candidate. It also explicitly excludes native motion/audio approval. That evidence is narrower than a current, ordinary settlement running all six roles through a real raid and saved aftermath.

Next acceptance: actual work/contact/output, delivery, meal/seating/service, bed recovery, defense and resumed work on one identified candidate. Observe the relevant motion and audio instead of treating test receipts or a film file's existence as visual approval. Preserve approved Lumber motion. This is an integration/experience evidence gap, not proof that every job has a defect.

## 5. Shared play and release identity remain unapproved

The owner target is 3–4 simultaneous players. Existing isolated GameTests or idle clients do not prove contested trades, shared storage, bed ownership, disconnect/rejoin and save behavior during play. A native installation also needs exact loaded-JAR identity, not just the latest file on disk.

Verified historical test log: `qa/reports/artifacts/20260917T174344.340436338Z-759.U2OEDg/gametest.log:8372` says all 901 required tests passed, on runtime build **41a73ba5a67690c5292d**. The later handbook candidate **8e1d648beacc0e1bce81** packaged successfully; the unchanged package reused prior outputs. These are separate layers and do not certify this candidate's whole player experience.

Next acceptance: real shared-settlement actions and restart on the same candidate; then the unchanged full/full/gate and sealed native release route required by the QA contract. Do not spend repeated full suites to diagnose the small Farmer/UI issues above.

## Order and preservation

Fix the Farmer verification path first, then the merchant state display, then measure and adjust the economy/events together. Validate the complete job/Tavern/defense loop and shared play on the resulting stable candidate. No new profession, event chain or cosmetic overhaul should displace those steps.

The working tree currently contains 720 modified/untracked paths, including artifacts and existing work; that is not a count of 720 code defects. Preserve it. Back up the exact affected files before a substantive fix and retain all original checkpoint evidence.
