package com.hearthstead.client.ui2.map;

import com.hearthstead.network.RealmMapLayoutPayload;
import com.hearthstead.network.RealmMapMarkersPayload;
import net.minecraft.Util;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Client-side holder of the last realm map data for the open Banner screen.
 *
 * <p>Display only: every value is a server projection. The store keeps one
 * settlement at a time; data for another settlement id replaces it. The
 * screen clears it on open and close. Receipt allocates (one array per
 * sample); rendering reads the arrays and never allocates.
 */
public final class RealmMapClient {
    private static UUID settlementId;
    private static RealmMapLayoutPayload layout;
    private static final Map<UUID, MarkerTrack> TRACKS = new HashMap<>();
    private static MarkerTrack[] ordered = new MarkerTrack[0];
    private static final Map<Integer, MarkerTrack> RAIDER_TRACKS = new HashMap<>();
    private static MarkerTrack[] raiders = new MarkerTrack[0];
    private static boolean[] raiderCaptain = new boolean[0];
    private static final Map<Integer, MarkerTrack> TALKER_TRACKS = new HashMap<>();
    private static MarkerTrack[] talkers = new MarkerTrack[0];
    private static String[] talkerNames = new String[0];
    private static String[] talkerTitles = new String[0];
    private static int[] talkerKinds = new int[0];
    private static final Map<UUID, RealmMapLayoutPayload.RosterEntry> ROSTER = new HashMap<>();
    private static final Map<UUID, Integer> ROSTER_INDEX = new HashMap<>();
    private static RealmMapMarkersPayload.Focus focus = RealmMapMarkersPayload.Focus.NONE;
    private static int coins = -1;
    private static long lastSampleMs = Long.MIN_VALUE;
    private static long intervalMs = 500L;
    private static int generation;
    /** Bumped whenever layout or roster content changes; UI caches key on it. */
    private static int layoutVersion;
    /** Bumped on every marker sample; status counts key on it. */
    private static int markerVersion;

    private RealmMapClient() {
    }

    public static void reset() {
        settlementId = null;
        layout = null;
        TRACKS.clear();
        ordered = new MarkerTrack[0];
        RAIDER_TRACKS.clear();
        raiders = new MarkerTrack[0];
        raiderCaptain = new boolean[0];
        TALKER_TRACKS.clear();
        talkers = new MarkerTrack[0];
        talkerNames = new String[0];
        ROSTER.clear();
        ROSTER_INDEX.clear();
        focus = RealmMapMarkersPayload.Focus.NONE;
        coins = -1;
        lastSampleMs = Long.MIN_VALUE;
        intervalMs = 500L;
        layoutVersion++;
        markerVersion++;
    }

    public static void acceptLayout(RealmMapLayoutPayload payload) {
        if (payload == null) return;
        if (settlementId != null && !settlementId.equals(payload.settlementId())) reset();
        settlementId = payload.settlementId();
        layout = payload;
        ROSTER.clear();
        ROSTER_INDEX.clear();
        List<RealmMapLayoutPayload.RosterEntry> roster = payload.roster();
        for (int i = 0; i < roster.size(); i++) {
            ROSTER.put(roster.get(i).id(), roster.get(i));
            ROSTER_INDEX.put(roster.get(i).id(), i);
        }
        layoutVersion++;
    }

    public static void acceptMarkers(RealmMapMarkersPayload payload) {
        if (payload == null) return;
        if (settlementId != null && !settlementId.equals(payload.settlementId())) reset();
        settlementId = payload.settlementId();
        long now = Util.getMillis();
        if (lastSampleMs != Long.MIN_VALUE) {
            long gap = now - lastSampleMs;
            if (gap > 0 && gap < 3000) intervalMs = Math.round(intervalMs * 0.7 + gap * 0.3);
            intervalMs = Math.max(250L, Math.min(1000L, intervalMs));
        }
        lastSampleMs = now;
        generation++;
        if (layout == null) return; // positions are relative to the layout's centre
        MarkerTrack[] next = new MarkerTrack[payload.markers().size()];
        int n = 0;
        for (RealmMapMarkersPayload.Marker m : payload.markers()) {
            MarkerTrack track = TRACKS.computeIfAbsent(m.id(), MarkerTrack::new);
            track.entityId = m.entityId();
            track.professionId = m.professionId();
            track.activityId = m.activityId();
            track.statusId = m.status();
            track.push(layout.centerX() + (double) m.x(), m.y(), layout.centerZ() + (double) m.z(), now, intervalMs);
            track.seenGeneration = generation;
            next[n++] = track;
        }
        TRACKS.values().removeIf(t -> t.seenGeneration != generation);
        ordered = next;
        MarkerTrack[] nextRaiders = new MarkerTrack[payload.raiders().size()];
        boolean[] captains = new boolean[nextRaiders.length];
        int r = 0;
        for (RealmMapMarkersPayload.Raider raider : payload.raiders()) {
            MarkerTrack track = RAIDER_TRACKS.computeIfAbsent(raider.entityId(),
                id -> new MarkerTrack(new UUID(0x7A1DE7L, id)));
            track.entityId = raider.entityId();
            track.push(layout.centerX() + (double) raider.x(), 0, layout.centerZ() + (double) raider.z(), now, intervalMs);
            track.seenGeneration = generation;
            captains[r] = raider.captain();
            nextRaiders[r++] = track;
        }
        RAIDER_TRACKS.values().removeIf(t -> t.seenGeneration != generation);
        raiders = nextRaiders;
        raiderCaptain = captains;
        MarkerTrack[] nextTalkers = new MarkerTrack[payload.talkers().size()];
        String[] names = new String[nextTalkers.length];
        String[] titles = new String[nextTalkers.length];
        int[] kinds = new int[nextTalkers.length];
        int k = 0;
        for (RealmMapMarkersPayload.Talker talker : payload.talkers()) {
            MarkerTrack track = TALKER_TRACKS.computeIfAbsent(talker.entityId(),
                id -> new MarkerTrack(new UUID(0x7A1CE5L, id)));
            track.entityId = talker.entityId();
            track.push(layout.centerX() + (double) talker.x(), 0, layout.centerZ() + (double) talker.z(), now, intervalMs);
            track.seenGeneration = generation;
            names[k] = talker.name();
            titles[k] = talker.titleKey();
            kinds[k] = talker.kind();
            nextTalkers[k++] = track;
        }
        TALKER_TRACKS.values().removeIf(t -> t.seenGeneration != generation);
        talkers = nextTalkers;
        talkerNames = names;
        talkerTitles = titles;
        talkerKinds = kinds;
        coins = payload.coins();
        focus = payload.focus();
        markerVersion++;
    }

    public static boolean hasData(UUID forSettlement) {
        return forSettlement != null && forSettlement.equals(settlementId) && layout != null;
    }

    public static UUID settlementId() {
        return settlementId;
    }

    public static RealmMapLayoutPayload layout() {
        return layout;
    }

    /** Live marker tracks in server roster order. Do not mutate. */
    public static MarkerTrack[] tracks() {
        return ordered;
    }

    /** Live raiders near the claim (display only). */
    public static MarkerTrack[] raiders() {
        return raiders;
    }

    /** People near the claim who want to talk (the conversation "!"), display only. */
    public static MarkerTrack[] talkers() {
        return talkers;
    }

    public static String talkerName(int index) {
        return index >= 0 && index < talkerNames.length ? talkerNames[index] : "";
    }

    /** Badge kind (RealmMapMarkersPayload.Talker.TALK/TRADE/PARLEY/QUEST). */
    public static int talkerKind(int index) {
        return index >= 0 && index < talkerKinds.length ? talkerKinds[index]
            : com.hearthstead.network.RealmMapMarkersPayload.Talker.TALK;
    }

    /** Lang key of the speaker's title ("Travelling peddler"), or "". */
    public static String talkerTitle(int index) {
        return index >= 0 && index < talkerTitles.length ? talkerTitles[index] : "";
    }

    public static boolean raiderIsCaptain(int index) {
        return index >= 0 && index < raiderCaptain.length && raiderCaptain[index];
    }

    public static MarkerTrack track(UUID id) {
        return id == null ? null : TRACKS.get(id);
    }

    public static RealmMapLayoutPayload.RosterEntry roster(UUID id) {
        return id == null ? null : ROSTER.get(id);
    }

    public static int rosterIndex(UUID id) {
        Integer index = id == null ? null : ROSTER_INDEX.get(id);
        return index == null ? -1 : index;
    }

    public static RealmMapMarkersPayload.Focus focus() {
        return focus;
    }

    public static int coins() {
        return coins;
    }

    public static int layoutVersion() {
        return layoutVersion;
    }

    public static int markerVersion() {
        return markerVersion;
    }
}
