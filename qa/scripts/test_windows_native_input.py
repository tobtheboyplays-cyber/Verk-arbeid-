#!/usr/bin/env python3
"""Adversarial contracts for the fail-closed native Windows input helper."""

from __future__ import annotations

import ctypes
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

import windows_native_input as native


def sample_window(**overrides: object) -> native.WindowInfo:
    values: dict[str, object] = {
        "hwnd": 0x1234_5678_9ABC_DEF0,
        "pid": 654321,
        "title": "Minecraft 1.21.1",
        "class_name": "GLFW30",
        "process_path": r"C:\Program Files\Java\bin\javaw.exe",
        "client_left": -1920,
        "client_top": 0,
        "client_width": 1920,
        "client_height": 1080,
    }
    values.update(overrides)
    return native.WindowInfo(**values)  # type: ignore[arg-type]


def sample_target(**window_overrides: object) -> native.BoundTarget:
    window = sample_window(**window_overrides)
    launch = native.LaunchBinding(
        launch_index=1,
        native_session_id="native_session_20260828",
        launch_nonce="launch_nonce_00000001",
        pid=window.pid,
        process_creation_epoch_millis=1_700_000_000_000,
        registered_epoch_millis=1_700_000_001_000,
        process_path=window.process_path,
        game_directory=r"C:\Game",
        runtime_jar_path=r"C:\Game\mods\hearthstead.jar",
        runtime_jar_sha256="a" * 64,
        world_directory=r"C:\Game\saves\fresh-world",
        display_profile="1280x720-gui3-en_us",
        observer_log_segment=(
            "logs/native-native_session_20260828-segment-0001-"
            "1280x720_gui3_en_us.log"
        ),
        launch_identity_sha256="b" * 64,
    )
    return native.BoundTarget(window, launch, Path("registry.jsonl"), "c" * 64)


def postcondition(target: native.BoundTarget) -> dict[str, object]:
    return {
        "foregroundHwnd": target.window.hwnd,
        "boundPid": target.window.pid,
        "launchNonce": target.launch.launch_nonce,
        "processCreationEpochMillis": target.launch.process_creation_epoch_millis,
        "allEventsDelivered": True,
        "cleanupRequired": False,
    }


class FakeDpiApi:
    def __init__(self, *, configured: bool = True, equal: bool = True) -> None:
        self.configured = configured
        self.equal = equal
        self.calls: list[str] = []

    def SetProcessDpiAwarenessContext(self, _context: object) -> bool:
        self.calls.append("set")
        return self.configured

    def GetThreadDpiAwarenessContext(self) -> int:
        self.calls.append("get")
        return 123

    def AreDpiAwarenessContextsEqual(self, _left: object,
                                     _right: object) -> bool:
        self.calls.append("equal")
        return self.equal


class NativeInputContractTest(unittest.TestCase):
    def test_dual_monitor_absolute_coordinates_cover_negative_origin(self) -> None:
        desktop = (-2560, -1440, 5120, 2880)
        self.assertEqual((0, 0), native.normalized_virtual_point(-2560, -1440, *desktop))
        self.assertEqual(
            (65535, 65535),
            native.normalized_virtual_point(2559, 1439, *desktop),
        )
        center_x, center_y = native.normalized_virtual_point(0, 0, *desktop)
        self.assertTrue(32760 <= center_x <= 32775)
        self.assertTrue(32770 <= center_y <= 32790)

    def test_per_monitor_v2_dpi_is_set_and_verified(self) -> None:
        api = FakeDpiApi()
        with mock.patch.object(native, "require_windows"):
            native.initialize_per_monitor_dpi(api)
        self.assertEqual(["set", "get", "equal"], api.calls)
        with (mock.patch.object(native, "require_windows"),
              self.assertRaises(native.NativeInputError)):
            native.initialize_per_monitor_dpi(FakeDpiApi(equal=False))

    def test_virtual_coordinate_contract_rejects_outside_and_bad_geometry(self) -> None:
        rejected = (
            (-2561, 0, -2560, 0, 5120, 1440),
            (2560, 0, -2560, 0, 5120, 1440),
            (0, -1, -2560, 0, 5120, 1440),
            (0, 1440, -2560, 0, 5120, 1440),
            (0, 0, 0, 0, 1, 1440),
            (0, 0, 0, 0, 5120, 1),
        )
        for values in rejected:
            with self.subTest(values=values), self.assertRaises(native.NativeInputError):
                native.normalized_virtual_point(*values)

    def test_window_allowlist_is_secondary_and_full_path_binding_is_exact(self) -> None:
        self.assertTrue(native.is_minecraft_window(sample_window()))
        spoof = sample_window(process_path=r"C:\malicious\javaw.exe")
        self.assertTrue(native.is_minecraft_window(spoof))
        self.assertFalse(native.same_windows_path(
            spoof.process_path, sample_target().launch.process_path
        ))
        for window in (
            sample_window(title="CurseForge"),
            sample_window(title="MinecraftLauncher"),
            sample_window(class_name="Chrome_WidgetWin_1"),
            sample_window(process_path=r"C:\Windows\notepad.exe"),
        ):
            with self.subTest(window=window):
                self.assertFalse(native.is_minecraft_window(window))

    def test_high_bit_hwnd_and_input_structures_are_not_truncated(self) -> None:
        target = sample_target(hwnd=0xFEDC_BA98_7654_3210)
        self.assertEqual(0xFEDC_BA98_7654_3210, target.window.hwnd)
        self.assertGreaterEqual(ctypes.sizeof(native.INPUT), ctypes.sizeof(ctypes.c_void_p))
        key = native.keyboard_event(native.KEYS["a"])
        released = native.keyboard_event(native.KEYS["a"], key_up=True)
        self.assertEqual(native.INPUT_KEYBOARD, key.type)
        self.assertEqual(native.KEYS["a"], key.ki.wVk)
        self.assertEqual(native.KEYEVENTF_KEYUP, released.ki.dwFlags)

    def test_focus_theft_triggers_target_only_release_and_no_completion(self) -> None:
        target = sample_target()
        down = native.keyboard_event(native.KEYS["w"])
        up = native.keyboard_event(native.KEYS["w"], key_up=True)
        checks = [
            target.window, native.NativeInputError("focus stolen"),
            target.window, target.window, target.window,
        ]
        with (mock.patch.object(native, "require_bound_foreground",
                                side_effect=checks),
              mock.patch.object(native, "raw_send_input", return_value=1) as sender,
              self.assertRaisesRegex(native.NativeInputError, "focus stolen")):
            native.send_inputs(target, [down, up])
        self.assertEqual(2, sender.call_count)
        cleanup = sender.call_args_list[-1].args[0]
        self.assertTrue(cleanup.ki.dwFlags & native.KEYEVENTF_KEYUP)

    def test_cleanup_never_sends_keyup_when_target_cannot_be_refocused(self) -> None:
        target = sample_target()
        with (mock.patch.object(
                  native, "require_bound_foreground",
                  side_effect=[target.window, native.NativeInputError("stolen"),
                               native.NativeInputError("still stolen")]),
              mock.patch.object(native, "focus_window",
                                side_effect=native.NativeInputError("cannot focus")),
              mock.patch.object(native, "raw_send_input", return_value=1) as sender,
              self.assertRaisesRegex(native.NativeInputError, "stolen")):
            native.send_inputs(target, [native.keyboard_event(native.KEYS["w"])])
        self.assertEqual(1, sender.call_count)

    def test_partial_delivery_fails_and_best_effort_releases_delivered_down(self) -> None:
        target = sample_target()
        events = [
            native.keyboard_event(native.KEYS["shift"]),
            native.keyboard_event(native.KEYS["e"]),
            native.keyboard_event(native.KEYS["e"], key_up=True),
            native.keyboard_event(native.KEYS["shift"], key_up=True),
        ]
        with (mock.patch.object(native, "require_bound_foreground",
                                return_value=target.window),
              mock.patch.object(native, "raw_send_input",
                                side_effect=[1, 0, 1]) as sender,
              self.assertRaisesRegex(native.NativeInputError, "0/1")):
            native.send_inputs(target, events)
        self.assertEqual(3, sender.call_count)
        release = sender.call_args_list[-1].args[0]
        self.assertEqual(native.KEYS["shift"], release.ki.wVk)
        self.assertTrue(release.ki.dwFlags & native.KEYEVENTF_KEYUP)

    def test_holds_are_bounded_pulses_not_one_long_stuck_key(self) -> None:
        target = sample_target()
        sleeps: list[float] = []
        with (mock.patch.object(native, "require_bound_foreground",
                                return_value=target.window),
              mock.patch.object(native, "raw_send_input", return_value=1) as sender):
            delivered = native.hold_key(target, "w", 250, sleeper=sleeps.append)
        self.assertEqual(6, delivered)
        self.assertEqual([0.1, 0.1, 0.05], sleeps)
        self.assertEqual(6, sender.call_count)

    def test_generic_hotkeys_and_clipboard_routes_do_not_parse(self) -> None:
        parser = native.parser()
        for argv in (
            ["hotkey", "--keys", "ctrl,v"],
            ["hotkey", "--keys", "alt,tab"],
        ):
            with self.subTest(argv=argv), self.assertRaises(SystemExit):
                parser.parse_args(argv)
        self.assertNotIn("ctrl", native.KEYS)
        self.assertNotIn("alt", native.KEYS)
        self.assertNotIn("tab", native.KEYS)

    def test_text_is_clear_settlement_name_only_and_command_safe(self) -> None:
        self.assertEqual(
            "Oak Haven",
            native.validate_settlement_name("Oak Haven", "settlement_name"),
        )
        for value, purpose in (
            ("/hearthstead demo", "settlement_name"),
            (".op Tobias", "settlement_name"),
            ("command recruit", "settlement_name"),
            ("Oak  Haven", "settlement_name"),
            (" Æsir", "settlement_name"),
            ("A" * 33, "settlement_name"),
            ("Oak", "chat"),
        ):
            with self.subTest(value=value, purpose=purpose), \
                    self.assertRaises(native.NativeInputError):
                native.validate_settlement_name(value, purpose)

    def test_path_contract_rejects_symlink_and_reparse_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            child = root / "run" / "logs" / "native-input.jsonl"
            child.parent.mkdir(parents=True)
            self.assertEqual(
                child.resolve(), native.canonical_relative_child(child, root, "test")
            )
            outside = root.parent / "outside.jsonl"
            with self.assertRaises(native.NativeInputError):
                native.canonical_relative_child(outside, root, "test")
            link = root / "linked"
            try:
                link.symlink_to(child.parent, target_is_directory=True)
            except OSError:
                pass
            else:
                with self.assertRaises(native.NativeInputError):
                    native.canonical_relative_child(link / "input.jsonl", root, "test")
        reparse_flag = getattr(native.stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400)
        self.assertTrue(native.is_reparse_point(
            SimpleNamespace(st_file_attributes=reparse_flag)
        ))

    def test_transcript_v3_is_contiguous_completed_and_tamper_evident(self) -> None:
        session = "native_session_20260828"
        target = sample_target()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            logs = root / "run" / "logs"
            logs.mkdir(parents=True)
            transcript = logs / "native-input.jsonl"
            registry = logs / "native-launch-registry.jsonl"
            registry.write_text("sealed-registry\n", encoding="utf-8")
            target = native.BoundTarget(
                target.window, target.launch,
                registry, native.sha256_file(registry),
            )
            original_root = native.TRANSCRIPT_ROOT
            native.TRANSCRIPT_ROOT = root
            try:
                first = native.append_transcript(
                    transcript, session, target, "INTENT", "focus", {},
                )
                second = native.append_transcript(
                    transcript, session, target, "COMPLETED", "focus", {},
                    postcondition(target),
                )
                self.assertEqual(1, first["sequence"])
                self.assertEqual(2, second["sequence"])
                self.assertEqual(first["chainSha256"], second["previousChainSha256"])
                self.assertEqual((second["chainSha256"], 3, None),
                                 native.transcript_tail(transcript, session))

                records = transcript.read_text(encoding="utf-8").splitlines()
                changed = json.loads(records[0])
                changed["action"] = "click"
                records[0] = json.dumps(changed, sort_keys=True)
                transcript.write_text("\n".join(records) + "\n", encoding="utf-8")
                with self.assertRaises(native.NativeInputError):
                    native.append_transcript(
                        transcript, session, target, "INTENT", "key",
                        {"key": "q", "repeat": 1},
                    )
            finally:
                native.TRANSCRIPT_ROOT = original_root

    def test_transcript_truncation_and_reordering_fail_closed(self) -> None:
        session = "native_session_20260828"
        target = sample_target()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            logs = root / "run" / "logs"
            logs.mkdir(parents=True)
            transcript = logs / "native-input.jsonl"
            registry = logs / "native-launch-registry.jsonl"
            registry.write_text("sealed-registry\n", encoding="utf-8")
            target = native.BoundTarget(
                target.window, target.launch,
                registry, native.sha256_file(registry),
            )
            original_root = native.TRANSCRIPT_ROOT
            native.TRANSCRIPT_ROOT = root
            try:
                native.append_transcript(
                    transcript, session, target, "INTENT", "focus", {},
                )
                native.append_transcript(
                    transcript, session, target, "COMPLETED", "focus", {},
                    postcondition(target),
                )
                records = transcript.read_text(encoding="utf-8").splitlines()

                transcript.write_text(records[0] + "\n", encoding="utf-8")
                _tail, next_sequence, pending = native.transcript_tail(
                    transcript, session
                )
                self.assertEqual(2, next_sequence)
                self.assertIsNotNone(pending)
                with self.assertRaises(native.NativeInputError):
                    native.append_transcript(
                        transcript, session, target, "INTENT", "key",
                        {"key": "q", "repeat": 1},
                    )

                transcript.write_text(
                    "\n".join(reversed(records)) + "\n", encoding="utf-8"
                )
                with self.assertRaises(native.NativeInputError):
                    native.transcript_tail(transcript, session)
            finally:
                native.TRANSCRIPT_ROOT = original_root

    def test_transcript_requires_registered_launch_in_the_same_direct_run(self) -> None:
        session = "native_session_20260828"
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            first_logs = root / "first-run" / "logs"
            second_logs = root / "second-run" / "logs"
            first_logs.mkdir(parents=True)
            second_logs.mkdir(parents=True)
            registry = first_logs / "native-launch-registry.jsonl"
            registry.write_text("sealed-registry\n", encoding="utf-8")
            base = sample_target()
            target = native.BoundTarget(
                base.window, base.launch, registry, native.sha256_file(registry),
            )
            original_root = native.TRANSCRIPT_ROOT
            native.TRANSCRIPT_ROOT = root
            try:
                with self.assertRaisesRegex(native.NativeInputError, "same release run"):
                    native.append_transcript(
                        second_logs / "native-input.jsonl", session, target,
                        "INTENT", "focus", {},
                    )
                future = native.BoundTarget(
                    target.window,
                    native.LaunchBinding(
                        **{
                            **target.launch.__dict__,
                            "registered_epoch_millis": 1_900_000_000_000,
                        }
                    ),
                    target.registry_path,
                    target.registry_sha256,
                )
                with (mock.patch.object(native.time, "time", return_value=1_800_000_000),
                      self.assertRaisesRegex(native.NativeInputError,
                                              "predates its launch registration")):
                    native.append_transcript(
                        first_logs / "native-input.jsonl", session, future,
                        "INTENT", "focus", {},
                    )
                nested_logs = root / "first-run" / "nested" / "logs"
                nested_logs.mkdir(parents=True)
                with self.assertRaisesRegex(native.NativeInputError,
                                             "direct release run"):
                    native.canonical_native_log_path(
                        nested_logs / "native-input.jsonl", "transcript",
                        "native-input.jsonl",
                    )
            finally:
                native.TRANSCRIPT_ROOT = original_root


if __name__ == "__main__":
    unittest.main(verbosity=2)
