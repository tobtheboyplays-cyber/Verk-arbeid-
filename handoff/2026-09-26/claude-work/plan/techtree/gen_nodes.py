"""Generate the in-game tech tree data from techtree.json (v3).

Writes one file per branch to
  Verk-arbeid-/hearthstead-neoforge/src/main/resources/data/hearthstead/techtree/<branch>.json
plus tree.json (branches + tiers).

After the first generation the per-branch files are the SOURCE OF TRUTH:
each branch lane owns its own file and edits it by hand (honest offers text,
gates, impl class). Re-running this script overwrites them, so only run it
with --force before the branch lanes start, or diff first.

honest.json (next to this script) holds per-node overrides applied on top of
the design: {"id": {"offers": "...", "details": [...], "gates": [...], "impl": "C"}}.
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.join(HERE, '..', '..', 'Verk-arbeid-', 'hearthstead-neoforge')
OUT = os.path.join(REPO, 'src', 'main', 'resources', 'data', 'hearthstead', 'techtree')

LEGACY_NODES = {
    'settlement_charter', 'timber_rights', 'stores_and_roads', 'cultivated_ground',
    'shore_provisions', 'home', 'hospitality', 'first_watch', 'arm_the_watch',
    'first_raid_aftermath', 'shield_doctrine', 'guild_doctrine', 'hearth_doctrine',
    'fortification', 'border_wardens', 'land_and_harvest', 'craft_and_industry',
    'hall_and_learning',
}
LEGACY_UPGRADES = {
    'courier_satchel', 'hand_cart', 'worker_packs', 'guard_arms_iron',
    'archer_longbow_drill', 'warm_hearth', 'sturdy_beds', 'feather_quilts',
    'sharpened_axes', 'fishers_nets', 'stout_straps', 'guard_drill', 'leather_pack',
    'frame_pack', 'paved_roads', 'swift_couriers', 'warehouse_racks',
    'great_storehouse', 'royal_storehouse', 'defense_plans', 'masonry',
}

AUTO = {'settlement_charter', 'first_raid_aftermath'}


def obj(name, target):
    return {'kind': 'objective', 'objective': name, 'target': target}


# Structured gates (the design's cost.other text, made machine-checkable).
# Legacy-backed nodes are still gated by their legacy quest / upgrade gate in
# code; these entries must match them (JUnit TechTreeDataTest checks).
GATES = {
    'first_raid_aftermath': [{'kind': 'first_raid'}],
    'town_charter': [{'kind': 'settlers', 'target': 15}, {'kind': 'raids_won', 'target': 3},
                     {'kind': 'branch_nodes', 'tier': 2, 'count': 2, 'branches': 3}],
    'castle_charter': [{'kind': 'settlers', 'target': 25}, {'kind': 'raids_won', 'target': 6}],
    'kingdom_crown': [{'kind': 'settlers', 'target': 40}, {'kind': 'raids_won', 'target': 12}],
    'first_watch': [obj('courier_deliveries', 1)],
    'guard_drill': [obj('guard_equipment_deliveries', 1)],
    'arm_the_watch': [obj('guard_equipment_deliveries', 1)],
    'shield_doctrine': [obj('guard_xp_earned', 40)],
    'stores_and_roads': [obj('lumber_logs_stored', 1)],
    'stout_straps': [obj('courier_deliveries', 6)],
    'paved_roads': [obj('courier_deliveries', 8)],
    'swift_couriers': [obj('courier_deliveries', 12)],
    'great_storehouse': [obj('courier_deliveries', 24)],
    'royal_storehouse': [obj('courier_deliveries', 64)],
    'timber_rights': [obj('foundation_ready', 1)],
    'home': [obj('foundation_ready', 1)],
    'sharpened_axes': [obj('lumber_logs_stored', 16)],
    'cultivated_ground': [obj('courier_deliveries', 1)],
    'shore_provisions': [obj('courier_deliveries', 1)],
    'fishers_nets': [obj('courier_deliveries', 4)],
    'guild_doctrine': [obj('productive_goods_moved', 64), obj('courier_deliveries', 3)],
    'tannery': [{'kind': 'owns_any', 'nodes': ['border_wardens', 'land_and_harvest']}],
    'warm_hearth': [obj('housed_settlers', 2)],
    'hospitality': [obj('housed_settlers', 3)],
    'sturdy_beds': [obj('housed_settlers', 3)],
    'hearth_doctrine': [obj('all_housed_ticks', 24000), obj('equipment_requests_served', 5)],
    'stone_walls': [{'kind': 'owns_any', 'nodes': ['palisade', 'earthworks']}],
}


def study_days(time):
    if not time or time == 'instant':
        return 0
    m = re.match(r'(\d+)', time)
    return int(m.group(1)) if m else 0


def goods_line(g):
    item = g['item']
    line = {'count': int(g['count'])}
    if item.startswith('#'):
        line['tag'] = item[1:]
    else:
        line['item'] = item
    return line


def main():
    force = '--force' in sys.argv
    with open(os.path.join(HERE, 'techtree.json'), encoding='utf-8') as f:
        design = json.load(f)
    honest = {}
    hp = os.path.join(HERE, 'honest.json')
    if os.path.exists(hp):
        with open(hp, encoding='utf-8') as f:
            honest = json.load(f)
    os.makedirs(OUT, exist_ok=True)
    if not force and any(os.path.exists(os.path.join(OUT, b['id'] + '.json')) for b in design['branches']):
        print('branch files exist; they are the source of truth now. Use --force to overwrite.')
        return 1

    by_branch = {b['id']: [] for b in design['branches']}
    for n in design['nodes']:
        c = n['cost']
        node = {
            'id': n['id'],
            'tier': n['tier'],
            'type': n['type'],
            'x': n['x'],
            'y': n['y'],
            'size': n.get('size', '-'),
            'coins': int(c.get('coins') or 0),
            'goods': [goods_line(g) for g in c.get('goods', [])],
            'study_days': study_days(c.get('time')),
            'requires': list(n['requires']),
            'excludes': list(n['excludes']),
            'gates': GATES.get(n['id'], []),
            'gate_text': c.get('other', '') or '',
        }
        if n['id'] in AUTO:
            node['auto'] = True
        if n['id'] in LEGACY_NODES:
            node['legacy'] = 'node:' + n['id']
        elif n['id'] in LEGACY_UPGRADES:
            node['legacy'] = 'upgrade:' + n['id']
        node['design_status'] = n['status']
        node['impl'] = '?'
        node['name'] = n['name']
        node['offers'] = n['offers']
        node['details'] = list(n.get('details', []))
        node['flavor'] = n.get('flavor', '')
        node['depends_on'] = list(n.get('depends_on', []))
        o = honest.get(n['id'])
        if o:
            for k, v in o.items():
                if not k.startswith('_'):
                    node[k] = v
        by_branch[n['branch']].append(node)

    for bid, nodes in by_branch.items():
        with open(os.path.join(OUT, bid + '.json'), 'w', encoding='utf-8', newline='\n') as f:
            json.dump({'branch': bid, 'nodes': nodes}, f, ensure_ascii=False, indent=2)
            f.write('\n')
    tree = {
        'version': design['meta']['version'],
        'source': 'plan/techtree/techtree.json (generated by plan/techtree/gen_nodes.py)',
        'branches': design['branches'],
        'tiers': design['tiers'],
    }
    with open(os.path.join(OUT, 'tree.json'), 'w', encoding='utf-8', newline='\n') as f:
        json.dump(tree, f, ensure_ascii=False, indent=2)
        f.write('\n')
    print('wrote', {k: len(v) for k, v in by_branch.items()})
    return 0


if __name__ == '__main__':
    sys.exit(main())
