package com.hearthstead.client.sound;

import com.hearthstead.Hearthstead;
import com.hearthstead.HearthsteadClientConfig;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.Music;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.SelectMusicEvent;

/**
 * The Bannerhold soundtrack, played the Minecraft way (sound pass, owner-approved 26 Sep): it only
 * chooses WHICH music the vanilla MusicManager plays, so tracks never overlap vanilla music, keep
 * the MUSIC slider, and keep long silences between songs.
 *
 * <ul>
 *   <li>Title screen: the Bannerhold title theme (vanilla menu timing).</li>
 *   <li>Near your settlement: village day or night tracks, 5-15 minutes of silence between them.</li>
 *   <li>At the tavern while the host or a bard is making music: the tavern jig, replacing the
 *       quiet note-block tune.</li>
 *   <li>A raid band close by: the raid track at once; it stops when the raiders are gone (the
 *       server plays the victory or defeat sting).</li>
 * </ul>
 * Off with [audio] bannerholdMusic = false, which leaves vanilla music untouched.
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class BannerholdMusicClient {
    private static final int MIN_GAP = 6000;    // 5 min
    private static final int MAX_GAP = 18000;   // 15 min
    private static long lastScan = Long.MIN_VALUE;
    private static Context context = Context.NONE;

    private enum Context { NONE, VILLAGE, TAVERN, RAID }

    private BannerholdMusicClient() {
    }

    /** True while the tavern jig is the chosen music, so the note-block tavern tune stays quiet. */
    public static boolean tavernTrackActive() {
        return HearthsteadClientConfig.bannerholdMusic() && context == Context.TAVERN;
    }

    @SubscribeEvent
    public static void select(SelectMusicEvent event) {
        if (!HearthsteadClientConfig.bannerholdMusic()) {
            context = Context.NONE;
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            context = Context.NONE;
            event.setMusic(new Music(ModSounds.MUSIC_TITLE, 20, 600, true));
            return;
        }
        long now = mc.level.getGameTime();
        if (now - lastScan >= 20 || now < lastScan) {
            lastScan = now;
            context = scan(mc);
        }
        SoundInstance playing = event.getPlayingMusic();
        boolean raidPlaying = playing != null && playing.getLocation().equals(ModSounds.MUSIC_RAID.getId());
        if (raidPlaying && context != Context.RAID) {
            mc.getMusicManager().stopPlaying();       // the band is gone: make room for the sting
        }
        switch (context) {
            case RAID -> event.setMusic(new Music(ModSounds.MUSIC_RAID, 0, 0, true));
            case TAVERN -> event.setMusic(new Music(ModSounds.MUSIC_TAVERN, 20, 200, true));
            case VILLAGE -> event.setMusic(new Music(mc.level.isDay() ? ModSounds.MUSIC_VILLAGE_DAY
                : ModSounds.MUSIC_VILLAGE_NIGHT, MIN_GAP, MAX_GAP, false));
            default -> { }
        }
    }

    private static Context scan(Minecraft mc) {
        int raiders = 0, settlers = 0;
        boolean tavern = false;
        for (Entity e : mc.level.entitiesForRendering()) {
            double d = mc.player.distanceToSqr(e);
            if (e instanceof RaiderEntity r && r.isAlive() && !r.isGoblinThiefDemo() && d < 48 * 48) {
                raiders++;
            } else if (e instanceof SettlerEntity s && s.isAlive() && d < 64 * 64) {
                settlers++;
                if (d < 14 * 14 && (s.getActivity() == SettlerActivity.PLAYING_MUSIC || s.tavernMusicActive())) {
                    tavern = true;
                }
            }
        }
        if (raiders >= 2) return Context.RAID;
        if (tavern) return Context.TAVERN;
        if (settlers >= 2) return Context.VILLAGE;
        return Context.NONE;
    }
}
