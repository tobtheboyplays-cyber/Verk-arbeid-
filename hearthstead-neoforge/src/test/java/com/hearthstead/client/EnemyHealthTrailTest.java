package com.hearthstead.client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class EnemyHealthTrailTest {
    @Test void damageHoldsThenCatchesUpByElapsedTimeIndependentOfFrames() {
        EnemyHealthTrail frequent=new EnemyHealthTrail(),sparse=new EnemyHealthTrail();
        frequent.observe(20,20,0);sparse.observe(20,20,0);
        frequent.observe(10,20,10_000_000);sparse.observe(10,20,10_000_000);
        assertEquals(.5F,frequent.actual());assertEquals(1F,frequent.trailing());
        frequent.observe(10,20,160_000_000);assertEquals(1F,frequent.trailing());
        for(long t=170_000_000;t<=310_000_000;t+=10_000_000) frequent.observe(10,20,t);
        sparse.observe(10,20,310_000_000);
        assertEquals(.75F,sparse.trailing(),.0001F);
        assertEquals(sparse.trailing(),frequent.trailing(),.0001F);
        frequent.observe(10,20,460_000_000);assertEquals(.5F,frequent.trailing());
        assertEquals(0F,frequent.flash(460_000_000));
    }
    @Test void healingAndMaximumChangeDoNotInventDelayedDamage() {
        EnemyHealthTrail state=new EnemyHealthTrail();state.observe(20,20,0);
        state.observe(8,20,10);state.observe(12,20,20);
        assertEquals(.6F,state.actual());assertEquals(.6F,state.trailing());assertEquals(0F,state.flash(20));
        state.observe(12,24,30);assertEquals(.5F,state.actual());assertEquals(.5F,state.trailing());
        state.observe(6,24,40);assertEquals(.25F,state.actual());assertEquals(.5F,state.trailing());
    }
}
