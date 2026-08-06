package com.fkc.game;

import com.fkc.game.core.GameModule;
import com.fkc.game.mahjong.MahjongModule;
import com.fkc.game.uno.UnoModule;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared entry point for every mini-game bundled in this plugin. Each game
 * lives in its own package (com.fkc.game.&lt;name&gt;) and implements
 * {@link GameModule}; this class just knows the list of modules and turns
 * them on/off together with the plugin itself.
 * <p>
 * To add a new game later: write it in its own com.fkc.game.&lt;newgame&gt;
 * package, implement GameModule, add its command block to plugin.yml, and
 * add one line to {@link #createModules()} below.
 */
public final class FkcGamePlugin extends JavaPlugin {

    private final List<GameModule> modules = new ArrayList<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();

        modules.addAll(createModules());
        for (GameModule module : modules) {
            try {
                module.onEnable(this);
            } catch (Exception e) {
                getLogger().severe("[" + module.id() + "] 啟用失敗: " + e);
            }
        }

        getLogger().info("FkcGame 已啟用，共載入 " + modules.size() + " 個遊戲模組。");
    }

    @Override
    public void onDisable() {
        for (GameModule module : modules) {
            try {
                module.onDisable(this);
            } catch (Exception e) {
                getLogger().severe("[" + module.id() + "] 停用時發生錯誤: " + e);
            }
        }
    }

    private List<GameModule> createModules() {
        List<GameModule> list = new ArrayList<>();
        list.add(new MahjongModule());
        list.add(new UnoModule());
        // 以後要加新遊戲，在這裡多加一行 list.add(new SomeOtherModule());
        return list;
    }
}
