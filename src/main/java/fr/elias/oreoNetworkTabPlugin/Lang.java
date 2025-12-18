package fr.elias.oreoNetworkTabPlugin;

import org.slf4j.Logger;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.loader.ConfigurationLoader;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Lang {

    private final Logger logger;
    private final Path dataDir;
    private final Path langFile;

    private volatile CommentedConfigurationNode root = CommentedConfigurationNode.root();

    public Lang(Logger logger, Path dataDir) {
        this.logger = logger;
        this.dataDir = dataDir;
        this.langFile = dataDir.resolve("lang.yml");
    }

    public void load() {
        try {
            Files.createDirectories(dataDir);

            if (Files.notExists(langFile)) {
                if (!copyDefaultLang()) {
                    // If resource missing in jar, create a minimal file so user isn't stuck
                    writeFallbackLang();
                    logger.warn("[OreoNetworkTab] lang.yml resource missing in jar, created fallback lang.yml at {}", langFile.toAbsolutePath());
                } else {
                    logger.info("[OreoNetworkTab] Created default lang.yml at {}", langFile.toAbsolutePath());
                }
            }

            ConfigurationLoader<CommentedConfigurationNode> loader = YamlConfigurationLoader.builder()
                    .path(langFile)
                    .build();

            this.root = loader.load();
        } catch (Exception e) {
            logger.error("[OreoNetworkTab] Failed to load lang.yml", e);
            this.root = CommentedConfigurationNode.root();
        }
    }

    /**
     * @return true if copied from jar resource, false if resource was not found
     */
    private boolean copyDefaultLang() throws IOException {
        // IMPORTANT: lang.yml must exist in src/main/resources/lang.yml to be inside the jar
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("lang.yml")) {
            if (in == null) {
                return false;
            }
            Files.copy(in, langFile);
            return true;
        }
    }

    private void writeFallbackLang() throws IOException {
        String content =
                "messages:\n" +
                        "  join:\n" +
                        "    enabled: true\n" +
                        "    format: \"<gradient:#FF1493:#00FF7F>+</gradient> <white>{name}</white> <gray>joined the network</gray>\"\n" +
                        "  quit:\n" +
                        "    enabled: true\n" +
                        "    format: \"<gradient:#FF1493:#00FF7F>-</gradient> <white>{name}</white> <gray>left the network</gray>\"\n" +
                        "  switch:\n" +
                        "    enabled: false\n" +
                        "    format: \"<gray>{name}</gray> <dark_gray>»</dark_gray> <white>{to}</white>\"\n" +
                        "\n" +
                        "tab:\n" +
                        "  enabled: true\n" +
                        "  showServerInName: true\n" +
                        "  unknownServerName: \"unknown\"\n";

        Files.writeString(langFile, content);
    }

    public boolean getBool(String path, boolean def) {
        return node(path).getBoolean(def);
    }

    public String getString(String path, String def) {
        String val = node(path).getString();
        return (val == null || val.isBlank()) ? def : val;
    }

    public String getMini(String path, String def) {
        return getString(path, def);
    }

    private CommentedConfigurationNode node(String dottedPath) {
        if (dottedPath == null || dottedPath.isBlank()) return root;

        CommentedConfigurationNode node = root;
        String[] parts = dottedPath.split("\\.");
        for (String p : parts) {
            if (!p.isEmpty()) node = node.node(p);
        }
        return node;
    }

    public Path getLangFile() {
        return langFile;
    }
}
