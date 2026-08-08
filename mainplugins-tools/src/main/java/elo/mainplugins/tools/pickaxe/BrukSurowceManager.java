package elo.mainplugins.tools.pickaxe;

import elo.mainplugins.tools.pickaxe.BrukSurowce.SurowiecDrop;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Bonus do farmienia bruku (COBBLESTONE) - każdy tier kilofa ma własną szansę % (patrz
 * resources/bruk-szanse.yml) na to, że w miejscu skopanego bruku pojawi się blok surowca
 * (rudy), który trzeba jeszcze dobić. Pula możliwych surowców kumuluje się z tierem kilofa
 * (patrz BrukSurowce#pulaDlaTieru) - np. kilof kamienny losuje z puli drewnianego + własne.
 *
 * Klucze PDC "tool_type"/"tool_owner"/"pk_tier" należą do LevelableToolsManager/
 * PickaxeSkillManager - tworzymy tu własne instancje NamespacedKey o tych samych nazwach
 * (ten sam plugin => równe klucze), zamiast wstrzykiwać tamte klasy tylko po to.
 *
 * Dobicie zwykłego BlockBreakEvent na wystawionym bloku rudy nie jest tu w żaden sposób
 * blokowane (poza podmianą lootu) - PickaxeSkillManager.onBlockBreak dostanie ten sam
 * event i naliczy exp/perki normalnie, bo blok jest realną rudą.
 */
public class BrukSurowceManager implements Listener {

    private final Plugin plugin;
    private final NamespacedKey keyType;
    private final NamespacedKey keyOwner;
    private final NamespacedKey pkTier;

    private FileConfiguration szanseConfig;

    // Bloki surowca czekające na dobicie - kluczowane po lokalizacji, znikają po skopaniu.
    // Celowo w pamięci (nie PDC/plik) - jak reszta krótkotrwałego stanu w tym module; jeśli
    // gracz zostawi taki blok nietknięty do restartu serwera, po prostu przepadnie.
    private final Map<Location, SurowiecDrop> aktywneBloki = new HashMap<>();

    public BrukSurowceManager(Plugin plugin) {
        this.plugin = plugin;
        this.keyType = new NamespacedKey(plugin, "tool_type");
        this.keyOwner = new NamespacedKey(plugin, "tool_owner");
        this.pkTier = new NamespacedKey(plugin, "pk_tier");
        wczytajKonfiguracje();
    }

    /** Szansa % (tier kilofa 0-4) skonfigurowana w bruk-szanse.yml - pod wyświetlanie w hubie kilofa. */
    public double szansaDlaTieru(int tier) {
        return szanseConfig.getDouble("tiery." + tier, 0.0);
    }

    private void wczytajKonfiguracje() {
        File plik = new File(plugin.getDataFolder(), "bruk-szanse.yml");
        if (!plik.exists()) {
            // Domyślne wartości żyją jako zasób pluginu (resources/bruk-szanse.yml) -
            // kopiujemy 1:1 tylko przy pierwszym uruchomieniu, żeby nie nadpisywać
            // ręcznych zmian admina przy kolejnych restartach.
            plugin.saveResource("bruk-szanse.yml", false);
        }
        szanseConfig = YamlConfiguration.loadConfiguration(plik);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Location loc = block.getLocation();

        SurowiecDrop oczekujacy = aktywneBloki.remove(loc);
        if (oczekujacy != null) {
            event.setDropItems(false);
            block.getWorld().dropItemNaturally(loc.clone().add(0.5, 0.5, 0.5),
                    new ItemStack(oczekujacy.item(), oczekujacy.ilosc()));
            block.getWorld().playSound(loc, Sound.ENTITY_ITEM_PICKUP, 1f, 1f);
            return;
        }

        if (block.getType() != Material.COBBLESTONE) return;

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isOwnedPickaxe(item, player)) return;

        int tier = tierOf(item);
        double szansa = szanseConfig.getDouble("tiery." + tier, 0.0);
        if (szansa <= 0 || ThreadLocalRandom.current().nextDouble(100.0) >= szansa) return;

        List<SurowiecDrop> pula = BrukSurowce.pulaDlaTieru(tier);
        if (pula.isEmpty()) return;
        SurowiecDrop wylosowany = pula.get(ThreadLocalRandom.current().nextInt(pula.size()));

        // Blok znika dopiero PO przetworzeniu tego eventu przez serwer - podmieniamy go
        // na rudę dopiero w następnym ticku, inaczej nasz setType zostałby zaraz nadpisany
        // z powrotem na AIR przez naturalny przebieg zniszczenia bruku.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (block.getType() == Material.AIR) {
                block.setType(wylosowany.blok());
                aktywneBloki.put(loc, wylosowany);
                player.playSound(loc, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1f);
                player.sendActionBar(Component.text("W bruku błysnął " + wylosowany.nazwa() + "!", NamedTextColor.AQUA));
            }
        });
    }

    private boolean isOwnedPickaxe(ItemStack item, Player player) {
        if (item == null || item.getType().isAir()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (!"pickaxe".equals(pdc.get(keyType, PersistentDataType.STRING))) return false;
        String owner = pdc.get(keyOwner, PersistentDataType.STRING);
        return owner != null && owner.equals(player.getUniqueId().toString());
    }

    private int tierOf(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return 0;
        return meta.getPersistentDataContainer().getOrDefault(pkTier, PersistentDataType.INTEGER, 0);
    }
}