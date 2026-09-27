package com.hearthstead.client.look;

import com.hearthstead.client.captain.CaptainClient;
import com.hearthstead.entity.SettlerEntity;

/** The battle-roles lane's hero-Captain client state, read by the skins lane. */
final class HeroHooks {
    private HeroHooks() {
    }

    static boolean isHero(SettlerEntity settler) {
        return CaptainClient.isHero(settler);
    }

    /** Dye id 0..15, or -1 for the outfit's own cape colour. */
    static int capeColour(SettlerEntity settler) {
        return CaptainClient.capeColour(settler);
    }

    static boolean plume(SettlerEntity settler) {
        return CaptainClient.plume(settler);
    }
}
