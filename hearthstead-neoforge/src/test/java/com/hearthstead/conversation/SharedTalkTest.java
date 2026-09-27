package com.hearthstead.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Co-op conversation rules (owner, 27 Sep): wait, join, first click wins, continue alone, leave. */
class SharedTalkTest {
    private static final UUID A = UUID.nameUUIDFromBytes("a".getBytes());
    private static final UUID B = UUID.nameUUIDFromBytes("b".getBytes());
    private static final UUID C = UUID.nameUUIDFromBytes("c".getBytes());

    private static SharedTalk coop(long now) {
        return new SharedTalk(A, List.of(B), now, 100, 200, 1200);
    }

    @Test
    void soloNeverWaitsAndAnswersAsBefore() {
        SharedTalk solo = SharedTalk.solo(A, 0, 7);
        assertFalse(solo.waiting());
        assertFalse(solo.shared());
        assertTrue(solo.click(A, 7), "the current revision is accepted");
        assertFalse(solo.click(A, 7), "a replay of the same revision is refused");
        assertTrue(solo.click(A, 8));
    }

    @Test
    void coopOpensWaitingAndRefusesRepliesUntilSomeoneJoins() {
        SharedTalk talk = coop(0);
        assertTrue(talk.waiting());
        assertEquals(1, talk.participants().size());
        assertEquals(2, talk.total());
        assertFalse(talk.click(A, 100), "no graph reply while waiting");
        assertTrue(talk.join(B));
        assertFalse(talk.waiting(), "the partner arriving starts the talk");
        assertEquals(List.of(A, B), talk.participants());
        assertEquals(101, talk.revision(), "the waiting panel's revision went stale");
    }

    @Test
    void firstClickWinsAndTheSecondIsStale() {
        SharedTalk talk = coop(0);
        talk.join(B);
        int shown = talk.revision();
        assertTrue(talk.click(B, shown), "B answers first");
        assertFalse(talk.click(A, shown), "A's click on the same state is stale");
        assertTrue(talk.click(A, shown + 1), "A may answer the next state");
    }

    @Test
    void outsidersAndUninvitedPlayersCannotAct() {
        SharedTalk talk = coop(0);
        talk.join(B);
        assertFalse(talk.click(C, talk.revision()), "not a participant");
        assertFalse(talk.join(C), "never invited");
        assertFalse(talk.join(B), "already in");
    }

    @Test
    void continueAloneUnlocksAfterTheDelayOnly() {
        SharedTalk talk = coop(1000);
        assertFalse(talk.continueAlone(A, 100, 1100), "locked for the first 10 s");
        assertEquals(100, talk.unlockIn(1100));
        assertFalse(talk.continueAlone(A, 99, 1200), "stale revision");
        assertFalse(talk.continueAlone(B, 100, 1200), "only a participant");
        assertTrue(talk.continueAlone(A, 100, 1200));
        assertFalse(talk.waiting());
        assertTrue(talk.click(A, 101), "the talk runs alone");
        assertTrue(talk.isInvited(B), "the partner may still walk up and join");
        assertTrue(talk.join(B));
    }

    @Test
    void theWaitEndsByItself() {
        SharedTalk talk = coop(0);
        assertFalse(talk.expireWait(1199));
        assertTrue(talk.expireWait(1200));
        assertFalse(talk.waiting());
        assertFalse(talk.expireWait(5000), "only once");
    }

    @Test
    void disconnectKeepsTheRestAndPassesTheLead() {
        SharedTalk talk = coop(0);
        talk.join(B);
        assertEquals(List.of(B), talk.leave(A));
        assertEquals(B, talk.lead());
        assertTrue(talk.click(B, talk.revision()), "the rest carry on");
        assertTrue(talk.leave(B).isEmpty(), "the last one out ends it");
    }

    @Test
    void anAbsentPartnerIsNotWaitedFor() {
        SharedTalk talk = coop(0);
        assertTrue(talk.uninvite(B));
        assertFalse(talk.waiting(), "logged out: the lead goes on at once");
        assertTrue(talk.click(A, talk.revision()));
    }

    @Test
    void leadLeavingWhileWaitingEndsEverything() {
        SharedTalk talk = coop(0);
        assertTrue(talk.leave(A).isEmpty());
        assertFalse(talk.waiting());
        assertFalse(talk.join(B), "nothing left to join");
    }
}
