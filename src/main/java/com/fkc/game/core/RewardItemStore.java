package com.fkc.game.core;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;

/**
 * Stores a single admin-captured ItemStack — with full NBT (enchantments,
 * custom name, lore, everything) — to disk, so it can later be handed out
 * as an exact clone to match winners. Also tracks, in the same file, how
 * many copies to give per award (independent of how many were in hand when
 * captured) and a daily distribution cap shared across every game that
 * uses this reward pool.
 * <p>
 * Backed by a small YAML file using Bukkit's own ItemStack serialization
 * (ConfigurationSection#getItemStack / #set), so the item itself is never
 * reconstructed from an external template (like ItemsAdder's /iagive would
 * do) — what an admin captures via /mahjong setreward or /uno setreward is
 * byte-for-byte what gets cloned out to winners, including any manually
 * applied enchantments or NBT edits that aren't part of the item's base
 * definition.
 */
public class RewardItemStore {
    private final File file;

    public RewardItemStore(File dataFolder, String filename) {
        this.file = new File(dataFolder, filename);
    }

    /**
     * Captures whatever the player is currently holding in their main hand
     * and saves it as the reward item (any previously-configured give
     * amount is preserved). Returns false if their hand is empty or the
     * save fails.
     */
    public boolean setFromHand(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) return false;
        YamlConfiguration config = loadOrNew();
        config.set("item", held.clone());
        if (!config.contains("give-amount")) config.set("give-amount", 1);
        return trySave(config);
    }

    /** Sets how many copies to give per award, independent of the captured item's original stack size. */
    public boolean setGiveAmount(int amount) {
        if (amount < 1) return false;
        YamlConfiguration config = loadOrNew();
        config.set("give-amount", amount);
        return trySave(config);
    }

    public int getGiveAmount() {
        if (!file.exists()) return 1;
        int amount = YamlConfiguration.loadConfiguration(file).getInt("give-amount", 1);
        return amount < 1 ? 1 : amount;
    }

    /** Returns a fresh clone of the saved reward item with its amount forced to the configured give-amount, or null if none has been set yet. */
    public ItemStack get() {
        if (!file.exists()) return null;
        ItemStack item = YamlConfiguration.loadConfiguration(file).getItemStack("item");
        if (item == null) return null;
        ItemStack clone = item.clone();
        clone.setAmount(getGiveAmount());
        return clone;
    }

    public boolean isSet() {
        return file.exists() && YamlConfiguration.loadConfiguration(file).getItemStack("item") != null;
    }

    /**
     * Checks and atomically consumes one "slot" from today's reward
     * allowance for the given game (e.g. "mahjong" or "uno") — each game
     * tracks its own independent daily count in this same file, even
     * though they share the same underlying reward item. dailyLimit &lt;= 0
     * means unlimited for that game (always succeeds without touching the
     * counter). The counter resets automatically the first time this is
     * called for that game on a new calendar day.
     */
    public synchronized boolean tryConsumeDailyAllowance(String gameKey, int dailyLimit) {
        if (dailyLimit <= 0) return true;
        YamlConfiguration config = loadOrNew();
        String dateKey = "daily-date-" + gameKey;
        String countKey = "daily-count-" + gameKey;
        String today = LocalDate.now().toString();
        String storedDate = config.getString(dateKey, "");
        int count = today.equals(storedDate) ? config.getInt(countKey, 0) : 0;
        if (count >= dailyLimit) {
            if (!today.equals(storedDate)) {
                config.set(dateKey, today);
                config.set(countKey, 0);
                trySave(config);
            }
            return false;
        }
        config.set(dateKey, today);
        config.set(countKey, count + 1);
        return trySave(config);
    }

    private YamlConfiguration loadOrNew() {
        return file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
    }

    private boolean trySave(YamlConfiguration config) {
        try {
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            config.save(file);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
