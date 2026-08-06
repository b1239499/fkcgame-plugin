package com.fkc.game.mahjong.game;

import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TableManager {

    private final Plugin plugin;
    private final Map<UUID, MahjongTable> tables = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> playerTable = new ConcurrentHashMap<>();

    public TableManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public MahjongTable create(UUID hostUuid, String hostName, com.fkc.game.mahjong.model.GameSettings.RuleSet ruleSet) {
        MahjongTable table = new MahjongTable(plugin, hostUuid);
        table.settings.ruleSet = ruleSet;
        table.addPlayer(hostUuid, hostName, false);
        applyConfigDefaults(table);
        tables.put(hostUuid, table);
        playerTable.put(hostUuid, hostUuid);
        return table;
    }

    private void applyConfigDefaults(MahjongTable table) {
        var config = plugin.getConfig();
        String lengthStr = config.getString("mahjong.rules.game-length", "EAST_ONLY");
        try {
            table.settings.gameLength = com.fkc.game.mahjong.model.GameSettings.GameLength.valueOf(lengthStr);
        } catch (IllegalArgumentException ignored) {
            // keep default EAST_ONLY if the config value doesn't parse
        }
        table.settings.minHan = config.getInt("mahjong.rules.min-han", 1);
        table.settings.redFiveCountPerSuit = config.getInt("mahjong.rules.red-five-count-per-suit", 1);
        table.settings.thinkingTimeSeconds = config.getInt("mahjong.rules.thinking-time-seconds", 15);
        table.settings.startingScore = config.getInt("mahjong.rules.starting-score", 25000);
        table.exchangeThreeEnabled = config.getBoolean("mahjong.rules.exchange-three-default", false);

        table.settings.minTai = config.getInt("mahjong.taiwan-rules.min-tai", 1);
        table.settings.pointsPerTai = config.getInt("mahjong.taiwan-rules.points-per-tai", 100);
        table.settings.taiwanStartingScore = config.getInt("mahjong.taiwan-rules.starting-score", 1000);
        table.settings.dealerMultiplier = config.getInt("mahjong.taiwan-rules.dealer-multiplier", 2);

        var tai = table.taiSettings;
        tai.menzen = config.getInt("mahjong.taiwan-rules.tai.menzen", tai.menzen);
        tai.tsumo = config.getInt("mahjong.taiwan-rules.tai.tsumo", tai.tsumo);
        tai.menzenTsumoBonus = config.getInt("mahjong.taiwan-rules.tai.menzen-tsumo-bonus", tai.menzenTsumoBonus);
        tai.pingHu = config.getInt("mahjong.taiwan-rules.tai.ping-hu", tai.pingHu);
        tai.duiDuiHu = config.getInt("mahjong.taiwan-rules.tai.dui-dui-hu", tai.duiDuiHu);
        tai.sanAnKe = config.getInt("mahjong.taiwan-rules.tai.san-an-ke", tai.sanAnKe);
        tai.siAnKe = config.getInt("mahjong.taiwan-rules.tai.si-an-ke", tai.siAnKe);
        tai.wuAnKe = config.getInt("mahjong.taiwan-rules.tai.wu-an-ke", tai.wuAnKe);
        tai.hunYiSe = config.getInt("mahjong.taiwan-rules.tai.hun-yi-se", tai.hunYiSe);
        tai.qingYiSe = config.getInt("mahjong.taiwan-rules.tai.qing-yi-se", tai.qingYiSe);
        tai.ziYiSe = config.getInt("mahjong.taiwan-rules.tai.zi-yi-se", tai.ziYiSe);
        tai.xiaoSanYuan = config.getInt("mahjong.taiwan-rules.tai.xiao-san-yuan", tai.xiaoSanYuan);
        tai.daSanYuan = config.getInt("mahjong.taiwan-rules.tai.da-san-yuan", tai.daSanYuan);
        tai.xiaoSiXi = config.getInt("mahjong.taiwan-rules.tai.xiao-si-xi", tai.xiaoSiXi);
        tai.daSiXi = config.getInt("mahjong.taiwan-rules.tai.da-si-xi", tai.daSiXi);
        tai.fengKe = config.getInt("mahjong.taiwan-rules.tai.feng-ke", tai.fengKe);
        tai.jianKe = config.getInt("mahjong.taiwan-rules.tai.jian-ke", tai.jianKe);
        tai.gangBonus = config.getInt("mahjong.taiwan-rules.tai.gang-bonus", tai.gangBonus);
        tai.flowerEach = config.getInt("mahjong.taiwan-rules.tai.flower-each", tai.flowerEach);
        tai.flowerFullSetBonus = config.getInt("mahjong.taiwan-rules.tai.flower-full-set-bonus", tai.flowerFullSetBonus);
        tai.flowerAllEightBonus = config.getInt("mahjong.taiwan-rules.tai.flower-all-eight-bonus", tai.flowerAllEightBonus);

        table.serverBroadcast.put(com.fkc.game.mahjong.game.MahjongTable.BroadcastCategory.ROUND_START,
                config.getBoolean("mahjong.broadcast-to-server.round-start", false));
        table.serverBroadcast.put(com.fkc.game.mahjong.game.MahjongTable.BroadcastCategory.DISCARD,
                config.getBoolean("mahjong.broadcast-to-server.discard", false));
        table.serverBroadcast.put(com.fkc.game.mahjong.game.MahjongTable.BroadcastCategory.CALL,
                config.getBoolean("mahjong.broadcast-to-server.call", false));
        table.serverBroadcast.put(com.fkc.game.mahjong.game.MahjongTable.BroadcastCategory.WIN,
                config.getBoolean("mahjong.broadcast-to-server.win", false));
        table.serverBroadcast.put(com.fkc.game.mahjong.game.MahjongTable.BroadcastCategory.DRAW,
                config.getBoolean("mahjong.broadcast-to-server.draw", false));
        table.serverBroadcast.put(com.fkc.game.mahjong.game.MahjongTable.BroadcastCategory.GAME_END,
                config.getBoolean("mahjong.broadcast-to-server.game-end", false));
        table.serverBroadcast.put(com.fkc.game.mahjong.game.MahjongTable.BroadcastCategory.EXCHANGE,
                config.getBoolean("mahjong.broadcast-to-server.exchange", false));
    }

    public MahjongTable get(UUID tableId) {
        return tables.get(tableId);
    }

    public MahjongTable tableOf(UUID playerUuid) {
        UUID tableId = playerTable.get(playerUuid);
        return tableId != null ? tables.get(tableId) : null;
    }

    public boolean join(UUID tableId, UUID playerUuid, String name) {
        MahjongTable table = tables.get(tableId);
        if (table == null || table.phase != MahjongTable.Phase.WAITING) return false;
        boolean added = table.addPlayer(playerUuid, name, false);
        if (added) playerTable.put(playerUuid, tableId);
        return added;
    }

    public void leave(UUID playerUuid) {
        MahjongTable table = tableOf(playerUuid);
        if (table == null) return;
        table.removePlayer(playerUuid);
        playerTable.remove(playerUuid);
        if (table.players.isEmpty()) {
            tables.remove(table.id);
        }
    }

    public void destroy(UUID tableId) {
        MahjongTable table = tables.remove(tableId);
        if (table == null) return;
        for (var p : table.players) playerTable.remove(p.uuid);
    }

    /**
     * Called periodically (see MahjongPlugin) to clean up tables that were
     * created but never started within the configured idle window, so the
     * lobby list doesn't fill up with abandoned tables.
     */
    public void expireIdleTables() {
        long timeoutMinutes = plugin.getConfig().getLong("mahjong.table-idle-timeout-minutes", 5);
        long timeoutMillis = timeoutMinutes * 60_000L;
        long now = System.currentTimeMillis();

        java.util.List<UUID> toRemove = new java.util.ArrayList<>();
        for (MahjongTable table : tables.values()) {
            if (table.phase == MahjongTable.Phase.WAITING && now - table.waitingSince > timeoutMillis) {
                toRemove.add(table.id);
            }
        }
        for (UUID id : toRemove) {
            MahjongTable table = tables.get(id);
            if (table != null) {
                table.broadcast(net.kyori.adventure.text.Component.text(
                                "這張牌桌閒置超過 " + timeoutMinutes + " 分鐘沒有開始，已自動解散。",
                                net.kyori.adventure.text.format.NamedTextColor.GRAY),
                        MahjongTable.BroadcastCategory.GAME_END);
            }
            destroy(id);
        }
    }

    public Map<UUID, MahjongTable> all() {
        return tables;
    }
}
