# UI consistency — audit and standard (UI-consistency lane, 26 Sep)

Owner's order: "Make sure the UI is consistent so it doesn't look different across tabs and screens. Fix it."
Reference: the Banner screen (`HearthScreen` + `client/ui2`). Stills: `videos/ui/banner-stills/figures/show-contact.png`.

## The standard (every window except the Banner itself)

| Piece | Standard | Kit |
|---|---|---|
| Frame | Walnut board with iron corner brackets (FRAME 6), crest hanging at the top left, 2 px header rule, one parchment page | `Ui2Frame.draw(g, Ui2FrameLayout)` |
| Geometry | `Ui2FrameLayout.centred(vw, vh, prefW, prefH, subtitle)`; header 20 (28 with a subtitle), page = header rule + 4 to bottom − 10, content = page inset 8 | `Ui2FrameLayout` (pure, unit-tested) |
| Title | Serif small caps (`Ui2Serif.Size.TITLE`) in `TEXT_ON_WOOD` on the wood; optional muted subtitle line | `Ui2Frame.title(...)` |
| Close | 11 px wooden key "×" at the header's right edge, tooltip "Close (Esc)"; Esc closes | `Ui2Frame.closeKey(layout, onClose)` |
| Palette | `Ui2Palette` only: ink `INK / INK_SOFT / INK_MUTED / INK_DISABLED` on parchment; `TEXT_ON_WOOD(_MUTED)` on wood; burgundy = the one primary action; FOREST = good/selected bar; DANGER only with a glyph/word | `Ui2Palette`, `BannerChrome` |
| Fonts | Vanilla font for body (1x, no shadow on parchment); serif small caps for window titles and section headings only | `Ui2Serif`, `Ui2Frame.heading` |
| Spacing | 4 / 8 / 12 / 16 (`Ui2FrameLayout.S/M/L/XL`); hairline rules instead of boxes | |
| Buttons | Primary: `Ui2Button.banner` (burgundy, serif label, chevron), 20 high, one per view. Secondary: `Ui2Button.secondary` text button, 12 high. Destructive: `Ui2Button.danger`, 20 high. Disabled = padlock + reason tooltip | `Ui2Button`, `Ui2FrameLayout.footerButtons` |
| Tabs | Text tabs with sliding underline, 14 high, 12 gap | `Ui2Tabs` |
| Lists | 14 px rows (12 in dense tables), hover tint, selected bar | `Ui2RowButton`, `Ui2Surface.row` |
| Status | Inset strip with a 2 px state bar + glyph (check / diamond / hollow diamond) + muted label; never a coloured box | `Ui2Frame.status(..., Tone)` |
| Tooltips | Vanilla tooltip; every action has one; disabled = "Action" + grey reason line; navigation delayed 650 ms | `Ui2Tips.tip / enable / why / nav` |
| Slots | Sunken 18 px parchment well | `Ui2Surface.slotWell` |
| HUD | Translucent walnut plate, gold top line, `TEXT_ON_WOOD` text, gold for keys/numbers | `Ui2Hud.plate`, `Ui2Hud.TEXT/MUTED/KEY/WARN` |
| Scaling | Layout checked at GUI 2/3/4 (960x540, 640x360, 480x270) and 427x240: nothing clipped, nothing overlapping | `Ui2LayoutAssert.GUI_2_TO_4` |

## Migration recipe (per screen)

1. Keep every packet, menu, slot coordinate and behaviour. Only drawing and widget classes change.
2. Replace the local window (`HearthMaterials.frame/header`, `HsUi.window/paperWindow`, `pixelWindow`) with a `Ui2FrameLayout` built once in `init()` and `Ui2Frame.draw` + `Ui2Frame.title` in `render()`.
   Container screens: slot x/y come from the server menu and must not move — size the frame so the slots land on the page (`Ui2FrameLayout.at(leftPos, topPos, imageWidth, imageHeight, false)`), draw `Ui2Surface.slotWell` under each slot.
3. Delete the private `PixelButton`/`TabButton`/`StockButton` copies; use `Ui2Button.banner` (primary), `.secondary` (text), `.danger`, `Ui2Tabs`, `Ui2RowButton`, `Ui2Frame.closeKey`. A "Close" footer button becomes the close key (Esc keeps working).
4. Replace `HsUiTokens.*`, `HearthPixelSurface.INK/MUTED/LIGHT_TEXT` and raw hex with `Ui2Palette` tokens. Replace `HsUi.statusPaper/taskPaper/card/inset` with `Ui2Frame.status`, `Ui2Surface.rule`, `Ui2Surface.row`, `Ui2Palette.INSET` strips.
5. Disabled buttons: `Ui2Tips.enable(button, enabled, tip, reason)`.
6. Add or extend a JUnit layout test using `Ui2LayoutAssert.GUI_2_TO_4` for whatever geometry is a pure function.
7. Compile privately; never leave the shared tree broken between edits.

Reference migration: `WorkZoneConfirmScreen` (+ `WorkZoneConfirmScreenLayoutTest`).

## Audit (before) — from code, 26 Sep 11:30

Legend: **W** = walnut board + crest + serif title (Banner kit). **P** = old paper kit (`HearthMaterials.frame`, moss header strip, moss/red atlas buttons). **S** = ui2 paper sheet without the wood.

| Screen | Frame / chrome | Title | Palette | Fonts | Spacing | Buttons | Tabs | Close | Tooltips | GUI 2–4 |
|---|---|---|---|---|---|---|---|---|---|---|
| Banner: Overview / Settlers / Buildings / Storage / Journey / Tech Tree rail | W (reference) | serif TITLE + subtitle | Ui2 | serif headings + vanilla | 4/8 | banner/secondary | left rail plates | wood key | nav 650 ms, disabled reasons | tested (BannerSheetLayoutTest) |
| Banner popouts: Mayor, Journey, Recruit, Aftermath | **P** (`ModalPixels` moss header, `ModalButton` moss/red) | vanilla, light on moss | HsUiTokens + local hex | vanilla | local | moss/red atlas | — | "Close" button | partial | fixed geometry |
| SettlerScreen (sheet) | W | serif TITLE | Ui2 + 6 hex | serif | 4/8 | Ui2 | own | wood key | yes | tested |
| SettlerInventoryScreen | **P** | vanilla | HsUi + 32 hex | vanilla | local | none / atlas | — | none | partial | no test |
| TechTreeScreen (new, tech-tree lane) | W-ish board + parchment, own counters | own | Ui2 + 24 local hex | vanilla | own | own | — | own | own | own test |
| DevelopmentScreen (old tech tree) | **P** | vanilla | HsUi + 61 hex | vanilla | local | atlas | — | — | — | tested |
| ResearchScreen | **P** | vanilla | HsUi + 13 hex | vanilla | local | local PixelButton | — | "Close" button | partial | tested |
| PlaqueScreen (+ plaque sheet) | **P** (`paperWindow`) | vanilla, centred | HsUi + 23 hex | vanilla | local | local PixelButton moss/red | local TabButton (moss) | "Close" button | partial | render-cache test only |
| StorageScreen | **P** | vanilla | HsUi + 14 hex | vanilla | local | local StockButton | — | "Close" button | partial | no |
| CoinMerchantScreen | **P** (`paperWindow`) | vanilla | HsUi + 12 hex | vanilla | local | vanilla slots | — | none | partial | no |
| EmblemShopScreen | **P** | vanilla | HsUi + 9 hex | vanilla | local | local PixelButton | — | "Close" button | partial | no |
| EquipmentRequestListScreen | **P** | vanilla | HsUi + 11 hex | vanilla | local | local PixelButton | — | button | partial | no |
| GuardOrderScreen | **P** | vanilla | HsUi + 12 hex | vanilla | local | local PixelButton | — | button | partial | no |
| BlessingScreen | **P** | vanilla, centred | HsUi + 37 hex | vanilla | local | local PixelButton | — | button | partial | transition test |
| WorkZoneConfirmScreen | **P** → **W** (done) | vanilla on moss → serif | HsUi → Ui2 | → serif | → 4/8 | moss → banner + text | — | none → wood key | yes | tested |
| BannerOrderScreen | vanilla `Button.builder`, centred strings | vanilla | 2 hex | vanilla | — | vanilla grey | — | vanilla | — | no |
| FishRackScreen | flat fills (15 hex) | vanilla | local hex | vanilla | — | — | — | — | — | no |
| BuilderPlanScreen / BuilderConfirmScreen / DesignNameScreen / Sites | W board + parchment, no crest, own title | vanilla/own | Ui2 | vanilla | own | Ui2 | — | own | yes | no |
| ConversationScreen | W bar + parchment, portrait | serif TITLE | Ui2 + 2 hex | serif | own | nav plates | — | own | yes | no |
| BarterScreen | W board, gold-outlined title plate | serif HEADING | Ui2 + 1 hex, own scrim hex | serif | own | nav plates | — | own | yes | no |
| PatrolRouteScreen | **S** (`Ui2Surface.sheet`, no wood) | vanilla | Ui2 | vanilla | own | Ui2 | — | own | yes | no |
| HandbookScreen (handbook lane) | W board + crest + parchment | serif TITLE | Ui2 + 6 hex | serif | own geometry | plates / wood key | — | wood key | yes | tested |
| HUD: CommandHud | walnut plate | — | Banner tokens | vanilla | — | — | — | — | — | — |
| HUD: DownedHud (revive) | own dark plate, own blood/gold/green hex | — | own | vanilla | — | — | — | — | — | — |
| HUD: PickupNoticeHud | own | — | white text | vanilla | — | — | — | — | — | — |
| HUD: finisher prompt | bare shadowed text | — | own | vanilla | — | — | — | — | — | — |
| Toast: handbook hints | (handbook lane) | | | | | | | | | |

## Audit (after) — 26 Sep, from code + layout tests (in-game stills pending a WSL slot)

Every row below: walnut board + crest + serif small-caps title on the wood + 11 px wood close key + parchment page, `Ui2Palette` only, 4/8 spacing, `Ui2Button` (burgundy primary / text secondary / danger), `Ui2Tips` disabled reasons. Only the differences are noted.

| Screen | Frame | Title | Buttons | Tabs | Close | Notes | GUI 2–4 test |
|---|---|---|---|---|---|---|---|
| Banner pages (reference) | W | serif + subtitle | banner / text | rail | wood key | unchanged; portrait frame colours now kit tokens | BannerSheetLayoutTest |
| Banner popouts: Mayor, Journey, Recruit, requests, readiness, aftermath | W board, wood header, parchment page (no crest: they sit on the Banner) | serif TITLE on the wood | Appoint / Admit / Check / Declare = burgundy; Previous / Next / Refresh / Cancel = text; Dismiss / Skip / confirm-skip = danger | — | wood key "Back to the Banner (Esc)"; Journey "?" (handbook) is a wood key beside it | `ModalPixels` reskinned; `ModalButton`, `PixelActionButton`, `SeatTabButton`, `TransparentRowButton` deleted | Hearth* tests |
| WorkZoneConfirmScreen | W + subtitle (workplace · type) | serif | Confirm = burgundy, Cancel = text | — | wood key (= cancel) | status strip with glyph | WorkZoneConfirmScreenLayoutTest |
| PatrolRouteScreen | W (was ui2 sheet with no wood) | serif; route count on the wood | unchanged Ui2 | — | wood key | editor grid unchanged, sits on the content area | PatrolRouteScreenLayoutTest (960/640/480; dense, 427x240 not required) |
| BuilderPlanScreen (Catalogue, Defense, Upgrades, Sites) | W + crest (had none) | serif (was 1.25x vanilla) | unchanged Ui2 | wood rail plates | wood key (new) | page starts under the header rule | — |
| BuilderConfirmScreen | W | serif | footer on the page | — | wood key (= Back) | | — |
| DesignNameScreen | W + subtitle (size) | serif | Back text / Save burgundy | — | wood key | | DesignNameScreenLayoutTest |
| BarterScreen | W (was dark-wood table + centred plaque) | serif | Accept = burgundy widget (disabled reason), Reset / Leave = text | — | wood key | goods in parchment slot wells; ink on parchment | BarterScreenLayoutTest |
| ConversationScreen | bottom dialogue bar (deliberate: world stays visible), Banner materials | serif name | nav plates | — | Esc | 2 one-off colours → tokens | — |
| PlaqueScreen (+ plaque sheet) | W + blessings subtitle (width 272 → 296) | serif building name | Check Room = burgundy; Fire / Dismiss = danger; Summon / Hire = text | `Ui2Tabs` Requirements / Staff / Hire | wood key | requirement rows = bar + check/pending glyph; no-room = status strip | PlaqueScreenLayoutTest |
| ResearchScreen | W + "x of 6 researched" subtitle | serif | Cancel = danger, Choose = text per row (read-mostly, no primary) | — | wood key | active project = inset strip + progress | ResearchScreenLayoutTest (rewritten: close key replaces the Close-button checks) |
| StorageScreen | W + settlement subtitle | serif | Refresh / Previous / Next = text (read-only) | `Ui2Tabs` category filter (wraps) | wood key | rows 22 px, quality tier colours from palette + written tier | StorageScreenLayoutTest |
| EmblemShopScreen | W + subtitle | serif | Buy = burgundy per row, Inspect = text | — | wood key | status strip, kit scrollbar | EmblemShopScreenLayoutTest |
| CoinMerchantScreen | W around fixed slots, slot wells | serif | — | — | wood key | purse = status strip | CoinMerchantScreenLayoutTest |
| EquipmentRequestListScreen | W + helper subtitle | serif | Refresh / Back text | — | wood key (+ Back) | tickets = standard rows | EquipmentRequestListScreenLayoutTest |
| BannerOrderScreen | W (was vanilla buttons, centred strings) | serif + team subtitle | commands = text, Take Command = burgundy | — | wood key | | BannerOrderScreenLayoutTest |
| GuardOrderScreen | W + subtitle | serif | 8 equal text commands (no single primary) | — | wood key "Back (Esc)" | status strip | GuardOrderScreenLayoutTest |
| FishRackScreen | W around container, slot wells | serif + subtitle | — | — | wood key | | FishRackScreenLayoutTest |
| SettlerInventoryScreen | W, crest carries the job icon (sibling of the settler sheet) | serif name | — | — | wood key | one page, rule-divided rail | SettlerInventoryScreenLayoutTest |
| BlessingScreen | W + settlement subtitle | serif | whole-card claim; one burgundy claim row lit | — | wood key (only when leaving is allowed) | cards = inset parchment | BlessingScreenLayoutTest |
| SettlerScreen (sheet) | W | serif | Ui2 | own | wood key | crest now uses `BannerChrome.crestCloth` (duplicate cloth code removed); portrait colours → tokens | SettlerScreenLayoutTest |
| TechTreeScreen (tech-tree lane, from this checklist) | W | serif | Research = burgundy | — | wood key + "?" | 0 local hex | own test |
| HandbookScreen (handbook lane) | W | serif | plates | — | wood key | header rule, tokens, `Ui2Tips` | HandbookLayoutTest |
| HUD: CommandHud, DownedHud, PickupNoticeHud, finisher prompt, handbook hint toast | `Ui2Hud.plate` | — | — | — | — | TEXT / MUTED / KEY / WARN / GOOD tokens; blood red kept as the functional bleed bar | — |
| DevelopmentScreen (old tech tree) | not changed — replaced by TechTreeScreen (tech-tree lane) | | | | | | |

## Title-fit rule (survival QA s2-046, Mayor's Seat header cut off)

Never clip text on the wood. Lay the header out so the normal strings fit; when a string still does not fit (narrow window, other languages) shorten it with "…" and show the full text as a tooltip on hover.
- Mayor's Seat: the 135 px inset status box is gone. Row 1 = serif title, then status line 1 (mourning countdown / seat empty / Mayor's name) right-aligned up to the close key; row 2 = status line 2 across the header. The choice rule shows while loading; afterwards it is in the header tooltip together with every full line. `MayorHeaderLayoutTest` checks both rows fit unshortened at GUI 2–4.
- All popout titles (`renderModalTitle`) and `Ui2Frame.title` report truncation (`Ui2Serif.Text.truncated()`); `Ui2Frame.titleTooltip` / the Banner's popout header-tooltip pass show the full title. Current English popout titles (≤ 20 chars) fit at every GUI 2–4 width.

## Progress

- [x] Shared kit: `Ui2FrameLayout`, `Ui2Frame`, `Ui2Tips`, `Ui2Hud` (+ `Ui2LayoutAssert`, `Ui2FrameLayoutTest`); `BannerChrome.crestCloth`, `LINEN`, `TEXT_ON_WOOD_IDLE`.
- [x] All screens above migrated; layout tests at GUI 2/3/4 where the geometry is a pure function.
- [x] BEFORE sheet: `videos/ui/consistency/before-contact.png`.
- [ ] AFTER stills: rig `videos/ui/consistency/rig/film.sh` (Banner pages + `/hsui <screen>` dev gallery), queued in WSL-QUEUE.md.
