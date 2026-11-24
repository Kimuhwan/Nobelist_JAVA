package com.scientificlens.harness.llm;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliCompatibilityTest {
    @Test
    void gpt61SolIsSelectableInsteadOfTheObsoleteFixedCatalog() {
        assertTrue(CliProvider.CODEX.models().contains("gpt-6.1-sol"),
                "The model picker must offer the available gpt-6.1-sol model");
        assertFalse(CliProvider.CODEX.defaultModel().contains("GPT-5"),
                "The CLI default must not claim a fixed old model family");
    }

    @Test
    void defaultSelectionUsesTheUsersCliModelConfiguration() {
        var args = CliProvider.CODEX.command(CliProvider.CODEX.defaultModel(), null);
        assertFalse(args.contains("--ignore-user-config"),
                "Ignoring config discards the user's configured gpt-6.1-sol default");
        assertFalse(args.contains("-m"));
        assertTrue(args.contains("read-only"));
        assertTrue(args.contains("--ephemeral"));
    }

    @Test
    void explicitGpt61ModelIsPassedWithoutRenamingIt() {
        var args = CliProvider.CODEX.command("gpt-6.1-sol", null);
        assertEquals("gpt-6.1-sol", args.get(args.indexOf("-m") + 1));
        assertTrue(args.contains(CliProvider.LAST_MESSAGE_PLACEHOLDER));
    }

    @Test
    void persistedLegacyDefaultMigratesToTheCurrentCliDefault() {
        assertEquals(CliProvider.CODEX.defaultModel(),
                new CliSettings(CliProvider.CODEX, "기본값 (GPT-5)").model());
        assertEquals("gpt-6.1-sol", new CliSettings(CliProvider.CODEX, "gpt-6.1-sol").model());
    }
    @Test
    void accountModelCacheOffersVisibleModelsWithoutDuplicates(@TempDir Path directory) throws Exception {
        Path cache = directory.resolve("models_cache.json");
        Files.writeString(cache, """
                {"models":[
                  {"slug":"gpt-6.1-sol","visibility":"list"},
                  {"slug":"private-routing-model","visibility":"hide"},
                  {"slug":"gpt-6.1-sol","visibility":"list"},
                  {"slug":"gpt-6-sol","visibility":"list"}
                ]}
                """);
        assertEquals(java.util.List.of(CliProvider.CODEX.defaultModel(), "gpt-6.1-sol", "gpt-6-sol"),
                CliProvider.codexModels(cache));
    }

    @Test
    void missingOrCorruptedOptionalCacheKeepsManualModelSelection(@TempDir Path directory) throws Exception {
        Path cache = directory.resolve("models_cache.json");
        assertTrue(CliProvider.codexModels(cache).contains("gpt-6.1-sol"));
        Files.writeString(cache, "{broken");
        assertTrue(CliProvider.codexModels(cache).contains("gpt-6.1-sol"));
    }

    @Test
    void desktopAlphaVersionOutranksTheOldPathAndNpmInstallations() {
        var desktop = CliEngine.parseVersion("codex-cli 0.162.0-alpha.17.2");
        assertTrue(desktop.compareTo(CliEngine.parseVersion("codex-cli 0.135.0")) > 0);
        assertTrue(desktop.compareTo(CliEngine.parseVersion("codex-cli 0.147.0")) > 0);
        assertTrue(CliEngine.parseVersion("codex-cli 0.162.0").compareTo(desktop) > 0);
        assertNull(CliEngine.parseVersion("unrelated output"));
        assertNull(CliEngine.parseVersion("codex-cli 98765432101234567890.1.0"));
    }

    @Test
    void malformedPathEntryDoesNotHideTheRemainingCodexInstallations(@TempDir Path directory) {
        String invalid = System.getProperty("os.name").startsWith("Windows") ? "C:\\tools\\*" : "bad\0entry";
        var candidates = CliEngine.codexPathCandidates(invalid + java.io.File.pathSeparator + directory);
        assertEquals(java.util.Set.of(directory.resolve("codex.exe")), candidates);
    }
}
