package com.hearthstead.client;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.TavernSeatEntity;
import com.hearthstead.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.ArrayList;
import java.util.List;

/** Original quiet note-block melody, spatially owned by the closest active Tavern. */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class TavernMusicClient {
    // Four phrases in D minor; the last sixteen slots are a natural breathing gap.
    private static final int[] MELODY = {
        2, 9, 14, 17, 14, 9, 5, 9, 0, 7, 12, 16,
        12, 7, 4, 7, 10, 5, 10, 14, 17, 14, 10, 5,
        0, 7, 12, 16, 19, 16, 12, 7, 2, 9, 14, 17,
        21, 17, 14, 9, 5, 9, 14, 9, 5, 2, -1, -1
    };
    private static Object world;
    private static long lastSlot = Long.MIN_VALUE;
    // Randomized, client-only accent clocks (game time); reset per world.
    private static long nextLaugh = Long.MIN_VALUE, nextMug = Long.MIN_VALUE;
    // Sound pass: a bard striking up opens with one short minstrel flourish.
    private static boolean wasBard;
    private TavernMusicClient() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (world != mc.level) {
            world = mc.level; lastSlot = Long.MIN_VALUE;
            nextLaugh = Long.MIN_VALUE; nextMug = Long.MIN_VALUE;
        }
        if (mc.level == null || mc.player == null || mc.isPaused()) return;
        long slot = mc.level.getGameTime() / 8;
        if (slot == lastSlot) return;
        lastSlot = slot;
        SettlerEntity closest = null, closestBard = null;
        double nearest = 20 * 20, nearestBard = 20 * 20;
        for (var actor : mc.level.entitiesForRendering()) {
            if (!(actor instanceof SettlerEntity host)) continue;
            // A server-selected evening bard (synced activity) carries the same
            // tune and takes precedence over the Innkeeper's background lease.
            if (host.isAlive() && host.getActivity() == SettlerActivity.PLAYING_MUSIC) {
                double distance = mc.player.distanceToSqr(host);
                if (distance < nearestBard) { closestBard = host; nearestBard = distance; }
                continue;
            }
            if (!host.tavernMusicActive()) continue;
            double distance = mc.player.distanceToSqr(host);
            if (distance < nearest) { closest = host; nearest = distance; }
        }
        boolean bard = closestBard != null;
        if (bard && !wasBard) {
            com.hearthstead.client.sound.HsSound.at("event.minstrel_sting", null, SoundSource.RECORDS,
                closestBard.getX(), closestBard.getY() + 1, closestBard.getZ(), 0.8F, 1.0F);
        }
        wasBard = bard;
        if (bard) closest = closestBard;
        if (closest == null) return;
        if (slot % 20 == 0) fireplace(mc, closest);
        tavernLife(mc, closest, slot);
        int index = (int) Math.floorMod(slot, 64L);
        // The recorded tavern jig (Bannerhold music) replaces the note-block tune when it is on.
        if (com.hearthstead.client.sound.BannerholdMusicClient.tavernTrackActive()) return;
        if (index >= MELODY.length || MELODY[index] < 0) return;
        float pitch = (float) Math.pow(2.0, (MELODY[index] - 12) / 12.0);
        // SoundSource.RECORDS honors the player's music-disc slider. Vanilla
        // attenuation keeps the tune local; no global music replacement/loop.
        mc.level.playLocalSound(closest.getX(), closest.getY() + 1, closest.getZ(),
            SoundEvents.NOTE_BLOCK_GUITAR.value(), SoundSource.RECORDS, .28F, pitch, false);
        if (bard && index % 8 == 0) {
            // Sparse client-only note above the performer (about every 3 s).
            mc.level.addParticle(ParticleTypes.NOTE, closest.getX(),
                closest.getY() + closest.getBbHeight() + 0.45, closest.getZ(), (index / 8) / 24.0, 0, 0);
        }
        if (index % 8 == 0) {
            float bass = (float) Math.pow(2.0, ((index < 16 || index >= 32 ? 2 : 5) - 12) / 12.0);
            mc.level.playLocalSound(closest.getX(), closest.getY() + 1, closest.getZ(),
                SoundEvents.NOTE_BLOCK_HARP.value(), SoundSource.RECORDS, .16F, bass, false);
        }
    }

    private static void fireplace(Minecraft mc, SettlerEntity host) {
        BlockPos closest = null;
        double distance = 12 * 12;
        // Loaded blocks only; a room without a lit fire must remain quiet.
        for (BlockPos pos : BlockPos.betweenClosed(host.blockPosition().offset(-6, -2, -6),
                host.blockPosition().offset(6, 2, 6))) {
            if (!mc.level.hasChunkAt(pos)) continue;
            var state = mc.level.getBlockState(pos);
            boolean lit = state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                || state.getBlock() instanceof CampfireBlock && state.getValue(CampfireBlock.LIT);
            double squared = mc.player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5);
            if (lit && squared < distance) { closest = pos.immutable(); distance = squared; }
        }
        if (closest != null) {
            // A sparse, quiet hearth accent alongside vanilla's real fire ambience.
            mc.level.playLocalSound(closest.getX() + .5, closest.getY() + .5, closest.getZ() + .5,
                ModSounds.TAVERN_FIRE.get(), SoundSource.BLOCKS, .6F, 1F, false);
        }
    }

    /**
     * Room tone plus sparse laughs and mugs, only while guests sit here. A
     * guest is a settler riding a synced {@link TavernSeatEntity} near the
     * playing host, so this costs one entity pass and only when a cue is due.
     */
    private static void tavernLife(Minecraft mc, SettlerEntity host, long slot) {
        long now = mc.level.getGameTime();
        boolean ambience = slot % 30 == 0; // 240 ticks: about every 12 s
        boolean laugh = now >= nextLaugh, mug = now >= nextMug;
        if (!ambience && !laugh && !mug) return;
        RandomSource random = mc.level.getRandom();
        List<SettlerEntity> guests = seatedGuests(mc, host);
        if (guests.isEmpty()) {
            // Keep the accents waiting a little after the room fills.
            if (laugh) nextLaugh = now + 200 + random.nextInt(300);
            if (mug) nextMug = now + 100 + random.nextInt(200);
            return;
        }
        if (ambience) {
            mc.level.playLocalSound(host.getX(), host.getY() + 1, host.getZ(),
                ModSounds.TAVERN_AMBIENCE.get(), SoundSource.AMBIENT, 1F, 1F, false);
        }
        if (laugh) {
            SettlerEntity guest = guests.get(random.nextInt(guests.size()));
            mc.level.playLocalSound(guest.getX(), guest.getEyeY(), guest.getZ(),
                ModSounds.TAVERN_LAUGH.get(), SoundSource.NEUTRAL, .5F,
                .95F + random.nextFloat() * .1F, false);
            nextLaugh = now + 400 + random.nextInt(500); // 20-45 s
        }
        if (mug) {
            SettlerEntity guest = guests.get(random.nextInt(guests.size()));
            mc.level.playLocalSound(guest.getX(), guest.getY() + .8, guest.getZ(),
                ModSounds.MUG_SET.get(), SoundSource.NEUTRAL, .6F,
                .95F + random.nextFloat() * .1F, false);
            nextMug = now + 160 + random.nextInt(340); // 8-25 s
        }
    }

    private static List<SettlerEntity> seatedGuests(Minecraft mc, SettlerEntity host) {
        List<SettlerEntity> guests = new ArrayList<>();
        for (var actor : mc.level.entitiesForRendering()) {
            if (actor instanceof SettlerEntity guest && guest != host && guest.isAlive()
                && guest.getVehicle() instanceof TavernSeatEntity
                && guest.distanceToSqr(host) < 12 * 12) guests.add(guest);
        }
        return guests;
    }
}
