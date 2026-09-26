#!/usr/bin/env python3
"""Mutation regressions for MELEE's deterministic transition contract."""

import copy
import json
import unittest

from tools import anim_check


def source_definitions():
    definitions = {}
    for source in anim_check.ANIMATION_SOURCES:
        definitions.update(anim_check.parse_definitions(source["path"]))
    return definitions


def channel_frames(clip, bone, target):
    return next(frames for candidate_bone, candidate_target, frames
                in clip["channels"]
                if candidate_bone == bone and candidate_target == target)


def replace_key(clip, bone, target, at, vector):
    frames = channel_frames(clip, bone, target)
    index = next(index for index, frame in enumerate(frames)
                 if abs(frame[0] - at) <= 1.0e-6)
    frame = frames[index]
    frames[index] = (frame[0], frame[1], vector, frame[3])


def leg_channel(bone, peak):
    values = {
        0.00: 0.0, 0.05: peak / 3.0, 0.10: peak * 2.0 / 3.0,
        0.15: peak, 0.20: peak, 0.25: peak, 0.30: peak * 0.8,
        0.35: peak * 0.4, 0.40: -peak / 60.0, 0.45: 0.0,
        0.50: 0.0,
    }
    frames = [
        (at, "degreeVec", (value, 0.0, 0.0),
         "LINEAR" if at in (0.20, 0.25) else "CATMULLROM")
        for at, value in values.items()
    ]
    return (bone, "ROTATION", frames)


def zero_root_position_channel():
    frames = [
        (at, "posVec", (0.0, 0.0, 0.0),
         "LINEAR" if at in (0.20, 0.25) else "CATMULLROM")
        for at in (0.00, 0.05, 0.10, 0.15, 0.20, 0.25, 0.30,
                   0.35, 0.40, 0.45, 0.50)
    ]
    return ("root", "POSITION", frames)


class MeleeTransitionContractTest(unittest.TestCase):

    def checked(self, definitions):
        errors = []
        anim_check.check_melee_transition_contract(definitions, errors)
        return errors

    def test_current_source_passes(self):
        self.assertEqual([], self.checked(source_definitions()))

    def test_old_dual_leg_lunge_fails_world_space_plant(self):
        definitions = copy.deepcopy(source_definitions())
        clip = definitions["MELEE"]
        clip["channels"].extend([
            leg_channel("right_leg", 30.0),
            leg_channel("left_leg", -30.0),
        ])
        errors = self.checked(definitions)
        self.assertTrue(any("no consistent support foot" in error
                            for error in errors), errors)

    def test_one_stationary_support_foot_is_not_rejected(self):
        definitions = copy.deepcopy(source_definitions())
        clip = definitions["MELEE"]
        clip["channels"].extend([
            leg_channel("right_leg", 0.0),
            leg_channel("left_leg", -30.0),
        ])
        errors = self.checked(definitions)
        self.assertFalse(any("foot-plant contract" in error
                             for error in errors), errors)

    def test_nonzero_penultimate_key_fails_terminal_hold(self):
        definitions = copy.deepcopy(source_definitions())
        replace_key(definitions["MELEE"], "right_arm", "ROTATION",
                    0.45, (0.2, 0.0, 0.0))
        errors = self.checked(definitions)
        self.assertTrue(any("terminal hold must be at rest" in error
                            for error in errors), errors)

    def test_same_side_fake_overshoot_is_rejected(self):
        definitions = copy.deepcopy(source_definitions())
        replace_key(definitions["MELEE"], "right_arm", "ROTATION",
                    0.40, (0.5, 0.4, 0.15))
        errors = self.checked(definitions)
        self.assertTrue(any("recovery must cross rest" in error
                            for error in errors), errors)

    def test_zero_root_channel_is_allowed(self):
        definitions = copy.deepcopy(source_definitions())
        definitions["MELEE"]["channels"].append(zero_root_position_channel())
        self.assertEqual([], self.checked(definitions))

    def test_off_bearing_primary_target_mutation_fails(self):
        with open(anim_check.PROP_CONTRACT, encoding="utf-8") as contract_file:
            target = copy.deepcopy(json.load(contract_file)["meleeEvidenceTarget"])
        target["centerModelPixels"][0] = 4.0
        errors = []
        anim_check.check_melee_evidence_target_contract(target, errors)
        self.assertTrue(any("off primary attack bearing" in error
                            for error in errors), errors)


class MeleeRuntimeCompositionMutationTest(unittest.TestCase):

    @staticmethod
    def sources():
        with open(anim_check.SETTLER_ENTITY_SOURCE, encoding="utf-8") as source_file:
            entity = source_file.read()
        with open(anim_check.SETTLER_MODEL_SOURCE, encoding="utf-8") as source_file:
            model = source_file.read()
        return entity, model

    @staticmethod
    def checked(entity, model):
        errors = []
        anim_check.check_melee_runtime_composition_source(entity, model, errors)
        return errors

    def test_current_runtime_composition_passes(self):
        entity, model = self.sources()
        self.assertEqual([], self.checked(entity, model))

    def test_walk_gait_arm_contamination_mutation_fails(self):
        entity, model = self.sources()
        branch_start = model.index("else if (entity.patrolState.isStarted()")
        branch_end = model.index("else if (entity.carryState.isStarted()",
                                 branch_start)
        branch = model[branch_start:branch_end]
        self.assertIn("rightArm.resetPose();", branch)
        mutated_branch = branch.replace("rightArm.resetPose();", "", 1)
        mutated = model[:branch_start] + mutated_branch + model[branch_end:]
        errors = self.checked(entity, mutated)
        self.assertTrue(any("gait-arm contamination" in error
                            and "rightArm" in error for error in errors), errors)

    def test_guard_combat_martial_base_mutation_fails(self):
        entity, model = self.sources()
        patrol_start = entity.index("patrolState.animateWhen")
        guard_start = entity.index("profession == Profession.GUARD", patrol_start)
        archer_start = entity.index("profession == Profession.ARCHER", guard_start)
        guard_slice = entity[guard_start:archer_start]
        needle = "|| activity == SettlerActivity.COMBAT"
        self.assertIn(needle, guard_slice)
        mutated_slice = guard_slice.replace(needle, "", 1)
        mutated = entity[:guard_start] + mutated_slice + entity[archer_start:]
        errors = self.checked(mutated, model)
        self.assertTrue(any("PATROLLING and COMBAT" in error
                            for error in errors), errors)


if __name__ == "__main__":
    unittest.main()
