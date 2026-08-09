package com.fkc.game.core;

import org.bukkit.plugin.Plugin;

/**
 * One "game" that lives inside the shared FkcGame plugin (mahjong today,
 * more later). Each module gets a reference to the actual registered
 * JavaPlugin (as a plain Plugin, which is all it needs) and is responsible
 * for registering its own commands, listeners, and scheduled tasks.
 * <p>
 * Deliberately kept tiny — this isn't trying to be a full plugin-loading
 * framework, just enough structure that adding a second game later doesn't
 * mean tangling its code up with mahjong's.
 */
public interface GameModule {

    /** Short lowercase identifier, used in log messages (e.g. "mahjong"). */
    String id();

    /** Called once, from FkcGamePlugin#onEnable(). */
    void onEnable(Plugin plugin);

    /** Called once, from FkcGamePlugin#onDisable(). Optional to override. */
    default void onDisable(Plugin plugin) {
    }
}
