package com.hearthstead.event;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class GoblinTheftSavedDataTest {
    @Test void firstVisitNeedsTenActiveMinutesAndResolutionStartsThirtyMinuteCooldown() {
        var data=new GoblinTheftSavedData(); UUID village=UUID.randomUUID(), thief=UUID.randomUUID();
        data.observe(village,0,true);
        for (long tick=20; tick<GoblinTheftSavedData.GRACE; tick+=20) data.observe(village,tick,true);
        assertEquals(GoblinTheftSavedData.GRACE-20,data.view(village).eligibleTicks());
        assertFalse(data.mayAttempt(village,GoblinTheftSavedData.GRACE-20));
        data.observe(village,GoblinTheftSavedData.GRACE,true);
        assertTrue(data.mayAttempt(village,GoblinTheftSavedData.GRACE));
        assertTrue(data.published(village,thief));
        assertFalse(data.finished(village,UUID.randomUUID(),"killed"));
        assertTrue(data.finished(village,thief,"killed"));
        for(long tick=GoblinTheftSavedData.GRACE+20;
            tick<GoblinTheftSavedData.GRACE+GoblinTheftSavedData.COOLDOWN; tick+=20) {
            data.observe(village,tick,true);
        }
        assertFalse(data.mayAttempt(village,
            GoblinTheftSavedData.GRACE+GoblinTheftSavedData.COOLDOWN-20));
        data.observe(village,GoblinTheftSavedData.GRACE+GoblinTheftSavedData.COOLDOWN,true);
        assertTrue(data.mayAttempt(village,
            GoblinTheftSavedData.GRACE+GoblinTheftSavedData.COOLDOWN));
    }
    @Test void ineligibleAndOfflineTimeDoNotSpendGraceAndFailedAttemptWaitsOneMinute() {
        var data=new GoblinTheftSavedData(); UUID village=UUID.randomUUID();
        data.observe(village,0,true);
        for (long tick=20; tick<=GoblinTheftSavedData.GRACE; tick+=20) data.observe(village,tick,true);
        data.attempted(village,GoblinTheftSavedData.GRACE);
        assertFalse(data.mayAttempt(village,GoblinTheftSavedData.GRACE+1199));
        assertTrue(data.mayAttempt(village,GoblinTheftSavedData.GRACE+1200));
        data.observe(village,GoblinTheftSavedData.GRACE+20,false);
        data.observe(village,200000,true);
        assertEquals(GoblinTheftSavedData.GRACE,data.view(village).eligibleTicks());
        data.observe(village,200020,true);
        assertEquals(GoblinTheftSavedData.GRACE+20,data.view(village).eligibleTicks());
    }
    @Test void legacyV1RowsMigrateWithoutSurpriseAttackOrLosingActiveIdentity() {
        UUID village=UUID.randomUUID(), activeVillage=UUID.randomUUID(), thief=UUID.randomUUID();
        CompoundTag tag=new CompoundTag();
        tag.putInt("Version",1);
        tag.putBoolean("Quarantined",false);
        ListTag rows=new ListTag();
        CompoundTag inactive=new CompoundTag();
        inactive.putUUID("Settlement",village); inactive.putLong("Eligible",5000);
        inactive.putLong("Next",0); inactive.putLong("Retry",0);
        inactive.putBoolean("Warned",false); inactive.putString("Outcome","none");
        rows.add(inactive);
        CompoundTag active=new CompoundTag();
        active.putUUID("Settlement",activeVillage); active.putLong("Eligible",7000);
        active.putLong("Next",0); active.putLong("Retry",0);
        active.putBoolean("Warned",true); active.putString("Outcome","active");
        active.putUUID("Active",thief);
        rows.add(active);
        tag.put("Rows",rows);

        var loaded=GoblinTheftSavedData.load(tag,null);
        assertEquals(5000+GoblinTheftSavedData.COOLDOWN,loaded.view(village).nextVisit());
        assertEquals(thief,loaded.view(activeVillage).active());
        CompoundTag rewritten=loaded.save(new CompoundTag(),null);
        assertEquals(2,rewritten.getInt("Version"));
        assertFalse(rewritten.getBoolean("Quarantined"));
        assertTrue(loaded.finished(activeVillage,thief,"killed"));
        assertEquals(7000+GoblinTheftSavedData.COOLDOWN,loaded.view(activeVillage).nextVisit());
    }
    @Test void realCoinTheftIsBoundedAtEveryEarlyBalance() {
        assertEquals(0,GoblinTheftDirector.theftLimit(0));
        assertEquals(1,GoblinTheftDirector.theftLimit(1));
        assertEquals(1,GoblinTheftDirector.theftLimit(24));
        assertEquals(2,GoblinTheftDirector.theftLimit(25));
        assertEquals(2,GoblinTheftDirector.theftLimit(49));
        assertEquals(3,GoblinTheftDirector.theftLimit(50));
        assertEquals(3,GoblinTheftDirector.theftLimit(Integer.MAX_VALUE));
    }
    @Test void goblinNoticePolicyKeepsCivilianSightCloseAndGivesGuardsWatchRange() {
        assertTrue(GoblinThiefDemo.noticeDistanceAndFacing(false, 16D, .5D));
        assertFalse(GoblinThiefDemo.noticeDistanceAndFacing(false, 16.01D, 1D));
        assertFalse(GoblinThiefDemo.noticeDistanceAndFacing(false, 4D, .49D));
        assertTrue(GoblinThiefDemo.noticeDistanceAndFacing(true, 144D, -1D));
        assertFalse(GoblinThiefDemo.noticeDistanceAndFacing(true, 144.01D, 1D));
    }

    @Test void fleeingGoblinBrakesForSharpTurnsThenRecoversSmoothly() {
        double cruise=GoblinThiefDemo.fleeNavigationSpeedForHeading(
            GoblinThiefDemo.FLEE_CRUISE_SPEED,1D);
        double rightAngle=GoblinThiefDemo.fleeNavigationSpeedForHeading(cruise,0D);
        double sharpTurn=GoblinThiefDemo.fleeNavigationSpeedForHeading(cruise,-1D);
        double recovery=GoblinThiefDemo.fleeNavigationSpeedForHeading(sharpTurn,1D);

        assertEquals(GoblinThiefDemo.FLEE_CRUISE_SPEED,cruise,.000001D,
            "a straight escape keeps its normal run multiplier");
        assertTrue(cruise >= 1.0D && cruise < 1.2D,
            "a thief with one Coin uses a near-sprint navigation multiplier, never a burden-scaled crawl");
        assertTrue(GoblinThiefDemo.FLEE_TIGHT_TURN_SPEED
                >= GoblinThiefDemo.FLEE_CRUISE_SPEED * .80D,
            "a tight reroute is a modest brake instead of reducing the thief to a sneak");
        assertTrue(rightAngle<cruise && sharpTurn<cruise,
            "a route corner or full reversal brakes below the straight escape modifier");
        assertTrue(rightAngle>=GoblinThiefDemo.FLEE_TIGHT_TURN_SPEED
                && sharpTurn>=GoblinThiefDemo.FLEE_TIGHT_TURN_SPEED,
            "turning never falls below the bounded tight-turn multiplier");
        assertTrue(recovery>sharpTurn && recovery<=cruise
                && recovery-sharpTurn<=.025001D,
            "a straightened route recovers over route checks without an instant speed snap");
    }

    @Test void rareTrophyRollsAreStrictlyBoundedAndNeverGuaranteed() {
        assertTrue(GoblinThiefDemo.rareDropRoll(0.0F,
            GoblinThiefDemo.POOP_STICK_DEATH_DROP_CHANCE));
        assertTrue(GoblinThiefDemo.rareDropRoll(0.199F,
            GoblinThiefDemo.TROLL_TOENAIL_DEATH_DROP_CHANCE));
        assertFalse(GoblinThiefDemo.rareDropRoll(
            GoblinThiefDemo.POOP_STICK_DEATH_DROP_CHANCE,
            GoblinThiefDemo.POOP_STICK_DEATH_DROP_CHANCE));
        assertFalse(GoblinThiefDemo.rareDropRoll(1.0F,
            GoblinThiefDemo.TROLL_TOENAIL_DEATH_DROP_CHANCE));
        assertFalse(GoblinThiefDemo.rareDropRoll(Float.NaN,
            GoblinThiefDemo.TROLL_TOENAIL_DEATH_DROP_CHANCE));
    }
}
