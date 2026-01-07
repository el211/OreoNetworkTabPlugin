// File: src/main/java/fr/elias/oreoNetworkTabPlugin/OreoNetworkTabPlugin.java
package fr.elias.oreoNetworkTabPlugin;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.player.TabList;
import com.velocitypowered.api.proxy.player.TabListEntry;
import com.velocitypowered.api.util.GameProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Plugin(
        id = "oreo-network-tab",
        name = "OreoNetworkTab",
        version = "1.0.0",
        authors = {"Elias"}
)
public class OreoNetworkTabPlugin {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private ShardTransferHandler shardHandler;

    private final MiniMessage mm = MiniMessage.miniMessage();
    private Lang lang;

    // Track previous server for switch messages
    private final Map<UUID, String> lastServer = new ConcurrentHashMap<>();

    // Used to send "join network" only once, when player actually connects to first backend
    private final Set<UUID> pendingFirstConnect = ConcurrentHashMap.newKeySet();

    @Inject
    public OreoNetworkTabPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInit(ProxyInitializeEvent event) {
        this.lang = new Lang(logger, dataDirectory);
        this.lang.load();

        logger.info("[OreoNetworkTab] Initialized. Data folder: {}", dataDirectory.toAbsolutePath());

        // Initialize seamless shard transfer handler (configurable!)
        if (lang.getBool("sharding.enabled", false)) {
            try {
                String redisHost = lang.getString("sharding.redis.host", "localhost");
                int redisPort = lang.getInt("sharding.redis.port", 6379);
                String redisPassword = lang.getString("sharding.redis.password", "");
                int preloadDelay = lang.getInt("sharding.preloadDelay", 100);

                this.shardHandler = new ShardTransferHandler(
                        proxy,
                        logger,
                        this,           // plugin
                        redisHost,      // String
                        redisPort,      // int
                        redisPassword.isEmpty() ? null : redisPassword,  // String
                        preloadDelay    // int
                );

                // Register the shard transfer event listener
                proxy.getEventManager().register(this, shardHandler);

                logger.info("[ShardTransfer] Seamless shard transfer enabled!");
                logger.info("[ShardTransfer] Connected to Redis at {}:{}", redisHost, redisPort);
                logger.info("[ShardTransfer] Pre-load delay: {}ms", preloadDelay);
            } catch (Exception e) {
                logger.error("[ShardTransfer] Failed to initialize - seamless transfers DISABLED", e);
                logger.error("[ShardTransfer] Players will see loading screens on shard transfers");
            }
        } else {
            logger.info("[ShardTransfer] Disabled in config (sharding.enabled: false)");
            logger.info("[ShardTransfer] Players will see loading screens on shard transfers");
        }

        if (isTabEnabled()) {
            updateAllTabs();
        } else {
            logger.info("[OreoNetworkTab] TAB handling disabled (tab.enabled: false).");
        }
    }
    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        logger.info("[OreoNetworkTab] Shutting down...");

        // Shutdown shard transfer handler if it was initialized
        if (shardHandler != null) {
            shardHandler.shutdown();
        }

        logger.info("[OreoNetworkTab] Shutdown complete");
    }
    /**
     * NOTE:
     * PostLoginEvent fires when the player authenticates on the proxy,
     * but not necessarily connected to a backend yet.
     * We mark them as "pending", and we will broadcast join once they connect to their first server.
     */
    @Subscribe
    public void onJoin(PostLoginEvent event) {
        if (isTabEnabled()) updateAllTabs();
        pendingFirstConnect.add(event.getPlayer().getUniqueId());
    }

    @Subscribe
    public void onQuit(DisconnectEvent event) {
        if (isTabEnabled()) updateAllTabs();

        Player p = event.getPlayer();

        // Broadcast quit network (to allowed recipients)
        if (lang != null && lang.getBool("messages.quit.enabled", true)) {
            String quitFmt = lang.getMini(
                    "messages.quit.format",
                    "<gradient:#FF1493:#00FF7F>-</gradient> <white>{name}</white> <gray>left the network</gray>"
            );

            broadcastMini(braceToMiniPlaceholders(quitFmt),
                    Placeholder.parsed("name", p.getUsername())
            );
        }

        lastServer.remove(p.getUniqueId());
        pendingFirstConnect.remove(p.getUniqueId());
    }

    @Subscribe
    public void onPreConnect(ServerPreConnectEvent event) {
        Player p = event.getPlayer();
        String unknown = getUnknownServerName();

        String from = p.getCurrentServer()
                .map(s -> s.getServerInfo().getName())
                .orElse(unknown);

        lastServer.put(p.getUniqueId(), from);
    }

    @Subscribe
    public void onServerSwitch(ServerPostConnectEvent event) {
        if (isTabEnabled()) updateAllTabs();

        Player p = event.getPlayer();
        String unknown = getUnknownServerName();

        String to = p.getCurrentServer()
                .map(s -> s.getServerInfo().getName())
                .orElse(unknown);

        // 1) First server connect => fire JOIN message once (if enabled)
        if (pendingFirstConnect.remove(p.getUniqueId())) {
            if (lang != null && lang.getBool("messages.join.enabled", true)) {
                String joinFmt = lang.getMini(
                        "messages.join.format",
                        "<gradient:#FF1493:#00FF7F>+</gradient> <white>{name}</white> <gray>joined the network</gray>"
                );

                broadcastMini(braceToMiniPlaceholders(joinFmt),
                        Placeholder.parsed("name", p.getUsername()),
                        Placeholder.parsed("to", to),
                        Placeholder.parsed("from", unknown)
                );
            }
            return; // do not also show switch for first connect
        }

        // 2) Switch message (optional)
        if (lang == null || !lang.getBool("messages.switch.enabled", false)) return;

        String from = lastServer.getOrDefault(p.getUniqueId(), unknown);

        // Avoid useless switches
        if (from.equalsIgnoreCase(unknown)) return;
        if (from.equalsIgnoreCase(to)) return;

        String fmt = lang.getMini(
                "messages.switch.format",
                "<gray>{name}</gray> <dark_gray>»</dark_gray> <white>{to}</white>"
        );

        broadcastMini(braceToMiniPlaceholders(fmt),
                Placeholder.parsed("name", p.getUsername()),
                Placeholder.parsed("to", to),
                Placeholder.parsed("from", from)
        );
    }

    /**
     * Broadcast to all players on the proxy (cross-server),
     * optionally excluding recipients that are currently on serversException list.
     */
    private void broadcastMini(String mini) {
        Component c = mm.deserialize(mini);
        broadcastToAllowedPlayers(c);
    }

    private void broadcastMini(String mini, TagResolver... resolvers) {
        Component c = mm.deserialize(mini, resolvers);
        broadcastToAllowedPlayers(c);
    }

    private void broadcastToAllowedPlayers(Component component) {
        for (Player pl : proxy.getAllPlayers()) {
            if (isRecipientExcepted(pl)) continue;
            pl.sendMessage(component);
        }
    }

    /**
     * Recipients filter:
     * If "serversException" contains the player's current server, they do NOT receive network messages.
     * Example use-case: do not spam lobby with network join/quit.
     */
    private boolean isRecipientExcepted(Player recipient) {
        if (lang == null) return false;

        List<String> except = lang.getStringList("serversException");
        if (except == null || except.isEmpty()) return false;

        String srv = recipient.getCurrentServer()
                .map(cs -> cs.getServerInfo().getName())
                .orElse(getUnknownServerName());

        for (String s : except) {
            if (s != null && s.equalsIgnoreCase(srv)) return true;
        }
        return false;
    }

    /**
     * Converts {name}/{to}/{from} placeholders (YAML style) into MiniMessage placeholders (<name>/<to>/<from>).
     */
    private String braceToMiniPlaceholders(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.replace("{name}", "<name>")
                .replace("{to}", "<to>")
                .replace("{from}", "<from>");
    }

    /**
     * Updates TAB for all players. FULLY disabled when tab.enabled: false.
     */
    private void updateAllTabs() {
        if (!isTabEnabled()) return;

        Collection<Player> players = proxy.getAllPlayers();

        for (Player viewer : players) {
            TabList tab = viewer.getTabList();

            // This is what overrides other tab systems:
            tab.clearAll();

            for (Player target : players) {
                addOrUpdateEntry(viewer, target);
            }
        }
    }

    private void addOrUpdateEntry(Player viewer, Player target) {
        if (!isTabEnabled()) return;

        TabList tab = viewer.getTabList();

        Optional<TabListEntry> existing = tab.getEntry(target.getUniqueId());
        existing.ifPresent(entry -> tab.removeEntry(entry.getProfile().getId()));

        String unknown = getUnknownServerName();
        String serverName = target.getCurrentServer()
                .map(conn -> conn.getServerInfo().getName())
                .orElse(unknown);

        boolean showServer = lang != null && lang.getBool("tab.showServerInName", true);

        Component displayName;
        if (showServer) {
            displayName = Component.text()
                    .append(Component.text(target.getUsername(), NamedTextColor.WHITE))
                    .append(Component.space())
                    .append(Component.text("(", NamedTextColor.DARK_GRAY))
                    .append(Component.text(serverName, NamedTextColor.GRAY))
                    .append(Component.text(")", NamedTextColor.DARK_GRAY))
                    .build();
        } else {
            displayName = Component.text(target.getUsername(), NamedTextColor.WHITE);
        }

        GameProfile profile = target.getGameProfile();
        int ping = (int) Math.max(0, Math.min(Integer.MAX_VALUE, target.getPing()));

        TabListEntry entry = TabListEntry.builder()
                .tabList(tab)
                .profile(profile)
                .displayName(displayName)
                .latency(ping)
                .gameMode(0)
                .listed(true)
                .showHat(true)
                .build();

        tab.addEntry(entry);
    }

    private boolean isTabEnabled() {
        return lang != null && lang.getBool("tab.enabled", true);
    }

    private String getUnknownServerName() {
        return (lang == null) ? "unknown" : lang.getString("tab.unknownServerName", "unknown");
    }
}
