package com.fkc.game.core;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple in-memory per-player boolean preference (e.g. "do you want the
 * sidebar shown"). Not persisted across server restarts — resets to the
 * server-configured default each time the plugin (re)starts, which is a
 * reasonable trade-off for something this minor.
 */
public class PlayerPreferenceStore {

    private final Map<UUID, Boolean> overrides = new ConcurrentHashMap<>();

    /** @return the player's explicit override, or serverDefault if they haven't set one. */
    public boolean isEnabled(UUID uuid, boolean serverDefault) {
        return overrides.getOrDefault(uuid, serverDefault);
    }

    /** @return the new state after toggling. */
    public boolean toggle(UUID uuid, boolean serverDefault) {
        boolean next = !isEnabled(uuid, serverDefault);
        overrides.put(uuid, next);
        return next;
    }
}
