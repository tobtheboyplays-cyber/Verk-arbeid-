# Plaque job-fit component contract

This is a preview-only target contract. It specifies desired player-facing
behaviour and does not claim that every listed effect is implemented today.

## Placement and authority

Every workplace Plaque exposes a reusable `Best fit for this job` component on
Requirements and Staff. It contains the two or three attributes that matter
most for that exact profession, the inspected worker's numeric values when a
worker exists, and one concrete role effect per row. With an open post, it shows
useful thresholds instead of inventing a candidate.

The component never adds a job `Hire` action. A workplace states its required
role and Emblem, and instructs the player to give the physical Emblem directly
to a settler. Housing Plaques do not use job-fit language; they retain only
Residents, Move In, and Assign Bed terminology.

## Row shape

Each row contains:

- attribute name;
- exact `value / 100`, or a clearly marked threshold for an empty post;
- named tier through the shared attribute colour contract;
- a short quantified job effect;
- hover detail with formula, next threshold, and whether the effect is current
  runtime or an intended gameplay target.

Positive numbers are green, disadvantages are red, thresholds are amber, and
exceptional attribute values are gold. Text and numbers always carry the
meaning; colour is supplemental.

## Lumberer live slice

The Lumber Camp is the first role whose simple copy is backed by the same
server calculators used by its work loop.

| Attribute | Concise row at the sample value | Runtime status |
| --- | --- | --- |
| Strength 72 | `2 axe contacts • 15 items/trip` | Current Lumberer runtime. Contacts are 4 below 25, 3 from 25–69 and 2 from 70. Count capacity is `floor(8 × trait carry) + floor(Strength / 10)`, capped at 20. Mass is not yet enforced. |
| Stamina 76 | `76% minimum pace • 19% load relief` | Current runtime. Zero-energy pace is `65% + 0.15% × Stamina`; load slowdown is relieved by up to 25% at 99 Stamina. There is no daily stop. |
| Wits 39 | `+19.5% learning speed` | Current runtime (`1 + 39 / 200`). It applies while the Lumberer learns Strength from completed work. |

The formulas are server-authoritative and unit-tested. Native UI and animation
still require exact-JAR client evidence before they move from Candidate to
Approved.
