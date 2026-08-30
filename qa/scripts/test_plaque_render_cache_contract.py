#!/usr/bin/env python3
"""Fail-closed static contract for the plaque render allocation cache."""

from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "hearthstead-neoforge" / "src" / "main" / "java" / "com" / "hearthstead"
RENDERER = JAVA / "client" / "render" / "PlaqueRenderer.java"
CACHE = JAVA / "client" / "render" / "PlaqueSheetCache.java"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def main() -> None:
    renderer = RENDERER.read_text(encoding="utf-8")
    cache = CACHE.read_text(encoding="utf-8")
    compact_renderer = re.sub(r"\s+", " ", renderer)

    require("PlaqueSheet.of(" not in renderer,
            "PlaqueRenderer must never rebuild a PlaqueSheet directly per frame")
    require(
        "sheets.getOrCreate(plaque, plaque.type(), plaque.state(), "
        "plaque.lastSurvey(), plaque.occupants(), plaque.capacity())"
        in compact_renderer,
        "renderer cache call must carry every PlaqueSheet input",
    )
    require(cache.count("PlaqueSheet.of(") == 1,
            "PlaqueSheet construction must have one cache-owned call site")
    require("static final int MAX_ENTRIES = 256;" in cache,
            "cache must retain the reviewed hard 256-entry bound")
    require("new LinkedHashMap<>(32, 0.75F, true)" in cache,
            "cache must remain access-ordered LRU")
    require("return size() > PlaqueSheetCache.this.maximumSize;" in cache,
            "cache must evict immediately above its configured bound")
    require("List.copyOf(survey)" in cache,
            "cache must own an immutable survey snapshot")

    for exact_input in (
        "type == candidateType",
        "state == candidateState",
        "occupants == candidateOccupants",
        "capacity == candidateCapacity",
        "survey.equals(candidateSurvey)",
    ):
        require(exact_input in cache,
                f"cache invalidation lost exact input: {exact_input}")

    for forbidden in ("currentTimeMillis", "nanoTime", "CompletableFuture", "Thread("):
        require(forbidden not in cache,
                f"cache must rebuild synchronously without TTL/async route: {forbidden}")

    print("plaque-render cache contract: PASS")


if __name__ == "__main__":
    main()
