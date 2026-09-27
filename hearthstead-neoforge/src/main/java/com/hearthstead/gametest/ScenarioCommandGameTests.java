package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.network.FieldOrderRequestPayload;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import com.hearthstead.settlement.equipment.EquipmentRequirement;
import com.hearthstead.settlement.guard.FieldOrderRules;
import com.hearthstead.settlement.guard.FieldOrderRules.Group;
import com.hearthstead.settlement.guard.FieldOrderRules.Kind;
import com.hearthstead.settlement.guard.FieldOrderRules.Refusal;
import com.hearthstead.settlement.guard.FieldOrders;
import com.hearthstead.settlement.summon.PlayerSummons;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SCENARIO lane (26 Sep): every role-key order on a live army (batch
 * {@code scenario_command}). One soldier of each commandable role is hired
 * at its own hall and armed; each test issues one (group, order) pair
 * through {@link FieldOrders#issue}, the server path the keys use, and
 * checks the outcome on every soldier:
 *
 * <ul>
 *   <li>a refused pair gives the rule's refusal and moves nobody;</li>
 *   <li>otherwise every soldier the group includes takes the order's meaning
 *   for its role (a melee "high ground" is a line at the wall's base,
 *   healers never "attack"), and nobody outside the group is touched;</li>
 *   <li>RETURN clears a line order; HOLD_FIRE stops shooting at a distant
 *   enemy, and FIRE_AT_WILL lifts it again.</li>
 * </ul>
 *
 * Plus the Summon refusals (batch {@code scenario_summon_refusals}).
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class ScenarioCommandGameTests {
    private static final String TEMPLATE = Hearthstead.MODID + ":empty32";
    private static final int OCTANT = 4;
    private static final int WIDTH = 3;

    private static final Map<Group, Profession> ROLE = new EnumMap<>(Group.class);
    private static final Map<Group, BuildingType> HALL = new EnumMap<>(Group.class);
    static {
        ROLE.put(Group.KNIGHTS, Profession.GUARD);
        ROLE.put(Group.ARCHERS, Profession.ARCHER);
        ROLE.put(Group.SPEARMEN, Profession.SPEARMAN);
        ROLE.put(Group.LONGSWORDSMEN, Profession.LONGSWORDSMAN);
        ROLE.put(Group.MAGES, Profession.RUNE_MAGE);
        ROLE.put(Group.HEALERS, Profession.HEALER);
        HALL.put(Group.KNIGHTS, BuildingType.BARRACKS);
        HALL.put(Group.ARCHERS, BuildingType.WATCHTOWER);
        HALL.put(Group.SPEARMEN, BuildingType.PIKE_YARD);
        HALL.put(Group.LONGSWORDSMEN, BuildingType.SWORD_HALL);
        HALL.put(Group.MAGES, BuildingType.RUNE_HALL);
        HALL.put(Group.HEALERS, BuildingType.INFIRMARY);
    }

    @GameTestGenerator
    public static Collection<TestFunction> scenarioCommands() {
        List<TestFunction> out = new ArrayList<>();
        for (Group group : Group.values()) {
            for (Kind kind : Kind.values()) {
                out.add(new TestFunction("scenario_command",
                    "scenario_command_" + group.id() + "_" + kind.id(), TEMPLATE, Rotation.NONE,
                    200, 0L, true, helper -> order(helper, group, kind)));
            }
        }
        return out;
    }

    private record Army(Settlement settlement, Map<Group, SettlerEntity> soldiers, ServerPlayer player, Zombie enemy) {
    }

    private static Army army(GameTestHelper helper) {
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            for (int y = 1; y <= 4; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        Settlement s = new Settlement(UUID.randomUUID(), "Commandholm", helper.absolutePos(new BlockPos(16, 1, 16)));
        s.radius = 20;
        SettlementSavedData.get(helper.getLevel()).settlements.put(s.id, s);
        Map<Group, SettlerEntity> soldiers = new EnumMap<>(Group.class);
        int i = 0;
        for (Group role : Group.ROLES) {
            Building hall = GameTestFixtures.register(helper, s, HALL.get(role), 1 + i * 5, 26);
            SettlerEntity soldier = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(3 + i * 5, 1, 20));
            soldier.setSettlerName(role.id());
            soldier.bindTo(s.id, s.center);
            s.putRecord(soldier.getUUID(), role.id(), Profession.NONE);
            Employment.Hired hired = Employment.hire(helper.getLevel(), s, hall, soldier);
            helper.assertTrue(hired.ok() && soldier.getProfession() == ROLE.get(role),
                "fixture: a " + ROLE.get(role) + " is hired at the " + HALL.get(role).id() + ": " + hired);
            EquipmentRequirement need = EquipmentRequests.requirementFor(ROLE.get(role));
            if (need != null) soldier.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(need.preferredItem()));
            soldiers.put(role, soldier);
            i++;
        }
        @SuppressWarnings("removal")
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        NetworkRegistry.configureMockConnection(player.connection.getConnection());
        player.setGameMode(GameType.SURVIVAL);
        BlockPos at = helper.absolutePos(new BlockPos(16, 1, 14));
        player.setPos(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D);
        Zombie enemy = helper.spawn(EntityType.ZOMBIE, new BlockPos(16, 1, 3));
        enemy.setNoAi(true);
        enemy.setPersistenceRequired();
        return new Army(s, soldiers, player, enemy);
    }

    private static FieldOrderRequestPayload request(GameTestHelper helper, Army a, Group group, Kind kind) {
        BlockPos pos = kind.needsPos() ? helper.absolutePos(new BlockPos(16, 1, 8)) : FieldOrderRequestPayload.NO_POS;
        return new FieldOrderRequestPayload(group.wireId(), kind.wireId(), pos, OCTANT, WIDTH,
            kind.needsEnemy() ? a.enemy().getId() : -1);
    }

    static void order(GameTestHelper helper, Group group, Kind kind) {
        Army a = army(helper);
        // RETURN and FIRE_AT_WILL undo something: give that first.
        Kind first = kind == Kind.RETURN ? Kind.LINE : kind == Kind.FIRE_AT_WILL ? Kind.HOLD_FIRE : null;
        GameTestTicks.at(helper, 5, () -> {
            if (first != null) {
                FieldOrders.Result before = FieldOrders.issue(a.player(), request(helper, a, group, first));
                helper.assertTrue(before.accepted() || FieldOrderRules.validateShape(group.wireId(), first.wireId(),
                        OCTANT, WIDTH, 8, false) != Refusal.NONE,
                    "setup order " + first + " for " + group.id() + ": " + before.refusal());
            }
        });
        GameTestTicks.at(helper, 12, () -> {
            double distance = kind.needsPos() ? Math.sqrt(a.player().blockPosition()
                .distSqr(helper.absolutePos(new BlockPos(16, 1, 8)))) : -1.0D;
            Refusal rule = FieldOrderRules.validateShape(group.wireId(), kind.wireId(), OCTANT, WIDTH, distance,
                kind.needsEnemy());
            FieldOrders.Result result = FieldOrders.issue(a.player(), request(helper, a, group, kind));
            String what = group.id() + " " + kind.id();
            if (rule != Refusal.NONE) {
                helper.assertTrue(result.refusal() == rule, what + ": refused as " + rule + ", got " + result.refusal());
                for (SettlerEntity s : a.soldiers().values()) {
                    helper.assertTrue(first != null || FieldOrders.assignment(s) == null,
                        what + ": a refused order moves nobody (" + s.getSettlerName() + ")");
                }
                finish(helper, a);
                return;
            }
            helper.assertTrue(result.accepted(), what + ": accepted, got " + result.refusal());
            for (Group role : Group.ROLES) {
                SettlerEntity soldier = a.soldiers().get(role);
                FieldOrders.Assignment got = FieldOrders.assignment(soldier);
                boolean included = group.includes(role);
                Kind meaning = included ? FieldOrderRules.kindFor(role, kind).orElse(null) : null;
                String who = what + " -> " + role.id();
                if (meaning == null) {
                    boolean keptSetup = first != null && included;
                    helper.assertTrue(got == null || keptSetup, who + ": not addressed, no order (got "
                        + (got == null ? "none" : got.order.kind) + ")");
                    continue;
                }
                switch (meaning) {
                    case RETURN -> helper.assertTrue(got == null, who + ": back to posts clears the line");
                    case HOLD_FIRE -> helper.assertTrue(got != null && !FieldOrders.allowsTarget(soldier, a.enemy()),
                        who + ": holds fire at a distant enemy");
                    case FIRE_AT_WILL -> helper.assertTrue(FieldOrders.allowsTarget(soldier, a.enemy()),
                        who + ": fires at will again");
                    case RESUPPLY -> helper.assertTrue(got == null,
                        who + ": resupply never replaces the standing order");
                    default -> helper.assertTrue(got != null && got.order.kind == meaning,
                        who + ": takes " + meaning + ", got " + (got == null ? "none" : got.order.kind));
                }
            }
            finish(helper, a);
        });
    }

    private static void finish(GameTestHelper helper, Army a) {
        for (SettlerEntity s : a.soldiers().values()) FieldOrders.release(s);
        a.enemy().discard();
        SettlementSavedData.get(helper.getLevel()).settlements.remove(a.settlement().id);
        helper.succeed();
    }

    // -------------------------------------------------------- summon refusals --

    @GameTest(template = "empty32", timeoutTicks = 100, batch = "scenario_summon_refusals")
    public void summonRefusesWhatItShould(GameTestHelper helper) {
        Army a = army(helper);
        SettlerEntity guard = a.soldiers().get(Group.KNIGHTS);
        helper.assertTrue(PlayerSummons.request(a.player(), -1, UUID.randomUUID()).refusal()
            == PlayerSummons.Refusal.NOT_FOUND, "an unknown settler: NOT_FOUND");
        SettlerEntity stray = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(28, 1, 14));
        helper.assertTrue(PlayerSummons.request(a.player(), stray.getId(), stray.getUUID()).refusal()
            == PlayerSummons.Refusal.NOT_SETTLER, "an unbound settler: NOT_SETTLER");
        stray.discard();
        // Another village far away is not this player's to summon.
        Settlement other = new Settlement(UUID.randomUUID(), "Elsewhere", a.settlement().center.offset(400, 0, 0));
        SettlementSavedData.get(helper.getLevel()).settlements.put(other.id, other);
        SettlerEntity foreign = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(30, 1, 30));
        foreign.bindTo(other.id, other.center);
        other.putRecord(foreign.getUUID(), "Foreign", Profession.NONE);
        @SuppressWarnings("removal")
        ServerPlayer far = helper.makeMockServerPlayerInLevel();
        NetworkRegistry.configureMockConnection(far.connection.getConnection());
        far.setGameMode(GameType.SURVIVAL);
        // Beyond the 64-block "near a settler" reach of the foreign settler.
        far.setPos(foreign.getX(), foreign.getY(), foreign.getZ() - (PlayerSummons.NEAR_SETTLER + 16.0D));
        helper.assertTrue(PlayerSummons.request(far, foreign.getId(), foreign.getUUID()).refusal()
            == PlayerSummons.Refusal.NO_PERMISSION, "another village's settler, not near: NO_PERMISSION");
        helper.assertTrue(PlayerSummons.request(a.player(), guard.getId(), guard.getUUID()).accepted(),
            "the village's own guard comes");
        helper.assertTrue(PlayerSummons.request(a.player(), guard.getId(), guard.getUUID()).refusal()
            == PlayerSummons.Refusal.TOO_FAST, "a second click in the same tick: TOO_FAST");
        foreign.discard();
        SettlementSavedData.get(helper.getLevel()).settlements.remove(other.id);
        finish(helper, a);
    }
}
