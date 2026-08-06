package com.fkc.game.uno;

import com.fkc.game.core.GameModule;
import com.fkc.game.uno.command.UnoCommand;
import com.fkc.game.uno.econ.EconomyHook;
import com.fkc.game.uno.game.UnoTableManager;
import com.fkc.game.uno.listener.UnoCleanupListener;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public final class UnoModule implements GameModule {

    private UnoTableManager tableManager;

    @Override
    public String id() {
        return "uno";
    }

    @Override
    public void onEnable(Plugin plugin) {
        this.tableManager = new UnoTableManager(plugin);
        EconomyHook economy = new EconomyHook();

        var command = Bukkit.getPluginCommand("uno");
        if (command != null) {
            command.setExecutor(new UnoCommand(tableManager, plugin, economy));
        } else {
            plugin.getLogger().warning("[uno] 找不到 'uno' 指令，plugin.yml 的 commands 區塊可能沒有正確設定。");
        }

        plugin.getServer().getPluginManager().registerEvents(new UnoCleanupListener(tableManager), plugin);

        plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin,
                task -> tableManager.expireIdleTables(), 20L * 60, 20L * 60);

        plugin.getLogger().info("[uno] UNO 模組已啟用。使用 /uno create 建立牌桌。");
    }

    public UnoTableManager getTableManager() {
        return tableManager;
    }
}
