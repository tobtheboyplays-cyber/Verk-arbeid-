package com.hearthstead;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class BuildIdentityTest {
    @Test
    void generatedIdentityNamesTheExactSourceCommit() {
        assertTrue(BuildIdentity.gitCommit().matches("[0-9a-f]{40}"),
            "the packaged identity must contain a complete Git commit");
        assertFalse(BuildIdentity.version().isBlank());
        assertTrue(BuildIdentity.display().contains(BuildIdentity.shortCommit()),
            "the player-visible build identity must contain the short commit");
    }
}
