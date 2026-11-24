package se.coldmc.kitpvp;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class Commands implements TabExecutor {
    private final KitPvP plugin;
    private final Game game;
    private final Lang lang;

    public Commands(KitPvP plugin) { this.plugin = plugin; this.game = plugin.game; this.lang = plugin.lang; }

    private static String arg(String[] a, int i) { return a.length > i ? a[i] : null; }
    private static String rest(String[] a, int from) { return String.join(" ", Arrays.copyOfRange(a, Math.min(from, a.length), a.length)); }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        boolean admin = sender.hasPermission("kitpvp.admin");
        boolean inside = sender instanceof Player sp && game.session(sp) != null;
        if (!admin && !inside && !sender.hasPermission("kitpvp.play")) { lang.send(sender, "General.Unknown-Command"); return true; }
        String sub = a.length == 0 ? "help" : a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("admin")) {
            if (!admin) { lang.send(sender, "General.No-Permission"); return true; }
            admin(sender, a);
            return true;
        }
        if (!(sender instanceof Player p)) { lang.send(sender, "General.Players-Only"); return true; }
        Session s = game.session(p);
        switch (sub) {
            case "join" -> game.join(p, game.arena(a.length > 1 ? a[1] : plugin.getConfig().getString("Arenas.Default")));
            case "leave", "quit" -> {
                if (s == null) lang.send(p, "General.Not-In-Game");
                else if (s.inCombat()) lang.send(p, "Combat.Command-Blocked");
                else game.leave(p, true, false);
            }
            case "kit", "kits" -> {
                if (a.length < 2) plugin.menus.openKits(p, 1);
                else if (a[1].equalsIgnoreCase("upgrade")) upgrade(p, arg(a, 2));
                else game.select(p, game.kit(a[1]));
            }
            case "upgrade", "buy" -> {
                // "/kp buy <kit> confirm" buys the next tier without the menu
                Kit k = game.kit(arg(a, 1));
                if (k != null && k.enabled && a.length > 2 && a[2].equalsIgnoreCase(lang.raw("Kits.Confirm-Word"))) game.buyNext(p, k);
                else upgrade(p, arg(a, 1));
            }
            case "shop" -> plugin.menus.openKits(p, "shop", 1);
            case "duels" -> plugin.menus.openDuels(p);
            case "queue" -> {
                // "/kp queue <pvp|sumo|gunfight>" joins (again = leaves); "/kp queue leave" leaves
                if (a.length < 2) plugin.menus.openDuels(p);
                else if (a[1].equalsIgnoreCase("leave")) game.leaveQueue(p, false);
                else game.joinQueue(p, a[1]);
            }
            case "upgrades" -> plugin.menus.openKits(p, "upgrades", 1);
            case "crates" -> plugin.menus.openCrates(p);
            case "crate" -> {
                // "/kp crate <id>" rolls one; "/kp crate <id> <n>" opens several at once
                Crates.Crate c = plugin.crates.crate(arg(a, 1));
                if (c == null) plugin.menus.openCrates(p);
                else if (a.length > 2) plugin.menus.openMany(p, c);
                else plugin.menus.roll(p, c);
            }
            case "challenges" -> plugin.menus.openChallenges(p);
            case "challenge" -> {
                Challenges.Challenge c = a.length > 1 ? plugin.challenges.challenges.get(a[1].toLowerCase(Locale.ROOT)) : null;
                if (c == null || !plugin.challenges.offered().contains(c)) plugin.menus.openChallenges(p);
                else plugin.challenges.toggle(p, game.data(p), c);
            }
            case "achievements" -> plugin.menus.openAchievements(p, 1);
            case "spawn" -> {
                if (s == null) lang.send(p, "General.Not-In-Game");
                else if (s.inCombat() || s.duel != null) lang.send(p, "Combat.Command-Blocked");
                else { game.startLife(s, true); lang.send(p, "Spawn.Teleported"); }
            }
            case "respawn", "suicide" -> {
                // The Respawn button (ItemJoin, crafting grid). Only a combat-tagged player (or one in a duel)
                // dies, so the last attacker still gets the kill; anyone else just goes back to the spawn,
                // with no death counted (the owner, 2026-11-14).
                if (s == null) lang.send(p, "General.Not-In-Game");
                else if (s.duel == null && !s.inCombat()) { game.startLife(s, true); lang.send(p, "Spawn.Teleported"); }
                else game.kill(s, game.killerOf(s));
            }
            case "stats" -> stats(p, a);
            case "balance", "bal", "coins" -> {
                PlayerData d = a.length > 1 ? game.dataByName(a[1]) : game.data(p);
                if (d == null) lang.send(p, "General.Player-Not-Found", "player", a[1]);
                else lang.send(p, d.uuid.equals(p.getUniqueId()) ? "Economy.Balance" : "Economy.Balance-Other", "player", d.name, "coins", Game.num(game.balance(d)));
            }
            case "top", "leaderboard", "lb" -> top(p, a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "kills", arg(a, 2));
            case "duel" -> {
                if (a.length < 2) lang.send(p, "Duel.Usage");
                else game.duelRequest(p, Bukkit.getPlayerExact(a[1]));
            }
            case "accept" -> game.duelAccept(p);
            default -> { lang.send(p, "Help.Player"); if (admin) lang.send(p, "Help.Admin-Hint"); }
        }
        return true;
    }

    private void upgrade(Player p, String kitId) {
        Kit k = game.kit(kitId);
        if (k == null || !k.enabled || k.hidden) lang.send(p, "Kits.Not-Found"); else plugin.menus.openUpgrade(p, k);
    }

    private void stats(Player p, String[] a) {
        PlayerData d = game.data(p);
        Kit kit = null;
        for (int i = 1; i < a.length; i++) {
            Kit k = game.kit(a[i]);
            if (k != null) kit = k;
            else { d = game.dataByName(a[i]); if (d == null) { lang.send(p, "General.Player-Not-Found", "player", a[i]); return; } }
        }
        if (kit == null) {
            lang.send(p, "Stats.Header", "player", d.name);
            for (String st : plugin.getConfig().getStringList("Stats.Shown")) lang.send(p, "Stats.Line", "stat", game.statName(st), "value", Game.num(d.stat(st)));
            lang.send(p, "Stats.Line", "stat", lang.raw("Stats.Names.kd"), "value", Game.num(d.kd()));
            lang.send(p, "Stats.Line", "stat", lang.raw("Stats.Names.streak"), "value", Game.num(d.stat("streak")));
            lang.send(p, "Stats.Line", "stat", lang.raw("Stats.Names.coins"), "value", Game.num(game.balance(d)));
            String best = null; double most = 0;
            for (var e : d.kitStats.entrySet()) { double k = e.getValue().getOrDefault("kills", 0d); if (k > most) { most = k; best = e.getKey(); } }
            if (best != null) lang.send(p, "Stats.Best-Kit", "kit", best, "kills", Game.num(most));
            lang.send(p, "Stats.Footer");
        } else {
            lang.send(p, "Stats.Kit-Header", "player", d.name, "kit", Lang.plain(Lang.color(kit.name)));
            lang.send(p, "Stats.Line", "stat", lang.raw("Stats.Tier"), "value", game.owned(d, kit) + "/" + kit.maxTier());
            var m = d.kitStats.get(kit.id);
            if (m != null) for (var e : m.entrySet()) lang.send(p, "Stats.Line", "stat", game.statName(e.getKey()), "value", Game.num(e.getValue()));
            lang.send(p, "Stats.Footer");
        }
    }

    private void top(Player p, String stat, String kitId) {
        Kit kit = game.kit(kitId);
        List<PlayerData> best = game.top(stat, kit == null ? null : kit.id, 10);
        lang.send(p, "Top.Header", "stat", game.statName(stat) + (kit == null ? "" : lang.raw("Stats.With-Kit", "kit", kit.id)));
        int rank = 1;
        for (PlayerData d : best) {
            double v = stat.equals("coins") ? game.balance(d) : stat.equals("kd") ? d.kd() : kit == null ? d.stat(stat) : d.kitStat(kit.id, stat);
            if (v <= 0) break;
            lang.send(p, "Top.Line", "rank", rank++, "player", d.name, "value", Game.num(v));
        }
        if (rank == 1) lang.send(p, "Top.Empty");
        lang.send(p, "Top.Footer");
    }

    // ================================================================ admin

    private void admin(CommandSender sender, String[] a) {
        String sub = a.length < 2 ? "help" : a[1].toLowerCase(Locale.ROOT);
        Player p = sender instanceof Player pl ? pl : null;
        switch (sub) {
            case "reload" -> { plugin.reloadEverything(); lang.send(sender, "Admin.Reloaded", "kits", game.kits.size(), "arenas", game.arenas.size(), "abilities", plugin.scripts.abilities.size()); }
            case "kiteditor", "editor" -> { if (p != null) plugin.menus.openEditorList(p, 1); else lang.send(sender, "General.Players-Only"); }
            case "itemeditor" -> { if (p != null) plugin.menus.openItemEditor(p); else lang.send(sender, "General.Players-Only"); }
            case "item" -> { if (p != null) item(p, a); else lang.send(sender, "General.Players-Only"); }
            case "arena" -> arena(sender, p, a);
            case "join" -> {
                Player t = a.length > 2 ? Bukkit.getPlayerExact(a[2]) : null;
                if (t == null) lang.send(sender, "Admin.Usage", "usage", "/kp admin join <player> [arena]");
                else if (game.join(t, game.arena(a.length > 3 ? a[3] : plugin.getConfig().getString("Arenas.Default")))) lang.send(sender, "Admin.Done");
            }
            case "leave" -> {
                Player t = a.length > 2 ? Bukkit.getPlayerExact(a[2]) : null;
                if (t == null || game.session(t) == null) lang.send(sender, "General.Player-Not-Found", "player", String.valueOf(arg(a, 2)));
                else { game.leave(t, true, false); lang.send(sender, "Admin.Done"); }
            }
            case "setkit" -> {
                Player t = a.length > 2 ? Bukkit.getPlayerExact(a[2]) : null;
                Kit k = game.kit(arg(a, 3));
                Session s = game.session(t);
                if (s == null || k == null) { lang.send(sender, "Admin.Usage", "usage", "/kp admin setkit <player in KitPvP> <kit> [tier]"); return; }
                int tier = a.length > 4 ? parseInt(a[4], 1) : Math.max(1, game.owned(s.data, k));
                if (!k.tiers.containsKey(tier)) { lang.send(sender, "Admin.No-Such-Tier", "kit", k.id, "tier", tier); return; }
                game.equip(s, k, tier, false);
                lang.send(sender, "Admin.Done");
            }
            case "settier" -> {
                PlayerData d = game.dataByName(arg(a, 2));
                Kit k = game.kit(arg(a, 3));
                if (d == null || k == null || a.length < 5) { lang.send(sender, "Admin.Usage", "usage", "/kp admin settier <player> <kit> <tier>"); return; }
                d.tiers.put(k.id, Math.max(0, Math.min(k.maxTier(), parseInt(a[4], 0)))); d.dirty = true;
                lang.send(sender, "Admin.Tier-Set", "player", d.name, "kit", k.id, "tier", d.tiers.get(k.id));
            }
            case "coins" -> {
                PlayerData d = game.dataByName(arg(a, 2));
                if (d == null || a.length < 5) { lang.send(sender, "Admin.Usage", "usage", "/kp admin coins <player> <set|add|remove> <amount>"); return; }
                double n = parseDouble(a[4]);
                switch (a[3].toLowerCase(Locale.ROOT)) {
                    case "set" -> game.give(d, n - game.balance(d));
                    case "add" -> game.give(d, n);
                    case "remove" -> game.give(d, -n);
                    default -> { lang.send(sender, "Admin.Usage", "usage", "/kp admin coins <player> <set|add|remove> <amount>"); return; }
                }
                lang.send(sender, "Admin.Coins-Set", "player", d.name, "coins", Game.num(game.balance(d)));
            }
            case "stat" -> {
                PlayerData d = game.dataByName(arg(a, 2));
                if (d == null || a.length < 6) { lang.send(sender, "Admin.Usage", "usage", "/kp admin stat <player> <stat> <set|add|remove> <amount> [kit]"); return; }
                String stat = a[3].toLowerCase(Locale.ROOT);
                double n = parseDouble(a[5]);
                Kit k = game.kit(arg(a, 6));
                String kit = k == null ? null : k.id;
                switch (a[4].toLowerCase(Locale.ROOT)) {
                    case "set" -> d.set(stat, n, kit);
                    case "add" -> d.add(stat, n, kit);
                    case "remove" -> d.add(stat, -n, kit);
                    default -> { lang.send(sender, "Admin.Usage", "usage", "/kp admin stat <player> <stat> <set|add|remove> <amount> [kit]"); return; }
                }
                lang.send(sender, "Admin.Stat-Set", "player", d.name, "stat", stat, "value", Game.num(kit == null ? d.stat(stat) : d.kitStat(kit, stat)));
            }
            case "reset" -> {
                PlayerData d = game.dataByName(arg(a, 2));
                if (d == null) { lang.send(sender, "General.Player-Not-Found", "player", String.valueOf(arg(a, 2))); return; }
                Player t = Bukkit.getPlayer(d.uuid);
                if (t != null && game.session(t) != null) game.leave(t, true, false);
                game.data.remove(d.uuid);
                File f = new File(plugin.getDataFolder(), "players/" + d.uuid + ".yml");
                File dir = new File(plugin.getDataFolder(), "players-removed");
                dir.mkdirs();
                if (f.exists()) f.renameTo(new File(dir, d.uuid + "-" + System.currentTimeMillis() + ".yml"));
                lang.send(sender, "Admin.Reset", "player", d.name);
            }
            case "ability" -> {
                // /kp admin ability <id> <trigger> [cooldown] [ITEM|-] [function]   - meant for a Skript "on load"
                if (a.length < 4) { lang.send(sender, "Admin.Usage", "usage", "/kp admin ability <id> <trigger> [cooldown] [item|-] [skript function]"); return; }
                Scripts.Ability ab = new Scripts.Ability();
                ab.id = a[2]; ab.name = a[2];
                ab.trigger = a[3].toLowerCase(Locale.ROOT).replace('-', '_');
                ab.cooldown = a.length > 4 ? parseDouble(a[4]) : 0;
                ab.projectile = "";
                if (a.length > 5 && !a[5].equals("-")) ab.item = Material.matchMaterial(a[5]);
                ab.run = new ArrayList<>(List.of("skript:" + (a.length > 6 ? a[6] : Scripts.defaultFunction(a[2]))));
                Scripts.Ability old = plugin.scripts.ability(ab.id);
                if (old != null) { ab.name = old.name; ab.description = old.description; ab.cancel = old.cancel; ab.silent = old.silent; }
                plugin.scripts.register(ab);
                lang.send(sender, "Admin.Ability-Registered", "ability", ab.id, "trigger", ab.trigger);
            }
            case "use" -> {
                // /kp admin use <player> <ability> [target] - runs an ability now, whatever its item and cooldown
                Player who = a.length > 2 ? Bukkit.getPlayerExact(a[2]) : null;
                Session ws = who == null ? null : game.session(who);
                if (ws == null || a.length < 4) { lang.send(sender, "Admin.Usage", "usage", "/kp admin use <player> <ability> [target]"); return; }
                boolean ok = plugin.scripts.use(ws, a[3], a.length > 4 ? Bukkit.getPlayerExact(a[4]) : null);
                lang.send(sender, ok ? "Admin.Done" : "Admin.Usage", "usage", "/kp admin use <player> <ability> [target]  (the player needs a kit)");
            }
            case "damage" -> {
                // /kp admin damage <attacker> <amount> <victim name or uuid> - damage that counts as the attacker's (for Skript abilities)
                Player att = a.length > 2 ? Bukkit.getPlayerExact(a[2]) : null;
                org.bukkit.entity.Entity v = null;
                if (a.length > 4) { v = Bukkit.getPlayerExact(a[4]); if (v == null) try { v = Bukkit.getEntity(java.util.UUID.fromString(a[4])); } catch (IllegalArgumentException ignored) {} }
                if (att == null || !(v instanceof org.bukkit.entity.LivingEntity le)) return;
                le.damage(parseDouble(a[3]), att);
            }
            case "keys" -> {
                PlayerData d = game.dataByName(arg(a, 2));
                Crates.Crate c = plugin.crates.crate(a.length > 5 ? a[5] : plugin.crates.crates.isEmpty() ? null : plugin.crates.crates.values().iterator().next().id);
                if (d == null || c == null || a.length < 5) { lang.send(sender, "Admin.Usage", "usage", "/kp admin keys <player> <set|add|remove> <amount> [crate]"); return; }
                int n = parseInt(a[4], 0);
                switch (a[3].toLowerCase(Locale.ROOT)) {
                    case "set" -> plugin.crates.giveKeys(d, c, n - plugin.crates.keys(d, c));
                    case "add" -> plugin.crates.giveKeys(d, c, n);
                    case "remove" -> plugin.crates.giveKeys(d, c, -n);
                    default -> { lang.send(sender, "Admin.Usage", "usage", "/kp admin keys <player> <set|add|remove> <amount> [crate]"); return; }
                }
                lang.send(sender, "Admin.Keys-Set", "player", d.name, "crate", c.name, "keys", plugin.crates.keys(d, c));
            }
            case "station" -> {
                // Look at a block: it becomes (or stops being) a soup station.
                org.bukkit.block.Block b = p == null ? null : p.getTargetBlockExact(6);
                if (b == null) { lang.send(sender, "Admin.Usage", "usage", "/kp admin station  (look at the block)"); return; }
                lang.send(sender, plugin.extras.toggleStation(b) ? "Soup-Station.Added" : "Soup-Station.Removed");
            }
            case "remove" -> {
                // /kp admin remove <entity uuid> - takes a summoned mob out of the world, no drops (for Skript abilities)
                try {
                    org.bukkit.entity.Entity en = a.length > 2 ? Bukkit.getEntity(java.util.UUID.fromString(a[2])) : null;
                    if (en != null && !(en instanceof Player)) en.remove();
                } catch (IllegalArgumentException ignored) {}
            }
            case "kill" -> {
                // /kp admin kill <victim> [killer] - a KitPvP death, credited to the killer when one is named
                Session vs = game.session(a.length > 2 ? Bukkit.getPlayerExact(a[2]) : null);
                Session ks = game.session(a.length > 3 ? Bukkit.getPlayerExact(a[3]) : null);
                if (vs == null || (a.length > 3 && ks == null) || ks == vs) { lang.send(sender, "Admin.Usage", "usage", "/kp admin kill <victim in KitPvP> [killer in KitPvP]"); return; }
                game.kill(vs, ks);
                lang.send(sender, "Admin.Done");
            }
            case "give" -> {
                Player t = a.length > 2 ? Bukkit.getPlayerExact(a[2]) : null;
                ItemStack it = a.length > 3 ? Items.parse(rest(a, 3)) : null;
                if (t == null || it == null) { lang.send(sender, "Admin.Usage", "usage", "/kp admin give <player> <item text>"); return; }
                t.getInventory().addItem(it);
                lang.send(sender, "Admin.Done");
            }
            default -> lang.send(sender, "Help.Admin");
        }
    }

    private static int parseInt(String s, int def) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; } }
    private static double parseDouble(String s) { try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0; } }

    private void arena(CommandSender sender, Player p, String[] a) {
        String op = a.length < 3 ? "list" : a[2].toLowerCase(Locale.ROOT);
        Arena ar = game.arena(arg(a, 3));
        switch (op) {
            case "list" -> {
                lang.send(sender, "Arena.List-Header");
                for (Arena x : game.arenas.values()) lang.send(sender, "Arena.List-Line", "arena", x.id, "type", x.type, "spawns", x.spawns.size(), "enabled", x.enabled, "radius", Game.num(x.safeRadius));
            }
            case "create" -> {
                if (a.length < 4 || ar != null) { lang.send(sender, "Admin.Usage", "usage", "/kp admin arena create <new id> [ffa|duel]"); return; }
                Arena n = new Arena(a[3]);
                n.type = a.length > 4 && a[4].equalsIgnoreCase("duel") ? Arena.Type.DUEL : Arena.Type.FFA;
                if (p != null) n.spawns.add(Arena.toText(p.getLocation()));
                game.arenas.put(n.id.toLowerCase(Locale.ROOT), n);
                game.saveArenas();
                lang.send(sender, "Arena.Created", "arena", n.id, "type", n.type);
            }
            case "addspawn" -> {
                if (ar == null || p == null) { lang.send(sender, "Admin.Usage", "usage", "/kp admin arena addspawn <arena>  (stand on the spot)"); return; }
                ar.spawns.add(Arena.toText(p.getLocation()));
                game.saveArenas();
                lang.send(sender, "Arena.Spawn-Added", "arena", ar.id, "spawns", ar.spawns.size());
            }
            case "clearspawns" -> {
                if (ar == null) { lang.send(sender, "Arena.Not-Found"); return; }
                ar.spawns.clear(); game.saveArenas();
                lang.send(sender, "Arena.Spawns-Cleared", "arena", ar.id);
            }
            case "saferadius" -> {
                if (ar == null || a.length < 5) { lang.send(sender, "Admin.Usage", "usage", "/kp admin arena saferadius <arena> <blocks>"); return; }
                ar.safeRadius = Math.max(0, parseDouble(a[4])); game.saveArenas();
                lang.send(sender, "Arena.Radius-Set", "arena", ar.id, "radius", Game.num(ar.safeRadius));
            }
            case "enable", "disable" -> {
                if (ar == null) { lang.send(sender, "Arena.Not-Found"); return; }
                ar.enabled = op.equals("enable"); game.saveArenas();
                lang.send(sender, "Admin.Done");
            }
            case "remove" -> {
                if (ar == null) { lang.send(sender, "Arena.Not-Found"); return; }
                game.arenas.remove(ar.id.toLowerCase(Locale.ROOT)); game.saveArenas();
                lang.send(sender, "Arena.Removed", "arena", ar.id);
            }
            case "tp" -> {
                if (ar == null || p == null || ar.spawn(0) == null) { lang.send(sender, "Arena.Not-Found"); return; }
                p.teleport(ar.spawn(0));
            }
            default -> lang.send(sender, "Help.Admin");
        }
    }

    private void item(Player p, String[] a) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) { lang.send(p, "Editor.Hold-Item"); return; }
        String op = a.length < 3 ? "" : a[2].toLowerCase(Locale.ROOT);
        String text = rest(a, 3);
        ItemMeta m = hand.getItemMeta();
        switch (op) {
            case "name" -> m.displayName(text.isEmpty() ? null : Lang.color(text));
            case "addlore" -> { var l = m.hasLore() ? new ArrayList<>(m.lore()) : new ArrayList<net.kyori.adventure.text.Component>(); l.add(Lang.color(text)); m.lore(l); }
            case "clearlore" -> m.lore(null);
            case "enchant" -> {
                Enchantment en = a.length > 3 ? Items.enchant(a[3]) : null;
                if (en == null) { lang.send(p, "Editor.Unknown-Enchant"); return; }
                int lvl = a.length > 4 ? parseInt(a[4], 1) : 1;
                if (lvl <= 0) m.removeEnchant(en); else m.addEnchant(en, lvl, true);
            }
            case "model" -> m.setItemModel(text.isEmpty() ? null : NamespacedKey.fromString((text.contains(":") ? text : "nexo:" + text).toLowerCase(Locale.ROOT)));
            case "ability" -> {
                if (text.isEmpty()) m.getPersistentDataContainer().remove(Items.ABILITY);
                else if (plugin.scripts.ability(text) == null) { lang.send(p, "Editor.Unknown-Ability", "ability", text); return; }
                else m.getPersistentDataContainer().set(Items.ABILITY, PersistentDataType.STRING, text);
            }
            case "unbreakable" -> m.setUnbreakable(!m.isUnbreakable());
            case "text" -> { lang.send(p, "Editor.Item-Text", "text", String.valueOf(Items.serialize(hand))); return; }
            default -> { lang.send(p, "Admin.Usage", "usage", "/kp admin item <name|addlore|clearlore|enchant|model|ability|unbreakable|text> ..."); return; }
        }
        hand.setItemMeta(m);
        lang.send(p, "Editor.Item-Updated");
    }

    // ================================================================ tab

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] a) {
        boolean admin = sender.hasPermission("kitpvp.admin");
        if (!admin && !sender.hasPermission("kitpvp.play") && !(sender instanceof Player sp && game.session(sp) != null)) return List.of();
        List<String> o = new ArrayList<>();
        if (a.length == 1) {
            o.addAll(List.of("join", "leave", "kit", "upgrades", "shop", "crates", "challenges", "achievements", "spawn", "respawn", "stats", "balance", "top", "duel", "accept", "duels", "queue", "help"));
            if (admin) o.add("admin");
        } else if (a[0].equalsIgnoreCase("admin") && admin) {
            if (a.length == 2) o.addAll(List.of("reload", "kiteditor", "itemeditor", "item", "arena", "join", "leave", "setkit", "settier", "coins", "keys", "station", "stat", "reset", "kill", "ability", "give", "help"));
            else switch (a[1].toLowerCase(Locale.ROOT)) {
                case "item" -> { if (a.length == 3) o.addAll(List.of("name", "addlore", "clearlore", "enchant", "model", "ability", "unbreakable", "text")); else if (a[2].equalsIgnoreCase("ability")) o.addAll(plugin.scripts.abilities.keySet()); }
                case "arena" -> { if (a.length == 3) o.addAll(List.of("list", "create", "addspawn", "clearspawns", "saferadius", "enable", "disable", "remove", "tp")); else if (a.length == 4) game.arenas.values().forEach(x -> o.add(x.id)); }
                case "join", "leave", "reset", "give" -> { if (a.length == 3) Bukkit.getOnlinePlayers().forEach(x -> o.add(x.getName())); else if (a.length == 4 && a[1].equalsIgnoreCase("join")) game.arenas.values().forEach(x -> o.add(x.id)); }
                case "kill" -> { if (a.length <= 4) game.sessions.values().forEach(x -> o.add(x.player.getName())); }
                case "setkit", "settier" -> { if (a.length == 3) Bukkit.getOnlinePlayers().forEach(x -> o.add(x.getName())); else if (a.length == 4) game.kits.values().forEach(k -> o.add(k.id)); }
                case "keys" -> { if (a.length == 3) Bukkit.getOnlinePlayers().forEach(x -> o.add(x.getName())); else if (a.length == 4) o.addAll(List.of("set", "add", "remove")); else if (a.length == 6) plugin.crates.crates.values().forEach(x -> o.add(x.id)); }
                case "coins" -> { if (a.length == 3) Bukkit.getOnlinePlayers().forEach(x -> o.add(x.getName())); else if (a.length == 4) o.addAll(List.of("set", "add", "remove")); }
                case "stat" -> { if (a.length == 3) Bukkit.getOnlinePlayers().forEach(x -> o.add(x.getName())); else if (a.length == 4) o.addAll(plugin.getConfig().getStringList("Stats.Shown")); else if (a.length == 5) o.addAll(List.of("set", "add", "remove")); else if (a.length == 7) game.kits.values().forEach(k -> o.add(k.id)); }
                default -> {}
            }
        } else switch (a[0].toLowerCase(Locale.ROOT)) {
            case "join" -> game.arenas.values().forEach(x -> { if (x.type == Arena.Type.FFA && x.enabled) o.add(x.id); });
            case "kit", "kits", "upgrade", "buy" -> game.kits.values().forEach(k -> { if (k.enabled && !k.hidden) o.add(k.id); });
            case "stats" -> { Bukkit.getOnlinePlayers().forEach(x -> o.add(x.getName())); game.kits.values().forEach(k -> o.add(k.id)); }
            case "balance", "duel" -> Bukkit.getOnlinePlayers().forEach(x -> o.add(x.getName()));
            case "top" -> { if (a.length == 2) { o.addAll(plugin.getConfig().getStringList("Stats.Shown")); o.addAll(List.of("coins", "kd")); } else game.kits.values().forEach(k -> o.add(k.id)); }
            default -> {}
        }
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        o.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return o;
    }
}
