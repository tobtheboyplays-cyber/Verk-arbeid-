# Friend mods for the Sunday pack

Owner-named companion mods, tested against Bannerhold by the mod-compat lane in a private test
environment only (WSL hidden client `/root/hsmc-run` + a private copy of the test server
`/root/hsmc-srv`; never the owner's instance, server or saves).

Every file comes from Modrinth's official CDN, and its sha512 was checked against the Modrinth API.
Files are in `C:\Users\tobia\Hearthstead-Claude\mods-test\`.

Status legend:
- PENDING: not tested yet.
- KEEP: works.
- KEEP WITH SETTING: works with a documented setting.
- THROW OUT: breaks Bannerhold; evidence given.

| Mod | File | Modrinth | Side | sha512 | Status |
|---|---|---|---|---|---|
| Cubes Without Borders (borderless fullscreen) | cwb-neoforge-3.0.0+mc1.21.jar | https://modrinth.com/mod/cubes-without-borders (project ETlrkaYF) | client only | `cbe2770179a948bc5cc7ab9fffdb65262745bb42902d03860cb6218708c1d5ffbabfafe40046dd350c3290ab9d07797bf0225ee4a41f917fff120b23b9d9cc04` | PENDING |
| Detail Armor Bar Reconstructed 5.0.2 | DetailArmorBarReconstructed-5.0.2+1.21.1-neoforge.jar | https://modrinth.com/mod/detail-armor-bar-reconstructed (project Si9Uim4y) | client only | `116069651b32374b7a12cfb23eb4756ea5f6149714d0378dd3a4adea4b8d8081623824c52f1860bc0424613a5f323510d9a16d1c2b3348866f1ca8a9acba503c` | PENDING |
| Entity Model Features 3.3.9 | entity_model_features-3.3.9-1.21-neoforge.jar | https://modrinth.com/mod/entity-model-features | client only | `566abfd11f6281bb0817e253624e9c7c2aa028f85ac93a24c276a0561fbc5c19a527f8f2ca200477d16cdb362ced9ca6b740b81ad926133188706c6ada0048cc` | PENDING |
| Entity Texture Features 7.2.4 | entity_texture_features-7.2.4-1.21-neoforge.jar | https://modrinth.com/mod/entitytexturefeatures | client only | `ab24eae8a255c74157f6599bcc603ef5ce03f259b0b1db43deb78896d6274512ffa2c783a925396c83d606c50fd87b12099160bd20f8986672b24641e7e47c16` | PENDING |
| Fresh Animations 1.10.4 (resource pack, needs EMF+ETF) | FreshAnimations_v1.10.4.zip | https://modrinth.com/resourcepack/fresh-animations | client only | `41258f9bea1a773d823f9a014d0c08206e9e7b339bc538e1538211fff28fadd06878a836d292cbb636ed6829cd2801a509368ae3eb3ad4bedf42190a0d5f7a90` | PENDING |
| Fresh Animations: Extensions 1.8.1 (resource pack, needs FA) | FA+All_Extensions-v1.8.1.zip | https://modrinth.com/resourcepack/fresh-animations-extensions | client only | `a035c1e6c771177676672f93fa68858cfd949426a83bc141ad9fec6def3714aadb62c052d93b259662d03a399bd03ca70947a2ff7dd9b7262dcb16d311e33234` | PENDING |
| Bray's FA Chest GUI Fix 2.0 (resource pack) | Brays-FA-ChestFix-v2.0.zip | Modrinth (FA Extensions: Bray's Chest GUI Fix) | client only | `cd713f411bf9d3f68ef29da7ed6115919e5259c2bdfac20484d3127711bb9f9e5f3d4c3b2ee8e4af903411a19a13152638f383bd7848320ad36da770b7051537` | PENDING |
| Diagonal Fences 21.1.1 | DiagonalFences-v21.1.1-1.21.1-NeoForge.jar | https://modrinth.com/mod/diagonal-fences | **server + client** | `856e035193eb99264321e6d971881bad14baeb7196cb3ae75463457802dfb4d967eaec27a046e1290f074721fc1cd85d59adfd773a287b159a7baac1435c09d1` | PENDING |
| Puzzles Lib 21.1.60 (required by Diagonal Fences) | PuzzlesLib-v21.1.60-mc1.21.1-NeoForge.jar | https://modrinth.com/mod/puzzles-lib | **server + client** | `630bea2bfeed34074d82bc75a4e3f94bb8235d5ae4b13d59c3ee8832bd493eb34584e81d183c5447bd1754e1845d8397eb910d16678243f64e7df8389ce147ac` | PENDING |

Resource pack order, top to bottom in the pack screen: Bray's ChestFix > FA Extensions > Fresh
Animations.

## Test notes
(filled in after the test session)
