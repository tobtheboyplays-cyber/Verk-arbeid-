#!/usr/bin/env python3
"""Mutation regressions for SettlerActivity -> AnimationState -> clip wiring."""

import copy
import unittest

from tools import anim_check


def sources():
    with open(anim_check.SETTLER_ACTIVITY_SOURCE, encoding="utf-8") as source:
        activity = source.read()
    with open(anim_check.SETTLER_ENTITY_SOURCE, encoding="utf-8") as source:
        entity = source.read()
    with open(anim_check.SETTLER_MODEL_SOURCE, encoding="utf-8") as source:
        model = source.read()
    definitions = {}
    for animation_source in anim_check.ANIMATION_SOURCES:
        definitions.update(anim_check.parse_definitions(
            animation_source["path"]))
    return activity, entity, model, definitions


class ActivityAnimationReachabilityTest(unittest.TestCase):

    def checked(self, activity, entity, model, definitions,
                work_mapping=None):
        return anim_check.activity_animation_reachability_errors(
            activity, entity, model, definitions, work_mapping=work_mapping)

    def test_current_source_passes(self):
        self.assertEqual([], self.checked(*sources()))

    def test_missing_work_craft_mapping_is_caught(self):
        activity, entity, model, definitions = sources()
        mapping = copy.deepcopy(anim_check.ACTIVITY_ANIMATION_REACHABILITY)
        mapping.pop("WORK_CRAFT")
        errors = self.checked(activity, entity, model, definitions,
                              work_mapping=mapping)
        self.assertTrue(any("WORK_CRAFT" in error
                            and "no state/clip contract" in error
                            for error in errors), errors)

    def test_missing_work_craft_state_gate_is_caught(self):
        activity, entity, model, definitions = sources()
        entity = entity.replace(
            "craftState.animateWhen(activity == SettlerActivity.WORK_CRAFT",
            "craftState.animateWhen(activity == SettlerActivity.IDLE")
        errors = self.checked(activity, entity, model, definitions)
        self.assertTrue(any("WORK_CRAFT never starts craftState" in error
                            for error in errors), errors)

    def test_phase_offset_on_contact_clip_is_caught(self):
        activity, entity, model, definitions = sources()
        model = model.replace(
            "SettlerAnimations.LUMBER_CRAFT,\n                ageInTicks)",
            "SettlerAnimations.LUMBER_CRAFT,\n                ageInTicks + 7)")
        errors = self.checked(activity, entity, model, definitions)
        self.assertTrue(any("craftState contact clip" in error
                            for error in errors), errors)


if __name__ == "__main__":
    unittest.main()
