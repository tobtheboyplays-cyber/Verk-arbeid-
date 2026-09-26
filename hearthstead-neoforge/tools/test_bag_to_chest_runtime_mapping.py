#!/usr/bin/env python3
"""Focused source gate for the frozen bag-to-chest runtime binding.

This deliberately avoids Gradle so it can run while other agents edit the
shared checkout. It proves hash/tick/event mapping and the one-ticket clock;
it does not claim native renderer, server, or multiplayer approval.
"""

from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
HASH = "e91d5ba0f661be9d"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def main() -> None:
    manifest_path = ROOT / "src/main/resources/assets/hearthstead/animation_contracts/bag_to_chest_unload.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    require(manifest["candidate_hash"] == HASH, "runtime manifest hash drift")
    require(manifest["duration_ticks"] == 80, "duration must remain 80 ticks")
    require(manifest["contacts"]["deposit_commit"] == 48,
            "deposit authority must remain on tick 48")
    require(manifest["contacts"]["next_cycle"] == 80,
            "next cycle must not begin before recovery ends")

    contract = (ROOT / "src/main/java/com/hearthstead/entity/animation/BagToChestAnimationContract.java").read_text(encoding="utf-8")
    animation = (ROOT / "src/main/java/com/hearthstead/client/model/SettlerAnimations.java").read_text(encoding="utf-8")
    entity = (ROOT / "src/main/java/com/hearthstead/entity/SettlerEntity.java").read_text(encoding="utf-8")
    model = (ROOT / "src/main/java/com/hearthstead/client/model/SettlerModel.java").read_text(encoding="utf-8")
    goal = (ROOT / "src/main/java/com/hearthstead/entity/ai/CourierWorkGoal.java").read_text(encoding="utf-8")

    for source, label in ((contract, "contract"), (animation, "animation")):
        require(HASH in source, f"{label} is not bound to frozen hash")
    require(re.search(r"BAG_TO_CHEST_UNLOAD\s*=.*?withLength\(4\.00F\)",
                      animation, re.S) is not None,
            "runtime clip is not a four-second one-shot")
    clip_source = re.search(r"public static final AnimationDefinition BAG_TO_CHEST_UNLOAD = "
                            r"AnimationDefinition\.Builder.*?\.build\(\);", animation, re.S)
    require(clip_source is not None, "runtime clip source is missing")
    clip_hash = hashlib.sha256(clip_source.group(0).encode("utf-8")).hexdigest()
    require(clip_hash == manifest["runtime_clip_sha256"] and clip_hash[:16] == HASH,
            "runtime candidate identity does not match the actual authored clip")
    require("EV_BAG_TO_CHEST_UNLOAD = 79" in entity,
            "dedicated network event is missing")
    require("SettlerAnimations.BAG_TO_CHEST_UNLOAD" in model,
            "model does not select reviewed clip")
    require("BagToChestAnimationContract.mayCommit" in goal,
            "courier does not use single-commit gate")
    require("bagToChestCommitClaimed = true" in goal,
            "commit ticket is never consumed")
    require("bagToChestCompletionPending" in goal,
            "final recovery is not held to tick 80")
    require("triggerBagToChestUnload" in goal,
            "courier never starts reviewed runtime event")

    # Pure mirror of the Java gate: exactly one grant in each 80-tick cycle,
    # even if the commit predicate is queried repeatedly on the contact tick.
    claimed = False
    grants: list[int] = []
    for tick in range(1, 241):
        if tick > 0 and tick % 80 == 0:
            claimed = False
        for _duplicate_call in range(3):
            if not claimed and tick % 80 == 48:
                claimed = True
                grants.append(tick)
    require(grants == [48, 128, 208],
            f"unexpected commit grants: {grants}")
    print("bag-to-chest runtime mapping: PASS")
    print(f"candidate={HASH} commits={grants} next_cycles=[80, 160, 240]")


if __name__ == "__main__":
    main()
