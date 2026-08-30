#!/usr/bin/env python3
"""Real-font contract for the compact Settler Citizen Dossier.

This mirrors the small amount of responsive geometry in
``SettlerScreen.compactLayout`` and measures the live EN/NB language files with
Minecraft's actual client font through ``tools/mcfont.py``.  It deliberately
checks the 320px floor as well as the reviewed 427px target; rectangle-only
tests cannot detect a translated label that technically stays inside its cell
only because runtime ellipsis hid most of it.
"""

import json
import os
import sys

from mcfont import McFont


HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
LANG_ROOT = os.path.join(
    ROOT, "src", "main", "resources", "assets", "hearthstead", "lang")

ATTRIBUTES = (
    "strength", "stamina", "wits", "dexterity",
    "spirit", "perception", "focus", "presence",
)
EFFECTS = (
    "physical_output", "fatigue_pace", "learning_rate",
    "precision_execution", "morale_resilience", "target_discovery",
    "task_continuity", "social_influence", "carry_capacity",
    "lumber_contacts",
)


def language(locale):
    path = os.path.join(LANG_ROOT, f"{locale}.json")
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def geometry(viewport_width):
    panel = min(411, max(1, viewport_width - 16))
    content = max(1, panel - 16)
    frame_gap = min(6, max(1, content - 2))
    summary = min(142, max(1, content * 36 // 100))
    if summary + frame_gap >= content:
        summary = max(1, content - frame_gap - 1)
    attributes = max(1, content - summary - frame_gap)
    inset = min(8, max(0, (attributes - 2) // 4))
    attribute_content = max(1, attributes - inset * 2)
    attribute_gap = min(9, max(1, attribute_content // 12))
    first = max(1, (attribute_content - attribute_gap) // 2)
    second = max(1, attribute_content - attribute_gap - first)

    footer_gap = min(4, max(0, (content - 4) // 3))
    available = max(4, content - footer_gap * 3)
    regular = min(92, max(1, available // 4))
    close = max(1, available - regular * 3)
    return {
        "panel": panel,
        "content": content,
        "summary": summary,
        "attributes": attributes,
        "attribute_cells": (first, second),
        "job_area": attribute_content,
        "footer": (regular, regular, regular, close),
    }


def format_mc(template, *args):
    """Substitute the Java translations used by this contract."""
    rendered = template
    for index, value in enumerate(args, start=1):
        rendered = rendered.replace(f"%{index}$s", str(value))
    for value in args:
        if "%s" in rendered:
            rendered = rendered.replace("%s", str(value), 1)
    return rendered


def require_fit(font, failures, locale, viewport, name, text, box):
    width = font.width(text)
    if width > box:
        failures.append(
            f"{locale} {viewport}px {name}: {text!r} is {width}px, box {box}px")
    return width


def run():
    font = McFont.load()
    failures = []
    selections = {}

    for locale in ("en_us", "nb_no"):
        lang = language(locale)
        for viewport in (320, 427):
            geo = geometry(viewport)
            summary_inner = geo["summary"] - 16
            max_pace_width = font.width("100%")
            right_now = lang["hearthstead.settler.compact.right_now"]
            pace = lang["hearthstead.settler.compact.pace"]
            show_pace = (font.width(right_now) + font.width(pace)
                         + max_pace_width + 8 <= summary_inner)
            right_box = (summary_inner - max_pace_width - 4
                         - (font.width(pace) + 4 if show_pace else 0))
            require_fit(font, failures, locale, viewport,
                        "right-now", right_now, right_box)
            if show_pace:
                require_fit(font, failures, locale, viewport,
                            "pace", pace, font.width(pace))

            for need_key in ("hunger", "energy", "morale"):
                text = lang[f"hearthstead.gui.{need_key}"]
                width = require_fit(font, failures, locale, viewport,
                                    f"need-{need_key}", text, 46)
                if width + 4 + font.width("100") > 62:
                    failures.append(
                        f"{locale} {viewport}px need-{need_key}: label/value overlap")

            require_fit(font, failures, locale, viewport, "attributes-heading",
                        lang["hearthstead.settler.compact.attributes"],
                        geo["attributes"] - 16)
            require_fit(font, failures, locale, viewport, "mayor-mark",
                        lang["hearthstead.settler.mayor_mark"], 50)

            value_width = font.width("100/100")
            selected = {}
            for index, attribute in enumerate(ATTRIBUTES):
                cell = geo["attribute_cells"][index % 2]
                label_box = cell - value_width - 3
                preferred = lang[f"hearthstead.attribute.{attribute}.compact"]
                abbreviation = lang[f"hearthstead.attribute.{attribute}.abbr"]
                label = preferred if font.width(preferred) <= label_box \
                    else abbreviation
                require_fit(font, failures, locale, viewport,
                            f"attribute-{attribute}", label, label_box)
                selected[attribute] = label
            selections[(locale, viewport)] = selected

            # Active requests reserve 24px for the real item icon.
            request_card = geo["summary"] - 16
            request_text_box = request_card - 30 - 5
            require_fit(font, failures, locale, viewport, "request-heading",
                        lang["hearthstead.settler.compact.request"],
                        request_text_box)

            require_fit(font, failures, locale, viewport, "no-job-band",
                        lang["hearthstead.settler.compact.job.none"],
                        geo["job_area"] - 12)

            column = max(1, (geo["job_area"] - 12 - 6) // 2)
            job_template = lang["hearthstead.settler.compact.job.value"]
            for attribute in ATTRIBUTES:
                job_label = format_mc(job_template, selected[attribute], 100)
                require_fit(font, failures, locale, viewport,
                            f"job-label-{attribute}", job_label, column)
            for effect in EFFECTS:
                require_fit(font, failures, locale, viewport,
                            f"job-effect-{effect}",
                            lang[f"hearthstead.settler.compact.job.effect.{effect}.band"],
                            column)
            require_fit(font, failures, locale, viewport, "lumber-live-band",
                        format_mc(lang["hearthstead.settler.compact.job.lumber.band"],
                                  9, 64), column)
            require_fit(font, failures, locale, viewport, "stamina-live-band",
                        format_mc(lang["hearthstead.settler.compact.job.stamina.band"],
                                  100), column)

            footer_keys = (
                "hearthstead.settler.compact.inventory",
                "hearthstead.settler.compact.workplace",
                "hearthstead.settler.compact.actions",
                "hearthstead.settler.close",
            )
            for key, button_width in zip(footer_keys, geo["footer"]):
                require_fit(font, failures, locale, viewport,
                            f"footer-{key.rsplit('.', 1)[-1]}", lang[key],
                            button_width - 8)

            require_fit(font, failures, locale, viewport, "actions-title",
                        lang["hearthstead.settler.actions.title"], geo["content"])
            require_fit(font, failures, locale, viewport, "actions-help",
                        lang["hearthstead.settler.actions.help"], geo["content"])

    if failures:
        print("FAIL  compact Settler real-font contract", file=sys.stderr)
        for failure in failures:
            print(f"  - {failure}", file=sys.stderr)
        return 1

    for locale in ("en_us", "nb_no"):
        for viewport in (320, 427):
            labels = ", ".join(selections[(locale, viewport)].values())
            print(f"PASS  {locale} {viewport}px: {labels}")
    print("PASS  all compact Settler labels use real Minecraft font widths")
    return 0


if __name__ == "__main__":
    raise SystemExit(run())
