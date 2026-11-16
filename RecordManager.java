package de.deinserver.coreplugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class RecordManager implements CommandExecutor, Listener {

    private final CorePlugin plugin;
    private int allTimeRecord;
    private String recordDate;
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy 'um' HH:mm 'Uhr'");

    public RecordManager(CorePlugin plugin) {
        this.plugin = plugin;
        this.allTimeRecord = plugin.getConfig().getInt("record.count", 0);
        this.recordDate = plugin.getConfig().getString("record.date", "Noch nicht aufgestellt");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, this::checkRecord, 1L);
    }

    public void checkRecord() {
        int currentOnline = Bukkit.getOnlinePlayers().size();
        if (currentOnline > allTimeRecord) {
            allTimeRecord = currentOnline;
            recordDate = LocalDateTime.now().format(formatter);
            saveRecord();
            Bukkit.broadcastMessage(ChatColor.GOLD + "★ NEUER SPIELERREKORD! " + ChatColor.YELLOW
                    + "Es sind aktuell " + ChatColor.AQUA + ChatColor.BOLD + allTimeRecord + ChatColor.YELLOW + " Spieler gleichzeitig online!");
        }
    }

    public void saveRecord() {
        plugin.getConfig().set("record.count", allTimeRecord);
        plugin.getConfig().set("record.date", recordDate);
        plugin.saveConfig();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        int current = Bukkit.getOnlinePlayers().size();
        sender.sendMessage(ChatColor.GOLD + "=== Server Spielerrekord ===");
        sender.sendMessage(ChatColor.GRAY + "Aktuell online: " + ChatColor.WHITE + current);
        sender.sendMessage(ChatColor.GRAY + "All-Time Rekord: " + ChatColor.AQUA + ChatColor.BOLD + allTimeRecord + " Spieler");
        sender.sendMessage(ChatColor.GRAY + "Aufgestellt am: " + ChatColor.YELLOW + recordDate);
        return true;
    }
}
