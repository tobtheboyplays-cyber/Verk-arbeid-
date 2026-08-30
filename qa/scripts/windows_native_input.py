#!/usr/bin/env python3
"""Fail-closed Windows input driver for Hearthstead native client QA.

This helper does not inspect or mutate Minecraft state. It emits ordinary
Windows mouse/keyboard input only after resolving an exact visible GLFW
Minecraft window, focuses that same HWND, keeps client coordinates inside its
bounds, and appends a hash-chained transcript. Server commands, direct game
memory, test hooks, and background-window messages are deliberately absent.

Listing and inspecting windows are read-only. Every input command requires a
native-session id and a transcript below the canonical release-client artifact
root so a final run can seal the exact player-equivalent action stream.
"""

from __future__ import annotations

import argparse
import ctypes
import json
import os
import re
import stat
import sys
import time
from ctypes import wintypes
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Callable, NoReturn

from native_evidence_contract import (
    EvidenceContractError,
    INPUT_DRIVER,
    INPUT_RECORD_SCHEMA,
    INPUT_TRANSCRIPT_PATH,
    LAUNCH_REGISTRY_PATH,
    SESSION,
    canonical_json,
    chained_record_sha256,
    is_reparse_point,
    launch_identity_sha256,
    parse_launch_registry,
    require_plain_path,
    sha256_file,
)


ROOT = Path(__file__).resolve().parents[2]
TRANSCRIPT_ROOT = ROOT / "qa" / "reports" / "artifacts" / "release-client"
CAPTURE_ROOT = ROOT / "qa" / "reports"
MINECRAFT_TITLE = re.compile(r"(?:^|\s)Minecraft(?:\s|$)", re.IGNORECASE)
MINECRAFT_CLASSES = {"GLFW30", "LWJGL"}
MINECRAFT_PROCESSES = {"java.exe", "javaw.exe"}


class NativeInputError(RuntimeError):
    """A native-input safety precondition was not met."""


def fail(message: str) -> NoReturn:
    raise NativeInputError(message)


def require_windows() -> None:
    if os.name != "nt":
        fail("native input is available only on Windows")


if os.name == "nt":
    user32 = ctypes.WinDLL("user32", use_last_error=True)
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
else:  # Importable by pure contract tests on non-Windows hosts.
    user32 = None
    kernel32 = None


ULONG_PTR = wintypes.WPARAM


class MOUSEINPUT(ctypes.Structure):
    _fields_ = (
        ("dx", wintypes.LONG),
        ("dy", wintypes.LONG),
        ("mouseData", wintypes.DWORD),
        ("dwFlags", wintypes.DWORD),
        ("time", wintypes.DWORD),
        ("dwExtraInfo", ULONG_PTR),
    )


class KEYBDINPUT(ctypes.Structure):
    _fields_ = (
        ("wVk", wintypes.WORD),
        ("wScan", wintypes.WORD),
        ("dwFlags", wintypes.DWORD),
        ("time", wintypes.DWORD),
        ("dwExtraInfo", ULONG_PTR),
    )


class HARDWAREINPUT(ctypes.Structure):
    _fields_ = (
        ("uMsg", wintypes.DWORD),
        ("wParamL", wintypes.WORD),
        ("wParamH", wintypes.WORD),
    )


class INPUT_VALUE(ctypes.Union):
    _fields_ = (("mi", MOUSEINPUT), ("ki", KEYBDINPUT), ("hi", HARDWAREINPUT))


class INPUT(ctypes.Structure):
    _anonymous_ = ("value",)
    _fields_ = (("type", wintypes.DWORD), ("value", INPUT_VALUE))


if os.name == "nt":
    # ctypes otherwise assumes C ``int`` arguments and return values.  That is
    # unsafe for 64-bit HWND/HANDLE values and can silently target a truncated
    # window.  Keep every native call used by this helper explicitly typed.
    assert user32 is not None and kernel32 is not None
    WNDENUMPROC = ctypes.WINFUNCTYPE(
        wintypes.BOOL, wintypes.HWND, wintypes.LPARAM
    )
    user32.EnumWindows.argtypes = (WNDENUMPROC, wintypes.LPARAM)
    user32.EnumWindows.restype = wintypes.BOOL
    user32.IsWindow.argtypes = (wintypes.HWND,)
    user32.IsWindow.restype = wintypes.BOOL
    user32.IsWindowVisible.argtypes = (wintypes.HWND,)
    user32.IsWindowVisible.restype = wintypes.BOOL
    user32.GetWindowTextLengthW.argtypes = (wintypes.HWND,)
    user32.GetWindowTextLengthW.restype = ctypes.c_int
    user32.GetWindowTextW.argtypes = (
        wintypes.HWND, wintypes.LPWSTR, ctypes.c_int,
    )
    user32.GetWindowTextW.restype = ctypes.c_int
    user32.GetClassNameW.argtypes = (
        wintypes.HWND, wintypes.LPWSTR, ctypes.c_int,
    )
    user32.GetClassNameW.restype = ctypes.c_int
    user32.GetClientRect.argtypes = (
        wintypes.HWND, ctypes.POINTER(wintypes.RECT),
    )
    user32.GetClientRect.restype = wintypes.BOOL
    user32.ClientToScreen.argtypes = (
        wintypes.HWND, ctypes.POINTER(wintypes.POINT),
    )
    user32.ClientToScreen.restype = wintypes.BOOL
    user32.GetWindowThreadProcessId.argtypes = (
        wintypes.HWND, ctypes.POINTER(wintypes.DWORD),
    )
    user32.GetWindowThreadProcessId.restype = wintypes.DWORD
    user32.ShowWindow.argtypes = (wintypes.HWND, ctypes.c_int)
    user32.ShowWindow.restype = wintypes.BOOL
    user32.GetForegroundWindow.argtypes = ()
    user32.GetForegroundWindow.restype = wintypes.HWND
    user32.BringWindowToTop.argtypes = (wintypes.HWND,)
    user32.BringWindowToTop.restype = wintypes.BOOL
    user32.SetForegroundWindow.argtypes = (wintypes.HWND,)
    user32.SetForegroundWindow.restype = wintypes.BOOL
    user32.SetFocus.argtypes = (wintypes.HWND,)
    user32.SetFocus.restype = wintypes.HWND
    user32.AttachThreadInput.argtypes = (
        wintypes.DWORD, wintypes.DWORD, wintypes.BOOL,
    )
    user32.AttachThreadInput.restype = wintypes.BOOL
    user32.GetSystemMetrics.argtypes = (ctypes.c_int,)
    user32.GetSystemMetrics.restype = ctypes.c_int
    user32.SendInput.argtypes = (
        wintypes.UINT, ctypes.POINTER(INPUT), ctypes.c_int,
    )
    user32.SendInput.restype = wintypes.UINT
    user32.GetCursorPos.argtypes = (ctypes.POINTER(wintypes.POINT),)
    user32.GetCursorPos.restype = wintypes.BOOL

    kernel32.OpenProcess.argtypes = (
        wintypes.DWORD, wintypes.BOOL, wintypes.DWORD,
    )
    kernel32.OpenProcess.restype = wintypes.HANDLE
    kernel32.QueryFullProcessImageNameW.argtypes = (
        wintypes.HANDLE, wintypes.DWORD, wintypes.LPWSTR,
        ctypes.POINTER(wintypes.DWORD),
    )
    kernel32.QueryFullProcessImageNameW.restype = wintypes.BOOL
    kernel32.CloseHandle.argtypes = (wintypes.HANDLE,)
    kernel32.CloseHandle.restype = wintypes.BOOL
    kernel32.GetCurrentThreadId.argtypes = ()
    kernel32.GetCurrentThreadId.restype = wintypes.DWORD
    kernel32.GetProcessTimes.argtypes = (
        wintypes.HANDLE, ctypes.POINTER(wintypes.FILETIME),
        ctypes.POINTER(wintypes.FILETIME), ctypes.POINTER(wintypes.FILETIME),
        ctypes.POINTER(wintypes.FILETIME),
    )
    kernel32.GetProcessTimes.restype = wintypes.BOOL
    user32.SetProcessDpiAwarenessContext.argtypes = (wintypes.HANDLE,)
    user32.SetProcessDpiAwarenessContext.restype = wintypes.BOOL
    user32.GetThreadDpiAwarenessContext.argtypes = ()
    user32.GetThreadDpiAwarenessContext.restype = wintypes.HANDLE
    user32.AreDpiAwarenessContextsEqual.argtypes = (
        wintypes.HANDLE, wintypes.HANDLE,
    )
    user32.AreDpiAwarenessContextsEqual.restype = wintypes.BOOL
else:
    WNDENUMPROC = None


INPUT_MOUSE = 0
INPUT_KEYBOARD = 1
KEYEVENTF_KEYUP = 0x0002
KEYEVENTF_UNICODE = 0x0004
MOUSEEVENTF_MOVE = 0x0001
MOUSEEVENTF_LEFTDOWN = 0x0002
MOUSEEVENTF_LEFTUP = 0x0004
MOUSEEVENTF_RIGHTDOWN = 0x0008
MOUSEEVENTF_RIGHTUP = 0x0010
MOUSEEVENTF_MIDDLEDOWN = 0x0020
MOUSEEVENTF_MIDDLEUP = 0x0040
MOUSEEVENTF_WHEEL = 0x0800
MOUSEEVENTF_VIRTUALDESK = 0x4000
MOUSEEVENTF_ABSOLUTE = 0x8000
SW_RESTORE = 9
PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
ERROR_ACCESS_DENIED = 5
SM_XVIRTUALSCREEN = 76
SM_YVIRTUALSCREEN = 77
SM_CXVIRTUALSCREEN = 78
SM_CYVIRTUALSCREEN = 79
DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2 = ctypes.c_void_p(-4)
MAX_HOLD_PULSE_MILLIS = 100


KEYS = {
    "backspace": 0x08,
    "enter": 0x0D,
    "shift": 0x10,
    "escape": 0x1B,
    "space": 0x20,
    "pageup": 0x21,
    "pagedown": 0x22,
    "end": 0x23,
    "home": 0x24,
    "left": 0x25,
    "up": 0x26,
    "right": 0x27,
    "down": 0x28,
    "delete": 0x2E,
    "0": 0x30,
    "1": 0x31,
    "2": 0x32,
    "3": 0x33,
    "4": 0x34,
    "5": 0x35,
    "6": 0x36,
    "7": 0x37,
    "8": 0x38,
    "9": 0x39,
    **{chr(code + 32): code for code in range(0x41, 0x5B)},
    **{f"f{number}": 0x6F + number for number in range(1, 13)},
}


@dataclass(frozen=True)
class WindowInfo:
    hwnd: int
    pid: int
    title: str
    class_name: str
    process_path: str
    client_left: int
    client_top: int
    client_width: int
    client_height: int

    @property
    def process_name(self) -> str:
        return Path(self.process_path).name.lower()


@dataclass(frozen=True)
class LaunchBinding:
    launch_index: int
    native_session_id: str
    launch_nonce: str
    pid: int
    process_creation_epoch_millis: int
    registered_epoch_millis: int
    process_path: str
    game_directory: str
    runtime_jar_path: str
    runtime_jar_sha256: str
    world_directory: str
    display_profile: str
    observer_log_segment: str
    launch_identity_sha256: str


@dataclass(frozen=True)
class BoundTarget:
    window: WindowInfo
    launch: LaunchBinding
    registry_path: Path
    registry_sha256: str


@dataclass(frozen=True)
class Delay:
    milliseconds: int


def canonical_relative_child(path: Path, root: Path, label: str) -> Path:
    """Resolve one plain path under a fixed repository evidence root."""
    try:
        root_resolved = require_plain_path(root, f"{label} root", directory=True)
    except EvidenceContractError as exc:
        fail(str(exc))
    candidate = path if path.is_absolute() else ROOT / path
    parent = candidate.parent
    try:
        parent_resolved = require_plain_path(parent, f"{label} parent", directory=True)
    except EvidenceContractError as exc:
        fail(str(exc))
    contained = path_is_within(parent_resolved, root_resolved)
    if not contained or candidate.name in {"", ".", ".."}:
        fail(f"{label} must stay below {root_resolved}")
    if candidate.exists():
        try:
            require_plain_path(candidate, label, directory=False)
        except EvidenceContractError as exc:
            fail(str(exc))
    return parent_resolved / candidate.name


def path_is_within(path: Path, root: Path) -> bool:
    """Compare containment with Windows case/separator normalization."""
    try:
        common = os.path.commonpath((str(root), str(path)))
    except ValueError:
        return False
    return os.path.normcase(os.path.normpath(common)) == os.path.normcase(
        os.path.normpath(str(root))
    )


def canonical_native_log_path(path: Path, label: str, filename: str) -> Path:
    """Require ``<release-root>/<one-run>/logs/<locked-name>`` exactly."""
    candidate = canonical_relative_child(path, TRANSCRIPT_ROOT, label)
    try:
        root = require_plain_path(
            TRANSCRIPT_ROOT, f"{label} root", directory=True
        )
    except EvidenceContractError as exc:
        fail(str(exc))
    if (candidate.name != filename or candidate.parent.name != "logs"
            or not path_is_within(candidate.parent.parent, root)
            or not same_windows_path(
                str(candidate.parent.parent.parent), str(root)
            )):
        fail(
            f"{label} must use one direct release run's logs/{filename}"
        )
    return candidate


def initialize_per_monitor_dpi(api: object | None = None) -> None:
    """Enter and verify per-monitor-v2 DPI mode before coordinate APIs."""
    require_windows()
    active = user32 if api is None else api
    assert active is not None
    ctypes.set_last_error(0)
    configured = bool(active.SetProcessDpiAwarenessContext(
        DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2
    ))
    error = ctypes.get_last_error()
    if not configured and error != ERROR_ACCESS_DENIED:
        fail(f"cannot enable per-monitor-v2 DPI awareness (Win32 error {error})")
    current = active.GetThreadDpiAwarenessContext()
    if not current or not active.AreDpiAwarenessContextsEqual(
            current, DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2):
        fail("process is not running in per-monitor-v2 DPI awareness")


def process_path(pid: int) -> str:
    require_windows()
    assert kernel32 is not None
    handle = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
    if not handle:
        return ""
    try:
        capacity = wintypes.DWORD(32768)
        buffer = ctypes.create_unicode_buffer(capacity.value)
        if not kernel32.QueryFullProcessImageNameW(
                handle, 0, buffer, ctypes.byref(capacity)):
            return ""
        return buffer.value
    finally:
        kernel32.CloseHandle(handle)


def process_creation_epoch_millis(pid: int) -> int:
    """Read the kernel process creation time; PID alone is never an identity."""
    require_windows()
    assert kernel32 is not None
    handle = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
    if not handle:
        fail(f"cannot open PID {pid} to verify its creation time")
    try:
        creation = wintypes.FILETIME()
        exit_time = wintypes.FILETIME()
        kernel_time = wintypes.FILETIME()
        user_time = wintypes.FILETIME()
        if not kernel32.GetProcessTimes(
                handle, ctypes.byref(creation), ctypes.byref(exit_time),
                ctypes.byref(kernel_time), ctypes.byref(user_time)):
            fail(f"cannot read creation time for PID {pid}")
        filetime_ticks = (int(creation.dwHighDateTime) << 32) \
            | int(creation.dwLowDateTime)
        # Windows FILETIME is 100-ns ticks since 1601-01-01 UTC.
        epoch_millis = (filetime_ticks - 116_444_736_000_000_000) // 10_000
        if epoch_millis <= 0:
            fail(f"PID {pid} has an implausible process creation time")
        return epoch_millis
    finally:
        kernel32.CloseHandle(handle)


def client_bounds(hwnd: int) -> tuple[int, int, int, int]:
    require_windows()
    assert user32 is not None
    rect = wintypes.RECT()
    if not user32.GetClientRect(hwnd, ctypes.byref(rect)):
        fail(f"cannot read client bounds for HWND {hwnd}")
    origin = wintypes.POINT(0, 0)
    if not user32.ClientToScreen(hwnd, ctypes.byref(origin)):
        fail(f"cannot map client bounds for HWND {hwnd}")
    width = rect.right - rect.left
    height = rect.bottom - rect.top
    if width < 320 or height < 180:
        fail(f"Minecraft client area is implausibly small: {width}x{height}")
    return origin.x, origin.y, width, height


def inspect_window(hwnd: int) -> WindowInfo:
    require_windows()
    assert user32 is not None
    if hwnd <= 0 or not user32.IsWindow(hwnd) or not user32.IsWindowVisible(hwnd):
        fail(f"HWND {hwnd} is not one visible live window")
    title_length = user32.GetWindowTextLengthW(hwnd)
    title_buffer = ctypes.create_unicode_buffer(max(1, title_length + 1))
    user32.GetWindowTextW(hwnd, title_buffer, len(title_buffer))
    class_buffer = ctypes.create_unicode_buffer(256)
    user32.GetClassNameW(hwnd, class_buffer, len(class_buffer))
    pid = wintypes.DWORD()
    user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
    left, top, width, height = client_bounds(hwnd)
    return WindowInfo(
        hwnd=hwnd,
        pid=int(pid.value),
        title=title_buffer.value,
        class_name=class_buffer.value,
        process_path=process_path(int(pid.value)),
        client_left=left,
        client_top=top,
        client_width=width,
        client_height=height,
    )


def is_minecraft_window(window: WindowInfo) -> bool:
    return (
        MINECRAFT_TITLE.search(window.title) is not None
        and window.class_name in MINECRAFT_CLASSES
        and window.process_name in MINECRAFT_PROCESSES
    )


def require_minecraft_window(hwnd: int, expected_pid: int | None) -> WindowInfo:
    window = inspect_window(hwnd)
    if not is_minecraft_window(window):
        fail(
            "refusing non-Minecraft target: "
            f"title={window.title!r} class={window.class_name!r} "
            f"process={window.process_name!r}"
        )
    if expected_pid is not None and window.pid != expected_pid:
        fail(f"Minecraft PID changed: expected {expected_pid}, observed {window.pid}")
    return window


def same_windows_path(left: str, right: str) -> bool:
    return os.path.normcase(os.path.normpath(left)) == os.path.normcase(
        os.path.normpath(right)
    )


def load_bound_target(hwnd: int, session: str, launch_nonce: str,
                      registry_path: Path) -> BoundTarget:
    if SESSION.fullmatch(session) is None:
        fail("session must contain 8-80 ASCII letters, digits, underscores or hyphens")
    registry = canonical_native_log_path(
        registry_path, "launch registry", Path(LAUNCH_REGISTRY_PATH).name
    )
    registry_sha256 = sha256_file(registry)
    try:
        launches, _tail = parse_launch_registry(registry, session)
    except EvidenceContractError as exc:
        fail(str(exc))
    if sha256_file(registry) != registry_sha256:
        fail("launch registry changed while its identity was being loaded")
    matches = [entry for entry in launches if entry["launchNonce"] == launch_nonce]
    if len(matches) != 1:
        fail("launch nonce does not select exactly one sealed launch identity")
    entry = matches[0]
    launch = LaunchBinding(
        launch_index=int(entry["launchIndex"]),
        native_session_id=str(entry["nativeSessionId"]),
        launch_nonce=str(entry["launchNonce"]),
        pid=int(entry["pid"]),
        process_creation_epoch_millis=int(entry["processCreationEpochMillis"]),
        registered_epoch_millis=int(entry["registeredEpochMillis"]),
        process_path=str(entry["processPath"]),
        game_directory=str(entry["gameDirectory"]),
        runtime_jar_path=str(entry["runtimeJarPath"]),
        runtime_jar_sha256=str(entry["runtimeJarSha256"]),
        world_directory=str(entry["worldDirectory"]),
        display_profile=str(entry["displayProfile"]),
        observer_log_segment=str(entry["observerLogSegment"]),
        launch_identity_sha256=str(entry["launchIdentitySha256"]),
    )
    try:
        game = require_plain_path(
            Path(launch.game_directory), "launch game directory", directory=True
        )
        runtime_jar = require_plain_path(
            Path(launch.runtime_jar_path), "launch runtime JAR", directory=False,
            nonempty=True,
        )
        world = require_plain_path(
            Path(launch.world_directory), "launch world directory", directory=True
        )
    except EvidenceContractError as exc:
        fail(str(exc))
    if runtime_jar.parent != game / "mods":
        fail("launch runtime JAR is not directly inside the exact game-directory mods")
    if world.parent != game / "saves":
        fail("launch world is not directly inside the exact game-directory saves")
    if sha256_file(runtime_jar) != launch.runtime_jar_sha256:
        fail("launch runtime JAR hash differs from the sealed registry")
    window = require_minecraft_window(hwnd, launch.pid)
    if not same_windows_path(window.process_path, launch.process_path):
        fail("live Minecraft executable path differs from the sealed launch registry")
    if process_creation_epoch_millis(window.pid) \
            != launch.process_creation_epoch_millis:
        fail("live Minecraft process creation time differs from the sealed launch registry")
    return BoundTarget(window, launch, registry, registry_sha256)


def require_bound_foreground(target: BoundTarget) -> WindowInfo:
    """Revalidate registry, HWND, PID, creation time, path and foreground."""
    require_windows()
    assert user32 is not None
    try:
        current_registry = require_plain_path(
            target.registry_path, "launch registry", directory=False, nonempty=True
        )
    except EvidenceContractError as exc:
        fail(str(exc))
    if sha256_file(current_registry) != target.registry_sha256:
        fail("launch registry changed while native input was in progress")
    window = require_minecraft_window(target.window.hwnd, target.launch.pid)
    if window != target.window:
        fail("bound Minecraft HWND identity or client geometry changed")
    if not same_windows_path(window.process_path, target.launch.process_path):
        fail("bound Minecraft executable path changed")
    if process_creation_epoch_millis(window.pid) \
            != target.launch.process_creation_epoch_millis:
        fail("bound Minecraft process creation time changed")
    if int(user32.GetForegroundWindow() or 0) != target.window.hwnd:
        fail("exact bound Minecraft HWND is not foreground")
    return window


def list_minecraft_windows() -> list[WindowInfo]:
    require_windows()
    assert user32 is not None
    windows: list[WindowInfo] = []
    assert WNDENUMPROC is not None

    def visit(hwnd: int, _parameter: int) -> bool:
        try:
            window = inspect_window(int(hwnd))
        except NativeInputError:
            return True
        if is_minecraft_window(window):
            windows.append(window)
        return True

    callback = WNDENUMPROC(visit)
    if not user32.EnumWindows(callback, 0):
        fail("EnumWindows failed")
    return sorted(windows, key=lambda value: (value.pid, value.hwnd))


def focus_window(target: BoundTarget) -> None:
    require_windows()
    assert user32 is not None and kernel32 is not None
    window = target.window
    # Identity is checked before requesting focus; after Windows grants focus,
    # require_bound_foreground repeats the complete binding check.
    require_minecraft_window(window.hwnd, target.launch.pid)
    user32.ShowWindow(window.hwnd, SW_RESTORE)
    foreground = user32.GetForegroundWindow()
    current_thread = kernel32.GetCurrentThreadId()
    target_pid = wintypes.DWORD()
    target_thread = user32.GetWindowThreadProcessId(
        window.hwnd, ctypes.byref(target_pid)
    )
    foreground_thread = 0
    if foreground:
        foreground_pid = wintypes.DWORD()
        foreground_thread = user32.GetWindowThreadProcessId(
            foreground, ctypes.byref(foreground_pid)
        )
    attached_target = bool(target_thread and target_thread != current_thread
                           and user32.AttachThreadInput(
                               current_thread, target_thread, True))
    attached_foreground = bool(
        foreground_thread and foreground_thread not in {current_thread, target_thread}
        and user32.AttachThreadInput(current_thread, foreground_thread, True)
    )
    try:
        user32.BringWindowToTop(window.hwnd)
        user32.SetForegroundWindow(window.hwnd)
        user32.SetFocus(window.hwnd)
    finally:
        if attached_foreground:
            user32.AttachThreadInput(current_thread, foreground_thread, False)
        if attached_target:
            user32.AttachThreadInput(current_thread, target_thread, False)
    time.sleep(0.08)
    require_bound_foreground(target)


def raw_send_input(event: INPUT) -> int:
    require_windows()
    assert user32 is not None
    buffer = (INPUT * 1)(event)
    return int(user32.SendInput(1, buffer, ctypes.sizeof(INPUT)))


def release_for(event: INPUT) -> INPUT | None:
    if event.type == INPUT_KEYBOARD:
        if event.ki.dwFlags & KEYEVENTF_KEYUP:
            return None
        if event.ki.dwFlags & KEYEVENTF_UNICODE:
            return unicode_event(int(event.ki.wScan), key_up=True)
        return keyboard_event(int(event.ki.wVk), key_up=True)
    if event.type != INPUT_MOUSE:
        return None
    flags = int(event.mi.dwFlags)
    releases = {
        MOUSEEVENTF_LEFTDOWN: MOUSEEVENTF_LEFTUP,
        MOUSEEVENTF_RIGHTDOWN: MOUSEEVENTF_RIGHTUP,
        MOUSEEVENTF_MIDDLEDOWN: MOUSEEVENTF_MIDDLEUP,
    }
    for down, up in releases.items():
        if flags & down:
            return INPUT(type=INPUT_MOUSE, mi=MOUSEINPUT(0, 0, 0, up, 0, 0))
    return None


def matching_release(event: INPUT, release: INPUT) -> bool:
    if event.type != release.type:
        return False
    if event.type == INPUT_KEYBOARD and event.ki.dwFlags & KEYEVENTF_KEYUP:
        if event.ki.dwFlags & KEYEVENTF_UNICODE:
            return bool(release.ki.dwFlags & KEYEVENTF_UNICODE) \
                and event.ki.wScan == release.ki.wScan
        return not bool(release.ki.dwFlags & KEYEVENTF_UNICODE) \
            and event.ki.wVk == release.ki.wVk
    if event.type == INPUT_MOUSE:
        pairs = {
            MOUSEEVENTF_LEFTUP: MOUSEEVENTF_LEFTUP,
            MOUSEEVENTF_RIGHTUP: MOUSEEVENTF_RIGHTUP,
            MOUSEEVENTF_MIDDLEUP: MOUSEEVENTF_MIDDLEUP,
        }
        return any((event.mi.dwFlags & up) and (release.mi.dwFlags & marker)
                   for up, marker in pairs.items())
    return False


def best_effort_release(target: BoundTarget, pending: list[INPUT]) -> None:
    """Release only while the exact target is foreground; never spray key-up."""
    if not pending:
        return
    try:
        try:
            require_bound_foreground(target)
        except NativeInputError:
            focus_window(target)
        for release in reversed(pending):
            require_bound_foreground(target)
            if raw_send_input(release) != 1:
                return
            require_bound_foreground(target)
    except NativeInputError:
        # Deliberately do not send another release when focus cannot be proven.
        return


def send_inputs(target: BoundTarget, steps: list[INPUT | Delay],
                *, sleeper: Callable[[float], None] = time.sleep) -> int:
    """Deliver single-event batches with exact pre/post focus verification."""
    pending_releases: list[INPUT] = []
    delivered = 0
    try:
        for step in steps:
            if isinstance(step, Delay):
                if not 1 <= step.milliseconds <= MAX_HOLD_PULSE_MILLIS:
                    fail("input delay exceeds the bounded hold-pulse contract")
                sleeper(step.milliseconds / 1000)
                require_bound_foreground(target)
                continue
            require_bound_foreground(target)
            sent = raw_send_input(step)
            if sent != 1:
                fail(f"SendInput delivered {sent}/1 event")
            delivered += 1
            release = release_for(step)
            if release is not None:
                pending_releases.append(release)
            else:
                for index in range(len(pending_releases) - 1, -1, -1):
                    if matching_release(step, pending_releases[index]):
                        pending_releases.pop(index)
                        break
            require_bound_foreground(target)
        if pending_releases:
            fail("input sequence ended with a pressed key or mouse button")
    except BaseException:
        best_effort_release(target, pending_releases)
        raise
    return delivered


def keyboard_event(vk: int, *, key_up: bool = False) -> INPUT:
    return INPUT(type=INPUT_KEYBOARD, ki=KEYBDINPUT(
        wVk=vk, wScan=0, dwFlags=KEYEVENTF_KEYUP if key_up else 0,
        time=0, dwExtraInfo=0,
    ))


def unicode_event(unit: int, *, key_up: bool = False) -> INPUT:
    flags = KEYEVENTF_UNICODE | (KEYEVENTF_KEYUP if key_up else 0)
    return INPUT(type=INPUT_KEYBOARD, ki=KEYBDINPUT(
        wVk=0, wScan=unit, dwFlags=flags, time=0, dwExtraInfo=0,
    ))


def normalized_virtual_point(screen_x: int, screen_y: int,
                             virtual_left: int, virtual_top: int,
                             virtual_width: int,
                             virtual_height: int) -> tuple[int, int]:
    if virtual_width <= 1 or virtual_height <= 1:
        fail("virtual desktop dimensions are invalid")
    if not (virtual_left <= screen_x < virtual_left + virtual_width
            and virtual_top <= screen_y < virtual_top + virtual_height):
        fail("screen point falls outside the virtual desktop")
    x = round((screen_x - virtual_left) * 65535 / (virtual_width - 1))
    y = round((screen_y - virtual_top) * 65535 / (virtual_height - 1))
    return x, y


def client_screen_point(window: WindowInfo, x: int, y: int) -> tuple[int, int]:
    if not 0 <= x < window.client_width or not 0 <= y < window.client_height:
        fail(
            f"client coordinate {x},{y} is outside "
            f"{window.client_width}x{window.client_height}"
        )
    return window.client_left + x, window.client_top + y


def move_to_client(target: BoundTarget, x: int, y: int) -> tuple[int, int]:
    require_windows()
    assert user32 is not None
    window = target.window
    screen_x, screen_y = client_screen_point(window, x, y)
    virtual_left = user32.GetSystemMetrics(SM_XVIRTUALSCREEN)
    virtual_top = user32.GetSystemMetrics(SM_YVIRTUALSCREEN)
    virtual_width = user32.GetSystemMetrics(SM_CXVIRTUALSCREEN)
    virtual_height = user32.GetSystemMetrics(SM_CYVIRTUALSCREEN)
    absolute_x, absolute_y = normalized_virtual_point(
        screen_x, screen_y, virtual_left, virtual_top,
        virtual_width, virtual_height,
    )
    send_inputs(target, [INPUT(type=INPUT_MOUSE, mi=MOUSEINPUT(
        dx=absolute_x, dy=absolute_y, mouseData=0,
        dwFlags=MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK,
        time=0, dwExtraInfo=0,
    ))])
    cursor = wintypes.POINT()
    if not user32.GetCursorPos(ctypes.byref(cursor)):
        fail("cannot verify cursor position")
    if abs(cursor.x - screen_x) > 1 or abs(cursor.y - screen_y) > 1:
        fail(
            f"cursor verification failed: expected {screen_x},{screen_y}, "
            f"observed {cursor.x},{cursor.y}"
        )
    return screen_x, screen_y


def click(target: BoundTarget, button: str, count: int) -> int:
    flags = {
        "left": (MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP),
        "right": (MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP),
        "middle": (MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP),
    }
    if button not in flags or not 1 <= count <= 3:
        fail("button/count is outside the bounded click contract")
    down, up = flags[button]
    events: list[INPUT] = []
    for _index in range(count):
        events.extend((
            INPUT(type=INPUT_MOUSE, mi=MOUSEINPUT(
                0, 0, 0, down, 0, 0,
            )),
            INPUT(type=INPUT_MOUSE, mi=MOUSEINPUT(
                0, 0, 0, up, 0, 0,
            )),
        ))
    return send_inputs(target, events)


def modified_click(target: BoundTarget, button: str, count: int,
                   modifiers: list[str]) -> int:
    normalized = [name.lower() for name in modifiers]
    if normalized != ["shift"]:
        fail("modified click permits only the exact Shift modifier")
    flags = {
        "left": (MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP),
        "right": (MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP),
        "middle": (MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP),
    }
    if button not in flags or not 1 <= count <= 3:
        fail("button/count is outside the bounded click contract")
    down, up = flags[button]
    events = [keyboard_event(KEYS[name]) for name in normalized]
    for _index in range(count):
        events.extend((
            INPUT(type=INPUT_MOUSE, mi=MOUSEINPUT(0, 0, 0, down, 0, 0)),
            INPUT(type=INPUT_MOUSE, mi=MOUSEINPUT(0, 0, 0, up, 0, 0)),
        ))
    events.extend(
        keyboard_event(KEYS[name], key_up=True) for name in reversed(normalized)
    )
    return send_inputs(target, events)


def scroll(target: BoundTarget, clicks: int) -> int:
    if clicks == 0 or abs(clicks) > 12:
        fail("wheel clicks must be between -12 and 12, excluding zero")
    wheel_delta = ctypes.c_ulong(clicks * 120).value
    return send_inputs(target, [INPUT(type=INPUT_MOUSE, mi=MOUSEINPUT(
        0, 0, wheel_delta, MOUSEEVENTF_WHEEL, 0, 0,
    ))])


def press_key(target: BoundTarget, name: str, repeat: int) -> int:
    key = name.lower()
    if key not in KEYS or not 1 <= repeat <= 20:
        fail("key/repeat is outside the bounded keyboard contract")
    events: list[INPUT] = []
    for _index in range(repeat):
        events.extend((keyboard_event(KEYS[key]), keyboard_event(KEYS[key], key_up=True)))
    return send_inputs(target, events)


def hold_key(target: BoundTarget, name: str, milliseconds: int,
             *, sleeper: Callable[[float], None] = time.sleep) -> int:
    key = name.lower()
    if key not in KEYS or not 25 <= milliseconds <= 5000:
        fail("held key must be allowlisted for 25-5000 milliseconds")
    remaining = milliseconds
    steps: list[INPUT | Delay] = []
    while remaining > 0:
        pulse = min(remaining, MAX_HOLD_PULSE_MILLIS)
        steps.extend((
            keyboard_event(KEYS[key]),
            Delay(pulse),
            keyboard_event(KEYS[key], key_up=True),
        ))
        remaining -= pulse
    return send_inputs(target, steps, sleeper=sleeper)


def relative_look(target: BoundTarget, dx: int, dy: int) -> int:
    if (dx == 0 and dy == 0) or abs(dx) > 2000 or abs(dy) > 2000:
        fail("relative look must be non-zero and each axis within -2000..2000")
    return send_inputs(target, [INPUT(type=INPUT_MOUSE, mi=MOUSEINPUT(
        dx, dy, 0, MOUSEEVENTF_MOVE, 0, 0,
    ))])


def validate_settlement_name(value: str, purpose: str) -> str:
    if purpose != "settlement_name":
        fail("text input is restricted to the settlement_name purpose")
    try:
        encoded_ascii = value.encode("ascii")
    except UnicodeEncodeError:
        fail("settlement name text must be strict ASCII")
    if not 1 <= len(encoded_ascii) <= 32:
        fail("settlement name text must be 1-32 ASCII bytes")
    if re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9 _'-]{0,31}", value) is None:
        fail("settlement name must start alphanumeric and use only safe name characters")
    if value != value.strip() or "  " in value:
        fail("settlement name may not have outer or repeated spaces")
    if value[0] in "/.\\:!@" or re.match(
            r"(?i)(?:command|cmd|execute|run)\b", value):
        fail("settlement name may not begin with a command prefix")
    return value


def type_settlement_name(target: BoundTarget, value: str, purpose: str) -> int:
    validate_settlement_name(value, purpose)
    encoded = value.encode("utf-16-le")
    units = [int.from_bytes(encoded[index:index + 2], "little")
             for index in range(0, len(encoded), 2)]
    events: list[INPUT] = []
    for unit in units:
        events.extend((unicode_event(unit), unicode_event(unit, key_up=True)))
    return send_inputs(target, events)


def transcript_tail(
    path: Path, session: str,
) -> tuple[str, int, dict[str, object] | None]:
    """Validate the chain and return tail, next sequence and pending intent."""
    if not path.exists():
        return "0" * 64, 1, None
    try:
        if path.stat().st_size > 32 * 1024 * 1024:
            fail("transcript exceeds the 32 MiB safety ceiling")
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as exc:
        fail(f"cannot read native input transcript: {exc}")
    if len(lines) > 200_000:
        fail("transcript exceeds the 200000-record safety ceiling")

    previous = "0" * 64
    for index, line in enumerate(lines, start=1):
        try:
            record = json.loads(line)
        except json.JSONDecodeError as exc:
            fail(f"transcript record {index} is not valid JSON: {exc}")
        if not isinstance(record, dict):
            fail(f"transcript record {index} is not an object")
        expected_keys = {
            "schemaVersion", "sequence", "session", "observedEpochMillis",
            "phase", "action", "detail", "window", "launchNonce",
            "launchIdentitySha256", "postcondition", "previousChainSha256",
            "chainSha256",
        }
        if set(record) != expected_keys:
            fail(f"transcript record {index} has an unexpected field set")
        if record.get("schemaVersion") != INPUT_RECORD_SCHEMA:
            fail(f"transcript record {index} has an unsupported schema")
        if record.get("sequence") != index:
            fail(f"transcript record {index} has a non-contiguous sequence")
        if record.get("session") != session:
            fail(f"transcript record {index} belongs to another native session")
        if record.get("previousChainSha256") != previous:
            fail(f"transcript record {index} breaks the previous-hash link")
        declared = record.get("chainSha256")
        if not isinstance(declared, str) or re.fullmatch(r"[0-9a-f]{64}", declared) is None:
            fail(f"transcript record {index} has a malformed chain hash")
        actual = chained_record_sha256(previous, record, "chainSha256")
        if actual != declared:
            fail(f"transcript record {index} has been changed")
        previous = declared
        expected_phase = "INTENT" if index % 2 == 1 else "COMPLETED"
        if record.get("phase") != expected_phase:
            fail(f"transcript record {index} breaks the intent/completed pairing")
        if expected_phase == "COMPLETED":
            intent = json.loads(lines[index - 2])
            for field in (
                "session", "action", "detail", "window", "launchNonce",
                "launchIdentitySha256",
            ):
                if record.get(field) != intent.get(field):
                    fail(f"transcript record {index} differs from its intent {field}")
            post = record.get("postcondition")
            if (not isinstance(post, dict)
                    or set(post) != {
                        "foregroundHwnd", "boundPid", "launchNonce",
                        "processCreationEpochMillis", "allEventsDelivered",
                        "cleanupRequired",
                    }
                    or post.get("foregroundHwnd") != record["window"]["hwnd"]
                    or post.get("boundPid") != record["window"]["pid"]
                    or post.get("launchNonce") != record["launchNonce"]
                    or type(post.get("processCreationEpochMillis")) is not int
                    or int(post["processCreationEpochMillis"]) <= 0
                    or post.get("allEventsDelivered") is not True
                    or post.get("cleanupRequired") is not False):
                fail(f"transcript record {index} lacks the exact completion postcondition")
        elif record.get("postcondition") is not None:
            fail(f"transcript record {index} intent has a forged postcondition")
    pending = json.loads(lines[-1]) if len(lines) % 2 == 1 else None
    return previous, len(lines) + 1, pending


def append_transcript(path: Path, session: str, target: BoundTarget,
                      phase: str, action: str, detail: dict[str, object],
                      postcondition: dict[str, object] | None = None) \
        -> dict[str, object]:
    if SESSION.fullmatch(session) is None:
        fail("session must contain 8-80 ASCII letters, digits, underscores or hyphens")
    transcript = canonical_native_log_path(
        path, "transcript", Path(INPUT_TRANSCRIPT_PATH).name
    )
    registry = canonical_native_log_path(
        target.registry_path, "launch registry", Path(LAUNCH_REGISTRY_PATH).name
    )
    try:
        registry = require_plain_path(
            registry, "launch registry", directory=False, nonempty=True
        )
    except EvidenceContractError as exc:
        fail(str(exc))
    if sha256_file(registry) != target.registry_sha256:
        fail("launch registry changed before transcript append")
    if not same_windows_path(str(transcript.parent), str(registry.parent)):
        fail("transcript and launch registry must belong to the same release run")
    previous, sequence, pending = transcript_tail(transcript, session)
    if phase == "INTENT":
        if pending is not None:
            fail("a previous native input intent is incomplete")
        if postcondition is not None:
            fail("native input intent may not declare a completion postcondition")
    elif phase == "COMPLETED":
        if pending is None:
            fail("native input completion has no matching intent")
        if (pending.get("action") != action
                or pending.get("detail") != detail
                or pending.get("window") != asdict(target.window)
                or pending.get("launchNonce") != target.launch.launch_nonce
                or pending.get("launchIdentitySha256")
                != target.launch.launch_identity_sha256):
            fail("native input completion differs from its pending intent")
        expected_postcondition = {
            "foregroundHwnd": target.window.hwnd,
            "boundPid": target.window.pid,
            "launchNonce": target.launch.launch_nonce,
            "processCreationEpochMillis": target.launch.process_creation_epoch_millis,
            "allEventsDelivered": True,
            "cleanupRequired": False,
        }
        if postcondition != expected_postcondition:
            fail("native input completion lacks the exact verified postcondition")
    else:
        fail("native input phase must be INTENT or COMPLETED")
    observed_epoch_millis = int(time.time() * 1000)
    if observed_epoch_millis < target.launch.registered_epoch_millis:
        fail("native input timestamp predates its launch registration")
    record: dict[str, object] = {
        "schemaVersion": INPUT_RECORD_SCHEMA,
        "sequence": sequence,
        "session": session,
        "observedEpochMillis": observed_epoch_millis,
        "phase": phase,
        "action": action,
        "detail": detail,
        "window": asdict(target.window),
        "launchNonce": target.launch.launch_nonce,
        "launchIdentitySha256": target.launch.launch_identity_sha256,
        "postcondition": postcondition,
        "previousChainSha256": previous,
    }
    record["chainSha256"] = chained_record_sha256(
        previous, record, "chainSha256"
    )
    try:
        with transcript.open("a", encoding="utf-8", newline="\n") as handle:
            handle.write(json.dumps(record, ensure_ascii=True, sort_keys=True) + "\n")
            handle.flush()
            os.fsync(handle.fileno())
    except OSError as exc:
        fail(f"cannot append native input transcript: {exc}")
    tail, next_sequence, pending_after = transcript_tail(transcript, session)
    if (tail != record["chainSha256"] or next_sequence != sequence + 1
            or (phase == "INTENT") != (pending_after is not None)):
        fail("native input transcript changed during its durable append")
    return record


def capture(window: WindowInfo, output: Path) -> dict[str, object]:
    destination = canonical_relative_child(output, CAPTURE_ROOT, "capture")
    if destination.suffix.lower() != ".png":
        fail("capture output must be PNG")
    try:
        from PIL import ImageGrab
        image = ImageGrab.grab(
            bbox=(window.client_left, window.client_top,
                  window.client_left + window.client_width,
                  window.client_top + window.client_height),
            all_screens=True,
        )
        image.save(destination, format="PNG")
    except (OSError, ValueError) as exc:
        fail(f"cannot capture Minecraft client: {exc}")
    payload = destination.read_bytes()
    return {
        "path": str(destination),
        "sha256": sha256_file(destination),
        "sizeBytes": len(payload),
        "width": window.client_width,
        "height": window.client_height,
    }


def parser() -> argparse.ArgumentParser:
    value = argparse.ArgumentParser(description=__doc__)
    sub = value.add_subparsers(dest="command", required=True)
    sub.add_parser("list")

    inspect = sub.add_parser("inspect")
    inspect.add_argument("--hwnd", type=int, required=True)

    for name in (
        "focus", "move", "click", "modified-click", "scroll", "key",
        "hold", "look", "text",
    ):
        action = sub.add_parser(name)
        action.add_argument("--hwnd", type=int, required=True)
        action.add_argument("--session", required=True)
        action.add_argument("--launch-registry", type=Path, required=True)
        action.add_argument("--launch-nonce", required=True)
        action.add_argument("--transcript", type=Path, required=True)
        if name in {"move", "click", "modified-click", "scroll"}:
            action.add_argument("--x", type=int, required=True)
            action.add_argument("--y", type=int, required=True)
        if name in {"click", "modified-click"}:
            action.add_argument("--button", choices=("left", "right", "middle"),
                                default="left")
            action.add_argument("--count", type=int, default=1)
            if name == "modified-click":
                action.add_argument(
                    "--modifiers", required=True,
                    help="must be the single modifier 'shift'",
                )
        elif name == "scroll":
            action.add_argument("--clicks", type=int, required=True)
        elif name == "key":
            action.add_argument("--key", required=True)
            action.add_argument("--repeat", type=int, default=1)
        elif name == "hold":
            action.add_argument("--key", required=True)
            action.add_argument("--milliseconds", type=int, required=True)
        elif name == "look":
            action.add_argument("--dx", type=int, required=True)
            action.add_argument("--dy", type=int, required=True)
        elif name == "text":
            action.add_argument("--value", required=True)
            action.add_argument("--purpose", required=True,
                                choices=("settlement_name",))

    screenshot = sub.add_parser("screenshot")
    screenshot.add_argument("--hwnd", type=int, required=True)
    screenshot.add_argument("--session", required=True)
    screenshot.add_argument("--launch-registry", type=Path, required=True)
    screenshot.add_argument("--launch-nonce", required=True)
    screenshot.add_argument("--output", type=Path, required=True)
    return value


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    initialize_per_monitor_dpi()
    if args.command == "list":
        print(json.dumps([asdict(window) for window in list_minecraft_windows()], indent=2))
        return 0
    if args.command == "inspect":
        print(json.dumps(asdict(inspect_window(args.hwnd)), indent=2))
        return 0

    target = load_bound_target(
        args.hwnd, args.session, args.launch_nonce, args.launch_registry
    )
    window = target.window
    if args.command == "screenshot":
        focus_window(target)
        require_bound_foreground(target)
        captured = capture(window, args.output)
        require_bound_foreground(target)
        print(json.dumps(captured, indent=2))
        return 0

    # Build and validate the complete action description before writing its
    # INTENT.  Once an intent exists, only its exact COMPLETED partner may be
    # appended; a delivery/focus crash therefore poisons the run fail-closed
    # instead of silently omitting an input that may have reached Minecraft.
    detail: dict[str, object] = {}
    if args.command == "focus":
        pass
    elif args.command == "move":
        screen_x, screen_y = client_screen_point(window, args.x, args.y)
        detail = {"clientX": args.x, "clientY": args.y,
                  "screenX": screen_x, "screenY": screen_y}
    elif args.command == "click":
        if not 1 <= args.count <= 3:
            fail("button/count is outside the bounded click contract")
        screen_x, screen_y = client_screen_point(window, args.x, args.y)
        detail = {"clientX": args.x, "clientY": args.y,
                  "screenX": screen_x, "screenY": screen_y,
                  "button": args.button, "count": args.count}
    elif args.command == "modified-click":
        modifiers = [
            name.strip().lower() for name in args.modifiers.split(",")
            if name.strip()
        ]
        if not 1 <= args.count <= 3 or modifiers != ["shift"]:
            fail("modified click arguments are outside the bounded contract")
        screen_x, screen_y = client_screen_point(window, args.x, args.y)
        detail = {"clientX": args.x, "clientY": args.y,
                  "screenX": screen_x, "screenY": screen_y,
                  "button": args.button, "count": args.count,
                  "modifiers": modifiers}
    elif args.command == "scroll":
        if args.clicks == 0 or abs(args.clicks) > 12:
            fail("wheel clicks must be between -12 and 12, excluding zero")
        screen_x, screen_y = client_screen_point(window, args.x, args.y)
        detail = {"clientX": args.x, "clientY": args.y,
                  "screenX": screen_x, "screenY": screen_y,
                  "clicks": args.clicks}
    elif args.command == "key":
        if args.key.lower() not in KEYS or not 1 <= args.repeat <= 20:
            fail("key/repeat is outside the bounded keyboard contract")
        detail = {"key": args.key.lower(), "repeat": args.repeat}
    elif args.command == "hold":
        if args.key.lower() not in KEYS or not 25 <= args.milliseconds <= 5000:
            fail("held key is outside the bounded keyboard contract")
        detail = {"key": args.key.lower(), "milliseconds": args.milliseconds}
    elif args.command == "look":
        if ((args.dx == 0 and args.dy == 0)
                or abs(args.dx) > 2000 or abs(args.dy) > 2000):
            fail("relative look is outside the bounded mouse contract")
        detail = {"dx": args.dx, "dy": args.dy}
    elif args.command == "text":
        validate_settlement_name(args.value, args.purpose)
        detail = {
            "purpose": "settlement_name",
            "text": args.value,
            "asciiLength": len(args.value.encode("ascii")),
        }
    else:
        fail(f"unsupported command {args.command!r}")

    append_transcript(
        args.transcript, args.session, target, "INTENT", args.command, detail
    )
    focus_window(target)
    if args.command == "move":
        move_to_client(target, args.x, args.y)
    elif args.command == "click":
        move_to_client(target, args.x, args.y)
        click(target, args.button, args.count)
    elif args.command == "modified-click":
        move_to_client(target, args.x, args.y)
        modified_click(target, args.button, args.count, modifiers)
    elif args.command == "scroll":
        move_to_client(target, args.x, args.y)
        scroll(target, args.clicks)
    elif args.command == "key":
        press_key(target, args.key, args.repeat)
    elif args.command == "hold":
        hold_key(target, args.key, args.milliseconds)
    elif args.command == "look":
        relative_look(target, args.dx, args.dy)
    elif args.command == "text":
        type_settlement_name(target, args.value, args.purpose)
    # Focus is itself the complete action for the focus command.
    require_bound_foreground(target)
    postcondition = {
        "foregroundHwnd": target.window.hwnd,
        "boundPid": target.window.pid,
        "launchNonce": target.launch.launch_nonce,
        "processCreationEpochMillis": target.launch.process_creation_epoch_millis,
        "allEventsDelivered": True,
        "cleanupRequired": False,
    }
    record = append_transcript(
        args.transcript, args.session, target, "COMPLETED", args.command,
        detail, postcondition,
    )
    print(json.dumps(record, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except NativeInputError as exc:
        print(f"windows-native-input: {exc}", file=sys.stderr)
        raise SystemExit(1)
