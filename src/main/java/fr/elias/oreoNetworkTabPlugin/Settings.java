package fr.elias.oreoNetworkTabPlugin;

import org.slf4j.Logger;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.loader.ConfigurationLoader;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class Settings {

    private final Logger logger;
    private final Path dataDir;
    private final Path settingsFile;

    private volatile CommentedConfigurationNode root = CommentedConfigurationNode.root();

    public Settings(Logger logger, Path dataDir) {
        this.logger = logger;
        this.dataDir = dataDir;
        this.settingsFile = dataDir.resolve("settings.yml");
    }

    public void load() {
        try {
            Files.createDirectories(dataDir);

            if (Files.notExists(settingsFile)) {
                copyDefault();
            }

            ConfigurationLoader<CommentedConfigurationNode> loader = YamlConfigurationLoader.builder()
                    .path(settingsFile)
                    .build();

            this.root = loader.load();
            logger.info("[OreoVelocity] Loaded settings.yml from {}", settingsFile.toAbsolutePath());
        } catch (Exception e) {
            logger.error("[OreoVelocity] Failed to load settings.yml", e);
            this.root = CommentedConfigurationNode.root();
        }
    }

    private void copyDefault() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("settings.yml")) {
            if (in != null) {
                Files.copy(in, settingsFile);
                logger.info("[OreoVelocity] Created default settings.yml at {}", settingsFile.toAbsolutePath());
            }
        }
    }

    public boolean getBool(String path, boolean def) {
        return node(path).getBoolean(def);
    }

    public String getString(String path, String def) {
        String val = node(path).getString();
        return (val == null || val.isBlank()) ? def : val;
    }

    public int getInt(String path, int def) {
        return node(path).getInt(def);
    }

    public List<String> getStringList(String path) {
        CommentedConfigurationNode n = node(path);
        if (n == null || n.virtual()) return List.of();
        List<String> out = new ArrayList<>();
        for (CommentedConfigurationNode child : n.childrenList()) {
            String s = child.getString();
            if (s != null && !s.isBlank()) out.add(s);
        }
        return out;
    }

    private CommentedConfigurationNode node(String dottedPath) {
        if (dottedPath == null || dottedPath.isBlank()) return root;
        CommentedConfigurationNode node = root;
        for (String p : dottedPath.split("\\.")) {
            if (!p.isEmpty()) node = node.node(p);
        }
        return node;
    }
}
