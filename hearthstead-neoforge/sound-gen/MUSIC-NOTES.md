# Bannerhold soundtrack: notes

Made with Eleven Music (`POST /v1/music`, model `music_v1`, `force_instrumental`) under the owner's
ElevenLabs Creator plan, 26 Sep 2026. Script: `sound-gen/music.py` (prompts), mastering:
`sound-gen/music_build.py`, playback: `client/sound/BannerholdMusicClient.java`.

## Licence (read before any monetisation)

Eleven Music model-specific terms, Creator row (read 26 Sep 2026):

- Commercial use: "All online and offline commercial use permitted, except film, TV, radio, & Studio Games".
- "Studio Games" = "Video games which are commercialised (either by sale, advertising or any other forms of
  monetisation) and made available for download or use through more than one platform."
- Paid plans: no attribution required.
- Creator monthly caps: 62 min of music generation, 250 min of downloads; "high-quality downloads" not included
  (we used mp3 44.1 kHz 128 kbps, re-encoded to Vorbis).

**Caveat:** a free, non-monetised mod is outside "Studio Games". If Bannerhold is ever monetised (author
rewards, ads, paid builds, Patreon-gated releases) AND distributed on more than one platform (e.g.
CurseForge + Modrinth), the music would need an Enterprise music licence or must be replaced. SFX, voices
and dialogue are not under these music terms.

## Cost

- Probe: 35 s = ~481 credits on the account (~825/min); ElevenLabs publishes ~900 credits/min.
- Full run: 22 takes (11 tracks x 2) = 42.3 min generated (+0.6 min probe), within the 62-min cap.
  Ledger: 900 credits/min = ~39.4k.

## Tracks shipped (`assets/hearthstead/sounds/el/music/`, stereo Vorbis q2, 13.1 MB)

| Event | Track (take) | Length |
|---|---|---|
| music.title | title (t1) | 146 s |
| music.village_day | day_meadow (t1), day_harvest (t1), day_hearth (t2), day_banner (t1) | 138-150 s |
| music.village_night | night_embers (t1), night_watch (t2) | 136-148 s |
| music.tavern | tavern_jig (t1), seamless loop (2 s equal-power crossfade) | 88 s |
| music.raid | raid (t1) | 119 s |
| music.raid_victory | raid_victory (t2) | 9 s |
| music.raid_defeat | raid_defeat (t1) | 18 s |

Takes were picked from spectrograms (no dropouts or holes, steady instrumentation). Tracks are mastered to
-18 LUFS integrated (vanilla game music measures -17 to -20), stingers to -17, -1 dBTP soft ceiling, short
fade-out; long tracks stream.

## Playback

`BannerholdMusicClient` only chooses the music through NeoForge's `SelectMusicEvent`, so the vanilla music
manager plays it: it never overlaps vanilla music, it follows the Music slider, and it keeps vanilla's sparse
timing.

- Title screen: title theme.
- Near 2+ settlers: village day or night track, 5-15 min silence between songs.
- Within 14 blocks of a tavern host or bard making music: the tavern jig (the note-block tune goes quiet).
- 2+ raiders within 48 blocks: the raid track immediately; it is stopped when the band is gone.
- Raid end: `RaidPresentation.resolved` plays the victory or defeat sting (MUSIC source).
- Client config `[audio] bannerholdMusic = false` turns all of it off (vanilla music only).

Preview: `videos/audio/music-preview.mp3` (about 9 s excerpts of every track, labelled).
