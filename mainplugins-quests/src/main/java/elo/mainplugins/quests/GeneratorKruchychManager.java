package elo.mainplugins.quests;

import elo.mainplugins.core.util.CustomItemKeys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generator "Kruchych Surowców" (piasek/żwir, poziom 1) - planowana nagroda questu 11
 * Głównej Ścieżki (NIE wpięta jeszcze do QuestManager/questyKategorii - czeka na finalne
 * przenumerowanie ścieżki po dodaniu wszystkich nowych questów, patrz rozmowa
 * 2026-08-11). Ten plik to sama INFRASTRUKTURA, gotowa do podpięcia jako nagroda:
 *
 * Gracz stawia blok (CHISELED_SANDSTONE, otagowany CUSTOM_ID_GENERATOR - zwykły,
 * kupiony/wydobyty rzeźbiony piaskowiec NIE jest generatorem, patrz jestGeneratorem).
 * Wykopanie go ŁOPATĄ (naturalny dobór narzędzia dla piasku/żwiru) daje losowo piasek ALBO
 * żwir (nigdy oba naraz, 50/50 - patrz onBreak), a blok wraca po krótkim, celowo
 * minimalnym opóźnieniu (ODNOWA_TICKOW - patrz onBreak). Wykopanie narzędziem z flagą
 * CustomItemKeys.SPECJALNY_SILK_TOUCH to jedyny sposób, żeby blok zniknął na stałe -
 * wtedy daje z powrotem CAŁY generator jako przenośny przedmiot, bez odbudowy na starym
 * miejscu - na razie ŻADNE narzędzie tej flagi nie dostaje (świadomie odłożone, łopata
 * działa jak zwykłe narzędzie), więc ta gałąź jest chwilowo martwym kodem, gotowym na później.
 *
 * Drugi sposób zdobycia kolejnych generatorów (poza jednorazową nagrodą questu) to
 * REALNA receptura w stole rzemieślniczym (patrz zarejestrujReceptureGeneratora w
 * MainpluginsQuests) - ShapelessRecipe (liczą się tylko ILOŚCI składników, nie ich
 * pozycja w siatce), zmieszczona w twardym limicie 9 składników 3x3 siatki - patrz
 * komentarz w stworzKsiazkaPrzewodnik dla dokładnych ilości.
 */
public class GeneratorKruchychManager implements Listener {

    private static final String CUSTOM_ID_GENERATOR = "GENERATOR_KRUCHY_T1";
    private static final Material MATERIAL_GENERATORA = Material.CHISELED_SANDSTONE;
    private static final int ILOSC_SUROWCA_ZA_WYKOP = 2;
    // Celowo minimalne - blok ma się odnawiać "prawie od razu", nie stać pusty przez
    // dłuższą chwilę jak w starej wersji (60s), patrz GeneratorBrukuManager#ODNOWA_TICKOW.
    private static final long ODNOWA_TICKOW = 15L;

    private final Plugin plugin;

    // Lokalizacje aktywnych generatorów - CZYSTO w pamięci (jak sesje fal zombie w
    // QuestManager), bez zapisu do pliku. Nie przetrwa restartu serwera w trakcie odnowy -
    // świadomy kompromis prostoty, ten sam co reszta questowych mechanik.
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
                Component.text("można wykopać z niego piasek lub żwir.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Blok sam się odbudowuje po wykopaniu.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("Kopie się WYŁĄCZNIE łopatą.", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false),
                Component.text("Narzędzie ze specjalnym Silk Touchem", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false),
                Component.text("(np. ewoluująca łopata) zbiera cały blok.", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false)
        ));
        meta.getPersistentDataContainer().set(CustomItemKeys.CUSTOM_ITEM_ID, PersistentDataType.STRING, CUSTOM_ID_GENERATOR);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Książka-przewodnik po recepturze - CZYSTO informacyjna (lore), nie prawdziwa
     * wanilijska receptura odkrywana w Recipe Book. Receptura (patrz
     * MainpluginsQuests#zarejestrujReceptureGeneratora) jest SHAPELESS: liczą się tylko
     * ilości, gracz może je rozłożyć w dowolne wolne sloty stołu. Ilości NAPRAWIONE, żeby
     * łącznie mieściły się w twardym limicie 9 składników 3x3 siatki (Minecraft nie
     * pozwala na więcej - patrz komentarz przy zarejestrujReceptureGeneratora).
     */
    public static ItemStack stworzKsiazkaPrzewodnik() {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Przewodnik: Generator Kruchych Surowców", NamedTextColor.AQUA, TextDecoration.BOLD));
        meta.lore(List.of(
                Component.text("Skład receptury (dowolny układ w stole):", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("▪ 4x Kamień", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false),
                Component.text("▪ 2x Węgiel", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false),
                Component.text("▪ 1x Sztabka Miedzi", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false),
                Component.text("▪ 2x Ziemia", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false),
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

    /**
     * CHISELED_SANDSTONE jest wanilijsko "kilofowy" - kopanie go łopatą (wymagane przez
     * onBreak) nie dostaje żadnego bonusu prędkości, więc bez tego handlera kopanie
     * ciągnęłoby się realnie długo mimo pozornie miękkiego bloku. InstaBreak na starcie
     * kopania łopatą naprawia to bez zmiany samego bloku (patrz jestLopata).
     */
    @EventHandler
    public void onDamage(BlockDamageEvent event) {
        if (!aktywneGeneratory.contains(event.getBlock().getLocation())) return;
        if (jestLopata(event.getPlayer().getInventory().getItemInMainHand())) {
            event.setInstaBreak(true);
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        var block = event.getBlock();
        Location lokalizacja = block.getLocation();
        if (!aktywneGeneratory.contains(lokalizacja)) return;

        Player player = event.getPlayer();
        ItemStack narzedzie = player.getInventory().getItemInMainHand();

        if (!jestLopata(narzedzie)) {
            event.setCancelled(true);
            player.sendMessage(Component.text("Ten blok trzeba kopać łopatą!", NamedTextColor.RED));
            return;
        }
        event.setDropItems(false);

        if (maSpecjalnySilkTouch(narzedzie)) {
            // Jedyny przypadek, gdzie blok znika na stałe - gracz zabiera go ze sobą.
            aktywneGeneratory.remove(lokalizacja);
            block.getWorld().dropItemNaturally(lokalizacja.clone().add(0.5, 0.5, 0.5), stworzGenerator());
            player.sendMessage(Component.text("Zebrałeś cały generator - możesz postawić go gdzie indziej!", NamedTextColor.GREEN));
            return;
        }

        // Losowo piasek ALBO żwir, nigdy oba naraz - jak wanilijskie kopanie tych bloków.
        boolean piasek = ThreadLocalRandom.current().nextBoolean();
        Material surowiec = piasek ? Material.SAND : Material.GRAVEL;
        block.getWorld().dropItemNaturally(lokalizacja.clone().add(0.5, 0.5, 0.5), new ItemStack(surowiec, ILOSC_SUROWCA_ZA_WYKOP));
        player.playSound(lokalizacja, piasek ? Sound.BLOCK_SAND_BREAK : Sound.BLOCK_GRAVEL_BREAK, 1.0f, 1.0f);

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

    /** Generator kopie się WYŁĄCZNIE łopatą (dowolny tier, w tym ewoluująca) - inne narzędzia są blokowane, patrz onBreak. */
    private boolean jestLopata(ItemStack item) {
        return item != null && item.getType().name().endsWith("_SHOVEL");
    }
}
