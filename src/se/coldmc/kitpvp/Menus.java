package se.coldmc.kitpvp;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.EquippableComponent;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Every chest menu: the kit shop players use, and the kit creator admins use. */
public final class Menus implements Listener {
    private final KitPvP plugin;
    private final Game game;
    private final Lang lang;
    private final Map<UUID, Consumer<String>> prompts = new HashMap<>();

    public Menus(KitPvP plugin) { this.plugin = plugin; this.game = plugin.game; this.lang = plugin.lang; }

    // ================================================================ framework

    private static final class Menu implements InventoryHolder {
        Inventory inv;
        final Map<Integer, BiConsumer<Player, ClickType>> clicks = new HashMap<>();
        final Set<Integer> free = new HashSet<>();      // top slots the player may put items in
        boolean ownInventoryFree;                       // the player may move their own items too
        Runnable onClose;                               // e.g. Escape during a crate roll = show the result now
        boolean placeholders;                           // empty free slots hold a pane (kit editor), see onClick
        BiConsumer<Player, Integer> onEmptyDrop;         // Q on one of those panes
        @Override public Inventory getInventory() { return inv; }
    }

    private Menu menu(int rows, String titleKey, Object... kv) {
        Menu m = new Menu();
        m.inv = Bukkit.createInventory(m, rows * 9, lang.text(titleKey, kv));
        return m;
    }

    private Material mat(String key, Material def) {
        Material m = Material.matchMaterial(plugin.getConfig().getString("Menus.Items." + key, def.name()));
        return m == null ? def : m;
    }

    /** A button whose name and lore are lang.yml's Menus.<key>.Name / .Lore. */
    private ItemStack button(Material material, String key, Object... kv) {
        return named(new ItemStack(material), lang.text("Menus." + key + ".Name", kv), lang.lore("Menus." + key + ".Lore", kv));
    }

    private static ItemStack named(ItemStack it, Component name, List<Component> lore) {
        ItemMeta m = it.getItemMeta();
        if (m == null) return it;
        m.displayName(name);
        m.lore(lore);
        m.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        it.setItemMeta(m);
        return it;
    }

    private void set(Menu m, int slot, ItemStack it, BiConsumer<Player, ClickType> click) {
        m.inv.setItem(slot, it);
        if (click != null) m.clicks.put(slot, click);
    }

    private void fill(Menu m, int from, int to) {
        ItemStack pane = named(new ItemStack(mat("Filler", Material.GRAY_STAINED_GLASS_PANE)), Component.text(" "), List.of());
        for (int i = from; i <= to; i++) if (m.inv.getItem(i) == null) m.inv.setItem(i, pane);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu m) || !(e.getWhoClicked() instanceof Player p)) return;
        int raw = e.getRawSlot();
        if (raw < 0) return;
        if (m.placeholders && placeholderClick(e, m, p, raw)) return;
        if (raw >= m.inv.getSize()) {                       // the player's own inventory
            if (!m.ownInventoryFree || e.getClick() == ClickType.DOUBLE_CLICK) e.setCancelled(true);
            return;
        }
        if (m.free.contains(raw)) return;
        e.setCancelled(true);
        BiConsumer<Player, ClickType> c = m.clicks.get(raw);
        if (c != null) c.accept(p, e.getClick());
    }

    // ---------------------------------------------------------------- empty-slot panes (kit editor)

    private static final NamespacedKey EMPTY = new NamespacedKey("kitpvp", "editor_empty");

    private ItemStack placeholder() {
        ItemStack it = button(Material.GRAY_STAINED_GLASS_PANE, "Editor.Empty-Slot");
        ItemMeta im = it.getItemMeta();
        im.getPersistentDataContainer().set(EMPTY, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(im);
        return it;
    }
    private static boolean isPlaceholder(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(EMPTY, PersistentDataType.BYTE);
    }
    private void refillEmpty(Menu m) {
        for (int i : new java.util.TreeSet<>(m.free)) { ItemStack it = m.inv.getItem(i); if (it == null || it.getType().isAir()) m.inv.setItem(i, placeholder()); }
    }

    /** A pane stands for an empty slot: putting an item there replaces it, Q opens the ability list. true = handled. */
    private boolean placeholderClick(InventoryClickEvent e, Menu m, Player p, int raw) {
        boolean top = raw < m.inv.getSize();
        if (top && m.free.contains(raw) && isPlaceholder(m.inv.getItem(raw))) {
            e.setCancelled(true);
            ClickType c = e.getClick();
            if (c == ClickType.DROP || c == ClickType.CONTROL_DROP) { if (m.onEmptyDrop != null) m.onEmptyDrop.accept(p, raw); return true; }
            ItemStack cur = p.getItemOnCursor();
            if (!cur.getType().isAir()) {
                if (c == ClickType.RIGHT) {
                    ItemStack one = cur.clone(); one.setAmount(1); m.inv.setItem(raw, one);
                    cur.setAmount(cur.getAmount() - 1); p.setItemOnCursor(cur.getAmount() > 0 ? cur : null);
                } else { m.inv.setItem(raw, cur.clone()); p.setItemOnCursor(null); }
            } else if (c == ClickType.NUMBER_KEY && e.getHotbarButton() >= 0) {
                ItemStack h = p.getInventory().getItem(e.getHotbarButton());
                if (h != null && !h.getType().isAir()) { m.inv.setItem(raw, h.clone()); p.getInventory().setItem(e.getHotbarButton(), null); }
            }
            return true;
        }
        if (!top && e.isShiftClick() && m.ownInventoryFree) {            // shift-click up: the first empty (pane) slot
            e.setCancelled(true);
            ItemStack it = e.getCurrentItem();
            if (it == null || it.getType().isAir() || isPlaceholder(it)) return true;
            for (int i : new java.util.TreeSet<>(m.free)) if (isPlaceholder(m.inv.getItem(i))) { m.inv.setItem(i, it.clone()); e.setCurrentItem(null); break; }
            return true;
        }
        if (top && e.isShiftClick() && isPlaceholder(e.getCurrentItem())) { e.setCancelled(true); return true; }
        Bukkit.getScheduler().runTask(plugin, () -> refillEmpty(m));      // whatever this click empties gets its pane back
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu m)) return;
        if (m.placeholders) {
            boolean pane = false;
            for (int raw : e.getRawSlots()) if (raw < m.inv.getSize() && isPlaceholder(m.inv.getItem(raw))) pane = true;
            if (pane) {
                e.setCancelled(true);
                if (e.getRawSlots().size() == 1 && e.getWhoClicked() instanceof Player p) {
                    int raw = e.getRawSlots().iterator().next();
                    ItemStack held = e.getOldCursor().clone();
                    if (m.free.contains(raw)) Bukkit.getScheduler().runTask(plugin, () -> { m.inv.setItem(raw, held); p.setItemOnCursor(null); });
                }
                return;
            }
        }
        for (int raw : e.getRawSlots()) {
            if (raw < m.inv.getSize() ? !m.free.contains(raw) : !m.ownInventoryFree) { e.setCancelled(true); return; }
        }
    }

    @EventHandler
    public void onClose(org.bukkit.event.inventory.InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof Menu m && m.onClose != null) { Runnable r = m.onClose; m.onClose = null; r.run(); }
    }

    // ---------------------------------------------------------------- chat prompts

    private void prompt(Player p, String key, Consumer<String> then, Object... kv) {
        p.closeInventory();
        prompts.put(p.getUniqueId(), then);
        lang.send(p, key, kv);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Consumer<String> c = prompts.remove(e.getPlayer().getUniqueId());
        if (c == null) return;
        e.setCancelled(true);
        String msg = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (msg.equalsIgnoreCase(lang.raw("Editor.Cancel-Word"))) { lang.send(e.getPlayer(), "Editor.Cancelled"); c.accept(null); }
            else c.accept(msg);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { prompts.remove(e.getPlayer().getUniqueId()); }

    // ================================================================ players: the kit menu

    public void openKits(Player p, int page) { openKits(p, "kits", page); }

    /** mode: kits = what you own (click to take), upgrades = owned kits with a next tier, shop = kits you do not own, all = everything. */
    public void openKits(Player p, String mode, int page) {
        int rows = Math.max(2, Math.min(6, plugin.getConfig().getInt("Kits.Menu-Rows", 6)));
        int perPage = (rows - 1) * 9;
        // A page of the shop can have a name of its own (lang.yml Menus.Kits.Page-Names.<n>).
        String pageName = lang.has("Menus.Kits.Page-Names." + page) && (mode.equals("shop") || mode.equals("all"))
                ? lang.raw("Menus.Kits.Page-Names." + page) : lang.raw("Menus.Kits.Page-Default", "page", page);
        Menu m = menu(rows, "Menus.Kits.Title-" + mode, "page", page, "pagename", pageName);
        PlayerData d = game.data(p);
        List<Kit> shown = new ArrayList<>();
        boolean packed = !mode.equals("shop") && !mode.equals("all");     // the shop keeps each kit on its own page
        boolean shop = mode.equals("shop");
        for (Kit k : game.kits.values()) {
            if (!k.enabled || k.hidden) continue;
            int have = game.owned(d, k);
            boolean fits = switch (mode) {
                case "kits" -> have > 0;
                case "upgrades" -> have > 0 && k.tiers.higherKey(have) != null;
                default -> true;        // the shop shows every kit: a bought one as "Already bought"
            };
            if (fits) shown.add(k);
        }
        // Sorted by rarity (of the tier the menu shows), then price, then name (the owner, 2026-11-13).
        shown.sort(java.util.Comparator.comparingInt((Kit k) -> k.rarityOf(packed ? Math.max(1, game.owned(d, k)) : 1)).thenComparing(Kit.BY_RARITY));
        Map<Integer, Kit> here = new HashMap<>();
        int maxPage = 1;
        if (packed) {
            int i = 0;
            for (Kit k : shown) { int pg = i / perPage + 1; if (pg == page) here.put(i % perPage, k); maxPage = Math.max(maxPage, pg); i++; }
        } else {
            // A kit's Page: picks its shop page; inside the page the order is the sort above,
            // laid out in the middle seven columns from row 2 down, then any slot left over.
            List<Integer> order = shopSlots(rows, perPage);
            int i = 0;
            for (Kit k : shown) {
                maxPage = Math.max(maxPage, k.page);
                if (k.page == page && i < order.size()) here.put(order.get(i++), k);
            }
        }
        for (Map.Entry<Integer, Kit> e : here.entrySet()) {
            Kit k = e.getValue();
            if (shop && game.owned(d, k) > 0) {
                set(m, e.getKey(), named(new ItemStack(mat("Already-Bought", Material.GRAY_DYE)), lang.text("Menus.Kit.Already-Bought.Name", "kit", k.tierName(1)),
                        lang.lore("Menus.Kit.Already-Bought.Lore", "kit", k.tierName(1))), null);
                continue;
            }
            set(m, e.getKey(), kitIcon(p, d, k), (pl, click) -> {
                Runnable back = () -> openKits(pl, mode, page);     // Cancel and Buy return here, not to My kits
                if (click.isRightClick() || !mode.equals("kits")) { openUpgrade(pl, k, back); return; }
                if (game.owned(game.data(pl), k) <= 0) { openUpgrade(pl, k, back); return; }
                pl.closeInventory();
                game.select(pl, k);
            });
        }
        // Section signs in the shop (config.yml Kits.Shop-Labels): "VIP", "MVP" ...
        if (!packed) {
            org.bukkit.configuration.ConfigurationSection ls = plugin.getConfig().getConfigurationSection("Kits.Shop-Labels");
            if (ls != null) for (String key : ls.getKeys(false)) {
                int ps = ls.getInt(key + ".Slot", -1);
                if (ls.getInt(key + ".Page", 1) != page || ps < 0 || ps >= perPage || here.containsKey(ps)) continue;
                ItemStack icon = Items.parse(ls.getString(key + ".Item", "NAME_TAG"));
                if (icon == null) icon = new ItemStack(Material.NAME_TAG);
                List<Component> lore = new ArrayList<>();
                for (String line : ls.getStringList(key + ".Lore")) lore.add(Lang.color(line));
                set(m, ps, named(icon, Lang.color(ls.getString(key + ".Name", key)), lore), null);
            }
        }
        int base = (rows - 1) * 9;
        fill(m, base, base + 8);
        final int mp = maxPage;
        if (page > 1) set(m, base, button(mat("Previous", Material.ARROW), "Previous"), (pl, c) -> openKits(pl, mode, page - 1));
        if (page < mp) set(m, base + 8, button(mat("Next", Material.ARROW), "Next"), (pl, c) -> openKits(pl, mode, page + 1));
        // No tabs (the owner, 2026-11-14): My kits, Upgrades, Shop and Crates are separate menus, each opened by its own lobby item.
        if (shown.isEmpty()) set(m, 13, button(mat("Empty", Material.BARRIER), "Empty-" + mode), null);
        set(m, base + 4, button(mat("Balance", Material.GOLD_INGOT), "Balance", "coins", Game.num(game.balance(d)),
                "kills", Game.num(d.stat("kills")), "deaths", Game.num(d.stat("deaths")), "kd", Game.num(d.kd())), null);
        p.openInventory(m.inv);
    }

    /** Shop order of slots: rows 2.. of the middle seven columns, then whatever is left. */
    private static List<Integer> shopSlots(int rows, int perPage) {
        List<Integer> o = new ArrayList<>();
        for (int r = 1; r < rows - 1; r++) for (int c = 1; c <= 7; c++) if (r * 9 + c < perPage) o.add(r * 9 + c);
        for (int i = 0; i < perPage; i++) if (!o.contains(i)) o.add(i);
        return o;
    }

    private Set<Integer> pageSlots(List<Kit> kits, int page, int perPage) {
        Set<Integer> s = new HashSet<>();
        for (Kit k : kits) if (k.slot >= 0 && k.slot < perPage && k.page == page) s.add(k.slot);
        return s;
    }

    private ItemStack kitIcon(Player p, PlayerData d, Kit k) {
        ItemStack it = Items.parse(k.icon);
        if (it == null) it = new ItemStack(Material.IRON_SWORD);
        int have = game.owned(d, k);
        Integer next = k.tiers.higherKey(have);
        List<Component> lore = new ArrayList<>();
        for (String s : k.description) lore.add(Lang.color(s));
        boolean locked = !k.permission.isEmpty() && !p.hasPermission(k.permission);
        if (Rarity.enabled()) lore.addAll(lang.lore("Menus.Kit.Rarity", "rarity", Rarity.label(k.rarityOf(Math.max(have, 1)))));
        lore.addAll(lang.lore(have > 0 ? "Menus.Kit.Owned" : "Menus.Kit.Not-Owned", "tier", have, "max", k.maxTier(), "name", k.tierName(Math.max(have, 1))));
        if (locked) lore.addAll(lang.lore("Menus.Kit.Locked"));
        if (next != null) {
            Kit.Tier t = k.tiers.get(next);
            lore.addAll(lang.lore(have > 0 ? "Menus.Kit.Next-Tier" : "Menus.Kit.Price", "cost", Game.num(t.cost), "name", k.tierName(next), "tier", next));
            for (Game.Requirement r : game.requirements(p, d, k, t))
                lore.add(lang.text(r.met() ? "Menus.Kit.Requirement-Met" : "Menus.Kit.Requirement-Missing", "requirement", r.text(), "progress", r.progress()));
        } else lore.addAll(lang.lore("Menus.Kit.Max-Tier"));
        double kills = d.kitStat(k.id, "kills"), deaths = d.kitStat(k.id, "deaths");
        lore.addAll(lang.lore("Menus.Kit.Stats", "kills", Game.num(kills), "deaths", Game.num(deaths), "close", Game.num(d.kitStat(k.id, "close_kills")),
                "streak", Game.num(d.kitStat(k.id, "best_streak")), "uses", Game.num(d.kitStat(k.id, "uses"))));
        lore.addAll(lang.lore(have > 0 ? (next != null ? "Menus.Kit.Click-Owned" : "Menus.Kit.Click-Max") : "Menus.Kit.Click-Buy"));
        return named(it, Lang.color(k.tierName(Math.max(have, 1))), lore);
    }

    /** Shows exactly what the next tier gives before any coins are spent.
     *  Not owned (or highest tier owned): the buy preview. Owned with a next tier: the old tier beside the new one. */
    public void openUpgrade(Player p, Kit k) { openUpgrade(p, k, () -> p.closeInventory()); }

    /** back: where Cancel, and the menu after buying, go - the menu and page the kit was clicked in. */
    public void openUpgrade(Player p, Kit k, Runnable back) {
        PlayerData d = game.data(p);
        int have = game.owned(d, k);
        Integer next = k.tiers.higherKey(have);
        if (have > 0 && next != null && k.tiers.get(have) != null) { openUpgradePreview(p, k, d, have, next, back); return; }
        int show = next != null ? next : have;
        Kit.Tier t = k.tiers.get(show);
        if (t == null) { back.run(); return; }
        Menu m = menu(6, "Menus.Upgrade.Title", "kit", Lang.plain(Lang.color(k.tierName(show))), "tier", show);
        Parts n = parts(k, t);
        // row 2: armour and effects
        put(m, 11, n.helmet); put(m, 12, n.chestplate); put(m, 13, n.leggings); put(m, 14, n.boots);
        put(m, 15, n.effects);
        // row 4: weapon, the special item (blaze powder when there is none), extras
        put(m, 30, n.weapon); put(m, 31, n.special); put(m, 32, n.extra);
        bottom(m, p, d, k, t, show, next == null ? null : "Upgrade.Buy", back);
        // browse the upgrades (the owner, 2026-11-14): Next from the buy view, Previous from the highest tier
        Integer after = k.tiers.higherKey(show), before = k.tiers.lowerKey(show);
        if (have == 0 && after != null)
            set(m, 53, button(mat("Next", Material.ARROW), "Upgrade.Next-Upgrade", "tier", after), (pl, c) -> openUpgradePreview(pl, k, d, show, after, back));
        if (next == null && before != null && k.tiers.get(before) != null)
            set(m, 45, button(mat("Previous", Material.ARROW), "Upgrade.Previous-Upgrade", "tier", show), (pl, c) -> openUpgradePreview(pl, k, d, before, show, back));
        p.openInventory(m.inv);
    }

    /** Old tier on the left of each pair, the Next arrow, the new tier on the right. */
    private void openUpgradePreview(Player p, Kit k, PlayerData d, int have, int next, Runnable back) {
        int owned = game.owned(d, k);
        boolean buyable = have == owned;                     // only the step after the owned tier can be bought here
        Kit.Tier t = k.tiers.get(next);
        Menu m = menu(6, "Menus.Upgrade.Title", "kit", Lang.plain(Lang.color(k.tierName(next))), "tier", next);
        Parts o = parts(k, k.tiers.get(have)), n = parts(k, t);
        pair(m, 10, o.weapon, n.weapon, "weapon");          pair(m, 14, o.helmet, n.helmet, "helmet");
        pair(m, 19, o.special, n.special, "special");       pair(m, 23, o.chestplate, n.chestplate, "chestplate");
        pair(m, 28, o.extra, n.extra, "extra");             pair(m, 32, o.leggings, n.leggings, "leggings");
        pair(m, 37, o.effects, n.effects, "effects");       pair(m, 41, o.boots, n.boots, "boots");
        bottom(m, p, d, k, t, next, "Upgrade.Confirm", back);
        if (!buyable) set(m, 51, button(mat("Max-Tier", Material.BARRIER), next <= owned ? "Upgrade.Owned" : "Upgrade.Need-Previous", "tier", have), null);
        // slot 1 / 9 of row 6: the upgrade before and after this one, when there is one (the owner, 2026-11-14)
        Integer before = k.tiers.lowerKey(have), after = k.tiers.higherKey(next);
        if (before != null && k.tiers.get(before) != null)
            set(m, 45, button(mat("Previous", Material.ARROW), "Upgrade.Previous-Upgrade", "tier", have), (pl, c) -> openUpgradePreview(pl, k, d, before, have, back));
        if (after != null)
            set(m, 53, button(mat("Next", Material.ARROW), "Upgrade.Next-Upgrade", "tier", after), (pl, c) -> openUpgradePreview(pl, k, d, next, after, back));
        p.openInventory(m.inv);
    }

    /** What a tier shows in the previews, sorted into the boxes the menus have. */
    private record Parts(ItemStack helmet, ItemStack chestplate, ItemStack leggings, ItemStack boots,
                         ItemStack weapon, ItemStack special, ItemStack extra, ItemStack effects) {}

    private Parts parts(Kit k, Kit.Tier t) {
        java.util.TreeMap<Integer, ItemStack> items = new java.util.TreeMap<>();
        for (Map.Entry<Integer, Object> e : t.items.entrySet()) {
            ItemStack it = Items.parse(e.getValue());
            if (it != null && !it.getType().isAir()) items.put(e.getKey(), it);
        }
        ItemStack helmet = items.remove(Kit.HELMET), chest = items.remove(Kit.CHESTPLATE),
                legs = items.remove(Kit.LEGGINGS), boots = items.remove(Kit.BOOTS);
        // the offhand counts as an extra, after everything in the inventory
        ItemStack off = items.remove(Kit.OFFHAND);
        List<ItemStack> rest = new ArrayList<>(items.values());
        if (off != null) rest.add(off);
        List<String> abilities = mergedAbilities(k, t);

        ItemStack special = take(rest, it -> Items.abilityOf(it) != null);
        ItemStack weapon = take(rest, it -> melee(it.getType()));
        if (special == null) special = abilities.isEmpty()
                ? take(rest, it -> ranged(it.getType()) || melee(it.getType()))   // a bow, a throwable, or a second weapon (Assassin)
                : take(rest, it -> !extraLike(it));         // e.g. Thor's axe: the item the ability works through
        if (weapon == null) weapon = take(rest, it -> !extraLike(it) && !ranged(it.getType()));

        List<Component> abilityLore = new ArrayList<>();
        for (String id : abilities) {
            Scripts.Ability a = plugin.scripts.ability(id);
            abilityLore.add(lang.text("Menus.Preview.Ability-Line", "ability", a == null ? id : a.name));
            if (a != null) for (String line : a.description) abilityLore.add(Lang.color("&7" + line));
        }
        if (special != null) special = withLore(special, abilityLore);
        else if (!abilities.isEmpty()) {
            Scripts.Ability a = plugin.scripts.ability(abilities.get(0));
            special = named(new ItemStack(mat("Ability", Material.BLAZE_POWDER)),
                    lang.text("Menus.Preview.Ability-Icon.Name", "ability", a == null ? abilities.get(0) : a.name), abilityLore);
        } else if (rest.stream().anyMatch(Menus::isPotion)) {
            // e.g. Vanilla (the owner, 2026-11-14): the potions are the special item - one potion icon, every potion in its lore
            // the same potion in several slots is one line: "4x Splash Potion of Healing" (the owner, 2026-11-14)
            java.util.LinkedHashMap<String, Integer> count = new java.util.LinkedHashMap<>();
            ItemStack first = null;
            for (java.util.Iterator<ItemStack> i = rest.iterator(); i.hasNext(); ) {
                ItemStack it = i.next();
                if (!isPotion(it)) continue;
                if (first == null) first = it.clone();
                count.merge(potionName(it), it.getAmount(), Integer::sum);
                i.remove();
            }
            List<Component> lore = new ArrayList<>();
            for (Map.Entry<String, Integer> c : count.entrySet()) lore.add(lang.text("Menus.Preview.Potion-Line", "amount", c.getValue(), "potion", c.getKey()));
            first.setAmount(1);
            special = named(first, lang.text("Menus.Preview.Potions.Name"), lore);
        } else special = button(mat("No-Special", Material.BLAZE_POWDER), "Preview.No-Special");

        ItemStack extra = null;
        if (!rest.isEmpty()) {
            List<Component> also = new ArrayList<>();
            for (ItemStack it : rest.subList(1, rest.size()))
                also.add(lang.text("Menus.Preview.Also-Line", "amount", it.getAmount(), "item", itemName(it)));
            extra = withLore(rest.get(0), also);
        }
        return new Parts(helmet, chest, legs, boots, weapon, special, extra, effectsIcon(t.effects));
    }

    /** The item's own name, or its material tidied up ("SPECTRAL_ARROW" -> "Spectral Arrow"). */
    private static String itemName(ItemStack it) {
        ItemMeta m = it.getItemMeta();
        if (m != null && m.hasDisplayName()) return Lang.plain(m.displayName());
        StringBuilder b = new StringBuilder();
        for (String w : it.getType().name().toLowerCase(Locale.ROOT).split("_")) { if (b.length() > 0) b.append(' '); b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)); }
        return b.toString();
    }

    private static ItemStack take(List<ItemStack> l, java.util.function.Predicate<ItemStack> test) {
        for (int i = 0; i < l.size(); i++) if (test.test(l.get(i))) return l.remove(i);
        return null;
    }
    private static boolean isPotion(ItemStack it) { Material m = it.getType(); return m == Material.POTION || m == Material.SPLASH_POTION || m == Material.LINGERING_POTION; }

    /** "Splash Potion of Long Swiftness", or the item's own name. */
    private static String potionName(ItemStack it) {
        ItemMeta m = it.getItemMeta();
        if (m != null && m.hasDisplayName()) return Lang.plain(m.displayName());
        String kind = itemName(it);
        if (m instanceof org.bukkit.inventory.meta.PotionMeta pm && pm.getBasePotionType() != null)
            return kind + " of " + tidy(pm.getBasePotionType().getKey().getKey());
        return kind;
    }
    private static String tidy(String key) {
        StringBuilder b = new StringBuilder();
        for (String w : key.split("_")) { if (b.length() > 0) b.append(' '); b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)); }
        return b.toString();
    }

    private static boolean melee(Material m) { String n = m.name(); return n.endsWith("_SWORD") || n.endsWith("_AXE") || m == Material.MACE; }
    private static boolean ranged(Material m) { return m == Material.BOW || m == Material.CROSSBOW || m == Material.TRIDENT || m == Material.FISHING_ROD
            || m == Material.EGG || m == Material.SNOWBALL || m == Material.ENDER_PEARL || m == Material.WIND_CHARGE || m == Material.FIRE_CHARGE; }
    private static boolean extraLike(ItemStack it) {
        Material m = it.getType(); String n = m.name();
        return m.isEdible() || n.endsWith("ARROW") || m == Material.SHIELD || n.endsWith("POTION") || m == Material.MUSHROOM_STEW;
    }

    private static ItemStack withLore(ItemStack it, List<Component> add) {
        if (add.isEmpty()) return it;
        it = it.clone();
        ItemMeta m = it.getItemMeta();
        if (m == null) return it;
        List<Component> lore = m.lore() == null ? new ArrayList<>() : new ArrayList<>(m.lore());
        if (!lore.isEmpty()) lore.add(Component.empty());
        lore.addAll(add);
        m.lore(lore);
        it.setItemMeta(m);
        return it;
    }

    /** One effect: a potion in that effect's colour. Several: a brewing stand. None: an empty bottle. */
    private ItemStack effectsIcon(List<String> specs) {
        List<org.bukkit.potion.PotionEffect> fx = new ArrayList<>();
        for (String s : specs) { org.bukkit.potion.PotionEffect e = Scripts.parseEffect(s); if (e != null) fx.add(e); }
        if (fx.isEmpty()) return button(mat("No-Effects", Material.GLASS_BOTTLE), "Preview.No-Effects");
        List<Component> lore = new ArrayList<>();
        for (org.bukkit.potion.PotionEffect e : fx) lore.add(lang.text("Menus.Preview.Effect-Line", "effect", effectName(e)));
        if (fx.size() > 1) return named(new ItemStack(mat("Effects", Material.BREWING_STAND)), lang.text("Menus.Preview.Effects.Name"), lore);
        ItemStack it = new ItemStack(Material.POTION);
        if (it.getItemMeta() instanceof org.bukkit.inventory.meta.PotionMeta pm) { pm.setColor(fx.get(0).getType().getColor()); it.setItemMeta(pm); }
        return named(it, lang.text("Menus.Preview.Effect-One", "effect", effectName(fx.get(0))), List.of());
    }

    private static String effectName(org.bukkit.potion.PotionEffect e) {
        StringBuilder b = new StringBuilder();
        for (String w : e.getType().getKey().getKey().split("_")) { if (b.length() > 0) b.append(' '); b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)); }
        String[] roman = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        int lvl = e.getAmplifier() + 1;
        b.append(' ').append(lvl <= roman.length ? roman[lvl - 1] : String.valueOf(lvl));
        if (!e.isInfinite()) b.append(" (").append(e.getDuration() / 20).append("s)");
        return b.toString();
    }

    private ItemStack nexoButton(String key, String nexoId, Material fallback) {
        ItemStack it = Hooks.nexoItem(plugin.getConfig().getString("Menus.Nexo." + key, nexoId));
        return it != null ? it : new ItemStack(fallback);
    }
    private ItemStack nexoButton(String key, String nexoId, Material fallback, String langKey, Object... kv) {
        return named(nexoButton(key, nexoId, fallback), lang.text("Menus." + langKey + ".Name", kv), lang.lore("Menus." + langKey + ".Lore", kv));
    }

    private static void put(Menu m, int slot, ItemStack it) { if (it != null) m.inv.setItem(slot, it); }

    /** old -> Next -> new; just old when nothing changes. Something on one side only shows "No <part>" on the other; nothing on either side leaves the row blank. */
    private void pair(Menu m, int slot, ItemStack old, ItemStack now, String part) {
        if (old == null && now == null) return;
        // Unchanged between the tiers: only the left item, no arrow (the owner, 2026-11-14).
        if (old != null && old.equals(now)) { m.inv.setItem(slot, old); return; }
        String partName = lang.raw("Menus.Preview.Parts." + part);
        m.inv.setItem(slot, old != null ? old : button(mat("Preview-None", Material.BARRIER), "Preview.None", "part", partName));
        m.inv.setItem(slot + 1, nexoButton("Next", "button_next", Material.ARROW, "Preview.Next"));
        m.inv.setItem(slot + 2, now != null ? now : button(mat("Preview-None", Material.BARRIER), "Preview.None", "part", partName));
    }

    /** Row 6: cancel, the price book, and buy/upgrade - or Locked when the kit needs a rank the player lacks. confirmKey null = highest tier. */
    private void bottom(Menu m, Player p, PlayerData d, Kit k, Kit.Tier t, int tier, String confirmKey, Runnable back) {
        set(m, 47, nexoButton("Cancel", "button_deny", Material.RED_CONCRETE, "Preview.Cancel"), (pl, c) -> back.run());
        double coins = game.balance(d);
        List<Component> info = new ArrayList<>();
        if (k.soupFill) info.add(lang.text("Menus.Upgrade.Soup-Line"));
        for (Game.Requirement r : game.requirements(p, d, k, t))
            info.add(lang.text(r.met() ? "Menus.Kit.Requirement-Met" : "Menus.Kit.Requirement-Missing", "requirement", r.text(), "progress", r.progress()));
        Object[] kv = {"cost", Game.num(t.cost), "coins", Game.num(coins), "have_color", coins >= t.cost ? "&a" : "&c", "tier", tier};
        List<Component> lore = new ArrayList<>(lang.lore("Menus.Preview.Price.Lore", kv));   // "You have: N" under the price (the owner, 2026-11-14)
        lore.addAll(info);
        set(m, 49, named(new ItemStack(mat("Info", Material.BOOK)), lang.text("Menus.Preview.Price.Name", kv), lore), null);
        if (confirmKey == null) set(m, 51, button(mat("Max-Tier", Material.BARRIER), "Upgrade.Max"), null);
        else if (!k.permission.isEmpty() && !p.hasPermission(k.permission))
            set(m, 51, nexoButton("Locked", "button_locked", Material.BARRIER, "Preview.Locked", "rank", rankName(k.permission)), null);
        else set(m, 51, nexoButton("Accept", "button_accept", Material.LIME_CONCRETE, confirmKey, "cost", Game.num(t.cost)), (pl, c) -> {
            game.buyNext(pl, k);
            back.run();
        });
        Material fillMat = k.filler == null ? null : Material.matchMaterial(k.filler);
        if (fillMat == null || !fillMat.isItem() || fillMat.isAir()) {
            if (k.filler != null) plugin.getLogger().warning("Kit " + k.id + ": Filler '" + k.filler + "' is not an item - using the default");
            fillMat = mat("Preview-Filler", Material.BLACK_STAINED_GLASS_PANE);
        }
        ItemStack pane = named(new ItemStack(fillMat), Component.text(" "), List.of());
        for (int i = 0; i < m.inv.getSize(); i++) if (m.inv.getItem(i) == null) m.inv.setItem(i, pane);
    }

    /** kitpvp.premium -> VIP, kitpvp.mvp -> MVP: lang.yml Menus.Preview.Ranks, keyed on the text after the last dot. */
    private String rankName(String permission) {
        String key = permission.substring(permission.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String r = lang.raw("Menus.Preview.Ranks." + key);
        return r == null || r.isEmpty() || r.equals("Menus.Preview.Ranks." + key) ? lang.raw("Requirements.Permission") : r;
    }

    private List<String> mergedAbilities(Kit k, Kit.Tier t) {
        List<String> l = new ArrayList<>(k.abilities);
        for (String a : t.abilities) if (!l.contains(a)) l.add(a);
        return l;
    }

    /** Where an inventory slot sits in the 6-row item editor: hotbar on top, armour and offhand in row 5. */
    private static int editorSlot(int invSlot) {
        return switch (invSlot) {
            case Kit.HELMET -> 36; case Kit.CHESTPLATE -> 37; case Kit.LEGGINGS -> 38; case Kit.BOOTS -> 39; case Kit.OFFHAND -> 40;
            default -> invSlot;
        };
    }
    private static int invSlot(int editorSlot) {
        return switch (editorSlot) {
            case 36 -> Kit.HELMET; case 37 -> Kit.CHESTPLATE; case 38 -> Kit.LEGGINGS; case 39 -> Kit.BOOTS; case 40 -> Kit.OFFHAND;
            default -> editorSlot;
        };
    }

    // ================================================================ admins: the kit creator

    public void openEditorList(Player p, int page) {
        Menu m = menu(6, "Menus.Editor.List-Title", "page", page);
        List<Kit> all = new ArrayList<>(game.kits.values());
        int start = (page - 1) * 45;
        for (int i = 0; i < 45 && start + i < all.size(); i++) {
            Kit k = all.get(start + i);
            ItemStack icon = Items.parse(k.icon);
            if (icon == null) icon = new ItemStack(Material.BARRIER);
            set(m, i, named(icon, Lang.color(k.name), lang.lore("Menus.Editor.Kit-Entry.Lore", "id", k.id, "tiers", k.tiers.size(), "enabled", k.enabled)), (pl, c) -> {
                if (c == ClickType.DROP || c == ClickType.CONTROL_DROP) {
                    game.removeKit(k);
                    lang.send(pl, "Editor.Kit-Removed", "kit", k.id);
                    openEditorList(pl, page);
                } else openKitSettings(pl, k);
            });
        }
        fill(m, 45, 53);
        if (page > 1) set(m, 45, button(mat("Previous", Material.ARROW), "Previous"), (pl, c) -> openEditorList(pl, page - 1));
        if (start + 45 < all.size()) set(m, 53, button(mat("Next", Material.ARROW), "Next"), (pl, c) -> openEditorList(pl, page + 1));
        set(m, 49, button(mat("Create", Material.EMERALD_BLOCK), "Editor.Create"), (pl, c) -> prompt(pl, "Editor.Prompt.Kit-Id", id -> {
            if (id == null) { openEditorList(pl, page); return; }
            String clean = id.replaceAll("[^A-Za-z0-9_+-]", "_");
            if (game.kit(clean) != null) { lang.send(pl, "Editor.Kit-Exists", "kit", clean); openEditorList(pl, page); return; }
            Kit k = new Kit(clean);
            Kit.Tier t = new Kit.Tier();
            t.items.put(0, "IRON_SWORD");
            k.tiers.put(1, t);
            game.saveKit(k);
            lang.send(pl, "Editor.Kit-Created", "kit", clean);
            openKitSettings(pl, k);
        }));
        p.openInventory(m.inv);
    }

    public void openKitSettings(Player p, Kit k) {
        Menu m = menu(4, "Menus.Editor.Kit-Title", "kit", k.id);
        fill(m, 0, 35);
        ItemStack icon = Items.parse(k.icon);
        set(m, 10, named(icon == null ? new ItemStack(Material.BARRIER) : icon, lang.text("Menus.Editor.Icon.Name"), lang.lore("Menus.Editor.Icon.Lore")), (pl, c) -> {
            ItemStack hand = pl.getInventory().getItemInMainHand();
            if (hand.getType().isAir()) { lang.send(pl, "Editor.Hold-Item"); return; }
            ItemStack one = hand.clone(); one.setAmount(1);
            k.icon = Items.serialize(one);
            game.saveKit(k);
            openKitSettings(pl, k);
        });
        set(m, 11, button(Material.NAME_TAG, "Editor.Name", "name", k.name), (pl, c) -> prompt(pl, "Editor.Prompt.Name", s -> {
            if (s != null) { k.name = s; game.saveKit(k); }
            openKitSettings(pl, k);
        }));
        set(m, 12, button(Material.CLOCK, "Editor.Cooldown", "cooldown", k.cooldown), (pl, c) -> prompt(pl, "Editor.Prompt.Cooldown", s -> {
            if (s != null) try { k.cooldown = Math.max(0, Integer.parseInt(s)); game.saveKit(k); } catch (NumberFormatException e) { lang.send(pl, "Editor.Not-A-Number"); }
            openKitSettings(pl, k);
        }));
        set(m, 13, button(Material.TRIPWIRE_HOOK, "Editor.Permission", "permission", k.permission.isEmpty() ? lang.raw("Editor.None") : k.permission,
                "slot", k.slot < 0 ? lang.raw("Editor.Auto") : String.valueOf(k.slot), "page", k.page), (pl, c) -> {
            if (c.isRightClick()) prompt(pl, "Editor.Prompt.Slot", s -> {
                if (s != null) {
                    if (s.equalsIgnoreCase(lang.raw("Editor.Auto"))) k.slot = -1;
                    else try {
                        String[] a = s.split("\\s+");
                        k.slot = Integer.parseInt(a[0]);
                        if (a.length > 1) k.page = Math.max(1, Integer.parseInt(a[1]));
                    } catch (NumberFormatException e) { lang.send(pl, "Editor.Not-A-Number"); }
                    game.saveKit(k);
                }
                openKitSettings(pl, k);
            });
            else prompt(pl, "Editor.Prompt.Permission", s -> {
                if (s != null) { k.permission = s.equalsIgnoreCase(lang.raw("Editor.None")) ? "" : s; game.saveKit(k); }
                openKitSettings(pl, k);
            });
        });
        set(m, 14, button(k.soupFill ? Material.MUSHROOM_STEW : Material.BOWL, k.soupFill ? "Editor.Soup-On" : "Editor.Soup-Off"), (pl, c) -> {
            k.soupFill = !k.soupFill; game.saveKit(k); openKitSettings(pl, k);
        });
        set(m, 15, button(k.enabled ? Material.LIME_DYE : Material.GRAY_DYE, k.enabled ? "Editor.Enabled-On" : "Editor.Enabled-Off"), (pl, c) -> {
            k.enabled = !k.enabled; game.saveKit(k); openKitSettings(pl, k);
        });
        List<Component> dl = new ArrayList<>();
        for (String s : k.description) dl.add(Lang.color(s));
        dl.addAll(lang.lore("Menus.Editor.Description.Lore"));
        set(m, 16, named(new ItemStack(Material.WRITABLE_BOOK), lang.text("Menus.Editor.Description.Name"), dl), (pl, c) -> {
            if (c.isRightClick()) { k.description.clear(); game.saveKit(k); openKitSettings(pl, k); }
            else prompt(pl, "Editor.Prompt.Description", s -> { if (s != null) { k.description.add(s); game.saveKit(k); } openKitSettings(pl, k); });
        });
        set(m, 21, button(Material.CHEST, "Editor.Tiers", "tiers", k.tiers.size()), (pl, c) -> openTierList(pl, k));
        set(m, 22, button(Material.BLAZE_POWDER, "Editor.Abilities", "abilities", k.abilities.isEmpty() ? lang.raw("Editor.None") : String.join(", ", k.abilities)),
                (pl, c) -> openAbilityPicker(pl, k, 0, 1));
        List<Component> sl = new ArrayList<>();
        k.scripts.forEach((key, v) -> { for (String a : v) sl.add(lang.text("Menus.Editor.Scripts.Line", "trigger", key, "action", a)); });
        sl.addAll(lang.lore("Menus.Editor.Scripts.Lore", "file", "plugins/KitPvP/kits/" + k.id + ".yml"));
        set(m, 23, named(new ItemStack(Material.COMMAND_BLOCK), lang.text("Menus.Editor.Scripts.Name"), sl), null);
        set(m, 31, button(mat("Back", Material.ARROW), "Back"), (pl, c) -> openEditorList(pl, 1));
        p.openInventory(m.inv);
    }

    public void openTierList(Player p, Kit k) {
        Menu m = menu(3, "Menus.Editor.Tiers-Title", "kit", k.id);
        fill(m, 0, 26);
        int slot = 0;
        for (Map.Entry<Integer, Kit.Tier> e : k.tiers.entrySet()) {
            if (slot >= 18) break;
            int no = e.getKey();
            Kit.Tier t = e.getValue();
            set(m, slot++, button(Material.ENDER_CHEST, "Editor.Tier-Entry", "tier", no, "name", k.tierName(no), "cost", Game.num(t.cost), "items", t.items.size(),
                    "requirements", t.requirements.size()), (pl, c) -> openTier(pl, k, no));
        }
        int next = k.maxTier() + 1;
        set(m, 20, button(Material.LIME_CONCRETE, "Editor.Add-Tier", "tier", next), (pl, c) -> {
            Kit.Tier last = k.tiers.lastEntry().getValue().copy();
            last.name = ""; last.requirements.clear();
            k.tiers.put(next, last);
            game.saveKit(k);
            openTierList(pl, k);
        });
        set(m, 24, button(mat("Back", Material.ARROW), "Back"), (pl, c) -> openKitSettings(pl, k));
        p.openInventory(m.inv);
    }

    public void openTier(Player p, Kit k, int no) {
        Kit.Tier t = k.tiers.get(no);
        if (t == null) { openTierList(p, k); return; }
        Menu m = menu(3, "Menus.Editor.Tier-Title", "kit", k.id, "tier", no);
        fill(m, 0, 26);
        set(m, 10, button(Material.CHEST, "Editor.Tier-Items", "items", t.items.size()), (pl, c) -> openTierContents(pl, k, no));
        set(m, 11, button(Material.GOLD_INGOT, "Editor.Tier-Cost", "cost", Game.num(t.cost)), (pl, c) -> prompt(pl, "Editor.Prompt.Cost", s -> {
            if (s != null) try { t.cost = Math.max(0, Double.parseDouble(s)); game.saveKit(k); } catch (NumberFormatException e) { lang.send(pl, "Editor.Not-A-Number"); }
            openTier(pl, k, no);
        }, "tier", no));
        set(m, 12, button(Material.NAME_TAG, "Editor.Tier-Name", "name", t.name.isEmpty() ? k.name : t.name), (pl, c) -> prompt(pl, "Editor.Prompt.Tier-Name", s -> {
            if (s != null) { t.name = s.equalsIgnoreCase(lang.raw("Editor.None")) ? "" : s; game.saveKit(k); }
            openTier(pl, k, no);
        }));
        set(m, 13, button(Material.POTION, "Editor.Tier-Effects", "effects", t.effects.isEmpty() ? lang.raw("Editor.None") : String.join(", ", t.effects)), (pl, c) -> prompt(pl, "Editor.Prompt.Effects", s -> {
            if (s != null) {
                t.effects.clear();
                if (!s.equalsIgnoreCase(lang.raw("Editor.None"))) for (String fx : s.split("\\s*,\\s*")) {
                    if (Scripts.parseEffect(fx) != null) t.effects.add(fx); else lang.send(pl, "Editor.Unknown-Effect", "effect", fx);
                }
                game.saveKit(k);
            }
            openTier(pl, k, no);
        }));
        List<Component> rl = new ArrayList<>();
        for (String r : t.requirements) rl.add(lang.text("Menus.Editor.Tier-Requirements.Line", "requirement", r));
        rl.addAll(lang.lore("Menus.Editor.Tier-Requirements.Lore"));
        set(m, 14, named(new ItemStack(Material.PAPER), lang.text("Menus.Editor.Tier-Requirements.Name"), rl), (pl, c) -> {
            if (c.isRightClick()) { t.requirements.clear(); game.saveKit(k); openTier(pl, k, no); }
            else prompt(pl, "Editor.Prompt.Requirement", s -> { if (s != null) { t.requirements.add(s); game.saveKit(k); } openTier(pl, k, no); });
        });
        set(m, 15, button(Material.BLAZE_POWDER, "Editor.Abilities", "abilities", t.abilities.isEmpty() ? lang.raw("Editor.None") : String.join(", ", t.abilities)),
                (pl, c) -> openAbilityPicker(pl, k, no, 1));
        if (k.tiers.size() > 1) set(m, 16, button(Material.TNT, "Editor.Tier-Delete", "tier", no), (pl, c) -> {
            if (c != ClickType.DROP && c != ClickType.CONTROL_DROP) return;
            k.tiers.remove(no);
            game.saveKit(k);
            lang.send(pl, "Editor.Tier-Removed", "tier", no, "kit", k.id);
            openTierList(pl, k);
        });
        set(m, 22, button(mat("Back", Material.ARROW), "Back"), (pl, c) -> openTierList(pl, k));
        p.openInventory(m.inv);
    }

    /** The chest you fill: rows 1-4 are the inventory (top row = hotbar), row 5 = helmet, chestplate, leggings, boots, offhand. */
    public void openTierContents(Player p, Kit k, int no) { openTierContents(p, k, no, null); }

    /** draft: editor slot -> item, the unsaved layout when coming back from the ability list. */
    private void openTierContents(Player p, Kit k, int no, Map<Integer, ItemStack> draft) {
        Kit.Tier t = k.tiers.get(no);
        if (t == null) { openTierList(p, k); return; }
        Menu m = menu(6, "Menus.Editor.Items-Title", "kit", k.id, "tier", no);
        m.ownInventoryFree = true;
        for (int i = 0; i <= 40; i++) m.free.add(i);
        if (draft != null) draft.forEach((i, it) -> m.inv.setItem(i, it));
        else for (Map.Entry<Integer, Object> e : t.items.entrySet()) {
            ItemStack it = Items.parse(e.getValue());
            if (it != null) m.inv.setItem(editorSlot(e.getKey()), it);
        }
        // empty slots are panes so Q on one can be seen: it opens the premade abilities (the owner, 2026-11-14)
        m.placeholders = true;
        refillEmpty(m);
        m.onEmptyDrop = (pl, slot) -> {
            Map<Integer, ItemStack> now = new HashMap<>();
            for (int i = 0; i <= 40; i++) { ItemStack it = m.inv.getItem(i); if (it != null && !it.getType().isAir() && !isPlaceholder(it)) now.put(i, it.clone()); }
            openSlotAbilities(pl, k, no, now, slot, 1);
        };
        m.onClose = () -> p.getInventory().forEach(it -> { if (isPlaceholder(it)) it.setAmount(0); });
        fill(m, 41, 44);
        String[] labels = {"Helmet", "Chestplate", "Leggings", "Boots", "Offhand"};
        for (int i = 0; i < 5; i++) set(m, 45 + i, button(Material.WHITE_STAINED_GLASS_PANE, "Editor.Slot-" + labels[i]), null);
        fill(m, 50, 53);
        set(m, 51, button(Material.RED_CONCRETE, "Editor.Items-Cancel"), (pl, c) -> openTier(pl, k, no));
        set(m, 52, button(Material.BOOK, "Editor.Items-Tip"), null);
        set(m, 53, button(Material.LIME_CONCRETE, "Editor.Items-Save", "tier", no), (pl, c) -> {
            java.util.Set<Integer> reserved = new java.util.HashSet<>();
            for (Map.Entry<Integer, Object> e : t.items.entrySet()) if (Items.isEmptyMarker(e.getValue())) reserved.add(e.getKey());
            t.items.clear();
            for (int i = 0; i <= 40; i++) {
                ItemStack it = m.inv.getItem(i);
                if (it == null || it.getType().isAir() || isPlaceholder(it)) { if (reserved.contains(invSlot(i))) t.items.put(invSlot(i), "EMPTY"); continue; }
                if (k.soupFill && i < 36 && game.isSoup(it) && !it.hasItemMeta()) continue;   // Soup-Fill adds these itself
                Object spec = Items.serialize(it);
                if (spec != null) t.items.put(invSlot(i), spec);
            }
            game.saveKit(k);
            lang.send(pl, "Editor.Items-Saved", "count", t.items.size(), "kit", k.id, "tier", no);
            openTier(pl, k, no);
        });
        p.openInventory(m.inv);
    }

    /** Q on an empty slot in the kit editor: pick a premade ability; its item, bound to it, goes in that slot. */
    private void openSlotAbilities(Player p, Kit k, int no, Map<Integer, ItemStack> draft, int slot, int page) {
        Menu m = menu(6, "Menus.Editor.Slot-Abilities-Title", "page", page);
        List<Scripts.Ability> all = new ArrayList<>(plugin.scripts.abilities.values());
        int start = (page - 1) * 45;
        for (int i = 0; i < 45 && start + i < all.size(); i++) {
            Scripts.Ability a = all.get(start + i);
            List<Component> lore = new ArrayList<>();
            for (String s : a.description) lore.add(Lang.color("&7" + s));
            lore.addAll(lang.lore("Menus.Editor.Slot-Ability.Lore", "id", a.id, "trigger", a.trigger, "cooldown", Game.num(a.cooldown)));
            set(m, i, named(new ItemStack(a.item != null ? a.item : Material.BLAZE_POWDER), Lang.color(a.name), lore), (pl, c) -> {
                ItemStack it = new ItemStack(a.item != null ? a.item : Material.BLAZE_POWDER);
                ItemMeta im = it.getItemMeta();
                im.displayName(Lang.color(a.name).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                List<Component> l = new ArrayList<>();
                for (String s : a.description) l.add(Lang.color("&7" + s).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                im.lore(l);
                im.getPersistentDataContainer().set(Items.ABILITY, PersistentDataType.STRING, a.id);
                it.setItemMeta(im);
                draft.put(slot, it);
                openTierContents(pl, k, no, draft);
            });
        }
        fill(m, 45, 53);
        if (page > 1) set(m, 45, button(mat("Previous", Material.ARROW), "Previous"), (pl, c) -> openSlotAbilities(pl, k, no, draft, slot, page - 1));
        if (start + 45 < all.size()) set(m, 53, button(mat("Next", Material.ARROW), "Next"), (pl, c) -> openSlotAbilities(pl, k, no, draft, slot, page + 1));
        set(m, 49, button(mat("Back", Material.ARROW), "Back"), (pl, c) -> openTierContents(pl, k, no, draft));
        p.openInventory(m.inv);
    }

    /** tier 0 = the kit's own list; -1 = bind to the item in hand. */
    public void openAbilityPicker(Player p, Kit k, int tierNo, int page) {
        Menu m = menu(6, "Menus.Editor.Abilities-Title", "page", page);
        List<Scripts.Ability> all = new ArrayList<>(plugin.scripts.abilities.values());
        List<String> chosen = tierNo < 0 ? new ArrayList<>() : tierNo == 0 ? k.abilities : k.tiers.get(tierNo).abilities;
        if (tierNo < 0) { String b = Items.abilityOf(p.getInventory().getItemInMainHand()); if (b != null) chosen.add(b); }
        int start = (page - 1) * 45;
        for (int i = 0; i < 45 && start + i < all.size(); i++) {
            Scripts.Ability a = all.get(start + i);
            boolean on = chosen.stream().anyMatch(a.id::equalsIgnoreCase);
            Material icon = a.item != null ? a.item : (on ? Material.LIME_DYE : Material.GRAY_DYE);
            List<Component> lore = new ArrayList<>();
            for (String s : a.description) lore.add(Lang.color(s));
            lore.addAll(lang.lore(on ? "Menus.Editor.Ability-Entry.Lore-On" : "Menus.Editor.Ability-Entry.Lore-Off", "id", a.id, "trigger", a.trigger, "cooldown", Game.num(a.cooldown)));
            ItemStack it = named(new ItemStack(icon), Lang.color(a.name), lore);
            if (on) { ItemMeta im = it.getItemMeta(); im.setEnchantmentGlintOverride(true); it.setItemMeta(im); }
            set(m, i, it, (pl, c) -> {
                if (tierNo < 0) {
                    ItemStack hand = pl.getInventory().getItemInMainHand();
                    if (hand.getType().isAir()) { lang.send(pl, "Editor.Hold-Item"); return; }
                    ItemMeta im = hand.getItemMeta();
                    if (on) im.getPersistentDataContainer().remove(Items.ABILITY);
                    else im.getPersistentDataContainer().set(Items.ABILITY, PersistentDataType.STRING, a.id);
                    hand.setItemMeta(im);
                } else {
                    if (on) chosen.removeIf(a.id::equalsIgnoreCase); else chosen.add(a.id);
                    game.saveKit(k);
                }
                openAbilityPicker(pl, k, tierNo, page);
            });
        }
        fill(m, 45, 53);
        if (page > 1) set(m, 45, button(mat("Previous", Material.ARROW), "Previous"), (pl, c) -> openAbilityPicker(pl, k, tierNo, page - 1));
        if (start + 45 < all.size()) set(m, 53, button(mat("Next", Material.ARROW), "Next"), (pl, c) -> openAbilityPicker(pl, k, tierNo, page + 1));
        set(m, 49, button(mat("Back", Material.ARROW), "Back"), (pl, c) -> {
            if (tierNo < 0) openItemEditor(pl); else if (tierNo == 0) openKitSettings(pl, k); else openTier(pl, k, tierNo);
        });
        p.openInventory(m.inv);
    }

    // ================================================================ admins: the item customizer

    public void openItemEditor(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) { lang.send(p, "Editor.Hold-Item"); return; }
        Menu m = menu(4, "Menus.Item.Title");
        fill(m, 0, 35);
        m.inv.setItem(4, hand.clone());
        ItemMeta meta = hand.getItemMeta();
        set(m, 10, button(Material.NAME_TAG, "Item.Rename", "name", meta.hasDisplayName() ? Lang.plain(meta.displayName()) : lang.raw("Editor.None")), (pl, c) -> {
            if (c.isRightClick()) { edit(pl, im -> im.displayName(null)); openItemEditor(pl); }
            else prompt(pl, "Editor.Prompt.Item-Name", s -> { if (s != null) edit(pl, im -> im.displayName(Lang.color(s))); openItemEditor(pl); });
        });
        set(m, 11, button(Material.WRITABLE_BOOK, "Item.Lore", "lines", meta.hasLore() ? meta.lore().size() : 0), (pl, c) -> {
            if (c.isRightClick()) { edit(pl, im -> im.lore(null)); openItemEditor(pl); }
            else if (c.isShiftClick()) {
                edit(pl, im -> { List<Component> l = im.hasLore() ? new ArrayList<>(im.lore()) : new ArrayList<>(); if (!l.isEmpty()) l.remove(l.size() - 1); im.lore(l.isEmpty() ? null : l); });
                openItemEditor(pl);
            } else prompt(pl, "Editor.Prompt.Item-Lore", s -> {
                if (s != null) edit(pl, im -> { List<Component> l = im.hasLore() ? new ArrayList<>(im.lore()) : new ArrayList<>(); l.add(Lang.color(s)); im.lore(l); });
                openItemEditor(pl);
            });
        });
        set(m, 12, button(Material.ENCHANTED_BOOK, "Item.Enchants"), (pl, c) -> openEnchants(pl, 1));
        set(m, 13, button(Material.PAINTING, "Item.Model", "model", meta.hasItemModel() ? meta.getItemModel().toString() : lang.raw("Editor.None")), (pl, c) -> {
            if (c.isRightClick()) { edit(pl, im -> im.setItemModel(null)); openItemEditor(pl); }
            else prompt(pl, "Editor.Prompt.Item-Model", s -> {
                if (s != null) {
                    NamespacedKey key = NamespacedKey.fromString((s.contains(":") ? s : "nexo:" + s).toLowerCase(Locale.ROOT));
                    if (key == null) lang.send(pl, "Editor.Bad-Model"); else edit(pl, im -> im.setItemModel(key));
                }
                openItemEditor(pl);
            });
        });
        set(m, 14, button(Material.LEATHER_CHESTPLATE, "Item.Armor-Model", "model", meta.hasEquippable() && meta.getEquippable().getModel() != null ? meta.getEquippable().getModel().toString() : lang.raw("Editor.None")), (pl, c) -> {
            if (c.isRightClick()) { edit(pl, im -> im.setEquippable(null)); openItemEditor(pl); }
            else prompt(pl, "Editor.Prompt.Item-Armor-Model", s -> {
                if (s != null) {
                    NamespacedKey key = NamespacedKey.fromString((s.contains(":") ? s : "nexo:" + s).toLowerCase(Locale.ROOT));
                    ItemStack h = pl.getInventory().getItemInMainHand();
                    if (key == null) lang.send(pl, "Editor.Bad-Model");
                    else edit(pl, im -> {
                        EquippableComponent eq = im.getEquippable();
                        org.bukkit.inventory.EquipmentSlot slot = h.getType().getEquipmentSlot();
                        eq.setSlot(slot == org.bukkit.inventory.EquipmentSlot.HAND ? org.bukkit.inventory.EquipmentSlot.HEAD : slot);
                        eq.setModel(key);
                        im.setEquippable(eq);
                    });
                }
                openItemEditor(pl);
            });
        });
        String bound = Items.abilityOf(hand);
        set(m, 15, button(Material.BLAZE_POWDER, "Item.Ability", "ability", bound == null ? lang.raw("Editor.None") : bound), (pl, c) -> openAbilityPicker(pl, null, -1, 1));
        set(m, 16, button(meta.isUnbreakable() ? Material.ANVIL : Material.CHIPPED_ANVIL, meta.isUnbreakable() ? "Item.Unbreakable-On" : "Item.Unbreakable-Off"), (pl, c) -> {
            edit(pl, im -> im.setUnbreakable(!im.isUnbreakable())); openItemEditor(pl);
        });
        org.bukkit.inventory.meta.Damageable dmg = meta instanceof org.bukkit.inventory.meta.Damageable x ? x : null;
        int maxDur = dmg != null && dmg.hasMaxDamage() ? dmg.getMaxDamage() : hand.getType().getMaxDurability();
        if (maxDur > 0 && dmg != null)
            set(m, 22, button(Material.GRINDSTONE, "Item.Durability", "left", maxDur - dmg.getDamage(), "max", maxDur), (pl, c) ->
                    prompt(pl, "Editor.Prompt.Item-Durability", s -> {
                        if (s != null) try {
                            int left = Math.max(1, Math.min(maxDur, Integer.parseInt(s.trim())));
                            edit(pl, im -> { if (im instanceof org.bukkit.inventory.meta.Damageable dm) dm.setDamage(maxDur - left); });
                        } catch (NumberFormatException ignored) {}
                        openItemEditor(pl);
                    }, "max", maxDur));
        set(m, 21, button(Material.GOLDEN_APPLE, "Item.Duplicate"), (pl, c) -> pl.getInventory().addItem(pl.getInventory().getItemInMainHand().clone()));
        set(m, 23, button(Material.ENDER_EYE, "Item.Get-Custom"), (pl, c) -> prompt(pl, "Editor.Prompt.Custom-Item", s -> {
            if (s != null) {
                ItemStack it = Items.base(s.contains(":") ? s : "nexo:" + s);
                if (it == null) lang.send(pl, "Editor.No-Such-Item", "item", s); else { pl.getInventory().addItem(it); lang.send(pl, "Editor.Item-Given", "item", s); }
            }
        }));
        set(m, 31, button(Material.BARRIER, "Close"), (pl, c) -> pl.closeInventory());
        p.openInventory(m.inv);
    }

    private void edit(Player p, Consumer<ItemMeta> change) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) return;
        ItemMeta im = hand.getItemMeta();
        change.accept(im);
        hand.setItemMeta(im);
    }

    public void openEnchants(Player p, int page) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) { p.closeInventory(); return; }
        Menu m = menu(6, "Menus.Item.Enchants-Title", "page", page);
        List<Enchantment> all = new ArrayList<>();
        Registry.ENCHANTMENT.forEach(all::add);
        all.sort(java.util.Comparator.comparing(en -> en.getKey().getKey()));
        int start = (page - 1) * 45;
        for (int i = 0; i < 45 && start + i < all.size(); i++) {
            Enchantment en = all.get(start + i);
            int lvl = hand.getEnchantmentLevel(en);
            ItemStack it = button(lvl > 0 ? Material.ENCHANTED_BOOK : Material.BOOK, "Item.Enchant-Entry", "enchant", en.getKey().getKey(), "level", lvl);
            set(m, i, it, (pl, c) -> {
                ItemStack h = pl.getInventory().getItemInMainHand();
                if (h.getType().isAir()) return;
                int cur = h.getEnchantmentLevel(en);
                if (c.isRightClick()) h.removeEnchantment(en);
                else h.addUnsafeEnchantment(en, Math.min(255, cur + (c.isShiftClick() ? 5 : 1)));
                openEnchants(pl, page);
            });
        }
        fill(m, 45, 53);
        if (page > 1) set(m, 45, button(mat("Previous", Material.ARROW), "Previous"), (pl, c) -> openEnchants(pl, page - 1));
        if (start + 45 < all.size()) set(m, 53, button(mat("Next", Material.ARROW), "Next"), (pl, c) -> openEnchants(pl, page + 1));
        set(m, 49, button(mat("Back", Material.ARROW), "Back"), (pl, c) -> openItemEditor(pl));
        p.openInventory(m.inv);
    }

    // ================================================================ crates

    // ================================================================ players: duels (lobby item frame)

    /** Duel a player, and one button per queue (Duels.Queues). Clicking your own queue again leaves it. */
    public void openDuels(Player p) {
        Menu m = menu(3, "Menus.Duels.Title");
        set(m, 10, button(mat("Duel-Player", Material.PLAYER_HEAD), "Duels.Player"), (pl, c) -> openDuelPlayers(pl, 1));
        List<String> ids = game.queueIds();
        String mine = game.queueOf(p);
        int[] slots = ids.size() <= 3 ? new int[]{12, 14, 16} : new int[]{12, 13, 14, 15, 16};
        for (int i = 0; i < ids.size() && i < slots.length; i++) {
            String id = ids.get(i);
            Material icon = Material.matchMaterial(plugin.getConfig().getString("Duels.Queues." + id + ".Item", "IRON_SWORD"));
            boolean in = id.equals(mine), open = game.queueHasArena(id);
            ItemStack it = named(new ItemStack(icon == null ? Material.IRON_SWORD : icon), lang.text("Menus.Duels.Queues." + id + ".Name"),
                    lang.lore(in ? "Menus.Duels.Queue-In" : open ? "Menus.Duels.Queue-Join" : "Menus.Duels.Queue-Closed",
                            "waiting", game.queueSize(id), "queue", game.queueName(id)));
            List<Component> desc = lang.lore("Menus.Duels.Queues." + id + ".Lore");
            if (!desc.isEmpty()) { ItemMeta im = it.getItemMeta(); List<Component> l = new ArrayList<>(desc); l.addAll(im.lore()); im.lore(l); it.setItemMeta(im); }
            if (in) { ItemMeta im = it.getItemMeta(); im.setEnchantmentGlintOverride(true); it.setItemMeta(im); }
            set(m, slots[i], it, (pl, c) -> {
                game.joinQueue(pl, id);
                // The last player in starts the duel at once: close instead of reopening this menu over it (the owner, 2026-11-14).
                Session ps = game.session(pl);
                if (ps != null && ps.duel == null) openDuels(pl); else pl.closeInventory();
            });
        }
        fill(m, 0, 26);
        p.openInventory(m.inv);
    }

    /** Every other player in KitPvP: click to challenge them, or to accept their challenge. */
    public void openDuelPlayers(Player p, int page) {
        List<Player> others = new ArrayList<>();
        for (Session s : game.sessions.values()) if (s.player != p) others.add(s.player);
        others.sort(java.util.Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        Menu m = menu(6, "Menus.Duels.Players-Title", "page", page);
        int per = 45, from = (page - 1) * per;
        for (int i = from; i < others.size() && i < from + per; i++) {
            Player t = others.get(i);
            Session ts = game.session(t);
            boolean asked = game.hasDuelRequestFrom(p, t);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            if (head.getItemMeta() instanceof org.bukkit.inventory.meta.SkullMeta sm) { sm.setOwningPlayer(t); head.setItemMeta(sm); }
            String state = ts != null && ts.duel != null ? "Menus.Duels.Player-Busy" : asked ? "Menus.Duels.Player-Accept" : "Menus.Duels.Player-Challenge";
            set(m, i - from, named(head, lang.text("Menus.Duels.Player-Name", "player", t.getName()), lang.lore(state, "player", t.getName())), (pl, c) -> {
                pl.closeInventory();
                if (game.hasDuelRequestFrom(pl, t)) game.duelAccept(pl); else game.duelRequest(pl, t);
            });
        }
        fill(m, 45, 53);
        if (others.isEmpty()) set(m, 22, button(mat("Empty", Material.BARRIER), "Duels.Nobody"), null);
        if (page > 1) set(m, 45, button(mat("Previous", Material.ARROW), "Previous"), (pl, c) -> openDuelPlayers(pl, page - 1));
        if (from + per < others.size()) set(m, 53, button(mat("Next", Material.ARROW), "Next"), (pl, c) -> openDuelPlayers(pl, page + 1));
        set(m, 49, button(mat("Back", Material.ARROW), "Back"), (pl, c) -> openDuels(pl));
        p.openInventory(m.inv);
    }

    public void openCrates(Player p) {
        Menu m = menu(3, "Menus.Crates.Title");
        fill(m, 0, 26);
        PlayerData d = game.data(p);
        List<Crates.Crate> all = new ArrayList<>(plugin.crates.crates.values());
        int start = Math.max(0, 13 - all.size() / 2);
        for (int i = 0; i < all.size() && i < 9; i++) {
            Crates.Crate c = all.get(i);
            int keys = plugin.crates.keys(d, c);
            ItemStack icon = Items.parse(c.icon);
            if (icon == null) icon = new ItemStack(Material.CHEST);
            icon.setAmount(Math.max(1, Math.min(64, keys)));
            List<Component> lore = new ArrayList<>();
            for (String line : c.description) lore.add(Lang.color(line));
            lore.addAll(lang.lore("Menus.Crates.Entry-Lore", "keys", keys, "multi", Math.min(Math.max(keys, 1), c.multiOpen),
                    "every", c.everyKills, "swap", lang.raw("Menus.Crates.Swap-Key")));
            set(m, start + i, named(icon, Lang.color(c.name), lore), (pl, click) -> {
                if (plugin.crates.keys(game.data(pl), c) <= 0) { lang.send(pl, "Crates.No-Keys", "crate", c.name); return; }
                if (click == ClickType.SWAP_OFFHAND) openMany(pl, c); else roll(pl, c);
            });
        }
        p.openInventory(m.inv);
    }

    /** One crate, with the reel. The reward is already the player's when the reel starts; closing the menu (Escape) just skips to it. */
    public void roll(Player p, Crates.Crate c) {
        PlayerData d = game.data(p);
        Crates.Result r = plugin.crates.open(p, d, c);
        if (r == null) { lang.send(p, "Crates.No-Keys", "crate", c.name); return; }
        Menu m = menu(3, "Menus.Crates.Roll-Title", "crate", Lang.plain(Lang.color(c.name)));
        fill(m, 0, 8); fill(m, 18, 26);
        ItemStack marker = button(mat("Roll-Marker", Material.HOPPER), "Crates.Marker");
        m.inv.setItem(4, marker); m.inv.setItem(22, marker);
        int steps = c.rollSteps;
        // The reel: the winning item sits where it will stop under the marker after the last step.
        List<ItemStack> reel = new ArrayList<>();
        for (int i = 0; i < steps + 9; i++) reel.add(plugin.crates.decoy(c));
        reel.set(steps + 4, plugin.crates.display(r));
        boolean[] done = {false};
        int[] step = {0};
        int[] wait = {0};
        org.bukkit.scheduler.BukkitTask[] task = new org.bukkit.scheduler.BukkitTask[1];
        Runnable finish = () -> {
            if (done[0]) return;
            done[0] = true;
            if (task[0] != null) task[0].cancel();
            announce(p, c, r);
        };
        m.onClose = finish;
        Runnable draw = () -> { for (int i = 0; i < 9; i++) m.inv.setItem(9 + i, reel.get(step[0] + i)); };
        draw.run();
        p.openInventory(m.inv);
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (done[0]) return;
            if (wait[0]-- > 0) return;
            if (step[0] >= steps) {
                // Stopped: light the winner up, leave it on screen a moment, then back to the crates.
                ItemStack win = m.inv.getItem(13);
                if (win != null) { ItemMeta im = win.getItemMeta(); im.setEnchantmentGlintOverride(true); win.setItemMeta(im); m.inv.setItem(13, win); }
                finish.run();
                Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline() && p.getOpenInventory().getTopInventory().getHolder() == m) openCrates(p); }, 50L);
                return;
            }
            step[0]++;
            draw.run();
            p.playSound(p.getLocation(), plugin.getConfig().getString("Crates.Tick-Sound", "ui.button.click"), 0.6f, 1.6f);
            // Fast at first, slower and slower over the last third.
            int left = steps - step[0];
            wait[0] = left > steps / 3 ? 1 : Math.min(9, 1 + (steps / 3 - left));
        }, 2L, 1L);
    }

    private void announce(Player p, Crates.Crate c, Crates.Result r) {
        lang.send(p, "Crates.Won." + r.type, "crate", c.name, "reward", plugin.crates.text(r), "keys", plugin.crates.keys(game.data(p), c));
        if (p.isOnline()) p.playSound(p.getLocation(), plugin.getConfig().getString("Crates.Win-Sound." + r.type, "entity.player.levelup"), 1f, 1f);
    }

    /** Off-hand key: as many keys as allowed at once, no reel, every result laid out. */
    public void openMany(Player p, Crates.Crate c) {
        PlayerData d = game.data(p);
        int n = Math.min(plugin.crates.keys(d, c), Math.min(c.multiOpen, 45));
        if (n <= 0) { lang.send(p, "Crates.No-Keys", "crate", c.name); return; }
        Menu m = menu(6, "Menus.Crates.Many-Title", "crate", Lang.plain(Lang.color(c.name)), "amount", n);
        double coins = 0; int kits = 0, upgrades = 0;
        for (int i = 0; i < n; i++) {
            Crates.Result r = plugin.crates.open(p, d, c);
            if (r == null) break;
            m.inv.setItem(i, plugin.crates.display(r));
            if (r.kit == null) coins += r.coins;
            else { if (r.type.equals("new-kit")) kits++; else upgrades++; lang.send(p, "Crates.Won." + r.type, "crate", c.name, "reward", plugin.crates.text(r), "keys", plugin.crates.keys(d, c)); }
        }
        lang.send(p, "Crates.Many-Summary", "amount", n, "crate", c.name, "kits", kits, "upgrades", upgrades, "coins", Game.num(coins), "keys", plugin.crates.keys(d, c));
        p.playSound(p.getLocation(), plugin.getConfig().getString("Crates.Win-Sound.new-kit", "entity.player.levelup"), 1f, 1f);
        fill(m, 45, 53);
        set(m, 49, button(mat("Back", Material.ARROW), "Back"), (pl, cl) -> openCrates(pl));
        p.openInventory(m.inv);
    }

    // ================================================================ challenges and achievements

    public void openChallenges(Player p) {
        Menu m = menu(6, "Menus.Challenges.Title");
        PlayerData d = game.data(p);
        Challenges ch = plugin.challenges;
        Challenges.Challenge active = ch.active(d);
        int slot = 0;
        for (Challenges.Challenge c : ch.offered()) {
            if (slot >= 45) break;
            ItemStack icon = Items.parse(c.icon);
            if (icon == null) icon = new ItemStack(Material.PAPER);
            boolean on = active == c, done = ch.done(d, c);
            List<Component> lore = new ArrayList<>();
            for (String line : c.description) lore.add(Lang.color(line));
            lore.addAll(lang.lore("Menus.Challenges.Entry-Lore", "period", lang.raw("Challenges.Periods." + c.period), "goal", Game.num(c.goal), "stat", game.statName(c.stat)));
            for (String id : c.modifiers) {
                Challenges.Modifier mo = ch.modifiers.get(id.toLowerCase(Locale.ROOT));
                if (mo == null) continue;
                lore.add(lang.text("Menus.Challenges.Modifier-Line", "modifier", mo.name));
                for (String line : mo.description) lore.add(Lang.color(line));
            }
            for (String rw : c.rewards) lore.add(lang.text("Menus.Challenges.Reward-Line", "reward", rewardText(rw)));
            lore.addAll(lang.lore(done ? "Menus.Challenges.State-Done" : on ? "Menus.Challenges.State-Active" : "Menus.Challenges.State-Open",
                    "progress", Game.num(Math.min(c.goal, ch.progress(d, c))), "goal", Game.num(c.goal)));
            ItemStack it = named(done ? new ItemStack(mat("Challenge-Done", Material.LIME_DYE)) : icon, Lang.color(c.name), lore);
            if (on) { ItemMeta im = it.getItemMeta(); im.setEnchantmentGlintOverride(true); it.setItemMeta(im); }
            set(m, slot++, it, (pl, click) -> { ch.toggle(pl, game.data(pl), c); openChallenges(pl); });
        }
        fill(m, 45, 53);
        set(m, 45, button(mat("Back", Material.ARROW), "Back"), (pl, c) -> openKits(pl, "kits", 1));
        set(m, 49, button(mat("Achievements", Material.DIAMOND), "Achievements-Button"), (pl, c) -> openAchievements(pl, 1));
        p.openInventory(m.inv);
    }

    /** A reward line in the player's words. */
    private String rewardText(String line) {
        String low = line.toLowerCase(Locale.ROOT);
        if (low.startsWith("coins:")) return lang.raw("Rewards.Text.coins", "coins", line.substring(6).trim());
        if (low.startsWith("keys:")) {
            String[] a = line.substring(5).split(":");
            Crates.Crate c = plugin.crates.crate(a[0].trim());
            return lang.raw("Rewards.Text.keys", "amount", a.length > 1 ? a[1].trim() : "1", "crate", c == null ? a[0] : c.name);
        }
        if (low.startsWith("kit:")) { Kit k = game.kit(line.substring(4).trim()); return lang.raw("Rewards.Text.kit", "kit", k == null ? line.substring(4) : k.name); }
        return lang.raw("Rewards.Text.other");
    }

    public void openAchievements(Player p, int page) {
        Menu m = menu(6, "Menus.Achievements.Title", "page", page);
        PlayerData d = game.data(p);
        record Row(Challenges.Achievement a, int step, List<String> rewards) {}
        // first step of every achievement, then every second step ... so the first row shows each kind once (the owner, 2026-11-14)
        List<List<Row>> per = new ArrayList<>();
        for (Challenges.Achievement a : plugin.challenges.achievements.values()) {
            List<Row> l = new ArrayList<>();
            for (Map.Entry<Integer, List<String>> st : a.steps.entrySet()) l.add(new Row(a, st.getKey(), st.getValue()));
            per.add(l);
        }
        List<Row> rows = new ArrayList<>();
        for (int idx = 0; ; idx++) {
            boolean any = false;
            for (List<Row> l : per) if (idx < l.size()) { rows.add(l.get(idx)); any = true; }
            if (!any) break;
        }
        int start = (page - 1) * 45;
        for (int i = 0; i < 45 && start + i < rows.size(); i++) {
            Row r = rows.get(start + i);
            boolean has = d.achievements.contains(r.a.id + ":" + r.step);
            double have = r.a.stat.equals("coins") ? game.balance(d) : d.stat(r.a.stat);
            ItemStack icon = has ? Items.parse(r.a.icon) : new ItemStack(mat("Achievement-Locked", Material.COAL_BLOCK));
            if (icon == null) icon = new ItemStack(Material.DIAMOND_BLOCK);
            List<Component> lore = new ArrayList<>();
            for (String rw : r.rewards) lore.add(lang.text("Menus.Challenges.Reward-Line", "reward", rewardText(rw)));
            lore.addAll(lang.lore(has ? "Menus.Achievements.Unlocked" : "Menus.Achievements.Locked", "progress", Game.num(Math.min(have, r.step)), "goal", r.step));
            set(m, i, named(icon, Lang.color(r.a.stepName(r.step)), lore), null);
        }
        fill(m, 45, 53);
        if (page > 1) set(m, 45, button(mat("Previous", Material.ARROW), "Previous"), (pl, c) -> openAchievements(pl, page - 1));
        if (start + 45 < rows.size()) set(m, 53, button(mat("Next", Material.ARROW), "Next"), (pl, c) -> openAchievements(pl, page + 1));
        set(m, 49, button(mat("Back", Material.ARROW), "Back"), (pl, c) -> openChallenges(pl));
        p.openInventory(m.inv);
    }

    // ================================================================ soup station

    /** A chest of soup to take from. then(how many were taken) runs when it closes. */
    public void openSoupStation(Player p, Material soup, java.util.function.IntConsumer then) {
        int rows = Math.max(1, Math.min(6, plugin.getConfig().getInt("Soup-Stations.Rows", 3)));
        Menu m = menu(rows, "Menus.Soup-Station.Title");
        m.ownInventoryFree = true;
        for (int i = 0; i < rows * 9; i++) { m.free.add(i); m.inv.setItem(i, new ItemStack(soup)); }
        m.onClose = () -> {
            int left = 0;
            for (ItemStack it : m.inv.getContents()) {
                if (it == null || it.getType().isAir()) continue;
                if (it.getType() == soup && !it.hasItemMeta()) left += it.getAmount();
                else p.getInventory().addItem(it);          // something of the player's own was put in: hand it back
            }
            then.accept(Math.max(0, rows * 9 - left));
        };
        p.openInventory(m.inv);
    }
}
