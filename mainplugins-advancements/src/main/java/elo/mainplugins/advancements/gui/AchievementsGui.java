package elo.mainplugins.advancements.gui;

import elo.mainplugins.advancements.AchievementManager;
import elo.mainplugins.advancements.CustomTriggerChecker;
import elo.mainplugins.advancements.config.AchievementsConfig;
import elo.mainplugins.advancements.model.AchievementCategory;
import elo.mainplugins.advancements.model.AchievementDef;
import elo.mainplugins.advancements.model.Reward;
import elo.mainplugins.core.util.GuiUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Buduje i otwiera panel osiągnięć. Jedna zakładka (kategoria) naraz, stronicowana;
 * dolny rząd to nawigacja (strony, kategorie, postęp, wyjście). Klik w zdobyte
 * osiągnięcie z nieodebraną nagrodą wręcza nagrodę (obsługa w
 * {@link AchievementsGuiListener}). Cały układ liczony od {@code gui.rozmiar}.
 */
public final class AchievementsGui {

    private static final LegacyComponentSerializer SER = LegacyComponentSerializer.legacyAmpersand();

    private final AchievementManager manager;
    private final CustomTriggerChecker checker;

    public AchievementsGui(AchievementManager manager, CustomTriggerChecker checker) {
        this.manager = manager;
        this.checker = checker;
    }

    public void otworz(Player player, boolean zMenu) {
        renderuj(player, new AchievementsGuiHolder(zMenu));
    }

    /** Przerysowuje panel w tym samym stanie nawigacji (po odbiorze nagrody / zmianie strony). */
    public void renderuj(Player player, AchievementsGuiHolder holder) {
        AchievementsConfig cfg = manager.config();
        List<AchievementCategory> kategorie = cfg.kategorie();
        if (kategorie.isEmpty()) {
            player.sendMessage(SER.deserialize("&cPanel osiągnięć nie ma jeszcze żadnej kategorii w osiagniecia.yml."));
            return;
        }

        int rozmiar = cfg.gui().rozmiar();
        int naStrone = rozmiar - 9;
        int base = rozmiar - 9;

        holder.kategoriaIndex = Math.floorMod(holder.kategoriaIndex, kategorie.size());
        AchievementCategory kategoria = kategorie.get(holder.kategoriaIndex);
        List<AchievementDef> lista = cfg.wgKategorii(kategoria.id());

        int maxStrona = lista.isEmpty() ? 0 : (lista.size() - 1) / naStrone;
        holder.strona = Math.max(0, Math.min(holder.strona, maxStrona));

        Component tytul = cfg.gui().tytul().append(Component.text(" • ")).append(kategoria.nazwa());
        Inventory inv = Bukkit.createInventory(holder, rozmiar, tytul);
        GuiUtils.fillBackground(inv, cfg.gui().tlo());

        holder.sloty.clear();
        for (int i = 0; i < naStrone; i++) {
            int idx = holder.strona * naStrone + i;
            if (idx >= lista.size()) break;
            AchievementDef def = lista.get(idx);
            inv.setItem(i, ikona(player, def));
            holder.sloty.put(i, def);
        }

        // --- dolny rząd: nawigacja ---
        inv.setItem(base, holder.strona > 0
                ? prosty(Material.SPECTRAL_ARROW, "&e‹ Poprzednia strona", null)
                : szyba(cfg.gui().tlo()));
        inv.setItem(base + 8, holder.strona < maxStrona
                ? prosty(Material.SPECTRAL_ARROW, "&eNastępna strona ›", null)
                : szyba(cfg.gui().tlo()));

        boolean wieleKategorii = kategorie.size() > 1;
        inv.setItem(base + 1, wieleKategorii
                ? prosty(Material.BOOK, "&e‹ Poprzednia kategoria", null)
                : szyba(cfg.gui().tlo()));
        inv.setItem(base + 7, wieleKategorii
                ? prosty(Material.BOOK, "&eNastępna kategoria ›", null)
                : szyba(cfg.gui().tlo()));

        List<String> infoLore = new ArrayList<>();
        infoLore.add("&7Ukończono: &f" + manager.ukonczoneWKategorii(player.getUniqueId(), kategoria.id()) + " &7/ &f" + lista.size());
        infoLore.add("&8Strona " + (holder.strona + 1) + " / " + (maxStrona + 1));
        inv.setItem(base + 3, prosty(kategoria.ikona(), legacyPlain(kategoria.nazwa()), infoLore));

        List<String> postepLore = new ArrayList<>();
        postepLore.add("&7Łącznie zdobyte: &f" + manager.liczbaUkonczonych(player.getUniqueId()) + " &7/ &f" + manager.liczbaWszystkich());
        int oczekujace = manager.liczbaOczekujacych(player.getUniqueId());
        if (oczekujace > 0) {
            postepLore.add("&eZaległe nagrody: &f" + oczekujace + " &7(zrób miejsce w ekwipunku)");
        }
        inv.setItem(base + 5, prosty(Material.NETHER_STAR, "&6Twój postęp", postepLore));

        inv.setItem(base + 4, GuiUtils.backOrCloseButton(holder.zMenu()));

        holder.setInventory(inv);
        player.openInventory(inv);
    }

    // ---------- ikona pojedynczego osiągnięcia ----------

    private ItemStack ikona(Player player, AchievementDef def) {
        boolean zdobyte = manager.ukonczone(player.getUniqueId(), def.id());
        boolean odebrane = manager.odebrane(player.getUniqueId(), def.id());

        if (def.ukryte() && !zdobyte) {
            return prosty(Material.GRAY_DYE, "&8???", List.of("&8Ukryte osiągnięcie", "&8Odkryjesz je, gdy je zdobędziesz."));
        }

        List<Component> lore = new ArrayList<>(def.opis());
        lore.add(Component.empty());

        if (!zdobyte) {
            long biezaca = checker.wartoscBiezaca(player, def.zrodlo());
            long cel = checker.wartoscDocelowa(def.zrodlo());
            if (cel > 0 && biezaca >= 0) {
                lore.add(legacy("&7Postęp: &f" + biezaca + " &7/ &f" + cel));
            } else {
                lore.add(legacy("&7Status: &cNiezdobyte"));
            }
        } else if (!def.maNagrody()) {
            lore.add(legacy("&aZdobyte!"));
        } else if (!odebrane) {
            lore.add(legacy("&aZdobyte  &e» kliknij, aby odebrać nagrodę"));
        } else {
            lore.add(legacy("&aZdobyte  &8» nagroda odebrana"));
        }

        if (def.maNagrody()) {
            lore.add(Component.empty());
            lore.add(legacy("&6Nagrody:"));
            for (Reward r : def.nagrody()) {
                lore.add(legacy("&7• &f" + opisNagrody(r)));
            }
        }

        ItemStack item = new ItemStack(def.ikona());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(def.nazwa());
        meta.lore(lore);
        if (zdobyte) {
            meta.setEnchantmentGlintOverride(true);
        }
        item.setItemMeta(meta);
        return item;
    }

    private static String opisNagrody(Reward r) {
        return switch (r) {
            case Reward.Money m -> ((long) m.amount()) + "$";
            case Reward.Item it -> it.amount() + "x " + ladnaNazwa(it.material());
            case Reward.CustomItem ci -> ci.amount() + "x " + ci.id();
            case Reward.Crate cr -> "Tajemnicza Skrzynka T" + cr.tier() + " + klucz";
            case Reward.Command ignored -> "nagroda specjalna";
        };
    }

    // ---------- drobne buildery ----------

    private static ItemStack prosty(Material material, String nazwaLegacy, List<String> loreLegacy) {
        return prosty(material, SER.deserialize(nazwaLegacy).decoration(TextDecoration.ITALIC, false), loreLegacy);
    }

    private static ItemStack prosty(Material material, Component nazwa, List<String> loreLegacy) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(nazwa.decoration(TextDecoration.ITALIC, false));
        if (loreLegacy != null && !loreLegacy.isEmpty()) {
            List<Component> lore = new ArrayList<>();
            for (String l : loreLegacy) lore.add(legacy(l));
            meta.lore(lore);
        }
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack szyba(Material tlo) {
        return GuiUtils.namedItem(tlo, Component.empty());
    }

    private static Component legacy(String s) {
        return SER.deserialize(s).decoration(TextDecoration.ITALIC, false);
    }

    private static String legacyPlain(Component c) {
        return SER.serialize(c);
    }

    private static String ladnaNazwa(Material m) {
        String[] czesci = m.name().toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String c : czesci) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(c.charAt(0))).append(c.substring(1));
        }
        return sb.toString();
    }
}
