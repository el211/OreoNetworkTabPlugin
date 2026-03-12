package fr.elias.oreoNetworkTabPlugin;

import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


public class RabbitMQCountPublisher {

    public static final String EXCHANGE_NAME = "oreo.network.counts";

    private final ProxyServer proxy;
    private final Logger logger;
    private final String uri;

    private volatile Connection connection;
    private volatile Channel channel;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "OreoTab-RabbitCountPublisher");
        t.setDaemon(true);
        return t;
    });

    public RabbitMQCountPublisher(ProxyServer proxy, Logger logger, String uri) {
        this.proxy  = proxy;
        this.logger = logger;
        this.uri    = uri;
    }


    public boolean start() {
        if (!connect()) return false;

        scheduler.scheduleAtFixedRate(() -> {
            try {
                publishCounts();
            } catch (Exception e) {
                logger.warn("[CountPublisher] Publish failed, attempting reconnect: {}", e.getMessage());
                reconnect();
            }
        }, 0, 1, TimeUnit.SECONDS);

        logger.info("[CountPublisher] Heartbeat started (1 s interval)");
        return true;
    }


    public void publishNow() {
        scheduler.submit(() -> {
            try {
                publishCounts();
            } catch (Exception e) {
                logger.warn("[CountPublisher] Immediate publish failed: {}", e.getMessage());
                reconnect();
            }
        });
    }

    private void publishCounts() throws IOException {
        ensureConnected();

        int networkTotal = proxy.getAllPlayers().size();
        var servers = proxy.getAllServers();

        ByteArrayOutputStream baos = new ByteArrayOutputStream(64);
        DataOutputStream out = new DataOutputStream(baos);

        out.writeInt(networkTotal);
        out.writeInt(servers.size());
        for (RegisteredServer server : servers) {
            out.writeUTF(server.getServerInfo().getName());
            out.writeInt(server.getPlayersConnected().size());
        }
        out.flush();

        channel.basicPublish(EXCHANGE_NAME, "", null, baos.toByteArray());
    }

    private synchronized boolean connect() {
        try {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setUri(uri);
            factory.setAutomaticRecoveryEnabled(true);
            factory.setNetworkRecoveryInterval(5_000);

            connection = factory.newConnection("OreoVelocity-CountPublisher");
            channel    = connection.createChannel();

            channel.exchangeDeclare(EXCHANGE_NAME, BuiltinExchangeType.FANOUT, true);

            logger.info("[CountPublisher] Connected to RabbitMQ, exchange '{}' ready", EXCHANGE_NAME);
            return true;
        } catch (Exception e) {
            logger.error("[CountPublisher] Failed to connect to RabbitMQ: {}", e.getMessage());
            connection = null;
            channel    = null;
            return false;
        }
    }

    private synchronized void ensureConnected() throws IOException {
        if (connection != null && connection.isOpen() && channel != null && channel.isOpen()) return;
        if (!connect()) throw new IOException("RabbitMQ not connected and reconnect failed");
    }

    private void reconnect() {
        closeQuietly();
        connect();
    }

    public void shutdown() {
        scheduler.shutdownNow();
        closeQuietly();
        logger.info("[CountPublisher] Shut down");
    }

    private void closeQuietly() {
        try { if (channel    != null) channel.close();    } catch (Exception ignored) {}
        try { if (connection != null) connection.close(); } catch (Exception ignored) {}
        channel    = null;
        connection = null;
    }
}