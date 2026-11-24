package se.coldmc.kitpvp;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class Listeners implements Listener {
    private final KitPvP plugin;
    private final Game game;
    private final Map<UUID, Long> lastDenyMessage = new HashMap<>();

    public Listeners(KitPvP plugin) { this.plugin = plugin; this.game = plugin.game; }

    private boolean cfg(String path) { return plugin.getConfig().getBoolean(path); }

    private void deny(Player p, String key) {
        long now = System.currentTimeMillis();
        if (now - lastDenyMessage.getOrDefault(p.getUniqueId(), 0L) < 1500) return;
        lastDenyMessage.put(p.getUniqueId(), now);
        plugin.lang.send(p, key);
    }

    private Player attackerOf(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        if (damager instanceof Tameable t && t.getOwner() instanceof Player p) return p;
        return null;
    }

    // ---------------------------------------------------------------- damage

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        Player attacker = attackerOf(e.getDamager());
        Session as = game.session(attacker);
        // Sumo (an arena with No-Damage): the hit still knocks back, it just does no harm.
        if (e.getEntity() instanceof Player hv) { Session hs = game.session(hv); if (hs != null && hs.duel != null && hs.duel.arena.noDamage) e.setDamage(0); }
        if (!(e.getEntity() instanceof Player victim)) {
            // A kit's own pets, or mobs: only the "hit" scripts apply.
            if (as != null && as.kit != null) {
                Scripts.Result r = plugin.scripts.fire(as, "hit", e.getEntity(), e.getEntity().getLocation(), e.getDamage(), false, null);
                if (r.cancel) e.setCancelled(true); else e.setDamage(r.damage);
            }
            return;
        }
        Session vs = game.session(victim);
        // Hitting someone with the challenge item asks them for a 1v1, the same as right-clicking them.
        if (as != null && vs != null && attacker == e.getDamager() && challengeClick(attacker, victim)) { e.setCancelled(true); return; }
        if (vs == null) { if (as != null) e.setCancelled(true); return; }
        if (attacker == null) return;                       // a mob or a trap: plain damage
        if (as == null) { e.setCancelled(true); return; }   // an outsider cannot hurt someone in KitPvP
        if (as == vs) return;                               // own arrow, own explosion
        if (as.duel != vs.duel || (as.duel != null && !as.duel.started())) { e.setCancelled(true); return; }
        if (as.duel == null && (as.arena.inSafeZone(victim.getLocation()) || as.arena.inSafeZone(attacker.getLocation()))) {
            e.setCancelled(true); deny(attacker, "Combat.Safezone-Deny"); return;
        }
        if (vs.spawnProtected()) { e.setCancelled(true); deny(attacker, "Combat.Protected"); return; }
        if (plugin.natives.blocksHit(attacker, victim)) { e.setCancelled(true); return; }
        // Still choosing a kit: not in the fight yet, on either side.
        if (cfg("Combat.No-Kit-Protection") && (as.kit == null || vs.kit == null)) { e.setCancelled(true); if (vs.kit == null) deny(attacker, "Combat.Protected"); return; }
        as.protectedUntil = 0;

        double dmg = e.getDamage();
        if (as.kit != null) {
            Scripts.Result r = plugin.scripts.fire(as, "hit", victim, victim.getLocation(), dmg, false, null);
            if (r.cancel) { e.setCancelled(true); return; }
            dmg = r.damage;
        }
        if (vs.kit != null) {
            Scripts.Result r = plugin.scripts.fire(vs, "hurt", attacker, victim.getLocation(), dmg, false, null);
            if (r.cancel) { e.setCancelled(true); return; }
            dmg = r.damage;
        }
        dmg *= plugin.challenges.dealt(as) * plugin.challenges.taken(vs);     // handicaps taken on as a challenge
        e.setDamage(Math.max(0, dmg));
        if (game.counts(as, vs)) {
            if (e.getDamager() instanceof org.bukkit.entity.Projectile) as.data.add("projectiles_hit", 1, as.kitId());
            double dealt = Math.min(e.getFinalDamage(), victim.getHealth());
            as.data.add("damage_dealt", dealt, as.kitId());
            vs.data.add("damage_taken", dealt, vs.kitId());
        }
        game.tag(vs, as);
        game.tag(as, vs);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e instanceof EntityDamageByEntityEvent || !(e.getEntity() instanceof Player p)) return;
        Session s = game.session(p);
        if (s == null) return;
        switch (e.getCause()) {
            case VOID -> {
                if (cfg("Combat.Void-Kills-Instantly")) { e.setCancelled(true); p.setFallDistance(0); kill(s); }
            }
            case FALL -> {
                if (!cfg("Combat.Fall-Damage")) { e.setCancelled(true); return; }
                Scripts.Result r = plugin.scripts.fire(s, "fall", null, p.getLocation(), e.getDamage(), false, null);
                if (r.cancel) e.setCancelled(true); else e.setDamage(Math.max(0, r.damage));
            }
            default -> { if (s.spawnProtected()) e.setCancelled(true); }
        }
    }

    // ---------------------------------------------------------------- death

    /** Kills without a death screen: stats, then straight back to the spawn. */
    /** The challenge item used on a player: ask for a 1v1, or take theirs if they asked first. True when it was that item. */
    private boolean challengeClick(Player p, Player other) {
        String action = Items.lobbyActionOf(p.getInventory().getItemInMainHand());
        if (action == null || !(action.equalsIgnoreCase("challenges") || action.equalsIgnoreCase("duel"))) return false;
        if (game.session(other) == null) return true;
        if (game.hasDuelRequestFrom(p, other)) game.duelAccept(p); else game.duelRequest(p, other);
        return true;
    }

    private void kill(Session s) { game.kill(s, game.killerOf(s)); }

    @EventHandler(priority = EventPriority.LOW)
    public void onDeath(PlayerDeathEvent e) {
        Session s = game.session(e.getEntity());
        if (s == null) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
        e.setKeepInventory(true);
        e.setKeepLevel(true);
        e.deathMessage(null);
        if (!cfg("Combat.Death-Screen")) {
            e.setCancelled(true);
            e.setReviveHealth(e.getEntity().getAttribute(Attribute.MAX_HEALTH).getValue());
            kill(s);
        } else {
            game.died(s, game.killerOf(s));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent e) {
        Session s = game.session(e.getPlayer());
        if (s == null) return;
        org.bukkit.Location l = s.arena.randomSpawn();
        if (l != null) e.setRespawnLocation(l);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (game.sessions.get(e.getPlayer().getUniqueId()) != s) return;
            game.startLife(s, false);
            plugin.scripts.fire(s, "respawn", null, s.player.getLocation(), 0, false, null);
        });
    }

    // ---------------------------------------------------------------- coming and going

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // Without MMOProfiles the join is the moment to hand back a snapshot a crash left behind.
        if (!Bukkit.getPluginManager().isPluginEnabled("MMOProfiles"))
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (e.getPlayer().isOnline()) game.recoverAfterCrash(e.getPlayer(), null); }, 20L);
    }

    /** LOWEST, so the inventory is handed back and the player stands at spawn before anything else
     *  sees the quit. MythicLib only marks the profile session closing at LOWEST; MMOProfiles takes
     *  its snapshot and saves at MONITOR (javap of MythicLib 1.7.1, 2026-11-13). */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        Session s = game.session(p);
        game.duelRequests.remove(p.getUniqueId());
        if (s == null) { game.profileOf.remove(p.getUniqueId()); return; }
        if (s.inCombat() && cfg("Combat.Combat-Log-Punish")) {
            Session killer = game.killerOf(s);
            if (killer != null) for (Session o : game.sessions.values()) plugin.lang.send(o.player, "Combat.Combat-Log", "victim", p.getName(), "attacker", killer.player.getName());
            game.died(s, killer);
        }
        game.leave(p, true, true);
        game.profileOf.remove(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        Session s = game.session(p);
        // Taken out of the arenas by something else (a warp, /tp): end the session where they stand.
        if (s != null && !game.isArenaWorld(p.getWorld().getName())) game.leave(p, false, false);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        String word = e.getMessage().substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
        String bare = word.contains(":") ? word.substring(word.indexOf(':') + 1) : word;
        boolean ours = bare.equals("kitpvp") || bare.equals("kp");
        // Someone already inside (an admin or an NPC put them there) can always use /kp, or they could never leave.
        if (ours && game.session(p) == null && !p.hasPermission("kitpvp.play") && !p.hasPermission("kitpvp.admin")) {
            e.setCancelled(true);
            plugin.lang.send(p, "General.Unknown-Command");
            return;
        }
        Session s = game.session(p);
        if (s == null || p.hasPermission("kitpvp.admin")) return;
        List<String> allowed = plugin.getConfig().getStringList("Join.Allowed-Commands");
        for (String a : allowed) if (a.equalsIgnoreCase(bare)) return;
        e.setCancelled(true);
        plugin.lang.send(p, "General.Command-Not-Here");
    }

    // ---------------------------------------------------------------- items and the world

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        Session s = game.session(p);
        if (s == null) return;
        boolean right = e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK;
        boolean left = e.getAction() == Action.LEFT_CLICK_AIR || e.getAction() == Action.LEFT_CLICK_BLOCK;
        if (!right && !left) return;
        ItemStack hand = p.getInventory().getItemInMainHand();
        String lobby = Items.lobbyActionOf(hand);
        if (lobby != null) {
            e.setCancelled(true);
            if (right) switch (lobby.toLowerCase(Locale.ROOT)) {
                case "kits" -> plugin.menus.openKits(p, "kits", 1);
                case "upgrades" -> plugin.menus.openKits(p, "upgrades", 1);
                case "shop" -> plugin.menus.openKits(p, "shop", 1);
                case "crates" -> plugin.menus.openCrates(p);
                case "duels" -> plugin.menus.openDuels(p);
                case "challenges" -> plugin.menus.openChallenges(p);
                case "achievements" -> plugin.menus.openAchievements(p, 1);
                case "locked" -> {}
                case "leave" -> p.performCommand("kitpvp leave");
                case "stats" -> p.performCommand("kitpvp stats");
                default -> p.performCommand(lobby);
            }
            return;
        }
        if (right && game.isSoup(hand) && cfg("Soup.Enabled") && !plugin.challenges.noSoup(s)) {
            double max = p.getAttribute(Attribute.MAX_HEALTH).getValue();
            boolean hungry = cfg("Combat.Hunger") && p.getFoodLevel() < 20;
            if (p.getHealth() < max || hungry) {
                e.setCancelled(true);
                p.setHealth(Math.min(max, p.getHealth() + plugin.getConfig().getDouble("Soup.Hearts") * 2));
                if (hungry) p.setFoodLevel(Math.min(20, p.getFoodLevel() + plugin.getConfig().getInt("Soup.Food")));
                Material leaves = Material.matchMaterial(plugin.getConfig().getString("Soup.Leaves", "BOWL"));
                p.getInventory().setItemInMainHand(leaves == null || leaves.isAir() ? null : new ItemStack(leaves));
                s.data.add("soups", 1, s.kitId());
                plugin.scripts.fire(s, "soup", null, p.getLocation(), 0, false, null);
                return;
            }
        }
        if (s.kit == null) return;
        org.bukkit.Location loc = e.getClickedBlock() != null ? e.getClickedBlock().getLocation() : p.getLocation();
        Scripts.Result r = plugin.scripts.fire(s, right ? "right_click" : "left_click", null, loc, 0, false, null);
        if (r.ran && r.cancel) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Session s = game.session(e.getPlayer());
        if (s == null || s.kit == null) return;
        if (e.getRightClicked() instanceof Player other && challengeClick(e.getPlayer(), other)) { e.setCancelled(true); return; }
        if (Items.lobbyActionOf(e.getPlayer().getInventory().getItemInMainHand()) != null) return;
        Scripts.Result r = plugin.scripts.fire(s, "right_click_entity", e.getRightClicked(), e.getRightClicked().getLocation(), 0, false, null);
        if (r.ran && r.cancel) e.setCancelled(true);
        // Right-clicking with a player in the way is still a right-click: the item abilities fire here too.
        r = plugin.scripts.fire(s, "right_click", e.getRightClicked(), e.getRightClicked().getLocation(), 0, false, null);
        if (r.ran && r.cancel) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent e) {
        Session s = game.session(e.getPlayer());
        if (s != null && s.kit != null && e.isSneaking()) plugin.scripts.fire(s, "sneak", null, e.getPlayer().getLocation(), 0, false, null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        Session s = game.session(e.getPlayer());
        if (s == null || s.kit == null) return;
        if (plugin.scripts.fire(s, "consume", null, e.getPlayer().getLocation(), 0, false, null).cancel) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent e) {
        if (!(e.getEntity().getShooter() instanceof Player p)) return;
        Session s = game.session(p);
        if (s == null || s.kit == null) return;
        if (plugin.scripts.fire(s, "projectile_launch", e.getEntity(), e.getEntity().getLocation(), 0, false, e.getEntityType().name()).cancel) e.setCancelled(true);
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent e) {
        if (!(e.getEntity().getShooter() instanceof Player p)) return;
        Session s = game.session(p);
        if (s == null) return;
        if (s.kit != null) plugin.scripts.fire(s, "projectile_hit", e.getHitEntity(), e.getEntity().getLocation(), 0, false, e.getEntityType().name());
        if (cfg("Combat.Remove-Arrows-On-Hit") && e.getEntity() instanceof AbstractArrow a && !(a instanceof Trident) && e.getHitBlock() != null) a.remove();
    }

    @EventHandler(ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        Session s = game.session(e.getPlayer());
        if (s == null || s.kit == null || e.getState() != PlayerFishEvent.State.CAUGHT_ENTITY || e.getCaught() == null) return;
        plugin.scripts.fire(s, "fish", e.getCaught(), e.getCaught().getLocation(), 0, false, null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Session s = game.session(e.getPlayer());
        if (s == null) return;
        boolean protect = cfg("Combat.Protect-World");
        Scripts.Result r = plugin.scripts.fire(s, "block_place", null, e.getBlock().getLocation(), 0, protect, null);
        if (r.cancel) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Session s = game.session(e.getPlayer());
        if (s == null) return;
        boolean protect = cfg("Combat.Protect-World");
        Scripts.Result r = plugin.scripts.fire(s, "block_break", null, e.getBlock().getLocation(), 0, protect, null);
        if (r.cancel) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (game.session(e.getPlayer()) == null) return;
        ItemStack it = e.getItemDrop().getItemStack();
        Material bowl = Material.matchMaterial(plugin.getConfig().getString("Soup.Leaves", "BOWL"));
        // An emptied bowl may always be thrown away, and is gone the moment it leaves the hand.
        if (bowl != null && it.getType() == bowl && !it.hasItemMeta() && cfg("Soup.Drop-Bowls")) {
            if (cfg("Soup.Dropped-Bowls-Vanish")) e.getItemDrop().remove();
            return;
        }
        if (!cfg("Combat.Drop-Items") || Items.lobbyActionOf(it) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && game.session(p) != null && !cfg("Combat.Pick-Up-Items")) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent e) {
        // Keyed on the arena WORLD, not the session: food sits above 20 there (Combat.Food-Level,
        // 22) so soup is always drunk (healed) and never eaten as food, with a buffer so a hunger
        // tick never dips it to 19 (the owner, 2026-11-13). Game.tick tops it up too.
        if (e.getEntity() instanceof Player p && !cfg("Combat.Hunger") && (game.session(p) != null || game.isArenaWorld(p.getWorld().getName()))) {
            int full = game.foodLevel();
            if (e.getFoodLevel() < full) { e.setCancelled(true); p.setFoodLevel(full); }
        }
    }

    /** No natural healing in the arena worlds (Combat.Natural-Regen: false, the owner 2026-11-13).
     *  Only the full-food regeneration is blocked; soup, kit abilities and potions still heal. */
    @EventHandler(ignoreCancelled = true)
    public void onRegen(EntityRegainHealthEvent e) {
        if (!(e.getEntity() instanceof Player p) || cfg("Combat.Natural-Regen")) return;
        if (e.getRegainReason() != EntityRegainHealthEvent.RegainReason.SATIATED && e.getRegainReason() != EntityRegainHealthEvent.RegainReason.REGEN) return;
        if (game.session(p) != null || game.isArenaWorld(p.getWorld().getName())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent e) {
        if (game.session(e.getPlayer()) != null && !cfg("Combat.Item-Durability")) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLobbyItemClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || game.session(p) == null) return;
        if (Items.lobbyActionOf(e.getCurrentItem()) != null || Items.lobbyActionOf(e.getCursor()) != null) e.setCancelled(true);
        // A number key swaps with a hotbar slot without ever "clicking" it.
        if (e.getClick() == org.bukkit.event.inventory.ClickType.NUMBER_KEY && e.getHotbarButton() >= 0
                && Items.lobbyActionOf(p.getInventory().getItem(e.getHotbarButton())) != null) e.setCancelled(true);
    }

    @EventHandler
    public void onSwapHands(org.bukkit.event.player.PlayerSwapHandItemsEvent e) {
        if (game.session(e.getPlayer()) == null) return;
        if (Items.lobbyActionOf(e.getMainHandItem()) != null || Items.lobbyActionOf(e.getOffHandItem()) != null) e.setCancelled(true);
    }
}
