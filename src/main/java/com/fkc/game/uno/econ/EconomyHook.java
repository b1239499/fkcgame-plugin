package com.fkc.game.uno.econ;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Thin, defensive wrapper around Vault's Economy service. If Vault (or an
 * economy plugin behind it) isn't installed, every method here just acts as
 * if there's no economy — table creation fees are effectively skipped
 * rather than the plugin throwing errors or refusing to load.
 */
public class EconomyHook {

    private Economy economy;
    private boolean checked = false;

    private Economy get() {
        if (!checked) {
            checked = true;
            RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
            if (provider != null) {
                economy = provider.getProvider();
            }
        }
        return economy;
    }

    public boolean isAvailable() {
        return get() != null;
    }

    public double balance(Player player) {
        Economy econ = get();
        return econ == null ? 0 : econ.getBalance((OfflinePlayer) player);
    }

    /** @return true if the withdrawal succeeded (or no economy is present, treated as "nothing to charge"). */
    public boolean withdraw(Player player, double amount) {
        if (amount <= 0) return true;
        Economy econ = get();
        if (econ == null) return true;
        if (econ.getBalance((OfflinePlayer) player) < amount) return false;
        econ.withdrawPlayer((OfflinePlayer) player, amount);
        return true;
    }
}
