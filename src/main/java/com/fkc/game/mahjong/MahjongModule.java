package com.fkc.game.mahjong;

import com.fkc.game.core.GameModule;
import com.fkc.game.mahjong.command.MahjongCommand;
import com.fkc.game.mahjong.econ.EconomyHook;
import com.fkc.game.mahjong.game.TableManager;
import com.fkc.game.mahjong.listener.PlayerCleanupListener;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public final class MahjongModule implements GameModule {

    private TableManager tableManager;

    @Override
    public String id() {
        return "mahjong";
    }

    @Override
    public void onEnable(Plugin plugin) {
        this.tableManager = new TableManager(plugin);
        EconomyHook economy = new EconomyHook();

        var command = Bukkit.getPluginCommand("mahjong");
        if (command != null) {
            command.setExecutor(new MahjongCommand(tableManager, plugin, economy));
        } else {
            plugin.getLogger().warning("[mahjong] 找不到 'mahjong' 指令，plugin.yml 的 commands 區塊可能沒有正確設定。");
        }

        plugin.getServer().getPluginManager().registerEvents(new PlayerCleanupListener(tableManager), plugin);

        // Sweep for abandoned/never-started tables once a minute.
        plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin,
                task -> tableManager.expireIdleTables(), 20L * 60, 20L * 60);

        plugin.getLogger().info("[mahjong] 麻將模組已啟用。使用 /mahjong create 建立牌桌。");
    }

    public TableManager getTableManager() {
        return tableManager;
    }
}
