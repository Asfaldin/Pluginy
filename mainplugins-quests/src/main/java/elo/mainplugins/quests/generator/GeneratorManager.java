package elo.mainplugins.quests.generator;

import elo.mainplugins.core.util.CustomItemKeys;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
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

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Silnik generatorów tier 1-4, w pełni konfigurowalny z generatory.yml - NOWE tiery
 * (T2-T4) DLA obu istniejących rodzin (Bruk/Kruchy), z prawdziwą tabelą dropów
 * (% szansy per przedmiot). Świadomie NIE zastępuje/nie dotyka {@code GeneratorBrukuManager}
 * ani {@code GeneratorKruchychManager} (oba zostają nietknięte, razem z ich T1 i - w
 * przypadku Bruku - łańcuchowym mrożeniem Kilofa Niflheim "Wieczna Zima", które jest
 * dostrojone wyłącznie pod ten konkretny, stary T1) - to OSOBNY, dodatkowy silnik,
 * pod NOWE generatory zdefiniowane w configu.
 *
 * Dwa tryby (patrz TrybGeneratora): PRZEPUSZCZAJACY (kilofowa rodzina Bruku - podmienia
 * blok na prawdziwy wanilijski Material i NIE przerywa dalszego przetwarzania eventu, więc
 * cała reszta ekonomii kilofa - patrz PickaxeSkillManager#rollBonusZBruku - liczy się
 * dokładnie jak przy zwykłym bloku na wyspie) i BEZPOSREDNI (łopatowa rodzina Kruchych -
 * sam nadaje drop z tabeli, bez podmiany bloku). W OBU trybach dodatkowo NIEZALEŻNIE
 * losowana jest tabela bonus-dropy (patrz GeneratorDrop) - to jest właściwe miejsce na
 * "% szansy na rzadki surowiec" per tier.
 */
public class GeneratorManager implements Listener {

    private final Plugin plugin;
    private final Map<String, GeneratorDefinition> definicje = new LinkedHashMap<>();

    // Lokalizacja aktywnego generatora -> jego id, CZYSTO w pamięci (ten sam kompromis co
    // GeneratorBrukuManager/GeneratorKruchychManager) - nie przetrwa restartu w trakcie odnowy.
    private final Map<Location, String> aktywneGeneratory = new HashMap<>();

    public GeneratorManager(Plugin plugin) {
        this.plugin = plugin;
        reload();
    }

    /** Wczytuje generatory.yml na nowo - pod /@reloadgeneratory. */
    public void reload() {
        definicje.clear();
        definicje.putAll(GeneratorLoader.load(plugin));
        plugin.getLogger().info("Wczytano " + definicje.size() + " definicji generatorów z generatory.yml.");
    }

    public Set<String> ids() {
        return Set.copyOf(definicje.keySet());
    }

    /** Nagroda/wynik zakupu - patrz komentarz klasy. */
    public ItemStack stworz(String id) {
        GeneratorDefinition def = definicje.get(id);
        if (def == null) return null;

        ItemStack item = new ItemStack(def.materialGeneratora());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(def.nazwa());
        if (!def.lore().isEmpty()) meta.lore(def.lore());
        meta.getPersistentDataContainer().set(CustomItemKeys.CUSTOM_ITEM_ID, PersistentDataType.STRING, id);
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        String id = idGeneratora(event.getItemInHand());
        if (id == null) return;
        aktywneGeneratory.put(event.getBlock().getLocation(), id);
    }

    /** InstaBreak na starcie kopania łopatą - jak GeneratorKruchychManager#onDamage, tylko dla generatorów WYMAGAJĄCYCH łopaty (blok mógłby być wanilijsko "kilofowy"). */
    @EventHandler
    public void onDamage(BlockDamageEvent event) {
        GeneratorDefinition def = definicjaNaLokacji(event.getBlock().getLocation());
        if (def == null || def.narzedzie() != WymaganeNarzedzie.SHOVEL) return;
        if (jestWlasciwymNarzedziem(event.getPlayer().getInventory().getItemInMainHand(), def)) {
            event.setInstaBreak(true);
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Location lokalizacja = block.getLocation();
        GeneratorDefinition def = definicjaNaLokacji(lokalizacja);
        if (def == null) return;

        Player player = event.getPlayer();
        ItemStack narzedzie = player.getInventory().getItemInMainHand();

        if (!jestWlasciwymNarzedziem(narzedzie, def)) {
            event.setCancelled(true);
            player.sendMessage(Component.text(def.narzedzie() == WymaganeNarzedzie.PICKAXE
                    ? "Ten blok trzeba kopać kilofem!" : "Ten blok trzeba kopać łopatą!", net.kyori.adventure.text.format.NamedTextColor.RED));
            return;
        }

        if (maSpecjalnySilkTouch(narzedzie)) {
            aktywneGeneratory.remove(lokalizacja);
            event.setDropItems(false);
            block.getWorld().dropItemNaturally(lokalizacja.clone().add(0.5, 0.5, 0.5), stworz(def.id()));
            player.sendMessage(Component.text("Zebrałeś cały generator - możesz postawić go gdzie indziej!", net.kyori.adventure.text.format.NamedTextColor.GREEN));
            return;
        }

        Location srodek = lokalizacja.clone().add(0.5, 0.5, 0.5);
        if (def.tryb() == TrybGeneratora.BEZPOSREDNI) {
            event.setDropItems(false);
            GeneratorDrop wybrany = losujRownoWazony(def.bazaDropy());
            if (wybrany != null) {
                block.getWorld().dropItemNaturally(srodek, new ItemStack(wybrany.material(), wybrany.losujIlosc()));
            }
        } else {
            // PRZEPUSZCZAJACY: podmieniamy na prawdziwy blok TUŻ PRZED dalszym przetwarzaniem
            // eventu (priority LOWEST) - dalej leci jak zwykłe wykopanie tego materiału.
            block.setType(def.materialBazowe(), false);
        }

        for (GeneratorDrop bonus : def.bonusDropy()) {
            if (bonus.szansaProcent() != null && ThreadLocalRandom.current().nextDouble(100) < bonus.szansaProcent()) {
                block.getWorld().dropItemNaturally(srodek, new ItemStack(bonus.material(), bonus.losujIlosc()));
            }
        }

        zaplanujOdnowe(lokalizacja, def);
    }

    private void zaplanujOdnowe(Location lokalizacja, GeneratorDefinition def) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!aktywneGeneratory.containsKey(lokalizacja)) return;
            if (lokalizacja.getBlock().getType() == Material.AIR) {
                lokalizacja.getBlock().setType(def.materialGeneratora());
            }
        }, def.odnowaTickow());
    }

    private GeneratorDrop losujRownoWazony(List<GeneratorDrop> pula) {
        if (pula.isEmpty()) return null;
        return pula.get(ThreadLocalRandom.current().nextInt(pula.size()));
    }

    private GeneratorDefinition definicjaNaLokacji(Location lokalizacja) {
        String id = aktywneGeneratory.get(lokalizacja);
        return id != null ? definicje.get(id) : null;
    }

    private String idGeneratora(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String id = item.getItemMeta().getPersistentDataContainer().get(CustomItemKeys.CUSTOM_ITEM_ID, PersistentDataType.STRING);
        return id != null && definicje.containsKey(id) ? id : null;
    }

    private boolean maSpecjalnySilkTouch(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return Boolean.TRUE.equals(item.getItemMeta().getPersistentDataContainer().get(CustomItemKeys.SPECJALNY_SILK_TOUCH, PersistentDataType.BOOLEAN));
    }

    private boolean jestWlasciwymNarzedziem(ItemStack item, GeneratorDefinition def) {
        if (item == null) return false;
        String sufiks = def.narzedzie() == WymaganeNarzedzie.PICKAXE ? "_PICKAXE" : "_SHOVEL";
        return item.getType().name().endsWith(sufiks);
    }
}
