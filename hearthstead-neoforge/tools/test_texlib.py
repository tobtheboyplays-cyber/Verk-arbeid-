#!/usr/bin/env python3
"""Generator-local regression tests for the B1 texture foundation."""

from __future__ import annotations

import io
import random
import statistics
import tempfile
import unittest
from pathlib import Path, PurePosixPath, PureWindowsPath

from PIL import Image

import texlib
import validate_assets
from validate_assets import (
    PIPELINE_GENERATORS,
    STONE_PROFILES,
    STONE_SEAM_LIMIT,
    STONE_SEED_MATRIX,
    _configure_windows_console,
    _entity_texture_key_and_folder,
    _stone_edge_contract_violations,
    _stone_seam_ratios,
    entity_atlas_sizes_from_models,
    palette_foundation_audit,
)


class PaletteFoundationTests(unittest.TestCase):

    def test_validator_gates_are_green(self):
        failures = palette_foundation_audit()
        self.assertEqual({rule: errors for rule, errors in failures.items() if errors}, {})

    def test_make_ramp_rejects_out_of_contract_tokens(self):
        good = dict(hue=30, saturation=60, value=24, hue_drift=12,
                    value_steps=(8, 8, 8, 8), family="warm")
        non_numbers = (True, "30", None, float("nan"), float("inf"),
                       float("-inf"))
        invalid_scalars = {
            "hue": (*non_numbers, -0.01, 360, 361),
            "saturation": (*non_numbers, 0, -1, 90.01),
            "value": (*non_numbers, 11.99, 65),
            "hue_drift": (*non_numbers, 0, 9.99, 15.01),
            "family": (True, 1, None, "", "neon", float("nan")),
        }
        for field, candidates in invalid_scalars.items():
            for candidate in candidates:
                bad = dict(good, **{field: candidate})
                with self.subTest(field=field, candidate=repr(candidate)), \
                        self.assertRaises(ValueError):
                    texlib.make_ramp(**bad)

        invalid_steps = (
            True, "8,8,8,8", None, 8, float("nan"), float("inf"),
            (), (8, 8, 8), (8, 8, 8, 8, 8),
            (7, 8, 8, 9), (8, 8, 8, 12),
            (8, 8, 8, True), (8, 8, 8, "8"), (8, 8, 8, None),
            (8, 8, 8, float("nan")), (8, 8, 8, float("inf")),
            {8, 9, 10, 11}, {8: 1, 9: 1, 10: 1, 11: 1},
        )
        for candidate in invalid_steps:
            with self.subTest(value_steps=repr(candidate)), self.assertRaises(ValueError):
                texlib.make_ramp(**dict(good, value_steps=candidate))

        invalid_curves = (
            True, "0.78,1,0.93,0.72,0.55", 1, float("nan"), float("inf"),
            (), (0.78, 1.0, 0.93, 0.72),
            (0.78, 1.0, 0.93, 0.72, 0.55, 0.40),
            (0.78, True, 0.93, 0.72, 0.55),
            (0.78, "1", 0.93, 0.72, 0.55),
            (0.78, None, 0.93, 0.72, 0.55),
            (0.78, 1.0, 0.93, 0.72, float("nan")),
            (0.78, 1.0, 0.93, 0.72, float("inf")),
            (-0.78, 1.0, 0.93, 0.72, 0.55),
            (0.78, 1.2, 0.93, 0.72, 0.55),
            (0.78, 0.9, 0.85, 0.7, 0.5),
            (1.0, 1.0, 0.93, 0.72, 0.55),
            (0.60, 1.0, 0.93, 0.72, 0.55),
            (0.78, 1.0, 0.93, 0.72, 0.80),
            {0.55, 0.72, 0.78, 0.93, 1.0},
        )
        for candidate in invalid_curves:
            with self.subTest(chroma_curve=repr(candidate)), self.assertRaises(ValueError):
                texlib.make_ramp(**good, chroma_curve=candidate)

        valid_curve = (0.75, 1.0, 0.92, 0.70, 0.50)
        expected = texlib.make_ramp(**good, chroma_curve=valid_curve)
        self.assertEqual(expected, texlib.make_ramp(
            **dict(good, value_steps=list(good["value_steps"])),
            chroma_curve=list(valid_curve)))
        self.assertEqual(expected, texlib.make_ramp(**good, chroma_curve=valid_curve))

    def test_invalid_boolean_hue_cannot_silently_pass_palette_audit(self):
        token = texlib.PALETTE_TOKENS["stone"]
        original_hue = token["hue"]
        try:
            token["hue"] = True
            failures = palette_foundation_audit()
        finally:
            token["hue"] = original_hue
        self.assertTrue(
            any("stone: make_ramp failed" in failure
                for failure in failures["determinism"]),
            failures)

    def test_stone_is_exactly_periodic_for_shipped_profiles(self):
        profiles = (
            (101, (3, 5), (2, 3)),
            (102, (2, 4), (2, 3)),
            (103, (3, 4), (3, 4)),
        )
        for seed, block_w, block_h in profiles:
            with self.subTest(seed=seed, block_w=block_w, block_h=block_h):
                tile = texlib.new_image(16, 16)
                field = texlib.new_image(48, 48)
                texlib.stone(tile, 0, 0, 16, 16, texlib.ramp("stone"),
                             random.Random(seed), block_w=block_w, block_h=block_h)
                texlib.stone(field, 0, 0, 48, 48, texlib.ramp("stone"),
                             random.Random(seed), block_w=block_w, block_h=block_h)
                expected = Image.new("RGBA", (48, 48))
                for row in range(3):
                    for column in range(3):
                        expected.paste(tile, (column * 16, row * 16))
                self.assertEqual(field.tobytes(), expected.tobytes())
                pixels = tile.load()

                def delta(first, second):
                    return sum(abs(a - b) for a, b in zip(first[:3], second[:3])) / 3

                horizontal_internal = [delta(pixels[x, y], pixels[x + 1, y])
                                       for x in range(15) for y in range(16)]
                vertical_internal = [delta(pixels[x, y], pixels[x, y + 1])
                                     for y in range(15) for x in range(16)]
                horizontal_seam = [delta(pixels[15, y], pixels[0, y])
                                   for y in range(16)]
                vertical_seam = [delta(pixels[x, 15], pixels[x, 0])
                                 for x in range(16)]
                self.assertLessEqual(statistics.mean(horizontal_seam) /
                                     statistics.mean(horizontal_internal), 1.45)
                self.assertLessEqual(statistics.mean(vertical_seam) /
                                     statistics.mean(vertical_internal), 1.45)

    def test_stone_broad_seed_matrix_is_deterministic_and_seam_safe(self):
        stone_ramp = texlib.ramp("stone")
        mortar = texlib.shade(stone_ramp[0], 0.85)
        self.assertGreaterEqual(len(STONE_SEED_MATRIX), 256)
        self.assertEqual(len(STONE_PROFILES), 3)
        for profile, block_w, block_h in STONE_PROFILES:
            for seed in STONE_SEED_MATRIX:
                with self.subTest(profile=profile, seed=seed):
                    first = texlib.new_image(16, 16)
                    second = texlib.new_image(16, 16)
                    texlib.stone(first, 0, 0, 16, 16, stone_ramp,
                                 random.Random(seed), block_w=block_w,
                                 block_h=block_h)
                    texlib.stone(second, 0, 0, 16, 16, stone_ramp,
                                 random.Random(seed), block_w=block_w,
                                 block_h=block_h)
                    self.assertEqual(first.tobytes(), second.tobytes())
                    self.assertEqual(
                        _stone_edge_contract_violations(first, mortar), [])
                    horizontal, vertical = _stone_seam_ratios(first)
                    self.assertLessEqual(horizontal, STONE_SEAM_LIMIT)
                    self.assertLessEqual(vertical, STONE_SEAM_LIMIT)

    def test_stone_respects_clipped_target_rect(self):
        sentinel = (1, 2, 3, 255)
        image = Image.new("RGBA", (20, 20), sentinel)
        texlib.stone(image, 2, 3, 16, 16, texlib.ramp("stone"), random.Random(44))
        pixels = image.load()
        self.assertEqual(pixels[1, 3], sentinel)
        self.assertEqual(pixels[2, 2], sentinel)
        self.assertEqual(pixels[18, 3], sentinel)
        self.assertNotEqual(pixels[2, 3], sentinel)
        self.assertNotEqual(pixels[17, 18], sentinel)

    def test_all_material_primitives_repeat_with_the_same_seed(self):
        def render() -> bytes:
            image = texlib.new_image(64, 16)
            texlib.metal(image, 0, 0, 16, 16, texlib.ramp("iron"),
                         random.Random(1), forged=True)
            texlib.wood_grain(image, 16, 0, 16, 16, texlib.ramp("oak"),
                              random.Random(2))
            texlib.fill(image, 32, 0, 16, 16, texlib.ramp("burgundy")[3])
            texlib.fold(image, 40, 2, 12, texlib.ramp("burgundy"), vertical=True)
            texlib.fill(image, 48, 0, 16, 16, texlib.ramp("leather")[2])
            texlib.worn_edge(image, 48, 0, 16, 16, texlib.ramp("leather"),
                             random.Random(3), edges=("top", "right"))
            return image.tobytes()

        self.assertEqual(render(), render())

    def test_worn_edge_is_stable_for_unordered_and_reordered_edges(self):
        def render(edges) -> bytes:
            image = texlib.new_image(16, 16)
            texlib.fill(image, 0, 0, 16, 16, texlib.ramp("leather")[2])
            texlib.worn_edge(
                image, 0, 0, 16, 16, texlib.ramp("leather"), random.Random(3),
                edges=edges)
            return image.tobytes()

        expected = render(("top", "right", "bottom", "left"))
        self.assertEqual(render(("left", "bottom", "right", "top")), expected)
        self.assertEqual(render({"top", "right", "bottom", "left"}), expected)

    def test_raider_generator_is_covered_by_pipeline_staleness_gate(self):
        self.assertIn("gen_raider.py", PIPELINE_GENERATORS)

    def test_every_missing_listed_pipeline_generator_is_a_hard_failure(self):
        saved_results = [(category, list(entries))
                         for category, entries in validate_assets.RESULTS.items()]
        saved_counts = dict(validate_assets.COUNTS)
        try:
            validate_assets.RESULTS.clear()
            validate_assets.COUNTS.update(
                {"pass": 0, "fail": 0, "warn": 0, "info": 0})
            with tempfile.TemporaryDirectory() as temp_dir:
                absent_tools = Path(temp_dir) / "missing-tools"
                validate_assets.check_pipeline(
                    tools_src=absent_tools,
                    generators=("missing_one.py", "missing_two.py"))
            self.assertEqual(validate_assets.COUNTS["fail"], 2)
            failures = [message for symbol, message
                        in validate_assets.RESULTS.get("Pipeline", [])
                        if symbol == "✗"]
            self.assertEqual(len(failures), 2)
            self.assertTrue(any("missing_one.py" in message for message in failures))
            self.assertTrue(any("missing_two.py" in message for message in failures))
        finally:
            validate_assets.RESULTS.clear()
            validate_assets.RESULTS.update(saved_results)
            validate_assets.COUNTS.clear()
            validate_assets.COUNTS.update(saved_counts)

    def test_entity_texture_paths_are_platform_neutral_and_raider_is_64_square(self):
        expected = ("raider/raider.png", "raider")
        for relative in (PureWindowsPath("raider", "raider.png"),
                         PurePosixPath("raider", "raider.png")):
            with self.subTest(path_type=type(relative).__name__):
                self.assertEqual(_entity_texture_key_and_folder(relative), expected)
        self.assertEqual(entity_atlas_sizes_from_models().get("raider"), (64, 64))

    def test_cp1252_console_is_reconfigured_without_losing_symbols(self):
        raw = io.BytesIO()
        stream = io.TextIOWrapper(raw, encoding="cp1252")
        symbols = "✓ ✗ ⚠ • —"
        try:
            self.assertTrue(
                _configure_windows_console(stream, platform_name="nt"))
            stream.write(symbols)
            stream.flush()
            self.assertEqual(raw.getvalue().decode("utf-8"), symbols)
        finally:
            stream.detach()


if __name__ == "__main__":
    unittest.main(verbosity=2)
