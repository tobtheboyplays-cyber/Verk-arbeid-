package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.gametest.framework.TestFunction;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

/**
 * Developer-only GameTest batch filter: when the system property
 * {@value #PROPERTY} is set (comma-separated batch-name prefixes), a
 * GameTest server keeps only the test functions whose batch starts with one
 * of them. Unset, it does nothing.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class GameTestBatchFilter {
    public static final String PROPERTY = "hearthstead.gametest.batchPrefix";
    public static final String TEST_PROPERTY = "hearthstead.gametest.testPrefix";
    public static final String TEST_BATCH_PROPERTY = "hearthstead.gametest.testPrefixBatch";

    private GameTestBatchFilter() {
    }

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        String raw = System.getProperty(PROPERTY);
        if (raw == null || raw.isBlank() || !(event.getServer() instanceof GameTestServer server)) {
            return;
        }
        List<String> prefixes = Arrays.stream(raw.split(",")).map(String::trim)
            .filter(s -> !s.isEmpty()).toList();
        try {
            Field field = GameTestServer.class.getDeclaredField("testFunctions");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<TestFunction> functions = (List<TestFunction>) field.get(server);
            int before = functions.size();
            functions.removeIf(function -> prefixes.stream()
                .noneMatch(prefix -> function.batchName().startsWith(prefix)));
            // Optional second filter on test names (Builder lane: one blueprint
            // subset of the slow tier), comma-separated prefixes.
            String names = System.getProperty(TEST_PROPERTY);
            if (names != null && !names.isBlank()) {
                List<String> tests = Arrays.stream(names.split(",")).map(String::trim)
                    .filter(t -> !t.isEmpty()).toList();
                // Only within the batches named by TEST_BATCH_PROPERTY (all when unset).
                String only = System.getProperty(TEST_BATCH_PROPERTY, "");
                functions.removeIf(function -> (only.isBlank() || function.batchName().startsWith(only))
                    && tests.stream().noneMatch(t -> function.testName().startsWith(t)));
            }
            Hearthstead.LOGGER.info("HSQA_GAMETEST_FILTER prefixes={} kept={} of {}",
                prefixes, functions.size(), before);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            Hearthstead.LOGGER.error("HSQA_GAMETEST_FILTER failed; running every test", failure);
        }
    }
}
