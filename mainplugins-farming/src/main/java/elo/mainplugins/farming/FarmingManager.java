package elo.mainplugins.farming;

import elo.mainplugins.farming.config.FarmingConfig;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class FarmingManager implements Listener {

    private final Plugin plugin;
    private final File plikUpraw;
    private final FileConfiguration configUpraw;
    private final Set<String> zloteMarchewki = new HashSet<>();
    private volatile FarmingConfig config;

    public FarmingManager(Plugin plugin, FarmingConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.plikUpraw = new File(plugin.getDataFolder(), "uprawy.yml");
        if (!plikUpraw.exists()) {
            plikUpraw.getParentFile().mkdirs();
            try { plikUpraw.createNewFile(); } catch (IOException ignored) {}
        }
        this.configUpraw = YamlConfiguration.loadConfiguration(plikUpraw);
        zloteMarchewki.addAll(configUpraw.getStringList("zlote-marchewki"));
    }

    public void aktualizujKonfiguracje(FarmingConfig nowy) {
        this.config = nowy;
    }

    @EventHandler
    public void onSadzenie(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Block klikniety = event.getClickedBlock();
        if (klikniety == null || klikniety.getType() != Material.FARMLAND) return;

        Player player = event.getPlayer();
        ItemStack wRece = player.getInventory().getItemInMainHand();
        if (wRece.getType() != Material.GOLDEN_CARROT) return;

        Block nad = klikniety.getRelative(BlockFace.UP);
        if (nad.getType() != Material.AIR) return;

        event.setCancelled(true);

        // Rośnie na silniku wzrostu zwykłej marchewki (losowe ticki gry) - realny czas wzrostu za darmo.
        nad.setType(Material.CARROTS);
        wRece.setAmount(wRece.getAmount() - 1);
        player.getInventory().setItemInMainHand(wRece);

        zloteMarchewki.add(kluczLokalizacji(nad.getLocation()));
        zapisz();
    }

    @EventHandler(ignoreCancelled = true)
    public void onZbior(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.CARROTS) return;

        String klucz = kluczLokalizacji(block.getLocation());
        if (!zloteMarchewki.remove(klucz)) return;
        zapisz();

        if (!(block.getBlockData() instanceof Ageable ageable) || ageable.getAge() < ageable.getMaximumAge()) {
            return; // niedojrzała - normalny drop zwykłej marchewki
        }

        event.setDropItems(false);
        block.getWorld().dropItemNaturally(block.getLocation(), new ItemStack(Material.GOLDEN_CARROT, config.losowaIloscZlotejMarchewki()));
    }

    private String kluczLokalizacji(Location loc) {
        return loc.getWorld().getName() + ";" + loc.getBlockX() + ";" + loc.getBlockY() + ";" + loc.getBlockZ();
    }

    private void zapisz() {
        configUpraw.set("zlote-marchewki", new ArrayList<>(zloteMarchewki));
        try { configUpraw.save(plikUpraw); }
        catch (IOException e) { plugin.getLogger().warning("Nie mozna zapisac uprawy.yml!"); }
    }
}