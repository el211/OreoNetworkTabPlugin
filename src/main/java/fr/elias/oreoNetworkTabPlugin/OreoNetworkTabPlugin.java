package fr.elias.oreoNetworkTabPlugin;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.player.TabList;
import com.velocitypowered.api.proxy.player.TabListEntry;
import com.velocitypowered.api.util.GameProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.Optional;

@Plugin(
        id = "oreo-network-tab",
        name = "OreoNetworkTab",
        version = "1.0.0",
        authors = {"Elias"}
)
public class OreoNetworkTabPlugin {

    private final ProxyServer proxy;
    private final Logger logger;

    @Inject
    public OreoNetworkTabPlugin(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInit(ProxyInitializeEvent event) {
        logger.info("[OreoNetworkTab] Initializing global tab plugin...");
        updateAllTabs();
    }

    @Subscribe
    public void onJoin(PostLoginEvent event) {
        updateAllTabs();
    }

    @Subscribe
    public void onQuit(DisconnectEvent event) {
        updateAllTabs();
    }

    @Subscribe
    public void onServerSwitch(ServerPostConnectEvent event) {
        updateAllTabs();
    }

    /**
     * Met à jour le TAB de tous les joueurs :
     * - clearAll() sur chaque tablist
     * - rajoute une entrée pour chaque joueur du réseau
     */
    private void updateAllTabs() {
        Collection<Player> players = proxy.getAllPlayers();

        for (Player viewer : players) {
            TabList tab = viewer.getTabList();

            // Vide le tab du viewer
            tab.clearAll();

            for (Player target : players) {
                addOrUpdateEntry(viewer, target);
            }
        }
    }

    private void addOrUpdateEntry(Player viewer, Player target) {
        TabList tab = viewer.getTabList();

        Optional<TabListEntry> existing = tab.getEntry(target.getUniqueId());
        existing.ifPresent(entry -> tab.removeEntry(entry.getProfile().getId()));

        String serverName = target.getCurrentServer()
                .map(conn -> conn.getServerInfo().getName())
                .orElse("unknown");

        Component displayName = Component.text()
                .append(Component.text(target.getUsername(), NamedTextColor.WHITE))
                .append(Component.space())
                .append(Component.text("(", NamedTextColor.DARK_GRAY))
                .append(Component.text(serverName, NamedTextColor.GRAY))
                .append(Component.text(")", NamedTextColor.DARK_GRAY))
                .build();

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
}
