package elo.mainplugins.tools.pickaxe;

import elo.mainplugins.tools.pickaxe.BrukSurowce.SurowiecDrop;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Bonus do farmienia bruku (COBBLESTONE) - każdy tier kilofa ma własną szansę % (patrz
 * resources/bruk-szanse.yml) na to, że skopanie bruku od razu doda do wypadających przedmiotów
 * bonusowy surowiec. Pula możliwych surowców kumuluje się z tierem kilofa (patrz
 * BrukSurowce#pulaDlaTieru) - np. kilof kamienny losuje z puli drewnianego + własne.
 *
 * Klucze PDC "tool_type"/"tool_owner"/"pk_tier"/"pk_bruk_on" należą do LevelableToolsManager/
 * PickaxeSkillManager - tworzymy tu własne instancje NamespacedKey o tych samych nazwach
 * (ten sam plugin => równe klucze), zamiast wstrzykiwać tamte klasy tylko po to. Przełącznik
 * pk_bruk_on (domyślnie włączony) jest ustawiany przez gracza w hubie kilofa - patrz
 * PickaxeSkillManager#handleHubClick.
 */
public class BrukSurowceManager implements Listener {

    private final Plugin plugin;
    private final NamespacedKey keyType;
    private final NamespacedKey keyOwner;
    private final NamespacedKey pkTier;
    private final NamespacedKey pkBrukOn;

    private FileConfiguration szanseConfig;

    public BrukSurowceManager(Plugin plugin) {
        this.plugin = plugin;
        this.keyType = new NamespacedKey(plugin, "tool_type");
        this.keyOwner = new NamespacedKey(plugin, "tool_owner");
        this.pkTier = new NamespacedKey(plugin, "pk_tier");
        this.pkBrukOn = new NamespacedKey(plugin, "pk_bruk_on");
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
        if (block.getType() != Material.COBBLESTONE) return;

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isOwnedPickaxe(item, player) || !isEnabled(item)) return;

        int tier = tierOf(item);
        double szansa = szanseConfig.getDouble("tiery." + tier, 0.0);
        if (szansa <= 0 || ThreadLocalRandom.current().nextDouble(100.0) >= szansa) return;

        List<SurowiecDrop> pula = BrukSurowce.pulaDlaTieru(tier);
        if (pula.isEmpty()) return;
        SurowiecDrop wylosowany = pula.get(ThreadLocalRandom.current().nextInt(pula.size()));

        Location loc = block.getLocation();
        block.getWorld().dropItemNaturally(loc.clone().add(0.5, 0.5, 0.5),
                new ItemStack(wylosowany.item(), wylosowany.ilosc()));
        player.playSound(loc, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1f);
        player.sendActionBar(Component.text("W bruku znalazłeś " + wylosowany.nazwa() + "!", NamedTextColor.AQUA));
    }

    private boolean isEnabled(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return true;
        return meta.getPersistentDataContainer().getOrDefault(pkBrukOn, PersistentDataType.BYTE, (byte) 1) != 0;
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