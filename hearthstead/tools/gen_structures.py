#!/usr/bin/env python3
"""Generate GameTest structure-template NBT files for Hearthstead (MC 1.20.1 Forge).

Pure Python 3.11 stdlib. Writes vanilla structure-template NBT files
(big-endian, gzip-compressed, DataVersion 3465) into
src/main/resources/data/hearthstead/structures/ for use with the Forge
GameTest framework.

Every position in each template volume is listed explicitly (air included),
matching vanilla gametest template practice so the framework clears/places
the full test area.

NBT encoding notes (cross-checked against vanilla NbtIo):
  - Named tag  = 1 byte type id, TAG_String name (2-byte BE unsigned length
    + UTF-8 bytes), then the payload. All strings here are ASCII, so Java's
    "modified UTF-8" is byte-identical to standard UTF-8.
  - Root       = a single named TAG_Compound with empty name "".
  - TAG_End(0)      : no payload; terminates compounds.
  - TAG_Int(3)      : 4-byte big-endian signed.
  - TAG_String(8)   : 2-byte BE unsigned length + UTF-8 bytes.
  - TAG_List(9)     : 1 byte element type, 4-byte BE signed count, then
    unnamed payloads. Empty lists are written with element type 0 (TAG_End)
    and count 0 — this is what vanilla itself writes and tolerates.
  - TAG_Compound(10): sequence of named tags terminated by a TAG_End byte.
  - The whole stream is gzip-compressed.

Usage:
    python3 tools/gen_structures.py            # generate all templates
    python3 tools/gen_structures.py --verify   # re-read + validate the files
"""

import argparse
import gzip
import struct
import sys
from pathlib import Path

DATA_VERSION = 3465  # Minecraft 1.20.1

REPO_ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = REPO_ROOT / "src" / "main" / "resources" / "data" / "hearthstead" / "structures"

# ---------------------------------------------------------------------------
# Tag type ids
# ---------------------------------------------------------------------------
TAG_END = 0
TAG_BYTE = 1
TAG_SHORT = 2
TAG_INT = 3
TAG_LONG = 4
TAG_FLOAT = 5
TAG_DOUBLE = 6
TAG_BYTE_ARRAY = 7
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10
TAG_INT_ARRAY = 11
TAG_LONG_ARRAY = 12

# Typed value model used by both the writer and the parser, so that a parsed
# tree compares equal to the tree it was generated from and re-serializes
# byte-identically:
#   TAG_BYTE/SHORT/INT/LONG -> int
#   TAG_FLOAT/DOUBLE        -> float
#   TAG_BYTE_ARRAY          -> bytes
#   TAG_INT_ARRAY/LONG_ARRAY-> list[int]
#   TAG_STRING              -> str
#   TAG_LIST                -> (element_type, [payload, ...])
#   TAG_COMPOUND            -> dict name -> (type, payload)   (ordered)

# ---------------------------------------------------------------------------
# Writer
# ---------------------------------------------------------------------------


def _write_payload(tag_type: int, value, out: bytearray) -> None:
    if tag_type == TAG_BYTE:
        out += struct.pack(">b", value)
    elif tag_type == TAG_SHORT:
        out += struct.pack(">h", value)
    elif tag_type == TAG_INT:
        out += struct.pack(">i", value)
    elif tag_type == TAG_LONG:
        out += struct.pack(">q", value)
    elif tag_type == TAG_FLOAT:
        out += struct.pack(">f", value)
    elif tag_type == TAG_DOUBLE:
        out += struct.pack(">d", value)
    elif tag_type == TAG_BYTE_ARRAY:
        out += struct.pack(">i", len(value))
        out += bytes(value)
    elif tag_type == TAG_STRING:
        raw = value.encode("utf-8")
        out += struct.pack(">H", len(raw))
        out += raw
    elif tag_type == TAG_LIST:
        elem_type, items = value
        if len(items) == 0 and elem_type != TAG_END:
            # Vanilla writes empty lists with element type TAG_End(0).
            elem_type = TAG_END
        out += struct.pack(">b", elem_type)
        out += struct.pack(">i", len(items))
        for item in items:
            _write_payload(elem_type, item, out)
    elif tag_type == TAG_COMPOUND:
        for name, (child_type, child_value) in value.items():
            _write_named(child_type, name, child_value, out)
        out += struct.pack(">b", TAG_END)
    elif tag_type == TAG_INT_ARRAY:
        out += struct.pack(">i", len(value))
        out += struct.pack(f">{len(value)}i", *value)
    elif tag_type == TAG_LONG_ARRAY:
        out += struct.pack(">i", len(value))
        out += struct.pack(f">{len(value)}q", *value)
    else:
        raise ValueError(f"cannot write tag type {tag_type}")


def _write_named(tag_type: int, name: str, value, out: bytearray) -> None:
    out += struct.pack(">b", tag_type)
    raw = name.encode("utf-8")
    out += struct.pack(">H", len(raw))
    out += raw
    _write_payload(tag_type, value, out)


def to_nbt_bytes(root: dict) -> bytes:
    """Serialize a root compound (dict, typed model) as a named root tag
    with empty name."""
    out = bytearray()
    _write_named(TAG_COMPOUND, "", root, out)
    return bytes(out)


# ---------------------------------------------------------------------------
# Parser
# ---------------------------------------------------------------------------


class _Reader:
    def __init__(self, data: bytes):
        self.data = data
        self.pos = 0

    def take(self, n: int) -> bytes:
        if self.pos + n > len(self.data):
            raise ValueError("unexpected end of NBT data")
        chunk = self.data[self.pos : self.pos + n]
        self.pos += n
        return chunk

    def unpack(self, fmt: str):
        return struct.unpack(fmt, self.take(struct.calcsize(fmt)))[0]

    def at_end(self) -> bool:
        return self.pos == len(self.data)


def _read_string(r: _Reader) -> str:
    length = r.unpack(">H")
    return r.take(length).decode("utf-8")


def _read_payload(tag_type: int, r: _Reader):
    if tag_type == TAG_BYTE:
        return r.unpack(">b")
    if tag_type == TAG_SHORT:
        return r.unpack(">h")
    if tag_type == TAG_INT:
        return r.unpack(">i")
    if tag_type == TAG_LONG:
        return r.unpack(">q")
    if tag_type == TAG_FLOAT:
        return r.unpack(">f")
    if tag_type == TAG_DOUBLE:
        return r.unpack(">d")
    if tag_type == TAG_BYTE_ARRAY:
        length = r.unpack(">i")
        return r.take(length)
    if tag_type == TAG_STRING:
        return _read_string(r)
    if tag_type == TAG_LIST:
        elem_type = r.unpack(">b")
        count = r.unpack(">i")
        if count < 0:
            raise ValueError(f"negative list length {count}")
        if elem_type == TAG_END and count > 0:
            raise ValueError("non-empty TAG_List with element type TAG_End")
        return (elem_type, [_read_payload(elem_type, r) for _ in range(count)])
    if tag_type == TAG_COMPOUND:
        result = {}
        while True:
            child_type = r.unpack(">b")
            if child_type == TAG_END:
                return result
            name = _read_string(r)
            if name in result:
                raise ValueError(f"duplicate key {name!r} in compound")
            result[name] = (child_type, _read_payload(child_type, r))
    if tag_type == TAG_INT_ARRAY:
        length = r.unpack(">i")
        return list(struct.unpack(f">{length}i", r.take(4 * length)))
    if tag_type == TAG_LONG_ARRAY:
        length = r.unpack(">i")
        return list(struct.unpack(f">{length}q", r.take(8 * length)))
    raise ValueError(f"cannot read tag type {tag_type}")


def parse_nbt(data: bytes):
    """Parse uncompressed NBT. Returns (root_name, root_compound_dict)."""
    r = _Reader(data)
    tag_type = r.unpack(">b")
    if tag_type != TAG_COMPOUND:
        raise ValueError(f"root tag must be TAG_Compound, got {tag_type}")
    name = _read_string(r)
    root = _read_payload(TAG_COMPOUND, r)
    if not r.at_end():
        raise ValueError(f"{len(r.data) - r.pos} trailing bytes after root tag")
    return name, root


# ---------------------------------------------------------------------------
# Structure templates
# ---------------------------------------------------------------------------


def palette_entry(name: str, properties: dict | None = None) -> dict:
    entry = {"Name": (TAG_STRING, name)}
    if properties:
        entry["Properties"] = (
            TAG_COMPOUND,
            {key: (TAG_STRING, val) for key, val in properties.items()},
        )
    return entry


def build_template(size, palette, state_at) -> dict:
    """Build a structure-template root compound. Lists EVERY position in the
    volume (air included) so GameTest clears the whole area on placement."""
    sx, sy, sz = size
    blocks = []
    for y in range(sy):
        for z in range(sz):
            for x in range(sx):
                blocks.append(
                    {
                        "pos": (TAG_LIST, (TAG_INT, [x, y, z])),
                        "state": (TAG_INT, state_at(x, y, z)),
                    }
                )
    return {
        "size": (TAG_LIST, (TAG_INT, [sx, sy, sz])),
        "entities": (TAG_LIST, (TAG_END, [])),  # empty list, element type 0
        "blocks": (TAG_LIST, (TAG_COMPOUND, blocks)),
        "palette": (TAG_LIST, (TAG_COMPOUND, palette)),
        "DataVersion": (TAG_INT, DATA_VERSION),
    }


# empty5 / empty16: palette 0 = stone, 1 = air; stone floor at y=0.
_FLOOR_PALETTE = [palette_entry("minecraft:stone"), palette_entry("minecraft:air")]


def _floor_state(x: int, y: int, z: int) -> int:
    return 0 if y == 0 else 1


# farm9: palette 0 = farmland(moisture=7), 1 = water, 2 = air.
_FARM_PALETTE = [
    palette_entry("minecraft:farmland", {"moisture": "7"}),
    palette_entry("minecraft:water"),
    palette_entry("minecraft:air"),
]


def _farm_state(x: int, y: int, z: int) -> int:
    if y > 0:
        return 2
    if (x, z) == (4, 4):
        return 1
    return 0


STRUCTURES = {
    "empty5.nbt": lambda: build_template((5, 5, 5), _FLOOR_PALETTE, _floor_state),
    "empty16.nbt": lambda: build_template((16, 8, 16), _FLOOR_PALETTE, _floor_state),
    "farm9.nbt": lambda: build_template((9, 5, 9), _FARM_PALETTE, _farm_state),
}


# ---------------------------------------------------------------------------
# Generate / verify
# ---------------------------------------------------------------------------


def generate() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for filename, builder in STRUCTURES.items():
        raw = to_nbt_bytes(builder())
        compressed = gzip.compress(raw, compresslevel=9, mtime=0)
        path = OUT_DIR / filename
        path.write_bytes(compressed)
        print(f"wrote {path} ({len(compressed)} bytes gzip, {len(raw)} bytes raw)")


def verify() -> None:
    failures = 0
    for filename, builder in STRUCTURES.items():
        path = OUT_DIR / filename
        print(f"--- {filename} ---")
        try:
            _verify_one(path, builder())
        except (AssertionError, ValueError, OSError) as exc:
            failures += 1
            print(f"  FAIL: {exc}")
    if failures:
        sys.exit(f"{failures} file(s) failed verification")
    print("All files verified OK.")


def _verify_one(path: Path, expected_root: dict) -> None:
    compressed = path.read_bytes()

    # 1. gzip magic bytes.
    assert compressed[:2] == b"\x1f\x8b", f"bad gzip magic: {compressed[:2].hex()}"

    # 2. Parse.
    raw = gzip.decompress(compressed)
    root_name, root = parse_nbt(raw)
    assert root_name == "", f"root tag name must be empty, got {root_name!r}"

    # 3. Byte-exact round trip: re-serialize the parsed tree.
    assert to_nbt_bytes(root) == raw, "re-serialized NBT differs from original"

    # 4. Logical equality with the freshly built expected structure.
    assert root == expected_root, "parsed tree differs from expected structure"

    # 5. Extract and sanity-check the fields.
    size_type, size = root["size"]
    assert (size_type, size[0]) == (TAG_LIST, TAG_INT) and len(size[1]) == 3
    sx, sy, sz = size[1]

    dv_type, dv = root["DataVersion"]
    assert (dv_type, dv) == (TAG_INT, DATA_VERSION), f"DataVersion {dv} != {DATA_VERSION}"

    ent_type, (ent_elem, ent_items) = root["entities"]
    assert ent_type == TAG_LIST and ent_elem == TAG_END and ent_items == []

    _, (pal_elem, pal_entries) = root["palette"]
    assert pal_elem == TAG_COMPOUND and pal_entries

    _, (blk_elem, blk_entries) = root["blocks"]
    assert blk_elem == TAG_COMPOUND
    volume = sx * sy * sz
    assert len(blk_entries) == volume, (
        f"expected {volume} block entries (all positions listed), got {len(blk_entries)}"
    )

    seen_pos = set()
    counts = [0] * len(pal_entries)
    for entry in blk_entries:
        _, (pos_elem, pos) = entry["pos"]
        assert pos_elem == TAG_INT and len(pos) == 3
        x, y, z = pos
        assert 0 <= x < sx and 0 <= y < sy and 0 <= z < sz, f"pos out of bounds: {pos}"
        assert (x, y, z) not in seen_pos, f"duplicate pos: {pos}"
        seen_pos.add((x, y, z))
        _, state = entry["state"]
        assert 0 <= state < len(pal_entries), f"state {state} outside palette"
        counts[state] += 1

    # 6. Report.
    print(f"  gzip magic OK, {len(compressed)} bytes compressed / {len(raw)} raw")
    print(f"  size=[{sx},{sy},{sz}]  DataVersion={dv}  entities={len(ent_items)}  "
          f"blocks={len(blk_entries)}")
    for idx, entry in enumerate(pal_entries):
        _, name = entry["Name"]
        props = ""
        if "Properties" in entry:
            _, prop_map = entry["Properties"]
            props = "[" + ",".join(f"{k}={v}" for k, (_, v) in prop_map.items()) + "]"
        print(f"  palette[{idx}] {name}{props}: {counts[idx]} block(s)")
    print("  round-trip: byte-exact OK, matches expected structure OK")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--verify",
        action="store_true",
        help="re-read the generated files, print their contents, and assert "
        "round-trip correctness instead of generating",
    )
    args = parser.parse_args()
    if args.verify:
        verify()
    else:
        generate()


if __name__ == "__main__":
    main()
