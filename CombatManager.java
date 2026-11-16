package de.deinserver.coreplugin;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class CombatManager implements Listener {

    private final CorePlugin plugin;
    private static final long COMBAT_DURATION_MS = 20_000L; // 20 Sekunden

    private final Map<UUID, Long> combatEndTime = new HashMap<>();
    private final Map<UUID, UUID> lastAttacker = new HashMap<>();
    private final Map<UUID, LootSession> activeLootSessions = new HashMap<>();

    private final Map<UUID, List<ItemStack>> pendingItemReturns = new HashMap<>();
    private final File returnsFile;
    private FileConfiguration returnsConfig;

    private static class LootSession {
        final UUID victimUuid;
        final String victimName;
        final List<ItemStack> items;
        boolean itemPicked = false;

        LootSession(UUID victimUuid, String victimName, List<ItemStack> items) {
            this.victimUuid = victimUuid;
            this.victimName = victimName;
            this.items = items;
        }
    }

    public CombatManager(CorePlugin plugin) {
        this.plugin = plugin;
        this.returnsFile = new File(plugin.getDataFolder(), "pending_returns.yml");
        loadPendingReturns();
        startCombatTimerTask();
    }

    private void startCombatTimerTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                Iterator<Map.Entry<UUID, Long>> it = combatEndTime.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<UUID, Long> entry = it.next();
                    UUID uuid = entry.getKey();
                    long remaining = entry.getValue() - now;
                    Player p = Bukkit.getPlayer(uuid);

                    if (remaining <= 0) {
                        it.remove();
                        lastAttacker.remove(uuid);
                        if (p != null && p.isOnline()) {
                            p.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                                    new TextComponent(ChatColor.GREEN + "✔ Du bist nicht mehr im Kampf!"));
                        }
                    } else if (p != null && p.isOnline()) {
                        double sec = Math.ceil(remaining / 100.0) / 10.0;
                        p.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                                new TextComponent(ChatColor.RED + "⚔ Im Kampf: " + ChatColor.BOLD + sec + "s"));
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 5L);
    }

    public boolean isInCombat(UUID uuid) {
        Long end = combatEndTime.get(uuid);
        return end != null && end > System.currentTimeMillis();
    }

    private void tagPlayer(Player victim, Player attacker) {
        long expire = System.currentTimeMillis() + COMBAT_DURATION_MS;
        if (!isInCombat(victim.getUniqueId())) {
            victim.sendMessage(ChatColor.RED + "⚔ Du bist nun für 20 Sekunden im Kampf! Logge dich nicht aus!");
        }
        if (!isInCombat(attacker.getUniqueId())) {
            attacker.sendMessage(ChatColor.RED + "⚔ Du bist nun für 20 Sekunden im Kampf! Logge dich nicht aus!");
        }
        combatEndTime.put(victim.getUniqueId(), expire);
        combatEndTime.put(attacker.getUniqueId(), expire);
        lastAttacker.put(victim.getUniqueId(), attacker.getUniqueId());
        lastAttacker.put(attacker.getUniqueId(), victim.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;

        Player attacker = null;
        if (event.getDamager() instanceof Player p) {
            attacker = p;
        } else if (event.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player p) {
            attacker = p;
        }

        if (attacker == null || attacker.equals(victim)) return;

        ClanManager.Clan c1 = plugin.getClanManager().getClan(victim.getUniqueId());
        ClanManager.Clan c2 = plugin.getClanManager().getClan(attacker.getUniqueId());
        if (c1 != null && c1 == c2) return;

        tagPlayer(victim, attacker);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        UUID attackerId = victim.getKiller() != null ? victim.getKiller().getUniqueId() : lastAttacker.get(victim.getUniqueId());

        combatEndTime.remove(victim.getUniqueId());
        lastAttacker.remove(victim.getUniqueId());

        if (attackerId != null) {
            Player killer = Bukkit.getPlayer(attackerId);
            if (killer != null && killer.isOnline() && !killer.equals(victim)) {
                List<ItemStack> allItems = new ArrayList<>();
                for (ItemStack item : event.getDrops()) {
                    if (item != null && item.getType() != Material.AIR) {
                        allItems.add(item.clone());
                    }
                }
                event.getDrops().clear();
                event.setKeepInventory(false);

                if (!allItems.isEmpty()) {
                    Bukkit.getScheduler().runTaskLater(plugin, () ->
                            openLootSelectionGUI(killer, victim.getUniqueId(), victim.getName(), allItems), 2L);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCombatLog(PlayerQuitEvent event) {
        Player quitter = event.getPlayer();
        if (!isInCombat(quitter.getUniqueId())) return;

        UUID attackerId = lastAttacker.get(quitter.getUniqueId());
        combatEndTime.remove(quitter.getUniqueId());
        lastAttacker.remove(quitter.getUniqueId());

        Bukkit.broadcastMessage(ChatColor.DARK_RED + "⚠ " + ChatColor.RED + quitter.getName()
                + ChatColor.GRAY + " hat sich im Kampf ausgeloggt!");

        List<ItemStack> allItems = new ArrayList<>();
        ItemStack[] contents = quitter.getInventory().getContents();
        for (ItemStack content : contents) {
            if (content != null && content.getType() != Material.AIR) {
                allItems.add(content.clone());
            }
        }
        quitter.getInventory().clear();
        quitter.saveData();

        if (allItems.isEmpty()) return;

        Player killer = attackerId != null ? Bukkit.getPlayer(attackerId) : null;
        if (killer != null && killer.isOnline()) {
            openLootSelectionGUI(killer, quitter.getUniqueId(), quitter.getName(), allItems);
        } else {
            queueReturnItems(quitter.getUniqueId(), allItems);
        }
    }

    private void openLootSelectionGUI(Player killer, UUID victimUuid, String victimName, List<ItemStack> items) {
        if (activeLootSessions.containsKey(killer.getUniqueId())) {
            finishLootSession(killer, -1);
        }

        Inventory gui = Bukkit.createInventory(null, 45, ChatColor.DARK_RED + "Wähle 1 Item: " + ChatColor.BLACK + victimName);
        for (int i = 0; i < Math.min(items.size(), 45); i++) {
            gui.setItem(i, items.get(i));
        }

        activeLootSessions.put(killer.getUniqueId(), new LootSession(victimUuid, victimName, items));
        killer.openInventory(gui);
        killer.sendMessage(ChatColor.GOLD + "★ Du hast " + victimName + " besiegt! Wähle " + ChatColor.AQUA + "1 Item" + ChatColor.GOLD + " aus. Den Rest erhält " + victimName + " zurück!");
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player killer)) return;
        LootSession session = activeLootSessions.get(killer.getUniqueId());
        if (session == null) return;

        event.setCancelled(true);

        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getView().getTopInventory())) {
            return;
        }

        int slot = event.getSlot();
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        finishLootSession(killer, slot);
        killer.closeInventory();
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player killer)) return;
        if (activeLootSessions.containsKey(killer.getUniqueId())) {
            finishLootSession(killer, -1);
        }
    }

    private void finishLootSession(Player killer, int chosenSlot) {
        LootSession session = activeLootSessions.remove(killer.getUniqueId());
        if (session == null || session.itemPicked) return;
        session.itemPicked = true;

        List<ItemStack> remainingItems = new ArrayList<>();

        for (int i = 0; i < session.items.size(); i++) {
            ItemStack item = session.items.get(i);
            if (i == chosenSlot) {
                Map<Integer, ItemStack> overflow = killer.getInventory().addItem(item);
                overflow.values().forEach(left -> killer.getWorld().dropItemNaturally(killer.getLocation(), left));
                killer.sendMessage(ChatColor.GREEN + "✔ Du hast " + ChatColor.GOLD + item.getType().name() + ChatColor.GREEN + " ausgewählt!");
            } else {
                remainingItems.add(item);
            }
        }

        if (!remainingItems.isEmpty()) {
            giveOrQueueItems(session.victimUuid, remainingItems);
        }
    }

    private void giveOrQueueItems(UUID victimUuid, List<ItemStack> items) {
        Player victim = Bukkit.getPlayer(victimUuid);
        if (victim != null && victim.isOnline() && !victim.isDead()) {
            for (ItemStack item : items) {
                Map<Integer, ItemStack> overflow = victim.getInventory().addItem(item);
                overflow.values().forEach(left -> victim.getWorld().dropItemNaturally(victim.getLocation(), left));
            }
            victim.sendMessage(ChatColor.YELLOW + "Du hast deine restlichen Items aus dem Kampf zurückerhalten!");
        } else {
            queueReturnItems(victimUuid, items);
        }
    }

    private void queueReturnItems(UUID uuid, List<ItemStack> items) {
        pendingItemReturns.computeIfAbsent(uuid, k -> new ArrayList<>()).addAll(items);
        savePendingReturns();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        checkPendingReturns(event.getPlayer());
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> checkPendingReturns(event.getPlayer()), 2L);
    }

    public void checkPendingReturns(Player player) {
        List<ItemStack> items = pendingItemReturns.remove(player.getUniqueId());
        if (items != null && !items.isEmpty()) {
            for (ItemStack item : items) {
                Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
                overflow.values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
            }
            player.sendMessage(ChatColor.GREEN + "✔ Deine restlichen Items aus dem letzten Kampf wurden dir ins Inventar gelegt!");
            savePendingReturns();
        }
    }

    public void savePendingReturns() {
        returnsConfig.set("returns", null);
        for (Map.Entry<UUID, List<ItemStack>> entry : pendingItemReturns.entrySet()) {
            returnsConfig.set("returns." + entry.getKey().toString(), entry.getValue());
        }
        try {
            returnsConfig.save(returnsFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @SuppressWarnings("unchecked")
    private void loadPendingReturns() {
        if (!returnsFile.exists()) {
            try {
                returnsFile.getParentFile().mkdirs();
                returnsFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        returnsConfig = YamlConfiguration.loadConfiguration(returnsFile);
        ConfigurationSection sec = returnsConfig.getConfigurationSection("returns");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                List<ItemStack> list = (List<ItemStack>) sec.getList(key);
                if (list != null) {
                    pendingItemReturns.put(UUID.fromString(key), new ArrayList<>(list));
                }
            }
        }
    }
}
