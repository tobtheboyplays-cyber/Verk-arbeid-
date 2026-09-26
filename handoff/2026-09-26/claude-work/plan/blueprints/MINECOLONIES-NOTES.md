# MineColonies: what makes their huts great (inspiration only)

Rule: **inspiration only.** We never download, copy or convert MineColonies or Structurize schematics. Every Bannerhold blueprint is authored by our own generator (`tools/blueprints/`).

Sources:
- [Forester's Hut](https://minecolonies.com/wiki/buildings/lumberjack/)
- [Fisher's Hut](https://minecolonies.com/wiki/buildings/fisherman/)
- [Blacksmith's Hut](https://minecolonies.com/wiki/buildings/blacksmith/)
- [Stonemason's Hut](https://minecolonies.com/wiki/buildings/stonemason/)
- [Schematic design rules](https://minecolonies.com/wiki/tutorials/schematics/schematic-design/)
- [Official schematics / styles](https://minecolonies.com/schematics/)
- [Style Explorer: Medieval Oak](https://tomp2.github.io/minecolonies-style-explorer/?theme=medievaloak), [Medieval Dark Oak](https://tomp2.github.io/minecolonies-style-explorer/?theme=medievaldarkoak), [Shire](https://tomp2.github.io/minecolonies-style-explorer/?theme=shire)
- [Medieval Oak extension pack](https://www.curseforge.com/minecraft/mc-addons/medieval-oak-extension-and-optimization-style-pack)
- [Old English stylepack](https://www.planetminecraft.com/mod/old-english-stylepack-for-minecolonies-1-20/)

## Takeaways
1. **The job is readable from 30 blocks away.** Each hut has one signature prop that sets its silhouette:
   - Forester: log piles and a sawbuck;
   - Fisher: a hut on a dock over 7x7x2 water;
   - Miner: a shaft head with a ladder/rail going down;
   - Blacksmith: an open forge with a chimney;
   - Stonemason: a yard of cut blocks.
2. **Growth you can see.** Levels 1-5 keep the same footprint. They get visibly richer: more props, a second storey, a better roof. The footprint is fixed so the level-5 shape fits the level-1 plot.
   - For us: a preset's lot should already contain the yard and props that Upgrade Orders will fill.
3. **Dense exterior dressing:**
   - barrels, crates (barrels plus trapdoor lids), lanterns on posts, flower pots, fences, banners, signs by the door;
   - "leisure" sit/stand spots outside (benches).
   Nothing is bare, and a hut is also a little yard.
4. **The interior shows the job:** racks of tools and materials, workbenches in the middle, and the job block where you would expect it.
5. **Varied roofs:** gable/hip mixes, dormers, deep overhangs, chimneys, and cross-wings on the bigger huts.
6. **One style per colony, many styles in total.** Medieval Oak/Spruce/Dark Oak, Nordic, Fortress, Caledonia and Shire keep the same huts in different skins. Our Elmfield / Whitewashed Timber / Stone Hall / Rustic Log presets play that role, and "Match town style" is the per-town skin.
7. **Rules that keep them workable:**
   - the hut block sits in the same place every level;
   - a groundlevel tag;
   - required job blocks are listed per hut (furnaces, beds, racks).
   We have the same in `ground_level`, `plaque.pos` and the L1 checklist.

## How Bannerhold applies it (concept pass, 26 Sep)
- **Structure follows the job, then the style:**
  - open-air yards for the camps (lumber, mason, builder);
  - sheds for sawmill and forge;
  - a dock for the fishery;
  - a headframe for the mine;
  - a real windmill.
- **Every lot gets a yard and props.** Houses, tavern, library and infirmary stay enclosed rooms.
- **Validation:** open-air types need a "work yard" mode (bounded lot, job blocks present and reachable, a small covered shelter). Proposed to the lanes that own RoomScanner/BuildingType/Plaque.
