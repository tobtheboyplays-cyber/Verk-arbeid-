#!/usr/bin/env python3
"""Generator-local regression tests for the B1 texture foundation."""

from __future__ import annotations

import gzip
import io
import random
import statistics
import tempfile
import unittest
from pathlib import Path, PurePosixPath, PureWindowsPath

from PIL import Image
from PIL.PngImagePlugin import PngInfo

import texlib
import validate_assets
from validate_assets import (
    PIPELINE_GENERATORS,
    STONE_PROFILES,
    STONE_SEAM_LIMIT,
    STONE_SEED_MATRIX,
    _configure_windows_console,
    _entity_texture_key_and_folder,
    _format_pipeline_paths,
    _pipeline_bytes_equal,
    _pipeline_committed_output_equal,
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


class PipelinePortabilityTests(unittest.TestCase):

    @staticmethod
    def _png_bytes(image: Image.Image, *, compression: int,
                   comment: str | None = None) -> bytes:
        output = io.BytesIO()
        metadata = None
        if comment is not None:
            metadata = PngInfo()
            metadata.add_text("Comment", comment)
        image.save(output, format="PNG", compress_level=compression,
                   pnginfo=metadata)
        return output.getvalue()

    def _compare(self, suffix: str, fresh_bytes: bytes,
                 committed_bytes: bytes) -> tuple[bool, str]:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            fresh = root / f"fresh{suffix}"
            committed = root / f"committed{suffix}"
            fresh.write_bytes(fresh_bytes)
            committed.write_bytes(committed_bytes)
            return _pipeline_committed_output_equal(fresh, committed)

    def test_png_same_pixels_different_compression_and_metadata_pass(self):
        image = Image.new("RGBA", (3, 2), (48, 36, 24, 255))
        image.putpixel((1, 1), (142, 94, 46, 173))
        compact = self._png_bytes(image, compression=9)
        decorated = self._png_bytes(
            image, compression=0, comment="encoding metadata may vary")
        self.assertNotEqual(compact, decorated)
        self.assertEqual(self._compare(".png", compact, decorated), (True, ""))

    def test_png_one_pixel_delta_and_corruption_fail_closed(self):
        original = Image.new("RGBA", (2, 2), (10, 20, 30, 255))
        changed = original.copy()
        changed.putpixel((1, 0), (10, 20, 31, 255))
        original_bytes = self._png_bytes(original, compression=6)
        changed_bytes = self._png_bytes(changed, compression=6)

        equal, reason = self._compare(".png", original_bytes, changed_bytes)
        self.assertFalse(equal)
        self.assertIn("pixel bytes differ", reason)

        equal, reason = self._compare(".png", original_bytes, b"not a PNG")
        self.assertFalse(equal)
        self.assertIn("decode/parse failed", reason)

    def test_png_mode_and_size_are_part_of_the_contract(self):
        rgba = self._png_bytes(
            Image.new("RGBA", (2, 2), (10, 20, 30, 255)), compression=6)
        rgb = self._png_bytes(
            Image.new("RGB", (2, 2), (10, 20, 30)), compression=6)
        wider = self._png_bytes(
            Image.new("RGBA", (3, 2), (10, 20, 30, 255)), compression=6)

        equal, reason = self._compare(".png", rgba, rgb)
        self.assertFalse(equal)
        self.assertIn("mode differs", reason)

        equal, reason = self._compare(".png", rgba, wider)
        self.assertFalse(equal)
        self.assertIn("size differs", reason)

    def test_mcmeta_uses_strict_deep_json_equality(self):
        compact = (b'{"animation":{"frametime":2,"frames":[0,1]},'
                   b'"interpolate":true}\n')
        reformatted = (b'{\r\n  "interpolate": true,\r\n  "animation": '
                       b'{"frames": [0, 1], "frametime": 2}\r\n}\r\n')
        self.assertEqual(
            self._compare(".mcmeta", compact, reformatted), (True, ""))

        changed = compact.replace(b"[0,1]", b"[0,2]")
        equal, reason = self._compare(".mcmeta", compact, changed)
        self.assertFalse(equal)
        self.assertIn("structure/value differs", reason)

        # Python considers True == 1; JSON structural equality must not.
        equal, reason = self._compare(
            ".mcmeta", b'{"interpolate":true}', b'{"interpolate":1}')
        self.assertFalse(equal)
        self.assertIn("structure/value differs", reason)

        for malformed in (b'{"animation":', b'{"x":1,"x":2}',
                          b'{"x":NaN}', b'\xff'):
            with self.subTest(malformed=malformed):
                equal, reason = self._compare(".mcmeta", compact, malformed)
                self.assertFalse(equal)
                self.assertIn("decode/parse failed", reason)

    def test_mcmeta_rejects_every_non_finite_number_on_either_side(self):
        valid = b'{"x":1.25}'
        non_finite_values = (
            b'{"x":1e9999}',
            b'{"x":-1e9999}',
            b'{"x":Infinity}',
            b'{"x":-Infinity}',
            b'{"x":NaN}',
        )
        for malformed in non_finite_values:
            for fresh, committed in ((malformed, valid), (valid, malformed)):
                with self.subTest(malformed=malformed,
                                  side="fresh" if fresh is malformed else "committed"):
                    equal, reason = self._compare(".mcmeta", fresh, committed)
                    self.assertFalse(equal)
                    self.assertIn("decode/parse failed", reason)

    def test_mcmeta_preserves_distinct_finite_numeric_values(self):
        collisions = (
            (b'{"x":1e-9999}', b'{"x":0.0}'),
            (b'{"x":0.100000000000000005}', b'{"x":0.1}'),
        )
        for left, right in collisions:
            for fresh, committed in ((left, right), (right, left)):
                with self.subTest(fresh=fresh, committed=committed):
                    equal, reason = self._compare(".mcmeta", fresh, committed)
                    self.assertFalse(equal)
                    self.assertIn("structure/value differs", reason)

    def test_pipeline_file_set_diagnostic_is_bounded(self):
        paths = [f"generated/path-{index:02d}.png" for index in range(13)]
        message = _format_pipeline_paths(paths)
        self.assertIn("path-00.png", message)
        self.assertIn("path-07.png", message)
        self.assertNotIn("path-08.png", message)
        self.assertIn("... and 5 more", message)

    def test_java_normalizes_only_newline_representation(self):
        lf = b"final class Token {\n    int value = 1;\n}\n"
        crlf = lf.replace(b"\n", b"\r\n")
        cr = lf.replace(b"\n", b"\r")
        self.assertEqual(self._compare(".java", lf, crlf), (True, ""))
        self.assertEqual(self._compare(".java", lf, cr), (True, ""))

        for changed in (lf.replace(b"value = 1", b"value = 2"),
                        lf.replace(b"    int", b"     int")):
            with self.subTest(changed=changed):
                equal, reason = self._compare(".java", lf, changed)
                self.assertFalse(equal)
                self.assertIn("differs beyond newline", reason)

        equal, reason = self._compare(".java", lf, b"class X { // \xff\n}\n")
        self.assertFalse(equal)
        self.assertIn("decode/parse failed", reason)

    def test_nbt_compares_decompressed_payload_not_gzip_wrapper(self):
        payload = b"\x0a\x00\x00hearthstead-nbt-payload\x00"
        fast = gzip.compress(payload, compresslevel=1, mtime=0)
        wrapped = bytearray(gzip.compress(payload, compresslevel=9, mtime=7))
        wrapped[9] = 3 if wrapped[9] != 3 else 0  # vary gzip OS metadata too
        wrapped = bytes(wrapped)
        self.assertNotEqual(fast, wrapped)
        self.assertEqual(self._compare(".nbt", fast, wrapped), (True, ""))

        changed = gzip.compress(payload + b"changed", compresslevel=9, mtime=0)
        equal, reason = self._compare(".nbt", fast, changed)
        self.assertFalse(equal)
        self.assertIn("payload differs", reason)

        equal, reason = self._compare(".nbt", fast, b"not gzip")
        self.assertFalse(equal)
        self.assertIn("decode/parse failed", reason)

    def test_unknown_suffix_falls_back_to_exact_bytes(self):
        self.assertEqual(self._compare(".bin", b"same", b"same"), (True, ""))
        equal, reason = self._compare(".bin", b"same", b"different")
        self.assertFalse(equal)
        self.assertIn("byte content differs", reason)

    def test_fresh_run_determinism_gate_remains_byte_exact(self):
        image = Image.new("RGBA", (2, 2), (75, 63, 51, 255))
        first_bytes = self._png_bytes(image, compression=0)
        second_bytes = self._png_bytes(
            image, compression=9, comment="same pixels, different bytes")
        self.assertNotEqual(first_bytes, second_bytes)

        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            first = root / "run-a.png"
            second = root / "run-b.png"
            first.write_bytes(first_bytes)
            second.write_bytes(second_bytes)
            # Fresh-vs-committed portability accepts the equivalent pixels ...
            self.assertEqual(
                _pipeline_committed_output_equal(first, second), (True, ""))
            # ... but fresh run A-vs-B still rejects their different raw bytes.
            equal, reason = _pipeline_bytes_equal(first, second)
            self.assertFalse(equal)
            self.assertEqual(reason, "byte content differs")


if __name__ == "__main__":
    unittest.main(verbosity=2)
