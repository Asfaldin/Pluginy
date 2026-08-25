package elo.mainplugins.tools.evolving;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.CustomItemService;
import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.util.CustomItemKeys;
import elo.mainplugins.core.util.GuiUtils;
import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generyczny, w pełni konfigurowalny z YAML (ewoluujace-narzedzia.yml) silnik progresji
 * narzędzia - wzorem Kilofa Niflheim (patrz PickaxeSkillManager: poziomy + STAŁE kamienie
 * milowe zamiast losowych ofert kart), tylko że definicja każdego narzędzia (material,
 * enchanty, staty, efekty na kamieniach milowych, cząsteczki) jest DANĄ z pliku, nie
 * hardkodowaną podklasą. Zastępuje dawny system kart (ToolSkillManager + Axe/Hoe/Sword
 * SkillManager) dla WSZYSTKICH narzędzi poza Kilofem Niflheim, który zostaje nietknięty
 * w swoim module (PickaxeSkillManager) - patrz uzasadnienie w rozmowie z użytkownikiem.
 *
 * W ODRÓŻNIENIU od starego systemu narzędzia stąd NIE są przypisane do gracza (brak tagu
 * właściciela) - można je swobodnie wyrzucić, sprzedać na targu, wręczyć innemu graczowi.
 *
 * Custom-id (patrz CustomItemKeys#CUSTOM_ITEM_ID) to jednocześnie klucz w YAML - ten sam
 * mechanizm co custom-items.yml (mainplugins-core), więc sklep/questy rozpoznają te
 * narzędzia identycznie jak każdy inny custom item.
 */
public class EvolvingToolManager implements Listener {

    private static final int AURA_DURATION_TICKS = 1_000_000;

    private final Plugin plugin;
    private final EconomyService economyService;
    private final Map<String, ToolDefinition> definicje = new LinkedHashMap<>();
    private final Set<PotionEffectType> auraTypyZarzadzane = new HashSet<>();

    private final NamespacedKey pkDefId;
    private final NamespacedKey pkLevel;
    private final NamespacedKey pkExp;
    private final NamespacedKey pkPvpModifier;

    public EvolvingToolManager(Plugin plugin, EconomyService economyService) {
        this.plugin = plugin;
        this.economyService = economyService;
        this.pkDefId = new NamespacedKey(plugin, "evo_def_id");
        this.pkLevel = new NamespacedKey(plugin, "evo_level");
        this.pkExp = new NamespacedKey(plugin, "evo_exp");
        this.pkPvpModifier = new NamespacedKey(plugin, "evo_pvp_bonus");
        reload();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickCzastkiOtoczenia, 20L, 10L);
    }

    /** Wczytuje ewoluujace-narzedzia.yml na nowo (kopiuje domyślny zasób tylko przy pierwszym uruchomieniu) - pod /@reloadnarzedzia. */
    public void reload() {
        definicje.clear();
        definicje.putAll(EvolvingToolLoader.load(plugin));

        auraTypyZarzadzane.clear();
        for (ToolDefinition def : definicje.values()) {
            for (ToolEffect fx : wszystkieEfekty(def)) {
                if (fx.typ() == EffectType.AURA_MIKSTURY) {
                    PotionEffectType typ = potionOd(fx.mikstura());
                    if (typ != null) auraTypyZarzadzane.add(typ);
                }
            }
        }
        plugin.getLogger().info("Wczytano " + definicje.size() + " ewoluujących narzędzi z ewoluujace-narzedzia.yml.");
    }

    public Set<String> ids() {
        return Set.copyOf(definicje.keySet());
    }

    // ==================================================== Tworzenie/odczyt ====

    /** Jedyne wejście do tworzenia tych narzędzi (quest rewards, /@dajewoluujace) - poziom 1, bez właściciela. */
    public ItemStack stworz(String id) {
        ToolDefinition def = definicje.get(id);
        if (def == null) return null;

        ItemStack item = new ItemStack(def.material());
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(pkDefId, PersistentDataType.STRING, id);
        pdc.set(pkLevel, PersistentDataType.INTEGER, 1);
        pdc.set(pkExp, PersistentDataType.INTEGER, 0);
        pdc.set(CustomItemKeys.CUSTOM_ITEM_ID, PersistentDataType.STRING, id);
        item.setItemMeta(meta);
        odswiezWyglad(item, def);
        return item;
    }

    /** Czy dany item to zarejestrowane narzędzie tego silnika - pod /@addlvl (MainpluginsTools). */
    public boolean jestNarzedziem(ItemStack item) {
        return definicjaZItemu(item) != null;
    }

    private ToolDefinition definicjaZItemu(ItemStack item) {
        if (item == null || item.getType().isAir() || item.getItemMeta() == null) return null;
        String id = item.getItemMeta().getPersistentDataContainer().get(pkDefId, PersistentDataType.STRING);
        return id != null ? definicje.get(id) : null;
    }

    private int poziomZ(ItemStack item) {
        return item.getItemMeta().getPersistentDataContainer().getOrDefault(pkLevel, PersistentDataType.INTEGER, 1);
    }

    /** NAJWYŻSZY poziom wśród wszystkich narzędzi danej kategorii u gracza - pod ToolsService#poziomSiekiery itd. Sprawdza plecak/hotbar ORAZ założoną zbroję (zbroja może leżeć w plecaku, ale też być akurat noszona). */
    public int najlepszyPoziom(Player player, Kategoria kategoria) {
        int max = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            max = Math.max(max, poziomJesliPasuje(item, kategoria));
        }
        for (ItemStack item : player.getInventory().getArmorContents()) {
            max = Math.max(max, poziomJesliPasuje(item, kategoria));
        }
        return max;
    }

    private int poziomJesliPasuje(ItemStack item, Kategoria kategoria) {
        ToolDefinition def = definicjaZItemu(item);
        return (def != null && def.kategoria() == kategoria) ? poziomZ(item) : 0;
    }

    /** Przedmiot "w użyciu" dla danej kategorii - main-hand dla narzędzi, wlasciwy slot zbroi dla HELMET/CHESTPLATE/LEGGINGS/BOOTS. */
    private ItemStack itemDlaKategorii(Player player, Kategoria kategoria) {
        return switch (kategoria) {
            case HELMET -> player.getInventory().getHelmet();
            case CHESTPLATE -> player.getInventory().getChestplate();
            case LEGGINGS -> player.getInventory().getLeggings();
            case BOOTS -> player.getInventory().getBoots();
            default -> player.getInventory().getItemInMainHand();
        };
    }

    private EquipmentSlotGroup slotGroupDlaKategorii(Kategoria kategoria) {
        return switch (kategoria) {
            case HELMET -> EquipmentSlotGroup.HEAD;
            case CHESTPLATE -> EquipmentSlotGroup.CHEST;
            case LEGGINGS -> EquipmentSlotGroup.LEGS;
            case BOOTS -> EquipmentSlotGroup.FEET;
            default -> EquipmentSlotGroup.MAINHAND;
        };
    }

    // ======================================================== Progresja ====

    public void debugAddLevels(ItemStack item, int levels) {
        ToolDefinition def = definicjaZItemu(item);
        if (def == null) return;
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        int level = pdc.getOrDefault(pkLevel, PersistentDataType.INTEGER, 1);
        pdc.set(pkLevel, PersistentDataType.INTEGER, Math.min(def.maxPoziom(), level + levels));
        pdc.set(pkExp, PersistentDataType.INTEGER, 0);
        item.setItemMeta(meta);
        odswiezWyglad(item, def);
    }

    private void dodajExp(Player player, ItemStack item, ToolDefinition def) {
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        int level = pdc.getOrDefault(pkLevel, PersistentDataType.INTEGER, 1);
        if (level >= def.maxPoziom()) return;

        int exp = pdc.getOrDefault(pkExp, PersistentDataType.INTEGER, 0) + 1;
        boolean leveledUp = false;
        if (exp >= def.expNaPoziom()) {
            exp = 0;
            level++;
            leveledUp = true;
        }
        pdc.set(pkLevel, PersistentDataType.INTEGER, level);
        pdc.set(pkExp, PersistentDataType.INTEGER, exp);
        item.setItemMeta(meta);

        odswiezWyglad(item, def);

        if (leveledUp) {
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.4f);
            player.sendActionBar(Component.text(plainText(def.nazwa()) + ": poziom " + level, NamedTextColor.AQUA));

            List<ToolEffect> odblokowane = def.kamienieMilowe().get(level);
            if (odblokowane != null && !odblokowane.isEmpty()) {
                player.showTitle(Title.title(
                        Component.text("Nowe Ulepszenie!", NamedTextColor.GREEN, TextDecoration.BOLD),
                        Component.text(plainText(def.nazwa()) + " - poziom " + level, NamedTextColor.YELLOW)));
                for (ToolEffect fx : odblokowane) {
                    player.sendMessage(Component.text("★ Odblokowano: " + opisEfektu(fx, level), NamedTextColor.GREEN, TextDecoration.BOLD));
                }
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            }
            if (level >= def.maxPoziom()) {
                player.sendMessage(Component.text("★ " + plainText(def.nazwa()) + " osiągnęła maksymalny poziom (" + level + ")!", NamedTextColor.GOLD, TextDecoration.BOLD));
            }
        }
    }

    private void odswiezWyglad(ItemStack item, ToolDefinition def) {
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        int level = pdc.getOrDefault(pkLevel, PersistentDataType.INTEGER, 1);
        int exp = pdc.getOrDefault(pkExp, PersistentDataType.INTEGER, 0);

        meta.displayName(def.nazwa().append(Component.text(" [Poziom " + level + "]", NamedTextColor.YELLOW, TextDecoration.BOLD)));
        if (def.model() != null) item.setData(DataComponentTypes.ITEM_MODEL, def.model());
        if (def.glint()) item.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);

        List<Component> lore = new ArrayList<>();
        for (ToolStat stat : def.staty()) {
            lore.add(Component.text(stat.nazwa() + ": " + formatPercent(stat.naPoziomie(level)) + "%", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        }
        int cap = def.maxPoziom();
        if (level >= cap) {
            lore.add(Component.text("Postęp: MAKSYMALNY POZIOM", NamedTextColor.GOLD, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.text("Postęp: " + exp + "/" + def.expNaPoziom() + " EXP", NamedTextColor.DARK_AQUA).decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.empty());
        lore.add(Component.text("Shift + PPM - drzewko ulepszeń", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);

        for (EnchantProgress ep : def.enchanty()) {
            int lvl = ep.enchantNaPoziomie(level);
            if (lvl > 0) meta.addEnchant(ep.enchant(), lvl, true);
            else meta.removeEnchant(ep.enchant());
        }
        // Staty podpięte pod prawdziwy enczant (patrz ToolStat#enchant) - stosowane PO liście
        // "enchanty" powyżej, więc jeśli obie ścieżki celują w ten sam enczant (nie powinny -
        // to dwa NIEZALEŻNE mechanizmy konfiguracyjne), staty wygrywają jako ostatnie.
        for (ToolStat stat : def.staty()) {
            if (stat.enchant() == null) continue;
            int lvl = stat.enchantPoziomNa(level);
            if (lvl > 0) meta.addEnchant(stat.enchant(), lvl, true);
            else meta.removeEnchant(stat.enchant());
        }

        meta.removeAttributeModifier(Attribute.ATTACK_DAMAGE);
        double bonusDmg = sumaWartosci(def, level, EffectType.PVP_BONUS_OBRAZENIA);
        if (bonusDmg > 0) {
            meta.addAttributeModifier(Attribute.ATTACK_DAMAGE, new AttributeModifier(
                    pkPvpModifier, bonusDmg, AttributeModifier.Operation.ADD_NUMBER, slotGroupDlaKategorii(def.kategoria())));
        }

        if (meta instanceof Damageable damageable) {
            short maxDur = item.getType().getMaxDurability();
            if (maxDur > 0) {
                boolean nieniszczalny = maEfekt(def, level, EffectType.NIENISZCZALNY);
                double progress = level >= cap ? 1.0 : (double) exp / def.expNaPoziom();
                int damage = nieniszczalny ? 0 : (int) Math.round(maxDur - maxDur * progress);
                damageable.setDamage(Math.max(0, Math.min(damage, maxDur - (nieniszczalny ? 0 : 1))));
            }
        }

        if (maEfekt(def, level, EffectType.SPECJALNY_SILK_TOUCH)) {
            pdc.set(CustomItemKeys.SPECJALNY_SILK_TOUCH, PersistentDataType.BOOLEAN, true);
        }

        item.setItemMeta(meta);
    }

    // ============================================================ Efekty ====

    /** Wszystkie AKTUALNIE aktywne efekty (pasywne, skalujące się od 1 lvl + odblokowane kamienie milowe do aktualnego poziomu). */
    private List<ToolEffect> aktywneEfekty(ToolDefinition def, int level) {
        List<ToolEffect> lista = new ArrayList<>(def.pasywne());
        lista.addAll(def.efektyOdblokowaneDo(level));
        return lista;
    }

    /** Wszystkie efekty zdefiniowane w ogóle (bez filtra poziomu) - pod reload() (jakie typy mikstur trzeba w ogóle obsługiwać). */
    private List<ToolEffect> wszystkieEfekty(ToolDefinition def) {
        List<ToolEffect> lista = new ArrayList<>(def.pasywne());
        for (List<ToolEffect> lvl : def.kamienieMilowe().values()) lista.addAll(lvl);
        return lista;
    }

    private boolean maEfekt(ToolDefinition def, int level, EffectType typ) {
        for (ToolEffect fx : aktywneEfekty(def, level)) if (fx.typ() == typ) return true;
        return false;
    }

    private ToolEffect pierwszyEfekt(ToolDefinition def, int level, EffectType typ) {
        for (ToolEffect fx : aktywneEfekty(def, level)) if (fx.typ() == typ) return fx;
        return null;
    }

    private double sumaWartosci(ToolDefinition def, int level, EffectType typ) {
        double suma = 0;
        for (ToolEffect fx : aktywneEfekty(def, level)) if (fx.typ() == typ) suma += fx.kwotaNaPoziomie(level);
        return suma;
    }

    /**
     * Efekty odpalane PRZY KONKRETNYM zdarzeniu (kopanie/atak) - reszta typów (aury/pasywne
     * staty) jest ciągła, obsłużona gdzie indziej. `cel` - trafione stworzenie (tylko SWORD,
     * null przy kopaniu) - pod PODWOJNY_ATAK/DEBUFF_PRZECIWNIKA.
     */
    private void zastosujEfektyTriggera(Player player, ItemStack item, ToolDefinition def, int level, Location miejsce, Block blok, LivingEntity cel) {
        for (ToolEffect fx : aktywneEfekty(def, level)) {
            switch (fx.typ()) {
                case DUPLIKUJ_DROP -> {
                    if (blok != null && procent(fx.szansaNaPoziomie(level))) {
                        dropCopy(blok, item);
                        fxKosmetyczny(fx, miejsce);
                    }
                }
                case BONUS_PIENIADZE -> {
                    if (procent(fx.szansaNaPoziomie(level))) {
                        double kwota = fx.kwotaNaPoziomie(level);
                        economyService.dodajKase(player.getUniqueId(), kwota);
                        player.sendActionBar(Component.text("+" + formatMoney(kwota) + " $", NamedTextColor.GREEN));
                        fxKosmetyczny(fx, miejsce);
                    }
                }
                case BONUS_XP -> {
                    if (procent(fx.szansaNaPoziomie(level))) {
                        int ilosc = Math.max(1, (int) Math.round(fx.kwotaNaPoziomie(level)));
                        miejsce.getWorld().spawn(miejsce, ExperienceOrb.class, orb -> orb.setExperience(ilosc));
                        fxKosmetyczny(fx, miejsce);
                    }
                }
                case MAGNES -> {
                    if (procent(fx.szansaNaPoziomie(level))) przyciagnijDropy(player, miejsce, fx.promien());
                }
                case CZASTKI_PRZY_TRIGGERZE -> {
                    if (procent(fx.szansaNaPoziomie(level))) fxKosmetyczny(fx, miejsce);
                }
                case OBSZAR_KRUSZENIA -> {
                    if (blok != null && procent(fx.szansaNaPoziomie(level))) {
                        int limit = Math.min(6, Math.max(0, (int) Math.round(fx.kwotaNaPoziomie(level))));
                        if (limit > 0) kopSasiednie(blok, item, limit);
                        fxKosmetyczny(fx, miejsce);
                    }
                }
                case ZYLA_GORNICZA -> {
                    if (blok != null && procent(fx.szansaNaPoziomie(level))) {
                        int limit = Math.min(32, Math.max(0, (int) Math.round(fx.kwotaNaPoziomie(level))));
                        if (limit > 0) kopZyleGornicza(blok, item, limit);
                        fxKosmetyczny(fx, miejsce);
                    }
                }
                case BONUS_PRZEDMIOT -> {
                    if (procent(fx.szansaNaPoziomie(level))) {
                        ItemStack bonus = stworzBonusPrzedmiot(fx, level);
                        if (bonus != null) {
                            miejsce.getWorld().dropItemNaturally(miejsce, bonus);
                            fxKosmetyczny(fx, miejsce);
                        }
                    }
                }
                case JACKPOT -> {
                    if (procent(fx.szansaNaPoziomie(level))) {
                        double kwota = fx.kwotaNaPoziomie(level);
                        economyService.dodajKase(player.getUniqueId(), kwota);
                        player.showTitle(Title.title(
                                Component.text("★ JACKPOT! ★", NamedTextColor.GOLD, TextDecoration.BOLD),
                                Component.text("+" + formatMoney(kwota) + " $", NamedTextColor.YELLOW)));
                        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 0.8f);
                        fxKosmetyczny(fx, miejsce);
                    }
                }
                case PODWOJNY_ATAK -> {
                    if (cel != null && procent(fx.szansaNaPoziomie(level))) {
                        var atakAttr = player.getAttribute(Attribute.ATTACK_DAMAGE);
                        double obrazenia = atakAttr != null ? atakAttr.getValue() : 1.0;
                        cel.damage(obrazenia, player);
                        fxKosmetyczny(fx, cel.getLocation());
                    }
                }
                case DEBUFF_PRZECIWNIKA -> {
                    if (cel != null && procent(fx.szansaNaPoziomie(level))) {
                        PotionEffectType typ = potionOd(fx.mikstura());
                        if (typ != null) {
                            int czasTickow = Math.max(1, (int) Math.round(fx.kwotaNaPoziomie(level)));
                            cel.addPotionEffect(new PotionEffect(typ, czasTickow, fx.poziomMikstury()));
                        }
                        fxKosmetyczny(fx, cel.getLocation());
                    }
                }
                case LECZENIE -> {
                    if (procent(fx.szansaNaPoziomie(level))) {
                        var maxZdrowie = player.getAttribute(Attribute.MAX_HEALTH);
                        double limit = maxZdrowie != null ? maxZdrowie.getValue() : player.getHealth();
                        player.setHealth(Math.min(limit, player.getHealth() + fx.kwotaNaPoziomie(level)));
                        fxKosmetyczny(fx, miejsce);
                    }
                }
                case SYCENIE -> {
                    if (procent(fx.szansaNaPoziomie(level))) {
                        int ilosc = Math.max(0, Math.min(20, player.getFoodLevel() + (int) Math.round(fx.kwotaNaPoziomie(level))));
                        player.setFoodLevel(ilosc);
                        fxKosmetyczny(fx, miejsce);
                    }
                }
                case PIORUN -> {
                    if (procent(fx.szansaNaPoziomie(level))) {
                        miejsce.getWorld().strikeLightning(miejsce);
                    }
                }
                default -> {
                    // AURA_MIKSTURY/PVP_BONUS_OBRAZENIA/NIENISZCZALNY/SPECJALNY_SILK_TOUCH/TELEKINEZA/
                    // ODBICIE_OBRAZEN - ciągłe/obsłużone gdzie indziej (odswiezWyglad/synchronizujAure/
                    // onBlockBreak/onEntityDamageAsVictim), nie tutaj.
                }
            }
        }
    }

    private boolean procent(double szansaProcent) {
        return ThreadLocalRandom.current().nextDouble(100) < szansaProcent;
    }

    private void fxKosmetyczny(ToolEffect fx, Location miejsce) {
        Particle particle = particleOd(fx.czastka());
        if (particle != null) miejsce.getWorld().spawnParticle(particle, miejsce, 12, 0.3, 0.3, 0.3, 0.02);
        Sound sound = soundOd(fx.dzwiek());
        if (sound != null) miejsce.getWorld().playSound(miejsce, sound, 1f, 1f);
    }

    private void dropCopy(Block block, ItemStack tool) {
        for (ItemStack drop : block.getDrops(tool)) {
            block.getWorld().dropItemNaturally(block.getLocation(), drop);
        }
    }

    private void przyciagnijDropy(Player player, Location center, double promien) {
        for (Entity e : center.getWorld().getNearbyEntities(center, promien, promien, promien)) {
            if (e instanceof Item dropped) {
                Vector dir = player.getLocation().add(0, 1, 0).toVector().subtract(dropped.getLocation().toVector());
                if (dir.lengthSquared() > 0.01) {
                    dropped.setVelocity(dropped.getVelocity().add(dir.normalize().multiply(0.25)));
                }
            }
        }
    }

    private static final BlockFace[] SASIEDNIE_SCIANY = {
            BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    /** Kruszy do `limit` BEZPOŚREDNICH sąsiadów origin tego samego Materiału - prosty, tani odpowiednik "obszaru". */
    private void kopSasiednie(Block origin, ItemStack tool, int limit) {
        int skruszono = 0;
        for (BlockFace face : SASIEDNIE_SCIANY) {
            if (skruszono >= limit) return;
            Block sasiad = origin.getRelative(face);
            if (sasiad.getType() == origin.getType()) {
                dropCopy(sasiad, tool);
                sasiad.setType(Material.AIR, false);
                skruszono++;
            }
        }
    }

    /** "Żyła górnicza" - flood-fill po POŁĄCZONYCH (nie tylko bezpośrednich) blokach tego samego Materiału, twardy limit `limit`. */
    private void kopZyleGornicza(Block origin, ItemStack tool, int limit) {
        Material typ = origin.getType();
        Set<Block> odwiedzone = new HashSet<>();
        ArrayDeque<Block> kolejka = new ArrayDeque<>();
        odwiedzone.add(origin);
        kolejka.add(origin);
        int skruszono = 0;

        while (!kolejka.isEmpty() && skruszono < limit) {
            Block current = kolejka.poll();
            if (current != origin) {
                dropCopy(current, tool);
                current.setType(Material.AIR, false);
                skruszono++;
            }
            for (BlockFace face : SASIEDNIE_SCIANY) {
                Block sasiad = current.getRelative(face);
                if (sasiad.getType() == typ && odwiedzone.add(sasiad)) {
                    kolejka.add(sasiad);
                }
            }
        }
    }

    /** BONUS_PRZEDMIOT - przedmiotCustomId ma pierwszeństwo (przez CustomItemService), inaczej zwykły Material. */
    private ItemStack stworzBonusPrzedmiot(ToolEffect fx, int level) {
        if (fx.przedmiotCustomId() != null && !fx.przedmiotCustomId().isBlank()) {
            CustomItemService customItemService = CoreAPI.getCustomItemService();
            if (customItemService != null) {
                int ilosc = Math.max(1, (int) Math.round(fx.kwotaNaPoziomie(level)));
                ItemStack item = customItemService.create(fx.przedmiotCustomId(), ilosc);
                if (item != null) return item;
            }
        }
        if (fx.przedmiotMaterial() != null && !fx.przedmiotMaterial().isBlank()) {
            Material material = Material.matchMaterial(fx.przedmiotMaterial());
            if (material != null) {
                int ilosc = Math.max(1, (int) Math.round(fx.kwotaNaPoziomie(level)));
                return new ItemStack(material, ilosc);
            }
        }
        return null;
    }

    // ==================================================== Wanilijskie eventy ====

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        ToolDefinition def = definicjaZItemu(item);
        if (def == null) return;
        if (def.kategoria() == Kategoria.SWORD) return;

        dodajExp(player, item, def);
        int level = poziomZ(item);
        Block blok = event.getBlock();

        // TELEKINEZA musi ingerować w event PRZED wanilijskim dropem (setDropItems), więc
        // jest wyjątkiem - obsłużona tutaj, nie w zastosujEfektyTriggera (post-processing).
        ToolEffect telekineza = pierwszyEfekt(def, level, EffectType.TELEKINEZA);
        if (telekineza != null && procent(telekineza.szansaNaPoziomie(level))) {
            for (ItemStack drop : blok.getDrops(item)) {
                for (ItemStack nadmiar : player.getInventory().addItem(drop).values()) {
                    blok.getWorld().dropItemNaturally(blok.getLocation(), nadmiar);
                }
            }
            event.setDropItems(false);
            fxKosmetyczny(telekineza, blok.getLocation().add(0.5, 0.5, 0.5));
        }

        zastosujEfektyTriggera(player, item, def, level, blok.getLocation().add(0.5, 0.5, 0.5), blok, null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        ItemStack item = player.getInventory().getItemInMainHand();
        ToolDefinition def = definicjaZItemu(item);
        if (def == null || def.kategoria() != Kategoria.SWORD) return;

        dodajExp(player, item, def);
        LivingEntity cel = event.getEntity() instanceof LivingEntity le ? le : null;
        zastosujEfektyTriggera(player, item, def, poziomZ(item), player.getLocation(), null, cel);
    }

    private static final Kategoria[] KATEGORIE_ZBROI = {Kategoria.HELMET, Kategoria.CHESTPLATE, Kategoria.LEGGINGS, Kategoria.BOOTS};

    /**
     * "Użycie" zbroi (patrz Kategoria#jestZbroja) - w odróżnieniu od narzędzi (trigger to
     * WŁASNA akcja gracza: kopanie/atak), zbroja "działa" gdy jej właściciel OTRZYMUJE
     * obrażenia. Sprawdza wszystkie 4 sloty naraz - gracz może mieć kilka RÓŻNYCH
     * zarejestrowanych części zbroi jednocześnie, każda poziomuje się NIEZALEŻNIE.
     *
     * ODBICIE_OBRAZEN wymaga SUROWEJ kwoty obrażeń z eventu (nie ma jej w
     * zastosujEfektyTriggera) - jedyny typ obsłużony bezpośrednio tutaj, reszta (w tym
     * PODWOJNY_ATAK/DEBUFF_PRZECIWNIKA - dla zbroi to "kontratak"/"kolce" na napastnika)
     * leci przez wspólny pipeline.
     */
    @EventHandler(ignoreCancelled = true)
    public void onEntityDamageAsVictim(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        LivingEntity attacker = event.getDamager() instanceof LivingEntity le ? le : null;

        for (Kategoria kategoria : KATEGORIE_ZBROI) {
            ItemStack item = itemDlaKategorii(victim, kategoria);
            ToolDefinition def = definicjaZItemu(item);
            if (def == null || def.kategoria() != kategoria) continue;

            dodajExp(victim, item, def);
            int level = poziomZ(item);

            if (attacker != null) {
                ToolEffect odbicie = pierwszyEfekt(def, level, EffectType.ODBICIE_OBRAZEN);
                if (odbicie != null && procent(odbicie.szansaNaPoziomie(level))) {
                    double procentOdbicia = Math.max(0, Math.min(100, odbicie.kwotaNaPoziomie(level)));
                    double odbite = event.getDamage() * procentOdbicia / 100.0;
                    if (odbite > 0) attacker.damage(odbite, victim);
                    fxKosmetyczny(odbicie, victim.getLocation());
                }
            }

            zastosujEfektyTriggera(victim, item, def, level, victim.getLocation(), null, attacker);
        }
    }

    @EventHandler
    public void onItemDamage(PlayerItemDamageEvent event) {
        ToolDefinition def = definicjaZItemu(event.getItem());
        if (def == null) return;
        if (maEfekt(def, poziomZ(event.getItem()), EffectType.NIENISZCZALNY)) {
            event.setCancelled(true);
        }
    }

    // ===================================================== Aura mikstury ====

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        synchronizujAure(event.getPlayer(), event.getPlayer().getInventory().getItemInMainHand());
    }

    /** getNewSlot() jeszcze nie jest "aktualnym" slotem w momencie eventu - stąd jawny odczyt zamiast getItemInMainHand() (byłby o jeden krok w tyle). */
    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) {
        synchronizujAure(event.getPlayer(), event.getPlayer().getInventory().getItem(event.getNewSlot()));
    }

    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        synchronizujAure(event.getPlayer(), event.getMainHandItem());
    }

    @EventHandler
    public void onArmorChange(com.destroystokyo.paper.event.player.PlayerArmorChangeEvent event) {
        synchronizujAure(event.getPlayer(), event.getPlayer().getInventory().getItemInMainHand());
    }

    /** Skanuje main-hand (przekazany jawnie - patrz onItemHeld) ORAZ wszystkie 4 sloty zbroi naraz - unia aktywnych aur ze WSZYSTKICH jednocześnie noszonych/trzymanych zarejestrowanych przedmiotów. */
    private void synchronizujAure(Player player, ItemStack mainHandItem) {
        if (auraTypyZarzadzane.isEmpty()) return;

        Set<PotionEffectType> aktywne = new HashSet<>();
        zbierzAuryZItemu(player, mainHandItem, aktywne);
        for (Kategoria kategoria : KATEGORIE_ZBROI) {
            zbierzAuryZItemu(player, itemDlaKategorii(player, kategoria), aktywne);
        }
        for (PotionEffectType zarzadzany : auraTypyZarzadzane) {
            if (aktywne.contains(zarzadzany)) continue;
            PotionEffect current = player.getPotionEffect(zarzadzany);
            if (current != null && current.getDuration() > 50_000) {
                player.removePotionEffect(zarzadzany);
            }
        }
    }

    private void zbierzAuryZItemu(Player player, ItemStack item, Set<PotionEffectType> aktywne) {
        ToolDefinition def = definicjaZItemu(item);
        if (def == null) return;
        int level = poziomZ(item);
        for (ToolEffect fx : aktywneEfekty(def, level)) {
            if (fx.typ() != EffectType.AURA_MIKSTURY) continue;
            PotionEffectType typ = potionOd(fx.mikstura());
            if (typ == null) continue;
            aktywne.add(typ);
            player.addPotionEffect(new PotionEffect(typ, AURA_DURATION_TICKS, fx.poziomMikstury(), true, false, false));
        }
    }

    // ================================================== Cząsteczki otoczenia ====

    private void tickCzastkiOtoczenia() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            ItemStack item = player.getInventory().getItemInMainHand();
            ToolDefinition def = definicjaZItemu(item);
            if (def == null || def.czastkaOtoczenia() == null) continue;
            Particle particle = particleOd(def.czastkaOtoczenia());
            if (particle == null) continue;
            player.getWorld().spawnParticle(particle, player.getLocation().add(0, 1, 0), 3, 0.3, 0.3, 0.3, 0.01);
        }
    }

    // ============================================================= GUI ====

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!event.getPlayer().isSneaking()) return;

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        ToolDefinition def = definicjaZItemu(item);
        if (def == null) return;

        event.setCancelled(true);
        openHub(player, item, def);
    }

    private static final class HubHolder implements InventoryHolder {
        final EvolvingToolManager owner;
        Inventory inventory;
        HubHolder(EvolvingToolManager owner) { this.owner = owner; }
        @Override public Inventory getInventory() { return inventory; }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof HubHolder holder && holder.owner == this) {
            event.setCancelled(true);
        }
    }

    private void openHub(Player player, ItemStack tool, ToolDefinition def) {
        int level = poziomZ(tool);

        HubHolder holder = new HubHolder(this);
        Component tytul = def.nazwa().append(Component.text(" — Ulepszenia", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD));
        Inventory gui = Bukkit.createInventory(holder, 45, tytul);
        holder.inventory = gui;
        GuiUtils.fillBackground(gui, Material.PURPLE_STAINED_GLASS_PANE);

        gui.setItem(4, GuiUtils.namedItem(def.material(),
                def.nazwa().append(Component.text(" [Poziom " + level + "]", NamedTextColor.YELLOW, TextDecoration.BOLD)),
                Component.text("Poziom " + level + "/" + def.maxPoziom(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));

        gui.setItem(22, statsIcon(def, level));

        int[] slotyMilestone = {28, 29, 30, 31, 32, 33, 34};
        int i = 0;
        for (var wpis : def.kamienieMilowe().entrySet()) {
            if (i >= slotyMilestone.length) break;
            gui.setItem(slotyMilestone[i], milestoneIcon(wpis.getKey(), wpis.getValue(), level));
            i++;
        }

        gui.setItem(40, GuiUtils.namedItem(Material.BARRIER, Component.text("Zamknij", NamedTextColor.RED, TextDecoration.BOLD)));
        player.openInventory(gui);
    }

    private ItemStack statsIcon(ToolDefinition def, int level) {
        List<Component> lore = new ArrayList<>();
        for (ToolStat stat : def.staty()) {
            lore.add(Component.text(stat.nazwa() + ": " + formatPercent(stat.naPoziomie(level)) + "%", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        }
        for (EnchantProgress ep : def.enchanty()) {
            int lvl = ep.enchantNaPoziomie(level);
            if (lvl > 0) {
                lore.add(Component.text(nazwaEnchantu(ep.enchant().getKey().getKey()) + " " + rzymskie(lvl), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            }
        }
        if (lore.isEmpty()) lore.add(Component.text("Brak statystyk.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        return GuiUtils.namedItem(Material.KNOWLEDGE_BOOK, Component.text("Statystyki", NamedTextColor.GOLD, TextDecoration.BOLD),
                lore.toArray(Component[]::new));
    }

    private ItemStack milestoneIcon(int poziomWymagany, List<ToolEffect> efekty, int aktualnyPoziom) {
        boolean odblokowany = aktualnyPoziom >= poziomWymagany;
        org.bukkit.Material material = odblokowany ? Material.NETHER_STAR : Material.GRAY_DYE;
        Component nazwa = odblokowany
                ? Component.text("★ Poziom " + poziomWymagany, NamedTextColor.GOLD, TextDecoration.BOLD)
                : Component.text("Poziom " + poziomWymagany + " (zablokowane)", NamedTextColor.DARK_GRAY, TextDecoration.BOLD);
        List<Component> lore = new ArrayList<>();
        for (ToolEffect fx : efekty) {
            NamedTextColor kolor = odblokowany ? NamedTextColor.GRAY : NamedTextColor.DARK_GRAY;
            lore.add(Component.text(opisEfektu(fx, poziomWymagany), kolor).decoration(TextDecoration.ITALIC, false));
        }
        return GuiUtils.namedItem(material, nazwa, lore.toArray(Component[]::new));
    }

    // ==================================================== Pomocnicze ====

    private String opisEfektu(ToolEffect fx, int poziom) {
        return switch (fx.typ()) {
            case DUPLIKUJ_DROP -> "Szansa " + formatPercent(fx.szansaNaPoziomie(poziom)) + "% na zdublowanie dropu.";
            case BONUS_PIENIADZE -> "Szansa " + formatPercent(fx.szansaNaPoziomie(poziom)) + "% na +" + formatMoney(fx.kwotaNaPoziomie(poziom)) + " $.";
            case BONUS_XP -> "Szansa " + formatPercent(fx.szansaNaPoziomie(poziom)) + "% na bonusowy orb XP.";
            case MAGNES -> "Przyciąga dropy w promieniu " + (int) fx.promien() + " bloków.";
            case AURA_MIKSTURY -> "Stała mikstura: " + (fx.mikstura() != null ? fx.mikstura() : "?") + " " + rzymskie(fx.poziomMikstury() + 1) + ".";
            case PVP_BONUS_OBRAZENIA -> "+" + formatMoney(fx.kwotaNaPoziomie(poziom)) + " obrażeń w walce.";
            case NIENISZCZALNY -> "Narzędzie nigdy nie traci wytrzymałości.";
            case SPECJALNY_SILK_TOUCH -> "Zbiera cały custom-blok generatora zamiast zwykłego dropu.";
            case CZASTKI_PRZY_TRIGGERZE -> "Efekt kosmetyczny.";
            case OBSZAR_KRUSZENIA -> "Szansa " + formatPercent(fx.szansaNaPoziomie(poziom)) + "% na skruszenie do " + (int) fx.kwotaNaPoziomie(poziom) + " sąsiednich bloków.";
            case ZYLA_GORNICZA -> "Szansa " + formatPercent(fx.szansaNaPoziomie(poziom)) + "% na \"żyłę górniczą\" - do " + (int) fx.kwotaNaPoziomie(poziom) + " połączonych bloków naraz.";
            case TELEKINEZA -> "Szansa " + formatPercent(fx.szansaNaPoziomie(poziom)) + "% na drop prosto do ekwipunku.";
            case BONUS_PRZEDMIOT -> "Szansa " + formatPercent(fx.szansaNaPoziomie(poziom)) + "% na dodatkowy przedmiot: " + (fx.przedmiotCustomId() != null ? fx.przedmiotCustomId() : fx.przedmiotMaterial()) + ".";
            case JACKPOT -> "Rzadka szansa " + formatPercent(fx.szansaNaPoziomie(poziom)) + "% na JACKPOT: +" + formatMoney(fx.kwotaNaPoziomie(poziom)) + " $.";
            case PODWOJNY_ATAK -> "Szansa " + formatPercent(fx.szansaNaPoziomie(poziom)) + "% na natychmiastowy drugi cios.";
            case DEBUFF_PRZECIWNIKA -> "Szansa " + formatPercent(fx.szansaNaPoziomie(poziom)) + "% na nałożenie " + (fx.mikstura() != null ? fx.mikstura() : "?") + " na przeciwnika.";
            case ODBICIE_OBRAZEN -> "Szansa " + formatPercent(fx.szansaNaPoziomie(poziom)) + "% na odbicie " + formatPercent(fx.kwotaNaPoziomie(poziom)) + "% obrażeń na atakującego.";
            case LECZENIE -> "Szansa " + formatPercent(fx.szansaNaPoziomie(poziom)) + "% na uleczenie " + formatMoney(fx.kwotaNaPoziomie(poziom)) + " HP.";
            case SYCENIE -> "Szansa " + formatPercent(fx.szansaNaPoziomie(poziom)) + "% na +" + (int) fx.kwotaNaPoziomie(poziom) + " punktów sytości.";
            case PIORUN -> "Rzadka szansa " + formatPercent(fx.szansaNaPoziomie(poziom)) + "% na uderzenie piorunem.";
        };
    }

    private String nazwaEnchantu(String klucz) {
        return switch (klucz) {
            case "efficiency" -> "Wydajność";
            case "fortune" -> "Fortuna";
            case "sharpness" -> "Ostrość";
            case "looting" -> "Grabież";
            case "unbreaking" -> "Trwałość";
            case "sweeping_edge" -> "Zamaszystość";
            case "knockback" -> "Odrzut";
            case "fire_aspect" -> "Podpalacz";
            case "protection" -> "Ochrona";
            case "silk_touch" -> "Delikatny Dotyk";
            case "fire_protection" -> "Ochrona przed Ogniem";
            case "blast_protection" -> "Ochrona przed Wybuchem";
            case "projectile_protection" -> "Ochrona przed Pociskami";
            case "feather_falling" -> "Piórkowy Upadek";
            case "thorns" -> "Kolce";
            case "respiration" -> "Oddychanie";
            case "aqua_affinity" -> "Wodne Powinowactwo";
            case "depth_strider" -> "Chodzenie po Głębinach";
            case "frost_walker" -> "Chodzenie po Lodzie";
            case "soul_speed" -> "Prędkość Dusz";
            case "swift_sneak" -> "Szybkie Skradanie";
            default -> klucz;
        };
    }

    private String rzymskie(int n) {
        return switch (n) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> String.valueOf(n);
        };
    }

    private String formatMoney(double amount) {
        return amount == Math.floor(amount) ? String.valueOf((long) amount) : String.format("%.1f", amount);
    }

    private String formatPercent(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.format("%.2f", value);
    }

    private String plainText(Component component) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component);
    }

    private Particle particleOd(String nazwa) {
        if (nazwa == null) return null;
        try {
            return Particle.valueOf(nazwa.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Sound soundOd(String nazwa) {
        if (nazwa == null) return null;
        try {
            return Sound.valueOf(nazwa.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private PotionEffectType potionOd(String nazwa) {
        if (nazwa == null) return null;
        return Registry.EFFECT.get(NamespacedKey.minecraft(nazwa.toLowerCase()));
    }
}
