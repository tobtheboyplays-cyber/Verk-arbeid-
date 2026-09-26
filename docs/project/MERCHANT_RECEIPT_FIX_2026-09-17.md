# Merchant callback receipt fix — 17 September 2026

Observed defect: Basic sale, Fine sale, then a replay of the earlier Basic callback
could debit the shared merchant purse and family demand again. The old receipt
remembered only the most recent offer. This was premature budget/demand consumption,
not evidence of duplicated physical player Coins.

The fix stores the highest completed use count for each offer under the existing
market revision. Older single-sale receipts migrate from persisted offer uses
before a menu or trader-service quote can accept a new payment. Migration preserves
spent budget, quotes and stock; it does not refill visitors.

The existing required owned-merchant GameTest now verifies non-adjacent callbacks,
save/load persistence, old receipt migration, and a genuine subsequent paid use.
No timeout or existing acceptance condition was relaxed. The total remains 901.

## Evidence

- Regression before product fix: `qa/reports/artifacts/20260917T202201.126847179Z-375.IlJDcD/`
  failed the new interleaved callback assertion. It also reported an independent
  Lumberer unload/reload timeout at the unchanged 900-tick limit.
- Package PASS: `qa/reports/artifacts/20260917T203317.526711431Z-379.pfvvvL/`.
- GameTest PASS: `qa/reports/artifacts/20260917T204416.733017823Z-374.Rx6Ua4/`;
  all 901 required tests, terminal controller exit 0. Runtime build identity:
  `0.2.0+g95425795c290.i8f463678625b6b7e3a46`.
- Exact JAR: `hearthstead-0.2.0-g95425795c290-i8f463678625b6b7e3a46.jar`.
- Before-edit dirty source backups: `Temp/merchant-receipt-review-20260917/before/`.

The unchanged Lumberer case passed in the confirming run, but its prior timeout
has not been causally explained. Keep that observation available if it recurs.
This is source/build/server-side automated evidence. No native install, UI/audio/
performance/co-op acceptance or release approval was performed. Merchant budget
visibility and the Farmer saved-world branch remain in CURRENT_TASK's queue.
