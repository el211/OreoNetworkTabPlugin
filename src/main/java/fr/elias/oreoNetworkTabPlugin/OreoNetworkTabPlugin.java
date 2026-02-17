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
import org.spongepowered.configurate.CommentedConfigurationNode;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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

    // Publishes player counts to RabbitMQ so OreoEssentials backend servers
    // can serve %oreo_crossserver_players_total% and %oreo_server_players_total%
    // via PlaceholderAPI with zero cross-server query latency.
    private RabbitMQCountPublisher countPublisher;

    private final MiniMessage mm = MiniMessage.miniMessage();
    private Lang lang;

    private final Map<UUID, String> lastServer = new ConcurrentHashMap<>();
    private final Set<UUID> pendingFirstConnect = ConcurrentHashMap.newKeySet();

    @Inject
    public OreoNetworkTabPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Subscribe
    public void onProxyInit(ProxyInitializeEvent event) {
        this.lang = new Lang(logger, dataDirectory);
        this.lang.load();

        logger.info("[OreoNetworkTab] Initialized. Data folder: {}", dataDirectory.toAbsolutePath());

        // ── Sharding ──────────────────────────────────────────────────────────
        if (lang.getBool("sharding.enabled", false)) {
            try {
                String redisHost     = lang.getString("sharding.redis.host", "localhost");
                int    redisPort     = lang.getInt("sharding.redis.port", 6379);
                String redisPassword = lang.getString("sharding.redis.password", "");
                int    preloadDelay  = lang.getInt("sharding.preloadDelay", 100);

                this.shardHandler = new ShardTransferHandler(
                        proxy, logger, this,
                        redisHost, redisPort,
                        redisPassword.isEmpty() ? null : redisPassword,
                        preloadDelay
                );

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

        // ── RabbitMQ player-count publisher ───────────────────────────────────
        // Config block: placeholders.rabbitmq in lang.yml
        // Uses the same RabbitMQ URI as OreoEssentials — no extra infrastructure.
        //
        // Enables on every OreoEssentials backend server:
        //   %oreo_crossserver_players_total%   — total across the whole network
        //   %oreo_server_players_total%         — players on this specific server
        //   %oreo_server_players_<name>%        — players on any named server
        if (lang.getBool("placeholders.rabbitmq.enabled", false)) {
            String uri = lang.getString("placeholders.rabbitmq.uri", "");
            if (uri.isBlank()) {
                logger.warn("[CountPublisher] placeholders.rabbitmq.uri is empty — publisher disabled.");
                logger.warn("[CountPublisher] Add to lang.yml:  placeholders.rabbitmq.uri: \"amqp://user:pass@host/vhost\"");
            } else {
                this.countPublisher = new RabbitMQCountPublisher(proxy, logger, uri);
                if (countPublisher.start()) {
                    logger.info("[CountPublisher] Publishing player counts via exchange '{}'",
                            RabbitMQCountPublisher.EXCHANGE_NAME);
                    logger.info("[CountPublisher] Backend PAPI placeholders now available:");
                    logger.info("[CountPublisher]   %oreo_crossserver_players_total%");
                    logger.info("[CountPublisher]   %oreo_server_players_total%");
                    logger.info("[CountPublisher]   %oreo_server_players_<name>%");
                } else {
                    logger.error("[CountPublisher] RabbitMQ connect failed — network count placeholders will return 0");
                    this.countPublisher = null;
                }
            }
        } else {
            logger.info("[CountPublisher] Disabled (placeholders.rabbitmq.enabled: false)");
            logger.info("[CountPublisher] To enable, add to lang.yml:");
            logger.info("[CountPublisher]   placeholders:");
            logger.info("[CountPublisher]     rabbitmq:");
            logger.info("[CountPublisher]       enabled: true");
            logger.info("[CountPublisher]       uri: \"amqp://user:pass@host/vhost\"");
        }

        // ── Tab ───────────────────────────────────────────────────────────────
        if (isTabEnabled()) {
            updateAllTabs();
        } else {
            logger.info("[OreoNetworkTab] TAB handling disabled (tab.enabled: false).");
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        logger.info("[OreoNetworkTab] Shutting down...");

        if (shardHandler   != null) shardHandler.shutdown();
        if (countPublisher != null) countPublisher.shutdown();

        logger.info("[OreoNetworkTab] Shutdown complete");
    }

    // -------------------------------------------------------------------------
    // Player events
    // -------------------------------------------------------------------------

    @Subscribe
    public void onJoin(PostLoginEvent event) {
        if (isTabEnabled()) updateAllTabs();
        pendingFirstConnect.add(event.getPlayer().getUniqueId());

        // Push counts immediately — don't wait for the 1 s heartbeat tick
        if (countPublisher != null) countPublisher.publishNow();
    }

    @Subscribe
    public void onQuit(DisconnectEvent event) {
        if (isTabEnabled()) updateAllTabs();

        Player p = event.getPlayer();

        if (lang != null && lang.getBool("messages.quit.enabled", true)) {
            String quitFmt = resolveRankedMessage(
                    p,
                    "messages.quit",
                    "<gradient:#FF1493:#00FF7F>-</gradient> <white>{name}</white> <gray>left the network</gray>"
            );
            broadcastMini(
                    braceToMiniPlaceholders(quitFmt),
                    Placeholder.parsed("name", p.getUsername())
            );
        }

        lastServer.remove(p.getUniqueId());
        pendingFirstConnect.remove(p.getUniqueId());

        // Network total drops immediately on disconnect
        if (countPublisher != null) countPublisher.publishNow();
    }

    @Subscribe
    public void onPreConnect(ServerPreConnectEvent event) {
        Player p       = event.getPlayer();
        String unknown = getUnknownServerName();

        String from = p.getCurrentServer()
                .map(s -> s.getServerInfo().getName())
                .orElse(unknown);

        lastServer.put(p.getUniqueId(), from);
    }

    @Subscribe
    public void onServerSwitch(ServerPostConnectEvent event) {
        if (isTabEnabled()) updateAllTabs();

        Player p       = event.getPlayer();
        String unknown = getUnknownServerName();

        String to = p.getCurrentServer()
                .map(s -> s.getServerInfo().getName())
                .orElse(unknown);

        // Per-server counts change on every switch — push immediately so
        // %oreo_server_players_<name>% reflects the new state without delay
        if (countPublisher != null) countPublisher.publishNow();

        // First connection to any server → fire JOIN message
        if (pendingFirstConnect.remove(p.getUniqueId())) {
            if (lang != null && lang.getBool("messages.join.enabled", true)) {
                String joinFmt = resolveRankedMessage(
                        p,
                        "messages.join",
                        "<gradient:#FF1493:#00FF7F>+</gradient> <white>{name}</white> <gray>joined the network</gray>"
                );
                broadcastMini(
                        braceToMiniPlaceholders(joinFmt),
                        Placeholder.parsed("name", p.getUsername()),
                        Placeholder.parsed("to",   to),
                        Placeholder.parsed("from", unknown)
                );
            }
            return;
        }

        // Subsequent server switches → optional switch message
        if (lang == null || !lang.getBool("messages.switch.enabled", false)) return;

        String from = lastServer.getOrDefault(p.getUniqueId(), unknown);
        if (from.equalsIgnoreCase(unknown)) return;
        if (from.equalsIgnoreCase(to))      return;

        String fmt = resolveRankedMessage(
                p,
                "messages.switch",
                "<gray>{name}</gray> <dark_gray>»</dark_gray> <white>{to}</white>"
        );
        broadcastMini(
                braceToMiniPlaceholders(fmt),
                Placeholder.parsed("name", p.getUsername()),
                Placeholder.parsed("to",   to),
                Placeholder.parsed("from", from)
        );
    }

    // -------------------------------------------------------------------------
    // Ranked message resolver
    // -------------------------------------------------------------------------

    /**
     * Resolves a message with per-rank override support.
     *
     * Config format:
     * <pre>
     * messages:
     *   join:
     *     enabled: true
     *     formats:
     *       - permission: "oreo.rank.vip"
     *         format: "<gold>[VIP]</gold> {name} joined"
     *       - permission: "oreo.rank.admin"
     *         format: "<red>[ADMIN]</red> {name} joined"
     *     format: "{name} joined"   # default fallback
     * </pre>
     *
     * Iterates formats in order — first matching permission wins.
     * Falls back to {@code messages.<type>.format}, then {@code defaultFallback}.
     */
    private String resolveRankedMessage(Player player, String basePath, String defaultFallback) {
        if (lang == null) return defaultFallback;

        for (CommentedConfigurationNode entry : lang.getNodeList(basePath + ".formats")) {
            String perm = entry.node("permission").getString();
            String fmt  = entry.node("format").getString();
            if (perm != null && !perm.isBlank() && fmt != null && !fmt.isBlank()) {
                if (player.hasPermission(perm)) return fmt;
            }
        }

        return lang.getMini(basePath + ".format", defaultFallback);
    }

    // -------------------------------------------------------------------------
    // Broadcasting helpers
    // -------------------------------------------------------------------------

    private void broadcastMini(String mini) {
        broadcastToAllowedPlayers(mm.deserialize(mini));
    }

    private void broadcastMini(String mini, TagResolver... resolvers) {
        broadcastToAllowedPlayers(mm.deserialize(mini, resolvers));
    }

    private void broadcastToAllowedPlayers(Component component) {
        for (Player pl : proxy.getAllPlayers()) {
            if (!isRecipientExcepted(pl)) pl.sendMessage(component);
        }
    }

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
     * Converts {name}/{to}/{from} brace-style placeholders to MiniMessage tag equivalents
     * so they can be resolved by {@link Placeholder#parsed}.
     */
    private String braceToMiniPlaceholders(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.replace("{name}", "<name>")
                .replace("{to}",   "<to>")
                .replace("{from}", "<from>");
    }

    // -------------------------------------------------------------------------
    // Tab list
    // -------------------------------------------------------------------------

    private void updateAllTabs() {
        if (!isTabEnabled()) return;

        Collection<Player> players = proxy.getAllPlayers();
        for (Player viewer : players) {
            viewer.getTabList().clearAll();
            for (Player target : players) {
                addOrUpdateEntry(viewer, target);
            }
        }
    }

    private void addOrUpdateEntry(Player viewer, Player target) {
        TabList tab    = viewer.getTabList();
        String unknown = getUnknownServerName();

        tab.getEntry(target.getUniqueId())
                .ifPresent(e -> tab.removeEntry(e.getProfile().getId()));

        String serverName = target.getCurrentServer()
                .map(conn -> conn.getServerInfo().getName())
                .orElse(unknown);

        boolean showServer = lang != null && lang.getBool("tab.showServerInName", true);

        Component displayName = showServer
                ? Component.text()
                .append(Component.text(target.getUsername(), NamedTextColor.WHITE))
                .append(Component.space())
                .append(Component.text("(",        NamedTextColor.DARK_GRAY))
                .append(Component.text(serverName, NamedTextColor.GRAY))
                .append(Component.text(")",        NamedTextColor.DARK_GRAY))
                .build()
                : Component.text(target.getUsername(), NamedTextColor.WHITE);

        tab.addEntry(TabListEntry.builder()
                .tabList(tab)
                .profile(target.getGameProfile())
                .displayName(displayName)
                .latency((int) Math.max(0, Math.min(Integer.MAX_VALUE, target.getPing())))
                .gameMode(0)
                .listed(true)
                .showHat(true)
                .build());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean isTabEnabled() {
        return lang != null && lang.getBool("tab.enabled", true);
    }

    private String getUnknownServerName() {
        return (lang == null) ? "unknown" : lang.getString("tab.unknownServerName", "unknown");
    }
}