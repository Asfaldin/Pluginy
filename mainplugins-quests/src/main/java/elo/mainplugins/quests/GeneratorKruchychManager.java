package elo.mainplugins.quests;

import elo.mainplugins.core.util.CustomItemKeys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Generator "Kruchych Surowców" (piasek/żwir, poziom 1) - planowana nagroda questu 11
 * Głównej Ścieżki (NIE wpięta jeszcze do QuestManager/questyKategorii - czeka na finalne
 * przenumerowanie ścieżki po dodaniu wszystkich nowych questów, patrz rozmowa
 * 2026-08-11). Ten plik to sama INFRASTRUKTURA, gotowa do podpięcia jako nagroda:
 *
 * Gracz stawia blok (CHISELED_SANDSTONE, otagowany CUSTOM_ID_GENERATOR - zwykły,
 * kupiony/wydobyty rzeźbiony piaskowiec NIE jest generatorem, patrz jestGeneratorem).
 * Wykopanie go ZWYKŁYM narzędziem daje piasek+żwir, a blok sam się odbudowuje po
 * ODNOWA_TICKOW (jak stacjonarny "Sniffer Farmera" z mainplugins-skyblock, tylko blok
 * zamiast moba). Wykopanie narzędziem z flagą CustomItemKeys.SPECJALNY_SILK_TOUCH daje
 * z powrotem CAŁY generator jako przenośny przedmiot, bez regeneracji na starym miejscu -
 * na razie ŻADNE narzędzie tej flagi nie dostaje (świadomie odłożone, łopata działa jak
 * zwykłe narzędzie), więc ta gałąź jest chwilowo martwym kodem, gotowym na później.
 *
 * Drugi sposób zdobycia kolejnych generatorów (poza jednorazową nagrodą questu) to
 * REALNA receptura w stole rzemieślniczym (patrz stworzRecepture we wywołaniu z
 * MainpluginsQuests) - ShapelessRecipe, bo opisany układ (2 stacki kamienia po bokach,
 * węgiel na środku, miedź i ziemia niżej) ma więcej "warstw" niż mieści 3x3 siatka
 * (patrz komentarz w stworzKsiazkaPrzewodnik) - liczą się tylko ILOŚCI składników,
 * nie ich pozycja w siatce.
 */
public class GeneratorKruchychManager implements Listener {

    private static final String CUSTOM_ID_GENERATOR = "GENERATOR_KRUCHY_T1";
    private static final Material MATERIAL_GENERATORA = Material.CHISELED_SANDSTONE;
    private static final long ODNOWA_TICKOW = 20L * 60; // 60s

    private final Plugin plugin;

    // Lokalizacje aktywnych generatorów - CZYSTO w pamięci (jak sesje fal zombie w
    // QuestManager), bez zapisu do pliku. Nie przetrwa restartu serwera w trakcie
    // odnowy - świadomy kompromis prostoty, ten sam co reszta questowych mechanik.
    private final Set<Location> aktywneGeneratory = new HashSet<>();

    public GeneratorKruchychManager(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Nagroda questu 11 (poziom 1) / wynik receptury - patrz komentarz klasy. */
    public static ItemStack stworzGenerator() {
        ItemStack item = new ItemStack(MATERIAL_GENERATORA);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Generator Kruchych Surowców [T1]", NamedTextColor.GOLD, TextDecoration.BOLD));
        meta.lore(List.of(
                Component.text("Postaw na wyspie - co jakiś czas", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("można wykopać z niego piasek i żwir.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Blok sam się odbudowuje po wykopaniu.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("Narzędzie ze specjalnym Silk Touchem", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false),
                Component.text("(np. ewoluująca łopata) zbiera cały blok.", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false)
        ));
        meta.getPersistentDataContainer().set(CustomItemKeys.CUSTOM_ITEM_ID, PersistentDataType.STRING, CUSTOM_ID_GENERATOR);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Książka-przewodnik po recepturze - CZYSTO informacyjna (lore), nie prawdziwa
     * wanilijska receptura odkrywana w Recipe Book. Opisany układ (kamień po bokach,
     * węgiel na środku, miedź NIŻEJ, ziemia na samym dole) ma logicznie 3 "poziomy" pod
     * jedną kolumną plus boczne kolumny kamienia - więcej pozycji niż mieści 3x3 siatka
     * rzemieślnicza, więc realna receptura (patrz stworzRecepture) jest SHAPELESS: liczą
     * się tylko ilości, gracz może je rozłożyć w dowolne wolne sloty stołu.
     */
    public static ItemStack stworzKsiazkaPrzewodnik() {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Przewodnik: Generator Kruchych Surowców", NamedTextColor.AQUA, TextDecoration.BOLD));
        meta.lore(List.of(
                Component.text("Skład receptury (dowolny układ w stole):", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("▪ 2x Stack Kamienia (128x)", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false),
                Component.text("▪ 10x Węgiel", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false),
                Component.text("▪ 5x Sztabka Miedzi", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false),
                Component.text("▪ 10x Ziemia", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("Wrzuć wszystko do stołu rzemieślniczego -", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("pozycja w siatce nie ma znaczenia.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        if (!jestGeneratorem(event.getItemInHand())) return;
        aktywneGeneratory.add(event.getBlock().getLocation());
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        var block = event.getBlock();
        Location lokalizacja = block.getLocation();
        if (!aktywneGeneratory.contains(lokalizacja)) return;

        Player player = event.getPlayer();
        ItemStack narzedzie = player.getInventory().getItemInMainHand();
        event.setDropItems(false);

        if (maSpecjalnySilkTouch(narzedzie)) {
            aktywneGeneratory.remove(lokalizacja);
            block.getWorld().dropItemNaturally(lokalizacja.clone().add(0.5, 0.5, 0.5), stworzGenerator());
            player.sendMessage(Component.text("Zebrałeś cały generator - możesz postawić go gdzie indziej!", NamedTextColor.GREEN));
            return;
        }

        block.getWorld().dropItemNaturally(lokalizacja.clone().add(0.5, 0.5, 0.5), new ItemStack(Material.SAND, 2));
        block.getWorld().dropItemNaturally(lokalizacja.clone().add(0.5, 0.5, 0.5), new ItemStack(Material.GRAVEL, 2));

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // Ktoś w międzyczasie zebrał go specjalnym silk touchem - nie odbudowuj pustego miejsca.
            if (!aktywneGeneratory.contains(lokalizacja)) return;
            if (lokalizacja.getBlock().getType() == Material.AIR) {
                lokalizacja.getBlock().setType(MATERIAL_GENERATORA);
            }
        }, ODNOWA_TICKOW);
    }

    private boolean jestGeneratorem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return CUSTOM_ID_GENERATOR.equals(item.getItemMeta().getPersistentDataContainer().get(CustomItemKeys.CUSTOM_ITEM_ID, PersistentDataType.STRING));
    }

    private boolean maSpecjalnySilkTouch(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return Boolean.TRUE.equals(item.getItemMeta().getPersistentDataContainer().get(CustomItemKeys.SPECJALNY_SILK_TOUCH, PersistentDataType.BOOLEAN));
    }
}