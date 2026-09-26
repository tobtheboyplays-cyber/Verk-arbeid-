# QA-BUTTONS: every button on every screen (Super QA lane)

Status: **static pass complete** (26 Sep 2026, 06:05). The dynamic pass is queued in WSL-QUEUE.md, after the captain's GameTests and the films.

Verdicts:
- PASS
- FAIL→fixed(verified)
- FAIL→assigned(lane)
- RISK: works, but has a UX or edge-case gap. Minor; listed for the owning lane.

Lane ids are the ones in RESUME-AFTER-LIMIT.md. "Banner lane" is acd0a5a18e727f5b9.

## Global checks

| Check | Result |
|---|---|
| Literal lang keys (`translatable`/`I18n.get`) missing from en_us.json | PASS. The only hit is `gui.cancel`, which is a vanilla key. About 80 more keys (events, conversation, settler bonus) are missing from en_us.json but use `translatableWithFallback`, so English renders and no raw keys show. That is localisation debt only. |
| Profession display names (`hearthstead.profession.*`) for all 33 professions | PASS |
| Job emblem item names for the 16 released emblems | PASS |
| Keybind lang (`key.hearthstead.*`, category) | PASS |
| Keybind default conflicts: R, G, J, K, N, H, B, O, plus Unbound "all" and finisher | PASS. None clash with vanilla 1.21 defaults, and all use IN_GAME context. |
| C2S payloads registered (`playToServer`) | PASS. All client-sent payloads are registered: ModBusEvents, BannerNetworkRegistration, FieldOrderNetworkRegistration, RealmMapNetworkRegistration, BuilderEvents, FinisherNetwork, ConversationNetwork. |
| Removed Banner pages (Defense, Tasks) reachable from any button | PASS. The nav has 6 entries (HearthScreen.java:534). `requestPanelOpen` is never set true, so the Tasks page code is dead but cannot be reached. The only "Defense" in the UI is the Builder's own Defense tab, which is valid. |
| **Progression that depends on removed pages** | **FAIL→assigned(Banner lane)**, see Q-001. |
| Text that points players at removed or renamed pages | **FAIL→assigned(Banner lane)**, see Q-002. |

## Findings (numbered, newest last)

| # | Sev | Where | Problem | Repro | Status |
|---|---|---|---|---|---|
| Q-001 | CRITICAL | HearthScreen.java:2451 `refreshRequestLedger`; JourneyDefinition.java:255 | Journey step fj_230 needs HearthMayorAction OPEN_REQUEST_LEDGER. Its only sender returns early unless `requestPanelOpen` is true, and nothing sets it since the Tasks page was removed. The Journey stops at "Open the Request Ledger". fj_240 onward (the whole Food, Home, Tavern and Watch chain) can then only be reached by skipping the whole guide. | New world → staff the Warehouse → Journey shows fj_230 and never completes | FIXED (Banner lane): opening Storage sends OPEN_REQUEST_LEDGER (HearthScreen.requestLedgerForStorage), and Storage lists the open requests with hover details; JUnit StorageLedgerJourneyTest (3/3), server path already covered by FirstRaidReadinessGameTests#viewRequestLedger |
| Q-002 | MAJOR | en_us.json | Player text names pages that no longer exist, "Banner → Tasks / People / Requests / Stores" (see list below). | Read the Handbook "Jobs" or "Logistics" chapter | FIXED (Banner lane): all 9 strings now point to Storage / Storage → Stores / Settlers → Review; fj_230 is retitled "Check the Open Requests" |
| Q-003 | MAJOR | en_us.json `hearthstead.raid.readiness.blocker.request_critical_conflict` | This readiness blocker tells the player to resolve the conflict in "Banner → Requests", but that page is gone and nothing else shows request conflicts. The first-raid readiness declaration can therefore be blocked with no visible cure. | Only when a critical request conflict exists | FIXED (Banner lane): the text points to Open requests on Banner → Storage, which now shows the ledger conflict (quarantine) line in red |
| Q-004 | minor | BannerOrderScreen.java:37 | With 0 team members the command buttons are disabled, but the tooltip still shows the order hint instead of the reason. | Hold a team banner with no linked soldiers | FAIL→fixed: tooltip now shows `hearthstead.banner.no_team` (javac OK) |
| Q-005 | minor | GuardOrderScreen.java:289-313 | The tooltips of Hold, Defend, Stop, Add point, Undo point and Start patrol only repeat the label. A disabled Start patrol or Undo point gives no reason (for example "needs 2 patrol points"). Only Tower has a reason tooltip. | Open guard orders with 0 points and hover Start patrol | FAIL→assigned(Command lane a77dcc6059299345d), low priority |
| Q-006 | minor | PlaqueScreen.java:411-433 | Summon, Fire and Dismiss are disabled when `!mayManage` or `!worker`, but the tooltip still says "Summon X" instead of the reason. | Open a plaque as a non-manager co-op friend | FAIL→open, re-route (the Settler UI lane does not own PlaqueScreen; the bug hunter will take it on resume) |
| Q-007 | minor | BuilderNetwork.java:87 | DELETE_DESIGN removes a saved design by id with no owner check, although `Design` records an owner. In co-op any friend can delete another player's scanned designs. | Friend opens Builder's Plan → Our Designs → Delete | FAIL→fixed by the Builder lane (06:15): the owner, a null owner or op 2 may delete; others get a refusal message |
| Q-008 | major (test) | DoctrineProgressionGameTests.java:100 | The test asserts the emblem catalog has exactly 11 entries; it now has 16 (Builder plus 4 battle roles). The GameTest fails on its own stale assertion. | Run the GameTests | FAIL→fixed by the Battle roles lane (now expects 16); the captain confirms in W1 |
| Q-009 | minor | HearthScreen.java:1143 "Beds cap/pop" need | The "Build beds" action opens the Tech Tree, not anything bed-related. Acceptable only if the Home node is still locked. | pop > cap with Homes already unlocked | RISK, noted to Banner lane |
| Q-010 | minor | HearthScreen.java:1906-1925 | The People page hides the Summon, Change (mayor) and Review (traveler) buttons when `sheet.stacked()` (small window or GUI scale 4). The mayor can then be changed only from SettlerScreen, and travelers only via the Overview need row. | GUI scale 4 on 1080p → Settlers page | PARTLY FIXED (Banner lane): at the stacked layout, Mayor and Review now sit in the bottom strip (see Q-013); Summon stays on the Overview settler card at the stacked layout |
| Q-011 | MAJOR | ClientHooks.java (7 open sites); HearthScreen Stores (:1650) and Tech Tree (:978) | A plain screen opened from the Banner (Tech Tree, Stores, settler sheet, plaque, research, emblem shop, blessing) replaced the container screen with `setScreen` and never sent a container close. The server kept HearthMenu open, so the vanilla inventory (E) clicks afterwards were ignored (container-id mismatch, ghost items) until the player walked out of reach. A held stack also stayed in the Banner menu. | Banner → Tech Tree → Esc → E → move an item | FAIL→fixed: `ClientHooks.leaveContainerScreen` calls `player.closeContainer()` first when the current screen is a container screen. javac compile OK. Dynamic verify pending. |
| Q-012 | HIGH | SettlerScreen.java:504; GuardAssignmentService.java:314 | The Guard Orders button was enabled for Spearman, Longswordsman and Rune Mage, but the server accepts only Barracks and Watchtower employers. The rejection was silent, and the screen timed out with "no reply". | Spearman sheet → Guard orders | FAIL→fixed by the Battle roles lane (ab2beb0f1e2d547df, 06:00): button disabled with a role-key tooltip, server sends a refusal message. Compiled in their build, not yet seen in game. |
| Q-013 | MAJOR | HearthScreen.java:1907 | At stacked layout (sheet under 380 GUI px, e.g. 1280x720 at GUI 4) the Settlers page returns early, so "Change" (mayor) and "Review" (traveler) are never built. Once a mayor exists, it can then only be changed from a SettlerScreen Appoint. A traveler can be admitted only if their need row is the top need (alert and food outrank it). | GUI 4 at 1280x720, food 0, traveler waiting → no admit path | FIXED (Banner lane): at the stacked layout, Mayor and Review sit in the bottom strip, left of View Settler (addStackedOfficeButtons) |
| Q-014 | minor | HearthScreen.java:1162 | The recruit-blocker need "Build a tavern" opens the Tech Tree with no tooltip (the label promises building). | No tavern → click | FIXED (Banner lane): tooltips added for Build a tavern and Build beds (recruit) explaining that the Tech Tree opens |
| Q-015 | minor | HearthScreen.java (whole screen) | Esc or E while a popout (Mayor, Recruitment, Journey) is open closes the whole Banner. The popout "Close" tooltip says "Close this screen". A panel opened from Settlers returns to Overview. | Open the Journey popout and press Esc | FIXED (Banner lane): Esc or the inventory key closes the popout back to the Banner; the Close tooltip reads "Back to the Banner (Esc)" |
| Q-016 | minor | StorageNetwork.java:31 | Stores uses the settlement nearest the player, not the Banner that is open. Near a border in co-op it can show the other settlement's stores. | Stand between two banners → Stores | FIXED (Banner lane): StorageNetwork.handleRequest prefers the open Banner menu's settlement and falls back to nearest only for a Stores-screen refresh |
| Q-017 | minor | HearthScreen.java:1032 | Merge artifact: `updateMayorPanelPosition(); if (!mayorTabOpen) {` is duplicated on one line. Harmless. | – | FIXED (Banner lane): the duplicated line is removed |
| Q-018 | minor | HearthScreen.java:2084, 3075, 3179, 4913, 5555 | Dead Tasks and request-panel code still ticks and renders branches (unreachable). The fix for Q-001 belongs here. | – | NOTED (Banner lane): the remaining request-panel branches are guarded by requestPanelOpen, which is now never true (a boolean check, no work); I left them to keep the diff small |
| Q-019 | minor | HearthScreen: nav while holding a stack (:568, :659, :1111); VIEW_SETTLER refused (HearthNetwork:149-163); OPEN_DEVELOPMENT out of reach (DevelopmentNetwork:37) | These actions silently do nothing: no disabled state and no message. | Hold an item on Storage → click Settlers | PARTLY FIXED (Banner lane): a held stack now gives the action-bar message "Put down the item you are holding first" (nav, category cycle, leaving Storage); the VIEW_SETTLER and OPEN_DEVELOPMENT server refusals are in other lanes' files and are unchanged |
| Q-020 | minor | HearthScreen.java:1878, 2079 | Tooltips: "View Settler" disabled with an empty roster still says "Open the loaded settler's…". "Not possible right now: " can be followed by an empty reason. | – | FIXED (Banner lane): the empty roster gives "No settler selected", and an empty reason drops the colon |
| Q-021 | minor | HearthScreen.java:1515 | Stacked settler card: Summon is placed at `followX - sw - 4` and can land at negative x, over the nav rail ("On the way to you"). | GUI 4, select a settler, Summon | FIXED (Banner lane): the stacked Summon x is clamped to the card |
| Q-022 | minor | SettlerScreen.java:469; SummonClient.java:76 | Locate and Summon are enabled for unbound travelers but always refused by the server. | Open a traveler's sheet → Locate | FAIL→fixed by the Settler UI lane (compile and JUnit 13/13; in-game check pending) |
| Q-023 | minor | SettlerNetwork.java:287 | `canManage` = not spectator only, but the server also refuses when `!mayBuild()`. Adventure-mode players see live Dismiss and Appoint buttons that get refused. | Adventure mode → Dismiss | FAIL→fixed by the Settler UI lane (compile and JUnit 13/13; in-game check pending) |
| Q-024 | minor | SettlerScreen.java:477-541 | Workplace, Work zone, Dismiss, Inventory and Map keep their action tooltip when disabled (no reason). The Courier "Requests" button has no tooltip at all, and times out when the Courier is unbound. | Spectator → hover | FAIL→fixed by the Settler UI lane (compile and JUnit 13/13; in-game check pending) |
| Q-025 | minor | HearthScreen.java:596, 1201 | The static `pendingMapFocus` from "Mark on map" is never cleared on disconnect, so it can carry into another world. | Mark → disconnect → other world → Banner | FIXED (Banner lane): RealmMapClientEvents clears pendingMapFocus and the map client state on LoggingOut |
| Q-026 | minor | SettlerScreen.java:460-510 | The action rows grow in x with no width check, so they may overflow at narrow GUI scales. | GUI 4, Courier/Guard sheet | FAIL→fixed by the Settler UI lane (compile and JUnit 13/13; in-game check pending) |
| Q-027 | info | SettlerScreen and HearthScreen | About 74 (Hearth) plus about 40 (Settler) `Component.literal` English strings: not localised, but they render fine. | – | noted (localisation debt) |

Stale navigation text (Q-002):
- `hearthstead.guide.jobs.body`: "Banner → Tasks show the real item they need"
- `hearthstead.guide.logistics.body`: "Open Banner → Tasks …" and "Open Banner → Stores …" (the nav says Storage)
- `hearthstead.guide.work_zones.body2`: "NO WORK ZONE in Banner → Tasks"
- `hearthstead.guide.crafting_orders.body`: "listed near the top of Banner → Tasks"
- `journey.hearthstead.step.fj_230_open_request_ledger.description`: "select Tasks, press Refresh"
- `journey.hearthstead.step.fj_240_request_first_pickup.description`: "row in Banner → Tasks"
- `hearthstead.message.traveler_waiting`: "Banner → People → Traveler" (the page is now called Settlers)
- `hearthstead.raid.readiness.blocker.settler_roster_insufficient`: "Banner → People → Traveler"
- `hearthstead.raid.readiness.blocker.request_critical_conflict`: "Banner → Requests"

## Journey steps vs UI paths (every UI-triggered step)

| Step | Needs | UI path today | Verdict |
|---|---|---|---|
| fj_020 Open Journey | OPEN_JOURNEY | Banner nav "Journey" (HearthScreen:434 openJourneyPanel) | PASS |
| fj_030 Appoint Mayor | APPOINT | Overview need "Appoint mayor", Settlers → Change (hidden when stacked), SettlerScreen Appoint | PASS |
| fj_100/200/300/400/420/500/551 Unlock | UNLOCK_NODE | Tech Tree → Learn (disabled with the server's reason) | PASS |
| fj_120/220/320/520/557 Staff | Emblem trade + bind | Right-click the Mayor → Emblem shop → Buy, then right-click a settler with the emblem | PASS (static) |
| fj_130 Open Lumberer inventory | SETTLER_INVENTORY_VIEW_OPENED | Shift-right-click with empty hands (SettlerEntity:3421) or SettlerScreen → Inventory (SettlerNetwork:165) | PASS |
| fj_140/330 Work zone | WORK_ZONE_COMMITTED | Settler → Edit work zone → WorkZoneConfirmScreen Confirm | PASS |
| **fj_230 Open Request Ledger** | **OPEN_REQUEST_LEDGER** | **none** | **FAIL (Q-001)** |
| fj_460/555 Admit traveler | ADMIT_TRAVELER | Overview need "Review" → recruitment panel → Admit | PASS |
| fj_550 Guard order / fj_559B tower post | GuardOrderAction | SettlerScreen → Orders → GuardOrderScreen | PASS |
| fj_560 Declare ready | OPEN/CONFIRM_RAID_READINESS | Journey panel → Check → Declare | PASS (Q-003 can block it) |
| fj_620 Review aftermath | OPEN_JOURNEY after the raid | Journey nav | PASS |

## Screen inventories

### DevelopmentScreen (Tech Tree), opened from Banner nav "Tech Tree"
| Element | Where | Does | Payload → server | Expected | Verdict |
|---|---|---|---|---|---|
| × Close | DevelopmentScreen:388 | close | client | screen closes | PASS |
| + / − zoom, Fit | :396-416 | zoom and fit the map | client | map rescales | PASS |
| Refresh | :418 | re-request the snapshot (also auto every 5 s, :528) | DevelopmentActionPayload REFRESH → DevelopmentNetwork:153 | counters update | PASS |
| Node or bonus card (click) | :452-466 | inspect | client | inspector fills | PASS |
| Inspector < > | :426-435 | page the detail | client | disabled at the ends | PASS |
| Learn / Buy | :436-445 | unlock the node or buy the upgrade | UNLOCK_NODE (DevelopmentNetwork:102), BUY_UPGRADE (:141) | node learned, coins spent. Disabled with the server's reason tooltip (:1044-1060). | PASS |
| Drag, scroll-zoom, keys | :1415-1493 | pan, zoom, keyboard focus | client | map moves | PASS (dynamic pending) |
| Inspector lists "X Emblem" for unimplemented nodes (Land & Harvest, Craft & Industry) | :876 | shows emblems that are not in the shop | n/a | These nodes are `implemented=false`, so they cannot be learned and the text is only a preview. | RISK (the Mayor never sells those emblems; see QA-JOBS "not in release") |

### ResearchScreen (Scholar's study block)
| Element | Where | Payload → server | Verdict |
|---|---|---|---|
| Choose (per project) | :160-175 | ResearchActionPayload START → ResearchNetwork:77 | PASS. Disabled with the blocked reason or read-only tooltip. |
| Cancel | :147-155 | CANCEL → :89 | PASS |
| Close; PgUp, PgDn, Home, End; wheel | :178, :290-320 | client | PASS |

### BlessingScreen (Banner right-click when a blessing is pending, HearthBlock:201)
| Element | Where | Payload → server | Verdict |
|---|---|---|---|
| Blessing cards (choose) | :257-265 | BlessingActionPayload CONFIRM → BlessingNetwork:122 | PASS. Inactive while waiting. |
| Close / terminal close | :289, :333 | CLOSE → :81 | PASS |
| Enter / Space on focused card | :363-379 | same | PASS |

### HandbookScreen (Handbook item, plain use; sneak-use asks for storage)
| Element | Where | Verdict |
|---|---|---|
| 21 chapter tabs (sidebar) | :340-352 | PASS. Every chapter title and body key exists (checked by script). |
| Page dots, < >, Close | :354-395 | PASS. Arrows are disabled at the ends. |
| PgUp, PgDn, Home, End, wheel | :507-550 | PASS |
| Content | lang | FAIL→assigned (Q-002: 4 chapters reference Banner → Tasks) |

### EmblemShopScreen (right-click the Mayor, MayorEmblemShopEvents:45)
| Element | Where | Payload → server | Verdict |
|---|---|---|---|
| Buy row x16 | :249-323, :386 | DevelopmentActionPayload BUY_EMBLEM (EMBLEM_SHOP view) → DevelopmentNetwork:120/242 | PASS. Rows stay focusable; purchasable styling plus the reason tooltip; the callback is guarded (:375-383). |
| Inspect Mayor | :261-275 | INSPECT_MAYOR | PASS |
| Close | :278 | client | PASS |

### PlaqueScreen (right-click a plaque)
| Element | Where | Payload → server | Verdict |
|---|---|---|---|
| Check room (Refresh) | :383 | PlaqueAction REFRESH → PlaqueNetwork:106 survey | PASS |
| Close | :390 | CLOSE → :110 (handled before world resolution) | PASS |
| Summon (workplace staff) | :411 | SUMMON → :107 | RISK (Q-006) |
| Fire (workplace staff) | :419 | FIRE → :108 | RISK (Q-006) |
| Dismiss (home resident) | :428 | EVICT → :105 | RISK (Q-006) |
| Hire (home candidates) | :448 | ASSIGN → :104 | PASS. The disabled state gives the blocked reason. |

### GuardOrderScreen (SettlerScreen → Orders)
| Element | Kind → GuardOrderNetwork | Enabled when | Verdict |
|---|---|---|---|
| Hold here | HOLD_HERE :139 | manage | PASS |
| Defend Banner | DEFEND_HEARTH :150 | manage | PASS |
| Stop | CLEAR_ORDER :202 | manage and an order is set | RISK (Q-005) |
| Add point / Undo point / Start patrol | :163 / :182 / :192 | point limits | RISK (Q-005) |
| Loop ↔ Ping-pong | TOGGLE_TRAVERSAL :229 | ≥ min points | RISK (Q-005) |
| Tower post | TOWER_POST :211 | tower available | PASS (has a locked tooltip) |
| Back | client | always | PASS |

### BannerOrderScreen (team banner in hand, B-menu / use)
| Element | Payload → BannerOrderNetwork | Verdict |
|---|---|---|
| Move / Follow / Attack / Hold | BannerOrderActionPayload MOVE, FOLLOW, ATTACK, HOLD → :64-68 | PASS. The tooltip reason when members = 0 is fixed (Q-004). |
| Take over | TAKEOVER → :56 | PASS |

### Command keys (Command lane)
| Key (default) | Does | Payload → server | Verdict |
|---|---|---|---|
| R knights, G archers, J spearmen, K longswordsmen, N mages, H healers, unbound "all" | Tap: aim order (move, attack or hold at the crosshair). Hold: preview. Shift+tap: return. | FieldOrderRequestPayload → FieldOrders.issue (FieldOrderNetworkRegistration:22) | PASS (static). An invalid aim shows the reason on the action bar plus a "no" sound (CommandKeys:222). |
| B command strip, then 1/2/3 | 1 hold fire or fire at will, 2 follow, 3 return; times out | same | PASS |
| O summon | summon the looked-at settler | SummonRequestPayload → PlayerSummons.request (:29) | PASS |
| Finisher (unbound by default) | finisher on a finishable enemy; R also triggers it in reach (CommandKeyHooks) | FinisherPayloads.Request → FinisherNetwork:28 | PASS |

### StorageScreen (Handbook sneak-use: settlement stock index)
| Element | Verdict |
|---|---|
| Category buttons, Find box, Prev/Next (disabled at the ends), Refresh (StorageRequestPayload → StorageNetwork), Close | PASS |

### WorkZoneConfirmScreen (work-zone wand flow)
| Element | Payload | Verdict |
|---|---|---|
| Confirm | WorkZoneActionPayload CONFIRM | PASS. Disabled while waiting or failed. |
| Cancel / Esc | CANCEL | PASS |

### CoinMerchantScreen (vanilla merchant with Coin offers), FishRackScreen (container)
- Offer rows use vanilla trade selection: PASS.
- FishRack has no custom buttons: PASS.

### Builder's Plan (BuilderPlanScreen: Catalog, Defense, Upgrades, Sites) and BuilderConfirmScreen
| Element | Payload → BuilderNetwork | Verdict |
|---|---|---|
| Page nav x4 | client | PASS |
| Catalog row, Place | PREVIEW, VALIDATE, PLACE | PASS. Place is disabled with a lock-reason tooltip (:206-208). |
| Delete design | DELETE_DESIGN :87 | RISK (Q-007, no owner check) |
| Defense line tools (Draw) | VALIDATE_LINE, PLACE_LINE | PASS. Disabled with a lock tooltip (:298-300). |
| Upgrades: Order / Deconstruct | VALIDATE_UPGRADE, ORDER_UPGRADE, DECONSTRUCT | PASS (Order is disabled at max level) |
| Sites: Request now, Pause/Resume, ▲▼, Rush, Stop, Dismantle, Allow overwrite | SITE (+ordinal) → BuildJobs.act :57-80 | PASS. Every ordinal is handled and the server replies with a status line. |

### Conversation and Barter (landed this morning; Conversation lane a86e7e73b8c0da88f)
| Element | Payload → ConversationService.handle :342 | Verdict |
|---|---|---|
| Option click, keys 1-9 / numpad, W/S/↑↓ + Enter | CHOOSE | PASS. Disabled options are ignored and there is a revision guard. |
| Esc | LEAVE | PASS |
| Barter: click or right-click goods, Accept (drawn disabled until acceptable), Reset, Back / Esc | BARTER_ACCEPT / client / BARTER_BACK | PASS. An unknown session makes the server answer with a close (:330). |

### World events (chat buttons)
- The answer buttons run `/hsevent respond <event> <answer>` (WorldEventVisitors:175).
- The command needs no permission (WorldEventCommand:44), so non-op co-op friends can answer.
- An unavailable option carries the reason on hover and has no click.
- Verdict: PASS.

### HearthScreen (Banner). HS = client/screen/HearthScreen.java, HN = network/HearthNetwork.java
All HearthMayorAction kinds are handled at HN:90-164 (UNKNOWN is rejected at :76). SummonRequest, StorageRequest and RealmMapRequest (subscribe, unsubscribe, focus) are all handled.

| Element | Where | Payload → server | Expected | Verdict |
|---|---|---|---|---|
| Nav Overview | HS:970 | client | map page | PASS |
| Nav Settlers | HS:971 | OPEN_PEOPLE → HN:144 | roster | PASS (Q-019 while holding a stack) |
| Nav Buildings | HS:972 | client | building table | PASS (Q-019) |
| Nav Storage | HS:973 | client | Banner slots and inventory | PASS |
| Nav Journey | HS:977 | OPEN_JOURNEY → HN:119 | journey popout | PASS (two nav items can light up) |
| Nav Tech Tree | HS:978 | OPEN_DEVELOPMENT → HN:109 | DevelopmentScreen | FAIL→fixed (Q-011) |
| × Close | HS:1004 | vanilla | closes | PASS |
| Job filter rows, "All settlers" | HS:1422-1429 | client / OPEN_PEOPLE | map highlight / roster | PASS |
| Need: See the raid, Next step | HS:1129, :1157 | OPEN_JOURNEY | journey | PASS |
| Need: Stock food | HS:1134, :1170 | client | Storage page | PASS |
| Need: Traveler review | HS:1139 | client | recruitment popout | PASS |
| Need: Build beds (pop > cap) | HS:1149 | OPEN_DEVELOPMENT | Tech Tree | PASS (tooltip at :1443) |
| Need: Build a tavern / Build beds (recruit blocker) | HS:1162 | OPEN_DEVELOPMENT | Tech Tree | FAIL→assigned (Q-014) |
| Need: See settlers, Appoint mayor | HS:1166, :1173 | OPEN_PEOPLE / REFRESH | roster / mayor popout | PASS |
| Settler card: ×, Follow, Center, "At workplace" | HS:1465-1500 | client | camera and selection | PASS |
| Settler card: Summon | HS:1546 | SummonRequestPayload → PlayerSummons.request | settler walks to you | PASS (Q-021 at stacked layout) |
| Settler card: View Settler | HS:1520 | VIEW_SETTLER → HN:149 | SettlerScreen | PASS (Q-011 fixed; disabled with a reason) |
| Building card: ×, worker rows, Show on map | HS:1583-1610 | client | selection | PASS |
| Buildings rows | HS:1634 | client | card | PASS (no PgUp/PgDn; nothing clickable while the layout loads) |
| Storage: Stores | HS:1650 | StorageRequestPayload → StorageNetwork:31 | StorageScreen | FAIL→fixed (Q-011); Q-016 assigned |
| Storage: category cycle; slots | HS:1658, slotClicked :3310 | client / vanilla clicks | slots move | PASS |
| Settlers: search, Find, rows, "Only X ×" | HS:1832-1888 | client (+ map FOCUS) | filtered list | PASS |
| Settlers: View Settler | HS:1873 | VIEW_SETTLER | SettlerScreen | PASS (Q-020 tooltip) |
| Settlers: Show on map, Summon | HS:1897, :1904 | client / Summon | – | PASS (hidden when stacked) |
| Settlers: Change (mayor), Review (traveler) | HS:1910, :1919 | REFRESH / client | popouts | FAIL at stacked layout (Q-013) |
| Mayor popout: Prev/Next, Close, Appoint | HS:1051-1090 | APPOINT → HN:91 (stale check) | new mayor | PASS (disabled with a reason while mourning) |
| Recruitment: Admit, Dismiss, Close | HS:2052, :2066 | ADMIT_TRAVELER → HN:136, REJECT_TRAVELER → :137 | traveler joins or leaves | PASS (Q-020) |
| Journey: Check readiness, Refresh, Declare | HS:2187, :2245, :2249 | OPEN/CONFIRM_RAID_READINESS → HN:140/:142 | blocker list / committed | PASS (Declare disabled with a reason) |
| Journey: Skip guide → Cancel/Confirm | HS:2203-2226 | SKIP_JOURNEY → HN:108 | skipped | PASS |
| Journey/readiness scroll | HS:3153-3163, :3248 | client | scroll | PASS |
| Map: click marker/building/ground, zoom ±, centre, legend, drag + fling, wheel, WASD/arrows, =/−, C, F, Esc | RealmMapView:1662-1797 | RealmMapRequestPayload | select, pan, zoom | PASS (dynamic pending) |
| Esc / E with a popout open | HS:3272 | vanilla | closes the whole Banner | RISK (Q-015) |

### SettlerScreen, SettlerInventoryScreen, EquipmentRequestListScreen. SN = network/SettlerNetwork.java
All seven SettlerActionPayload kinds have a real handler. All 33 professions have a job icon texture.

| Element | Where | Payload → server | Expected | Verdict |
|---|---|---|---|---|
| × Close (session release on removed) | SettlerScreen:394, :294 | CLOSE → SN:72 | closes | PASS |
| Tabs Overview / Skills / Gear; wheel; portrait drag | :410, :562, :576 | client | page swap | PASS |
| Summon | :463 | SummonRequestPayload → PlayerSummons:143 | walks to you or refusal | PASS (Q-022 unbound) |
| Locate | :468 | LOCATE → SN:142 | outline | PASS (Q-022) |
| Mark on map | :475 | client (HearthScreen.requestMapFocus) | selected on next Banner open | PASS (Q-025) |
| Open workplace | :485 | OPEN_WORKPLACE → SN:140/:185 → PlaqueNetwork.openFor | plaque opens | PASS |
| Edit work zone (Farmer, Lumberer) | :494 | EDIT_WORK_ZONE → SN:139/:168 | selection starts | PASS |
| Requests (Courier) | :501 | client → EquipmentRequestListScreen | queue | PASS (Q-024 tooltip) |
| Guard orders (Guard, Archer) | :505 | GuardOrderActionPayload REFRESH → GuardOrderNetwork | order editor | FAIL→fixed (Q-012) for the other martial roles |
| Appoint | :516 | APPOINT → SN:129 | becomes Mayor | PASS (disabled with a reason, :611-624) |
| Dismiss | :526 | DISMISS → SN:122 | job cleared | PASS (Q-023) |
| Open inventory | :538 | OPEN_INVENTORY → SN:137/:151 | container | PASS (no way back to the sheet; Esc exits) |
| Inventory slots | SettlerInventoryMenu:99-111, :314 | vanilla | move items | PASS |
| Requests list: Refresh, Back, drag reorder, Alt+↑↓, PgUp/PgDn, wheel | EquipmentRequestListScreen:199-495 | EquipmentRequestListRequest / EquipmentRequestMove (revision check) | reordered | PASS |

## Dynamic pass (hidden client), not started
Plan:
1. Disposable world copy with 4 settlers (early) and 40 settlers (late).
2. For each screen: open, then screenshot before and after every button at GUI scales 2, 3 and 4.
3. Verify the notice, state or screen change.
4. Needs a lock slot. Queued behind the captain, soak, finisher, conversation, builder and economy.
