package elo.mainplugins.quests;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.ToolsService;
import elo.mainplugins.core.util.CustomItemKeys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generator Bruku - siostrzana mechanika do {@link GeneratorKruchychManager} (postaw blok,
 * wykop odpowiednim narzędziem, blok wraca po krótkim odnowieniu - narzędzie ze
 * SPECJALNY_SILK_TOUCH to jedyny sposób, żeby blok faktycznie zniknął na stałe, bo wtedy
 * zbiera cały generator jako przenośny przedmiot) - tu zamiast piasku/żwiru daje bruk
 * (cobblestone). Kopie się KILOFEM (nie łopatą jak Kruchy) - to bruk/kamień, naturalny
 * dobór narzędzia.
 *
 * W odróżnieniu od Kruchego, zwykłe wykopanie NIE jest tu customowym dropem - podmieniamy
 * blok na PRAWDZIWY Material.COBBLESTONE tuż przed przetworzeniem eventu (stąd
 * {@code priority = EventPriority.LOWEST} na onBreak) i NIE anulujemy/nadpisujemy dropów,
 * więc dalszy event leci dokładnie tak, jakby gracz kopał zwykły bruk - PickaxeSkillManager
 * z mainplugins-tools (onBlockBreak, ignoreCancelled=true) widzi Material.COBBLESTONE i
 * nalicza wszystko (exp, bonusy, rollBonusZBruku) tak samo jak przy zwykłym bruku na
 * wyspie. Dzięki temu ten generator to renewable źródło surowca pod CAŁĄ resztę ekonomii
 * kilofa, nie tylko sam bruk, bez duplikowania logiki dropów tutaj.
 *
 * Zdobywany DWOMA sposobami (inaczej niż Kruchy, celowo bez questa/receptury na start):
 * sklep (custom-id GENERATOR_BRUK_T1, patrz categories/specjalne.yml) i komenda testowa
 * /dajbrukgen.
 */
public class GeneratorBrukuManager implements Listener {

    private static final String CUSTOM_ID_GENERATOR = "GENERATOR_BRUK_T1";
    private static final Material MATERIAL_GENERATORA = Material.MOSSY_COBBLESTONE;
    // Celowo minimalne - blok ma się odnawiać "prawie od razu", nie stać pusty przez
    // dłuższą chwilę jak w starej wersji (60s).
    private static final long ODNOWA_TICKOW = 15L;

    private final Plugin plugin;

    // Lokalizacje aktywnych generatorów - CZYSTO w pamięci (ten sam kompromis co
    // GeneratorKruchychManager/sesje fal zombie w QuestManager) - nie przetrwa restartu
    // serwera w trakcie odnowy.
    private final Set<Location> aktywneGeneratory = new HashSet<>();

    /**
     * Grupy zamrożonych (Wieczna Zima, patrz ToolsService#maWiecznaZime) sąsiednich bloków
     * generatora - lokalizacja -> WSPÓLNA instancja Set całej grupy. Bloki w grupie to
     * PRAWDZIWY Material.PACKED_ICE, nie pękają same - dopiero gdy gracz wykopie
     * KTÓRYKOLWIEK z nich (patrz onBreak/rozbijZamrozonaGrupe), reszta grupy pęka razem z nim
     * i każdy z osobna wraca do normalnego cyklu odnowy generatora (zaplanujOdnowe).
     */
    private final Map<Location, Set<Location>> zamrozoneGrupy = new HashMap<>();

    private static final BlockFace[] SASIEDNIE_SCIANY = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    public GeneratorBrukuManager(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Nagroda ze sklepu / wynik komendy /dajbrukgen - patrz komentarz klasy. */
    public static ItemStack stworzGenerator() {
        ItemStack item = new ItemStack(MATERIAL_GENERATORA);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Generator Bruku", NamedTextColor.GOLD, TextDecoration.BOLD));
        meta.lore(List.of(
                Component.text("Postaw na wyspie - co jakiś czas", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("można wykopać z niego bruk (cobblestone).", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Blok sam się odbudowuje po wykopaniu.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("Kopie się WYŁĄCZNIE kilofem.", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false),
                Component.text("Narzędzie ze specjalnym Silk Touchem", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false),
                Component.text("(np. ewoluujący kilof) zbiera cały blok.", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false)
        ));
        meta.getPersistentDataContainer().set(CustomItemKeys.CUSTOM_ITEM_ID, PersistentDataType.STRING, CUSTOM_ID_GENERATOR);
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        if (!jestGeneratorem(event.getItemInHand())) return;
        aktywneGeneratory.add(event.getBlock().getLocation());
    }

    /**
     * Priority LOWEST - musimy podmienić blok na zwykły bruk ZANIM PickaxeSkillManager
     * (mainplugins-tools, priority NORMAL) przetworzy ten sam event, patrz komentarz klasy.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent event) {
        var block = event.getBlock();
        Location lokalizacja = block.getLocation();

        Set<Location> grupa = zamrozoneGrupy.get(lokalizacja);
        if (grupa != null) {
            rozbijZamrozonaGrupe(event, grupa);
            return;
        }

        if (!aktywneGeneratory.contains(lokalizacja)) return;

        Player player = event.getPlayer();
        ItemStack narzedzie = player.getInventory().getItemInMainHand();

        if (!jestKilof(narzedzie)) {
            event.setCancelled(true);
            player.sendMessage(Component.text("Ten blok trzeba kopać kilofem!", NamedTextColor.RED));
            return;
        }

        if (maSpecjalnySilkTouch(narzedzie)) {
            // Jedyny przypadek, gdzie blok faktycznie znika na stałe - gracz zabiera go ze sobą.
            aktywneGeneratory.remove(lokalizacja);
            event.setDropItems(false);
            block.getWorld().dropItemNaturally(lokalizacja.clone().add(0.5, 0.5, 0.5), stworzGenerator());
            player.sendMessage(Component.text("Zebrałeś cały generator - możesz postawić go gdzie indziej!", NamedTextColor.GREEN));
            return;
        }

        // Zwykłe wykopanie: NIE anulujemy i NIE nadpisujemy dropów - podmiana na
        // Material.COBBLESTONE tuż przed dalszym przetwarzaniem eventu wystarczy, żeby
        // reszta (drop, dźwięk, PickaxeSkillManager#rollBonusZBruku) zadziałała dokładnie
        // jak przy zwykłym bruku, patrz komentarz klasy. Blok wraca po krótkim opóźnieniu.
        block.setType(Material.COBBLESTONE, false);
        zaplanujOdnowe(lokalizacja);

        ToolsService toolsService = CoreAPI.getToolsService();
        if (toolsService != null && toolsService.maWiecznaZime(narzedzie)) {
            zamrozPolaczoneGeneratory(lokalizacja, player);
        }
    }

    /** Planuje powrót bloku do MATERIAL_GENERATORA po ODNOWA_TICKOW - wspólne dla zwykłego wykopania i łańcuchowego wykopania zamrożonej grupy. */
    private void zaplanujOdnowe(Location lokalizacja) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // Ktoś w międzyczasie zebrał go specjalnym silk touchem - nie odbudowuj pustego miejsca.
            if (!aktywneGeneratory.contains(lokalizacja)) return;
            if (lokalizacja.getBlock().getType() == Material.AIR) {
                lokalizacja.getBlock().setType(MATERIAL_GENERATORA);
            }
        }, ODNOWA_TICKOW);
    }

    /**
     * Wieczna Zima Kilofa Niflheim (patrz ToolsService#maWiecznaZime) - do 5 POŁĄCZONYCH
     * (bezpośrednio sąsiadujących, zarejestrowanych jako generator - niezależnie od ich
     * aktualnego Materiału, bo gotowy generator to MOSSY_COBBLESTONE, nie COBBLESTONE jak
     * origin) sąsiadów origin zamienia w PRAWDZIWY, trwały Material.PACKED_ICE. Blok NIE
     * pęka sam - dopiero gdy gracz wykopie KTÓRYKOLWIEK blok z tej samej grupy (patrz
     * onBreak/rozbijZamrozonaGrupe), cała reszta pęka razem z nim.
     */
    private void zamrozPolaczoneGeneratory(Location origin, Player player) {
        List<Location> kandydaci = new ArrayList<>();
        for (BlockFace face : SASIEDNIE_SCIANY) {
            Location sasiad = origin.clone().add(face.getModX(), face.getModY(), face.getModZ());
            if (aktywneGeneratory.contains(sasiad) && !zamrozoneGrupy.containsKey(sasiad)) {
                kandydaci.add(sasiad);
            }
        }
        if (kandydaci.isEmpty()) return;
        Collections.shuffle(kandydaci);

        int ile = Math.min(1 + ThreadLocalRandom.current().nextInt(5), kandydaci.size());
        List<Location> wybrane = kandydaci.subList(0, ile);
        Set<Location> grupa = new HashSet<>(wybrane);

        for (Location loc : wybrane) {
            zamrozoneGrupy.put(loc, grupa);
            Block b = loc.getBlock();
            b.setType(Material.PACKED_ICE, false);
            Location center = loc.clone().add(0.5, 0.5, 0.5);
            b.getWorld().spawnParticle(Particle.SNOWFLAKE, center, 16, 0.35, 0.35, 0.35, 0.02);
            player.playSound(center, Sound.BLOCK_GLASS_BREAK, 1f, 1.3f);
        }
    }

    /**
     * Gracz wykopał JEDEN zamrożony blok grupy - reszta (do 4 pozostałych) pęka razem z nim,
     * daje normalny drop bruku generatora i wraca do własnego cyklu odnowy (zaplanujOdnowe).
     * Blok, który gracz faktycznie kliknął, zostaje jedynie podmieniony na Material.COBBLESTONE
     * (bez anulowania/nadpisywania dropów eventu) - dokładnie jak przy zwykłym wykopaniu, żeby
     * PickaxeSkillManager (priority NORMAL) naliczył dla niego bonus z bruku/exp normalnie.
     * Pozostali członkowie grupy nie mają własnego BlockBreakEvent, więc ich drop i odnowę
     * musimy obsłużyć ręcznie.
     */
    private void rozbijZamrozonaGrupe(BlockBreakEvent event, Set<Location> grupa) {
        Player player = event.getPlayer();
        ItemStack narzedzie = player.getInventory().getItemInMainHand();

        if (!jestKilof(narzedzie)) {
            event.setCancelled(true);
            player.sendMessage(Component.text("Ten blok trzeba kopać kilofem!", NamedTextColor.RED));
            return;
        }

        Location klikniety = event.getBlock().getLocation();
        for (Location member : new ArrayList<>(grupa)) {
            zamrozoneGrupy.remove(member);
            Location center = member.clone().add(0.5, 0.5, 0.5);
            member.getWorld().spawnParticle(Particle.SNOWFLAKE, center, 16, 0.35, 0.35, 0.35, 0.02);
            player.playSound(center, Sound.BLOCK_GLASS_BREAK, 1f, 1.3f);

            if (member.equals(klikniety)) {
                member.getBlock().setType(Material.COBBLESTONE, false);
            } else {
                Block b = member.getBlock();
                b.setType(Material.COBBLESTONE, false);
                Collection<ItemStack> drops = b.getDrops(narzedzie);
                b.setType(Material.AIR, false);
                for (ItemStack drop : drops) b.getWorld().dropItemNaturally(center, drop);
            }
            zaplanujOdnowe(member);
        }
    }

    private boolean jestGeneratorem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return CUSTOM_ID_GENERATOR.equals(item.getItemMeta().getPersistentDataContainer().get(CustomItemKeys.CUSTOM_ITEM_ID, PersistentDataType.STRING));
    }

    private boolean maSpecjalnySilkTouch(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return Boolean.TRUE.equals(item.getItemMeta().getPersistentDataContainer().get(CustomItemKeys.SPECJALNY_SILK_TOUCH, PersistentDataType.BOOLEAN));
    }

    /** Generator kopie się WYŁĄCZNIE kilofem (dowolny tier, w tym ewoluujący) - inne narzędzia są blokowane, patrz onBreak. */
    private boolean jestKilof(ItemStack item) {
        return item != null && item.getType().name().endsWith("_PICKAXE");
    }
}
