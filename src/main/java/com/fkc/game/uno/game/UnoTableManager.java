package com.fkc.game.uno.game;

import com.fkc.game.core.Leaderboard;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class UnoTableManager {

    private final Plugin plugin;
    private final Map<UUID, UnoTable> tables = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> playerTable = new ConcurrentHashMap<>();
    private final Leaderboard leaderboard;
    private final com.fkc.game.core.PlayerPreferenceStore sidebarPrefs = new com.fkc.game.core.PlayerPreferenceStore();

    public com.fkc.game.core.PlayerPreferenceStore sidebarPrefs() {
        return sidebarPrefs;
    }

    public UnoTableManager(Plugin plugin) {
        this.plugin = plugin;
        this.leaderboard = new Leaderboard(plugin.getDataFolder(), "leaderboard-uno.properties");
    }

    public Leaderboard leaderboard() {
        return leaderboard;
    }

    public UnoTable create(UUID hostUuid, String hostName) {
        UnoTable table = new UnoTable(plugin, hostUuid, leaderboard, sidebarPrefs);
        table.addPlayer(hostUuid, hostName, false);
        table.toggleReady(hostUuid); // host defaults to already-ready — no need to /uno ready themselves
        applyConfigDefaults(table);
        tables.put(hostUuid, table);
        playerTable.put(hostUuid, hostUuid);
        return table;
    }

    private void applyConfigDefaults(UnoTable table) {
        var config = plugin.getConfig();
        table.settings.startingHandSize = config.getInt("uno.rules.starting-hand-size", 7);
        table.settings.targetScore = config.getInt("uno.rules.target-score", 500);
        table.settings.thinkingTimeSeconds = config.getInt("uno.rules.thinking-time-seconds", 30);
        table.settings.unoCatchPenalty = config.getInt("uno.rules.uno-catch-penalty", 2);

        table.serverBroadcast.put(UnoTable.BroadcastCategory.ROUND_START,
                config.getBoolean("uno.broadcast-to-server.round-start", false));
        table.serverBroadcast.put(UnoTable.BroadcastCategory.PLAY,
                config.getBoolean("uno.broadcast-to-server.play", false));
        table.serverBroadcast.put(UnoTable.BroadcastCategory.DRAW,
                config.getBoolean("uno.broadcast-to-server.draw", false));
        table.serverBroadcast.put(UnoTable.BroadcastCategory.SPECIAL,
                config.getBoolean("uno.broadcast-to-server.special", false));
        table.serverBroadcast.put(UnoTable.BroadcastCategory.WIN,
                config.getBoolean("uno.broadcast-to-server.win", false));
        table.serverBroadcast.put(UnoTable.BroadcastCategory.GAME_END,
                config.getBoolean("uno.broadcast-to-server.game-end", false));
    }

    public UnoTable get(UUID tableId) {
        return tables.get(tableId);
    }

    public UnoTable tableOf(UUID playerUuid) {
        UUID tableId = playerTable.get(playerUuid);
        return tableId != null ? tables.get(tableId) : null;
    }

    public boolean join(UUID tableId, UUID playerUuid, String name) {
        UnoTable table = tables.get(tableId);
        if (table == null || table.phase != UnoTable.Phase.WAITING) return false;
        boolean added = table.addPlayer(playerUuid, name, false);
        if (added) playerTable.put(playerUuid, tableId);
        return added;
    }

    public void leave(UUID playerUuid) {
        UnoTable table = tableOf(playerUuid);
        if (table == null) return;
        table.removePlayer(playerUuid);
        playerTable.remove(playerUuid);
        if (table.players.isEmpty()) {
            tables.remove(table.id);
        }
    }

    public void destroy(UUID tableId) {
        UnoTable table = tables.remove(tableId);
        if (table == null) return;
        plugin.getLogger().info("[uno] 桌 " + tableId + " 已解散（解散當下 phase=" + table.phase + "）。");
        table.clearSidebars();
        for (var p : table.players) playerTable.remove(p.uuid);
    }

    public void expireIdleTables() {
        long timeoutMinutes = plugin.getConfig().getLong("uno.table-idle-timeout-minutes", 5);
        long timeoutMillis = timeoutMinutes * 60_000L;
        long now = System.currentTimeMillis();

        java.util.List<UUID> toRemove = new java.util.ArrayList<>();
        for (UnoTable table : tables.values()) {
            if (table.phase == UnoTable.Phase.WAITING && now - table.waitingSince > timeoutMillis) {
                toRemove.add(table.id);
            }
        }
        for (UUID id : toRemove) {
            UnoTable table = tables.get(id);
            if (table != null) {
                table.broadcast(net.kyori.adventure.text.Component.text(
                                "這張牌桌閒置超過 " + timeoutMinutes + " 分鐘沒有開始，已自動解散。",
                                net.kyori.adventure.text.format.NamedTextColor.GRAY),
                        UnoTable.BroadcastCategory.GAME_END);
            }
            destroy(id);
        }
    }

    public Map<UUID, UnoTable> all() {
        return tables;
    }
}
