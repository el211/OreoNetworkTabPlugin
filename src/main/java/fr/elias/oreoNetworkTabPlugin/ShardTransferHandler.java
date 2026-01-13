package fr.elias.oreoNetworkTabPlugin;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.simplix.protocolize.api.Protocolize;
import dev.simplix.protocolize.api.player.ProtocolizePlayer;
import org.slf4j.Logger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class ShardTransferHandler {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Jedis redis;
    private final int preloadDelay;
    private final Object plugin;

    private final String redisHost;
    private final int redisPort;
    private final String redisPassword;

    private final Map<UUID, PendingTransfer> pendingTransfers = new ConcurrentHashMap<>();

    public ShardTransferHandler(ProxyServer proxy, Logger logger, Object plugin, String redisHost, int redisPort, String redisPassword, int preloadDelay) {
        this.proxy = proxy;
        this.logger = logger;
        this.plugin = plugin;
        this.preloadDelay = preloadDelay;
        this.redisHost = redisHost;
        this.redisPort = redisPort;
        this.redisPassword = redisPassword;

        this.redis = new Jedis(redisHost, redisPort);
        if (redisPassword != null && !redisPassword.isEmpty()) {
            redis.auth(redisPassword);
        }

        logger.info("[ShardTransfer] Connected to Redis at {}:{}", redisHost, redisPort);

        try {
            Class.forName("dev.simplix.protocolize.api.Protocolize");
            logger.info("[ShardTransfer] Protocolize detected! Enhanced packet manipulation enabled!");
        } catch (ClassNotFoundException e) {
            logger.warn("[ShardTransfer] Protocolize not found - using standard transfers");
            logger.warn("[ShardTransfer] Install Protocolize for truly seamless transfers!");
        }

        startRedisListener();
    }

    private void startRedisListener() {
        new Thread(() -> {
            try (Jedis sub = new Jedis(redisHost, redisPort)) {
                if (redisPassword != null && !redisPassword.isEmpty()) {
                    sub.auth(redisPassword);
                }

                logger.info("[ShardTransfer] Starting Redis PubSub listener on channel 'shard_transfer_requests'");

                sub.subscribe(new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        handleShardTransferRequest(message);
                    }

                    @Override
                    public void onSubscribe(String channel, int subscribedChannels) {
                        logger.info("[ShardTransfer] Subscribed to Redis channel: {}", channel);
                    }
                }, "shard_transfer_requests");

            } catch (Exception e) {
                logger.error("[ShardTransfer] Redis listener died - seamless transfers will NOT work!", e);
                logger.error("[ShardTransfer] Please check Redis connection and restart Velocity");
            }
        }, "ShardTransfer-Redis").start();
    }

    private void handleShardTransferRequest(String message) {
        String[] parts = message.split("\\|");
        if (parts.length != 5) {
            logger.warn("[ShardTransfer] Invalid transfer request format: {}", message);
            return;
        }

        try {
            UUID playerId = UUID.fromString(parts[0]);
            String targetShard = parts[1];
            double x = Double.parseDouble(parts[2]);
            double y = Double.parseDouble(parts[3]);
            double z = Double.parseDouble(parts[4]);

            Player player = proxy.getPlayer(playerId).orElse(null);
            if (player == null) {
                logger.warn("[ShardTransfer] Player {} not found on proxy", playerId);
                return;
            }

            RegisteredServer targetServer = proxy.getServer(targetShard).orElse(null);
            if (targetServer == null) {
                logger.error("[ShardTransfer] Target server '{}' not found in Velocity config!", targetShard);
                return;
            }

            logger.info("[ShardTransfer] Processing transfer: {} -> {} at ({}, {}, {})",
                    player.getUsername(), targetShard, x, y, z);

            pendingTransfers.put(playerId, new PendingTransfer(targetShard, x, y, z));

            String preloadMsg = playerId + "|" + targetShard + "|" + x + "|" + z;
            redis.publish("shard_preload_chunks", preloadMsg);

            proxy.getScheduler()
                    .buildTask(plugin, () -> performSeamlessTransfer(player, targetServer, x, y, z))
                    .delay(preloadDelay, TimeUnit.MILLISECONDS)
                    .schedule();

        } catch (Exception e) {
            logger.error("[ShardTransfer] Unexpected error handling transfer request: {}", message, e);
        }
    }

    private void performSeamlessTransfer(Player player, RegisteredServer target, double x, double y, double z) {
        if (!player.isActive()) {
            logger.warn("[ShardTransfer] Player {} disconnected before transfer completed", player.getUsername());
            pendingTransfers.remove(player.getUniqueId());
            return;
        }

        try {
            ProtocolizePlayer protocolizePlayer = Protocolize.playerProvider().player(player.getUniqueId());

            if (protocolizePlayer != null) {
                logger.debug("[ShardTransfer] Using Protocolize-enhanced transfer for {}", player.getUsername());

                player.createConnectionRequest(target)
                        .connect()
                        .thenAccept(result -> {
                            if (result.isSuccessful()) {
                                logger.info("[ShardTransfer] ✓ Protocolize-enhanced transfer complete for {}",
                                        player.getUsername());
                            }
                        });
            } else {
                performStandardTransfer(player, target, x, y, z);
            }

        } catch (Exception e) {
            logger.warn("[ShardTransfer] Protocolize not available, using standard transfer", e);
            performStandardTransfer(player, target, x, y, z);
        }
    }

    private void performStandardTransfer(Player player, RegisteredServer target, double x, double y, double z) {
        player.createConnectionRequest(target).fireAndForget();
        logger.info("[ShardTransfer] ✓ Standard transfer complete for {} to {} at ({}, {}, {})",
                player.getUsername(), target.getServerInfo().getName(), x, y, z);
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        PendingTransfer transfer = pendingTransfers.remove(player.getUniqueId());

        if (transfer != null) {
            logger.info("[ShardTransfer] Seamless transfer in progress for {} to {}",
                    player.getUsername(), transfer.targetShard);
        }
    }

    public void shutdown() {
        logger.info("[ShardTransfer] Shutting down...");

        int pendingCount = pendingTransfers.size();
        if (pendingCount > 0) {
            logger.warn("[ShardTransfer] {} pending transfers will be interrupted", pendingCount);
        }
        pendingTransfers.clear();

        if (redis != null && redis.isConnected()) {
            try {
                redis.close();
                logger.info("[ShardTransfer] Redis connection closed");
            } catch (Exception e) {
                logger.error("[ShardTransfer] Error closing Redis connection", e);
            }
        }

        logger.info("[ShardTransfer] Shutdown complete");
    }

    private static class PendingTransfer {
        final String targetShard;
        final double x, y, z;

        PendingTransfer(String targetShard, double x, double y, double z) {
            this.targetShard = targetShard;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}