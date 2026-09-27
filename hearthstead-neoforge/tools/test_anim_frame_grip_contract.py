#!/usr/bin/env python3
"""Mutation regressions for the two-hand timber-frame posture exception."""

import copy
import json
import unittest

from tools import anim_check
from tools import anim_preview


def settler_source():
    return next(source for source in anim_check.ANIMATION_SOURCES
                if source["label"] == "settler")


def prop_contract():
    with open(anim_check.PROP_CONTRACT, encoding="utf-8") as contract_file:
        return json.load(contract_file)


class TwoHandFrameGripPostureContractTest(unittest.TestCase):

    def resolved(self, clip, contract, source=None):
        errors = []
        bounds = anim_check.resolve_arm_posture_bounds(
            source or settler_source(), clip, contract, errors)
        return bounds, errors

    def test_exact_suppressed_two_hand_contract_gets_reviewed_bounds(self):
        expected = {
            "left_arm": (-50.0, -30.0),
            "right_arm": (-50.0, -30.0),
        }
        for clip in ("HAUL_LOG", "HAUL_LOG_HEAVY"):
            bounds, errors = self.resolved(clip, prop_contract())
            self.assertEqual([], errors)
            self.assertEqual(expected, bounds)

    def test_missing_item_suppression_falls_back_to_crown_safe_bounds(self):
        contract = prop_contract()
        contract["suppressedHeldItemPresentationClips"].remove("HAUL_LOG")
        bounds, errors = self.resolved("HAUL_LOG", contract)
        self.assertTrue(any("rejected two-hand" in error for error in errors),
                        errors)
        self.assertEqual({
            "left_arm": (-35.0, 15.0),
            "right_arm": (-35.0, 15.0),
        }, bounds)

    def test_exception_cannot_be_copied_to_another_clip(self):
        contract = prop_contract()
        contract["twoHandFrameGripPostureContracts"]["CHOP"] = copy.deepcopy(
            contract["twoHandFrameGripPostureContracts"]["HAUL_LOG"])
        bounds, errors = self.resolved("CHOP", contract)
        self.assertTrue(any("rejected two-hand" in error for error in errors),
                        errors)
        self.assertEqual({}, bounds)

    def test_exception_rejects_any_extra_bone_or_wider_range(self):
        for mutation in ("bone", "range"):
            contract = prop_contract()
            entry = contract["twoHandFrameGripPostureContracts"]["HAUL_LOG"]
            if mutation == "bone":
                entry["exactOverlayBones"].append("head")
            else:
                entry["safeArmXBoundsDegrees"]["right_arm"] = [-90, 15]
            bounds, errors = self.resolved("HAUL_LOG", contract)
            self.assertTrue(any("rejected two-hand" in error for error in errors),
                            errors)
            self.assertEqual({
                "left_arm": (-35.0, 15.0),
                "right_arm": (-35.0, 15.0),
            }, bounds)


class EndsInPoseAllowlistContractTest(unittest.TestCase):

    def validation_errors(self, source):
        errors = []
        anim_check.check_ends_in_pose_allowlist_contract(source, errors)
        return errors

    def test_work_container_down_is_the_exact_reviewed_handoff(self):
        source = copy.deepcopy(settler_source())
        self.assertIn("WORK_CONTAINER_DOWN", source["ends_in_pose_allowlist"])
        self.assertEqual([], self.validation_errors(source))

    def test_arbitrary_one_shot_cannot_gain_the_exemption(self):
        source = copy.deepcopy(settler_source())
        source["ends_in_pose_allowlist"].add("ARBITRARY_ONE_SHOT")
        errors = self.validation_errors(source)
        self.assertEqual(1, len(errors), errors)
        self.assertIn("ARBITRARY_ONE_SHOT", errors[0])
        self.assertIn("reviewed closed contract", errors[0])

    def test_validated_reader_returns_only_the_closed_reviewed_set(self):
        self.assertEqual(
            anim_check.EXPECTED_ENDS_IN_POSE_ALLOWLISTS["settler"],
            anim_check.validated_ends_in_pose_allowlist("settler"))

    def test_terminal_release_is_clean_only_for_a_reviewed_handoff(self):
        clip = anim_check.parse_definitions(settler_source()["path"])[
            "WORK_CONTAINER_DOWN"]
        reviewed = anim_check.validated_ends_in_pose_allowlist("settler")
        self.assertEqual([], anim_preview.analyse(
            "WORK_CONTAINER_DOWN", clip, reviewed))
        unreviewed_notes = anim_preview.analyse(
            "WORK_CONTAINER_DOWN", clip, frozenset())
        self.assertTrue(any(kind == "pop" for kind, _ in unreviewed_notes),
                        unreviewed_notes)

    def test_copying_the_same_motion_to_an_arbitrary_clip_is_not_clean(self):
        clip = anim_check.parse_definitions(settler_source()["path"])[
            "WORK_CONTAINER_DOWN"]
        reviewed = anim_check.validated_ends_in_pose_allowlist("settler")
        notes = anim_preview.analyse("ARBITRARY_ONE_SHOT", clip, reviewed)
        self.assertTrue(any(kind == "pop" for kind, _ in notes), notes)


if __name__ == "__main__":
    unittest.main()
