# Mayor Courier visual projection — staged only

This patch is intentionally outside src while the source freeze is active.

It adds MAYOR only to the existing Courier cargo projection while carryState
or the server-authored bag-transfer presentation is active. It does not change
Mayor profession, labels, idle animation, Lumber behavior, authority, cargo,
or transfer ownership.

The patch covers the five Courier/Trader model gates:
1. laden gait compression,
2. carry-grip clip,
3. articulated sack hand grip,
4. detached transfer ownership,
5. attached/ground sack, load lean and sizing.

BagTransferPresentation.ownsBodyPose must also recognize MAYOR during an
active authenticated transfer; otherwise the Mayor can carry but cannot own the
existing put-down/lift body and ground-sack projection.

Apply after the freeze:
git apply PROJECT_STATE/mayor-courier-visual-20260912/mayor-courier-visual.patch

Separate functional note: TidyWarehouseGoal currently requires
Profession.COURIER and Employment.employerOf. It should use
Employment.courierWorkplace(...) if the product decision is that Mayor
assistance includes idle warehouse tidying; it is deliberately not included
here because this staged patch is only the requested visual carry/transfer fix.
