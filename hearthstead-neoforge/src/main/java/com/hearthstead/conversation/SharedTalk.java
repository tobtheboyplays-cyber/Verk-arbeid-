package com.hearthstead.conversation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Who is in one conversation and whose click counts (owner, 27 Sep: "the
 * first one who answers decides; a dual process"). Pure state, no Minecraft
 * types, so the rules are unit tested.
 *
 * <ul>
 *   <li>A solo talk has one participant and never waits (today's behaviour).</li>
 *   <li>A co-op talk opens WAITING: the lead sees "Waiting for ... (1/2)" and
 *       a "Continue alone" reply that unlocks after {@link #unlockTicks}. The
 *       wait also ends by itself after {@link #maxWaitTicks}, and when an
 *       invited partner joins.</li>
 *   <li>Every state carries one revision. The first participant to echo it
 *       consumes it; anyone else echoing the same revision is stale.</li>
 *   <li>A participant who leaves is dropped; the lead passes on; the talk
 *       ends only when nobody is left.</li>
 * </ul>
 */
public final class SharedTalk {
    public static final int DEFAULT_UNLOCK_TICKS = 200;
    public static final int DEFAULT_MAX_WAIT_TICKS = 1200;

    private final LinkedHashSet<UUID> participants = new LinkedHashSet<>();
    private final LinkedHashSet<UUID> invited = new LinkedHashSet<>();
    private final long waitStart;
    private final int unlockTicks;
    private final int maxWaitTicks;
    private boolean waiting;
    private int revision;

    public SharedTalk(UUID lead, Collection<UUID> partners, long now, int revision, int unlockTicks, int maxWaitTicks) {
        participants.add(lead);
        for (UUID partner : partners) if (partner != null && !partner.equals(lead)) invited.add(partner);
        this.waitStart = now;
        this.revision = revision;
        this.unlockTicks = Math.max(0, unlockTicks);
        this.maxWaitTicks = Math.max(this.unlockTicks, maxWaitTicks);
        this.waiting = !invited.isEmpty();
    }

    public static SharedTalk solo(UUID lead, long now, int revision) {
        return new SharedTalk(lead, List.of(), now, revision, DEFAULT_UNLOCK_TICKS, DEFAULT_MAX_WAIT_TICKS);
    }

    public UUID lead() {
        return participants.iterator().next();
    }

    public List<UUID> participants() {
        return List.copyOf(participants);
    }

    public Set<UUID> invited() {
        return Set.copyOf(invited);
    }

    public boolean isParticipant(UUID who) {
        return who != null && participants.contains(who);
    }

    public boolean isInvited(UUID who) {
        return who != null && invited.contains(who);
    }

    public boolean shared() {
        return participants.size() > 1 || !invited.isEmpty();
    }

    public boolean waiting() {
        return waiting;
    }

    public int revision() {
        return revision;
    }

    /** Participants over everyone asked (the "(1/2)" of the waiting line). */
    public int total() {
        return participants.size() + invited.size();
    }

    public boolean canContinueAlone(long now) {
        return waiting && now - waitStart >= unlockTicks;
    }

    /** Ticks until "Continue alone" unlocks (0 once it has). */
    public long unlockIn(long now) {
        return waiting ? Math.max(0L, unlockTicks - (now - waitStart)) : 0L;
    }

    /**
     * "Continue alone": a participant, the current revision, and only once
     * unlocked. Ends the wait and consumes the revision. Invited partners may
     * still walk up and join later.
     */
    public boolean continueAlone(UUID who, int echoed, long now) {
        if (!isParticipant(who) || echoed != revision || !canContinueAlone(now)) return false;
        waiting = false;
        revision++;
        return true;
    }

    /** The wait runs out by itself so a far-away partner never blocks play. */
    public boolean expireWait(long now) {
        if (!waiting || now - waitStart < maxWaitTicks) return false;
        waiting = false;
        revision++;
        return true;
    }

    /** An invited partner arrives. Ends the wait (the talk starts for everyone). */
    public boolean join(UUID who) {
        if (!isInvited(who)) return false;
        invited.remove(who);
        participants.add(who);
        if (waiting) {
            waiting = false;
            revision++;
        }
        return true;
    }

    /**
     * A reply click. First echo of the current revision wins and consumes it;
     * a non-participant, a stale revision or a click while waiting is refused.
     */
    public boolean click(UUID who, int echoed) {
        if (!isParticipant(who) || waiting || echoed != revision) return false;
        revision++;
        return true;
    }

    /** The server changed the state on its own (e.g. a failed cost): old revisions go stale. */
    public void bump() {
        revision++;
    }

    /**
     * Drops {@code who} (Esc, logout, out of reach, other dimension) and
     * forgets any invitation. Returns the remaining participants; empty means
     * the talk is over.
     */
    public List<UUID> leave(UUID who) {
        participants.remove(who);
        invited.remove(who);
        if (participants.isEmpty()) {
            invited.clear();
            waiting = false;
        } else if (waiting && invited.isEmpty()) {
            waiting = false;
            revision++;
        }
        return new ArrayList<>(participants);
    }

    /** A partner who can no longer come (logged out, declined): stop waiting for them. */
    public boolean uninvite(@Nullable UUID who) {
        if (who == null || !invited.remove(who)) return false;
        if (waiting && invited.isEmpty()) {
            waiting = false;
            revision++;
        }
        return true;
    }
}
