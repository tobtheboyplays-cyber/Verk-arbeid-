package com.hearthstead.settlement.guard;

import com.hearthstead.settlement.guard.FieldOrderRules.Group;
import com.hearthstead.settlement.guard.FieldOrderRules.Kind;
import com.hearthstead.settlement.guard.FieldOrderRules.Refusal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Field-order wire vocabulary, request validation and order lifetime. */
class FieldOrderRulesTest {

    @Test
    void wireIdsAreFrozen() {
        assertEquals(0, Group.ALL.wireId());
        assertEquals(1, Group.KNIGHTS.wireId());
        assertEquals(2, Group.ARCHERS.wireId());
        assertEquals(3, Group.SPEARMEN.wireId());
        assertEquals(4, Group.LONGSWORDSMEN.wireId());
        assertEquals(5, Group.MAGES.wireId());
        assertEquals(6, Group.HEALERS.wireId());
        assertEquals(1, Kind.LINE.wireId());
        assertEquals(2, Kind.ATTACK.wireId());
        assertEquals(3, Kind.HIGH_GROUND.wireId());
        assertEquals(4, Kind.FOLLOW.wireId());
        assertEquals(5, Kind.RETURN.wireId());
        assertEquals(6, Kind.HOLD_FIRE.wireId());
        assertEquals(7, Kind.FIRE_AT_WILL.wireId());
        assertTrue(Group.fromWire(99).isEmpty());
        assertTrue(Kind.fromWire(0).isEmpty());
    }

    @Test
    void rolesMatchProfessionKeys() {
        assertEquals(Group.KNIGHTS, Group.forProfessionKey("guard").orElseThrow());
        assertEquals(Group.ARCHERS, Group.forProfessionKey("archer").orElseThrow());
        assertEquals(Group.MAGES, Group.forProfessionKey("rune_mage").orElseThrow());
        assertTrue(Group.forProfessionKey("farmer").isEmpty());
        assertTrue(Group.forProfessionKey("").isEmpty(), "ALL is never a profession");
        assertTrue(Group.ALL.includes(Group.HEALERS));
        assertFalse(Group.KNIGHTS.includes(Group.ARCHERS));
        assertFalse(Group.ALL.includes(Group.ALL));
    }

    @Test
    void validShapesPass() {
        assertEquals(Refusal.NONE, FieldOrderRules.validateShape(1, 1, 4, 6, 20, false));
        assertEquals(Refusal.NONE, FieldOrderRules.validateShape(2, 2, 0, 1, -1, true));
        assertEquals(Refusal.NONE, FieldOrderRules.validateShape(2, 3, 0, 1, 30, false));
        assertEquals(Refusal.NONE, FieldOrderRules.validateShape(1, 5, 0, 1, -1, false));
        assertEquals(Refusal.NONE, FieldOrderRules.validateShape(0, 6, 0, 1, -1, false));
    }

    @Test
    void malformedRequestsAreRefused() {
        assertEquals(Refusal.UNKNOWN_GROUP, FieldOrderRules.validateShape(9, 1, 0, 1, 5, false));
        assertEquals(Refusal.UNKNOWN_KIND, FieldOrderRules.validateShape(1, 42, 0, 1, 5, false));
        assertEquals(Refusal.BAD_FACING, FieldOrderRules.validateShape(1, 1, 8, 1, 5, false));
        assertEquals(Refusal.BAD_FACING, FieldOrderRules.validateShape(1, 1, -1, 1, 5, false));
        assertEquals(Refusal.BAD_WIDTH, FieldOrderRules.validateShape(1, 1, 0, 0, 5, false));
        assertEquals(Refusal.BAD_WIDTH, FieldOrderRules.validateShape(1, 1, 0, 17, 5, false));
        assertEquals(Refusal.NO_TARGET, FieldOrderRules.validateShape(1, 2, 0, 1, 5, false));
        assertEquals(Refusal.NO_TARGET, FieldOrderRules.validateShape(1, 1, 0, 1, -1, false));
        assertEquals(Refusal.OUT_OF_RANGE, FieldOrderRules.validateShape(1, 1, 0, 1, 500, false));
    }

    @Test
    void ordersMatchTheRoleStyle() {
        // Melee cannot climb towers or hold fire; healers never attack.
        assertEquals(Refusal.WRONG_GROUP, FieldOrderRules.validateShape(1, 3, 0, 1, 5, false));
        assertEquals(Refusal.WRONG_GROUP, FieldOrderRules.validateShape(1, 6, 0, 1, -1, false));
        assertEquals(Refusal.WRONG_GROUP, FieldOrderRules.validateShape(6, 2, 0, 1, -1, true));
        assertTrue(FieldOrderRules.allowedFor(Group.MAGES, Kind.HIGH_GROUND));
        assertTrue(FieldOrderRules.allowedFor(Group.HEALERS, Kind.FOLLOW));
        // A wall aimed at with everyone: melee take its base (LINE), healers too.
        assertEquals(Kind.LINE, FieldOrderRules.kindFor(Group.KNIGHTS, Kind.HIGH_GROUND).orElseThrow());
        assertEquals(Kind.HIGH_GROUND, FieldOrderRules.kindFor(Group.ARCHERS, Kind.HIGH_GROUND).orElseThrow());
        assertTrue(FieldOrderRules.kindFor(Group.HEALERS, Kind.ATTACK).isEmpty());
        assertTrue(FieldOrderRules.kindFor(Group.KNIGHTS, Kind.HOLD_FIRE).isEmpty());
    }

    @Test
    void earshotAndRateLimit() {
        assertTrue(FieldOrderRules.withinEarshot(48 * 48));
        assertFalse(FieldOrderRules.withinEarshot(48 * 48 + 1));
        assertFalse(FieldOrderRules.rateLimited(Long.MIN_VALUE, 100));
        assertTrue(FieldOrderRules.rateLimited(100, 102));
        assertFalse(FieldOrderRules.rateLimited(100, 104));
    }

    @Test
    void raidOrderLastsUntilGraceAfterTheRaidEnds() {
        FieldOrderRules.Lifetime life = new FieldOrderRules.Lifetime(1000, true);
        assertFalse(life.update(5000, true));
        assertFalse(life.update(6000, false), "raid just ended: grace starts");
        assertFalse(life.update(6000 + FieldOrderRules.AFTER_RAID_GRACE_TICKS - 1, false));
        assertTrue(life.update(6000 + FieldOrderRules.AFTER_RAID_GRACE_TICKS, false));
    }

    @Test
    void raidResumingDuringGraceKeepsTheOrder() {
        FieldOrderRules.Lifetime life = new FieldOrderRules.Lifetime(0, true);
        assertFalse(life.update(100, false));
        assertFalse(life.update(300, true));
        assertFalse(life.update(400, false));
        assertFalse(life.update(400 + FieldOrderRules.AFTER_RAID_GRACE_TICKS - 1, false));
        assertTrue(life.update(400 + FieldOrderRules.AFTER_RAID_GRACE_TICKS, false));
    }

    @Test
    void peacetimeDrillLapsesAndARaidConvertsIt() {
        FieldOrderRules.Lifetime drill = new FieldOrderRules.Lifetime(0, false);
        assertFalse(drill.update(FieldOrderRules.PEACETIME_LIFETIME_TICKS - 1, false));
        assertTrue(drill.update(FieldOrderRules.PEACETIME_LIFETIME_TICKS, false));

        FieldOrderRules.Lifetime converted = new FieldOrderRules.Lifetime(0, false);
        assertFalse(converted.update(100, true));
        assertTrue(converted.sawRaid());
        assertFalse(converted.update(FieldOrderRules.PEACETIME_LIFETIME_TICKS + 10, true),
            "a drill that turns into a raid lasts the raid");
    }
}
