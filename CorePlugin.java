package de.deinserver.coreplugin;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public class CorePlugin extends JavaPlugin {

    private static CorePlugin instance;
    private ClanManager clanManager;
    private CombatManager combatManager;
    private RecordManager recordManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        this.clanManager = new ClanManager(this);
        this.combatManager = new CombatManager(this);
        this.recordManager = new RecordManager(this);

        // Befehle sicher registrieren
        PluginCommand clanCmd = getCommand("clan");
        if (clanCmd != null) {
            clanCmd.setExecutor(clanManager);
            clanCmd.setTabCompleter(clanManager);
        } else {
            getLogger().severe("FEHLER: Command 'clan' wurde in der plugin.yml nicht gefunden!");
        }

        PluginCommand ccCmd = getCommand("clanchat");
        if (ccCmd != null) {
            ccCmd.setExecutor(clanManager);
        }

        PluginCommand rekordCmd = getCommand("rekord");
        if (rekordCmd != null) {
            rekordCmd.setExecutor(recordManager);
        }

        // Events registrieren
        Bukkit.getPluginManager().registerEvents(clanManager, this);
        Bukkit.getPluginManager().registerEvents(combatManager, this);
        Bukkit.getPluginManager().registerEvents(recordManager, this);

        // Online-Spieler bei Reload aktualisieren
        Bukkit.getOnlinePlayers().forEach(p -> {
            clanManager.updatePlayerNametag(p);
            combatManager.checkPendingReturns(p);
        });
        recordManager.checkRecord();

        getLogger().info("========================================");
        getLogger().info("CorePlugin 1.21.11 erfolgreich geladen!");
        getLogger().info("Commands: /clan, /cc, /rekord aktiv.");
        getLogger().info("========================================");
    }

    @Override
    public void onDisable() {
        if (clanManager != null) clanManager.saveClans();
        if (combatManager != null) combatManager.savePendingReturns();
        if (recordManager != null) recordManager.saveRecord();
        getLogger().info("CorePlugin deaktiviert und Daten gespeichert.");
    }

    public static CorePlugin getInstance() {
        return instance;
    }

    public ClanManager getClanManager() {
        return clanManager;
    }
}
