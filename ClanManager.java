package de.deinserver.coreplugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class ClanManager implements CommandExecutor, TabCompleter, Listener {

    private final CorePlugin plugin;
    private final File file;
    private FileConfiguration config;

    private final Map<String, Clan> clans = new HashMap<>();
    private final Map<UUID, String> playerClan = new HashMap<>();
    private final Map<UUID, String> pendingInvites = new HashMap<>();

    public static class Clan {
        String tag;
        String name;
        UUID owner;
        Set<UUID> members = new HashSet<>();

        public Clan(String tag, String name, UUID owner) {
            this.tag = tag;
            this.name = name;
            this.owner = owner;
            this.members.add(owner);
        }
    }

    public ClanManager(CorePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "clans.yml");
        loadClans();
    }

    public void loadClans() {
        if (!file.exists()) {
            try {
                file.getParentFile().mkdirs();
                file.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        config = YamlConfiguration.loadConfiguration(file);
        clans.clear();
        playerClan.clear();

        ConfigurationSection sec = config.getConfigurationSection("clans");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                String tag = sec.getString(key + ".tag");
                String name = sec.getString(key + ".name");
                String ownerStr = sec.getString(key + ".owner");
                if (tag == null || name == null || ownerStr == null) continue;
                UUID owner = UUID.fromString(ownerStr);
                Clan clan = new Clan(tag, name, owner);
                for (String mStr : sec.getStringList(key + ".members")) {
                    UUID mUuid = UUID.fromString(mStr);
                    clan.members.add(mUuid);
                    playerClan.put(mUuid, key.toLowerCase());
                }
                clans.put(key.toLowerCase(), clan);
            }
        }
    }

    public void saveClans() {
        config.set("clans", null);
        for (Map.Entry<String, Clan> entry : clans.entrySet()) {
            String key = entry.getKey();
            Clan c = entry.getValue();
            config.set("clans." + key + ".tag", c.tag);
            config.set("clans." + key + ".name", c.name);
            config.set("clans." + key + ".owner", c.owner.toString());
            List<String> memberList = new ArrayList<>();
            for (UUID u : c.members) memberList.add(u.toString());
            config.set("clans." + key + ".members", memberList);
        }
        try {
            config.save(file);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public Clan getClan(UUID uuid) {
        String key = playerClan.get(uuid);
        return key == null ? null : clans.get(key);
    }

    public void updatePlayerNametag(Player player) {
        if (Bukkit.getScoreboardManager() == null) return;
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        Clan clan = getClan(player.getUniqueId());

        for (Team t : board.getTeams()) {
            if (t.getName().startsWith("clan_") && t.hasEntry(player.getName())) {
                t.removeEntry(player.getName());
            }
        }

        if (clan != null) {
            String teamName = "clan_" + clan.tag.toLowerCase();
            if (teamName.length() > 16) teamName = teamName.substring(0, 16);

            Team team = board.getTeam(teamName);
            if (team == null) {
                team = board.registerNewTeam(teamName);
            }
            team.setPrefix(ChatColor.DARK_GRAY + "[" + ChatColor.GOLD + clan.tag + ChatColor.DARK_GRAY + "] " + ChatColor.WHITE);
            team.addEntry(player.getName());
            player.setPlayerListName(ChatColor.DARK_GRAY + "[" + ChatColor.GOLD + clan.tag + ChatColor.DARK_GRAY + "] " + ChatColor.WHITE + player.getName());
        } else {
            player.setPlayerListName(ChatColor.WHITE + player.getName());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        updatePlayerNametag(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player p = event.getPlayer();
        Clan clan = getClan(p.getUniqueId());

        if (event.getMessage().startsWith("@") && clan != null) {
            event.setCancelled(true);
            String msg = event.getMessage().substring(1).trim();
            if (!msg.isEmpty()) {
                sendClanMessage(clan, p, msg);
            }
            return;
        }

        if (clan != null) {
            String tagPrefix = ChatColor.DARK_GRAY + "[" + ChatColor.GOLD + clan.tag + ChatColor.DARK_GRAY + "] " + ChatColor.RESET;
            event.setFormat(tagPrefix + "%1$s" + ChatColor.GRAY + ": " + ChatColor.WHITE + "%2$s");
        } else {
            event.setFormat(ChatColor.WHITE + "%1$s" + ChatColor.GRAY + ": " + ChatColor.WHITE + "%2$s");
        }
    }

    private void sendClanMessage(Clan clan, Player sender, String message) {
        String formatted = ChatColor.DARK_GRAY + "[" + ChatColor.AQUA + "ClanChat" + ChatColor.DARK_GRAY + "] "
                + ChatColor.GOLD + sender.getName() + ChatColor.GRAY + ": " + ChatColor.WHITE + message;
        for (UUID memberId : clan.members) {
            Player member = Bukkit.getPlayer(memberId);
            if (member != null && member.isOnline()) {
                member.sendMessage(formatted);
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Dieser Befehl ist nur für Spieler!");
            return true;
        }

        if (command.getName().equalsIgnoreCase("clanchat")) {
            Clan clan = getClan(player.getUniqueId());
            if (clan == null) {
                player.sendMessage(ChatColor.RED + "Du bist in keinem Clan!");
                return true;
            }
            if (args.length == 0) {
                player.sendMessage(ChatColor.RED + "Nutze: /cc <Nachricht>");
                return true;
            }
            sendClanMessage(clan, player, String.join(" ", args));
            return true;
        }

        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "create" -> {
                if (getClan(player.getUniqueId()) != null) {
                    player.sendMessage(ChatColor.RED + "Du bist bereits in einem Clan!");
                    return true;
                }
                if (args.length < 3) {
                    player.sendMessage(ChatColor.RED + "Nutze: /clan create <Tag> <Name>");
                    return true;
                }
                String tag = args[1].replaceAll("[^a-zA-Z0-9]", "");
                if (tag.length() < 2 || tag.length() > 5) {
                    player.sendMessage(ChatColor.RED + "Der Clan-Tag muss 2 bis 5 Zeichen lang sein!");
                    return true;
                }
                if (clans.containsKey(tag.toLowerCase())) {
                    player.sendMessage(ChatColor.RED + "Dieser Clan-Tag ist bereits vergeben!");
                    return true;
                }
                String name = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                Clan clan = new Clan(tag, name, player.getUniqueId());
                clans.put(tag.toLowerCase(), clan);
                playerClan.put(player.getUniqueId(), tag.toLowerCase());
                saveClans();
                updatePlayerNametag(player);
                player.sendMessage(ChatColor.GREEN + "Clan " + ChatColor.GOLD + "[" + tag + "] " + name + ChatColor.GREEN + " wurde erstellt!");
            }
            case "invite" -> {
                Clan clan = getClan(player.getUniqueId());
                if (clan == null || !clan.owner.equals(player.getUniqueId())) {
                    player.sendMessage(ChatColor.RED + "Nur der Clan-Leader kann Spieler einladen!");
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "Nutze: /clan invite <Spieler>");
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    player.sendMessage(ChatColor.RED + "Spieler ist nicht online!");
                    return true;
                }
                if (getClan(target.getUniqueId()) != null) {
                    player.sendMessage(ChatColor.RED + "Dieser Spieler ist bereits in einem Clan!");
                    return true;
                }
                pendingInvites.put(target.getUniqueId(), clan.tag.toLowerCase());
                player.sendMessage(ChatColor.GREEN + "Einladung an " + target.getName() + " gesendet.");
                target.sendMessage(ChatColor.GOLD + player.getName() + ChatColor.GREEN + " hat dich in den Clan " + ChatColor.GOLD + "[" + clan.tag + "]" + ChatColor.GREEN + " eingeladen! Tippe " + ChatColor.YELLOW + "/clan accept");
            }
            case "accept" -> {
                if (getClan(player.getUniqueId()) != null) {
                    player.sendMessage(ChatColor.RED + "Du bist bereits in einem Clan!");
                    return true;
                }
                String tagKey = pendingInvites.remove(player.getUniqueId());
                if (tagKey == null || !clans.containsKey(tagKey)) {
                    player.sendMessage(ChatColor.RED + "Du hast keine gültige Clan-Einladung!");
                    return true;
                }
                Clan clan = clans.get(tagKey);
                clan.members.add(player.getUniqueId());
                playerClan.put(player.getUniqueId(), tagKey);
                saveClans();
                updatePlayerNametag(player);
                sendClanMessage(clan, player, ChatColor.GREEN + "ist dem Clan beigetreten!");
            }
            case "leave" -> {
                Clan clan = getClan(player.getUniqueId());
                if (clan == null) {
                    player.sendMessage(ChatColor.RED + "Du bist in keinem Clan!");
                    return true;
                }
                if (clan.owner.equals(player.getUniqueId())) {
                    player.sendMessage(ChatColor.RED + "Als Leader musst du /clan disband nutzen!");
                    return true;
                }
                clan.members.remove(player.getUniqueId());
                playerClan.remove(player.getUniqueId());
                saveClans();
                updatePlayerNametag(player);
                player.sendMessage(ChatColor.YELLOW + "Du hast den Clan verlassen.");
            }
            case "kick" -> {
                Clan clan = getClan(player.getUniqueId());
                if (clan == null || !clan.owner.equals(player.getUniqueId())) {
                    player.sendMessage(ChatColor.RED + "Nur der Clan-Leader kann Mitglieder kicken!");
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "Nutze: /clan kick <Spieler>");
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null || !clan.members.contains(target.getUniqueId()) || target.equals(player)) {
                    player.sendMessage(ChatColor.RED + "Ungültiger Spieler!");
                    return true;
                }
                clan.members.remove(target.getUniqueId());
                playerClan.remove(target.getUniqueId());
                saveClans();
                updatePlayerNametag(target);
                target.sendMessage(ChatColor.RED + "Du wurdest aus dem Clan gekickt.");
                player.sendMessage(ChatColor.GREEN + target.getName() + " wurde gekickt.");
            }
            case "disband" -> {
                Clan clan = getClan(player.getUniqueId());
                if (clan == null || !clan.owner.equals(player.getUniqueId())) {
                    player.sendMessage(ChatColor.RED + "Nur der Clan-Leader kann den Clan auflösen!");
                    return true;
                }
                for (UUID m : clan.members) {
                    playerClan.remove(m);
                    Player onlineMember = Bukkit.getPlayer(m);
                    if (onlineMember != null) {
                        updatePlayerNametag(onlineMember);
                        onlineMember.sendMessage(ChatColor.RED + "Der Clan wurde aufgelöst.");
                    }
                }
                clans.remove(clan.tag.toLowerCase());
                saveClans();
            }
            case "chat", "c" -> {
                Clan clan = getClan(player.getUniqueId());
                if (clan == null) {
                    player.sendMessage(ChatColor.RED + "Du bist in keinem Clan!");
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "Nutze: /clan chat <Nachricht> oder @<Nachricht>");
                    return true;
                }
                sendClanMessage(clan, player, String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
            }
            case "info" -> {
                Clan clan = getClan(player.getUniqueId());
                if (clan == null) {
                    player.sendMessage(ChatColor.RED + "Du bist in keinem Clan!");
                    return true;
                }
                player.sendMessage(ChatColor.GOLD + "=== Clan [" + clan.tag + "] " + clan.name + " ===");
                player.sendMessage(ChatColor.GRAY + "Mitglieder: " + ChatColor.WHITE + clan.members.size());
            }
            default -> sendHelp(player);
        }
        return true;
    }

    private void sendHelp(Player p) {
        p.sendMessage(ChatColor.GOLD + "=== Clan Befehle ===");
        p.sendMessage(ChatColor.YELLOW + "/clan create <Tag> <Name>" + ChatColor.GRAY + " - Clan erstellen");
        p.sendMessage(ChatColor.YELLOW + "/clan invite <Spieler>" + ChatColor.GRAY + " - Spieler einladen");
        p.sendMessage(ChatColor.YELLOW + "/clan accept" + ChatColor.GRAY + " - Einladung annehmen");
        p.sendMessage(ChatColor.YELLOW + "/clan chat <Text> (oder @Text)" + ChatColor.GRAY + " - Clan-Chat");
        p.sendMessage(ChatColor.YELLOW + "/clan leave / kick / disband / info");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("clan") && args.length == 1) {
            return Arrays.asList("create", "invite", "accept", "leave", "kick", "disband", "chat", "info");
        }
        return null;
    }
}
