package elo.mainplugins;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

public class EconomyManager {

    private final Plugin plugin;
    private final File plikEkonomii;
    private final FileConfiguration configEkonomii;

    public EconomyManager(Plugin plugin) {
        this.plugin = plugin;
        this.plikEkonomii = new File(plugin.getDataFolder(), "ekonomia.yml");
        if (!plikEkonomii.exists()) {
            plikEkonomii.getParentFile().mkdirs();
            try { plikEkonomii.createNewFile(); } catch (IOException ignored) {}
        }
        this.configEkonomii = YamlConfiguration.loadConfiguration(plikEkonomii);
    }

    public double getKasa(UUID uuid) {
        return configEkonomii.getDouble(uuid.toString(), 0.0); // 0.0 to kasa na start
    }

    public void setKasa(UUID uuid, double ilosc) {
        configEkonomii.set(uuid.toString(), ilosc);
        zapisz();
    }

    public void dodajKase(UUID uuid, double ilosc) {
        setKasa(uuid, getKasa(uuid) + ilosc);
    }

    public void odejmijKase(UUID uuid, double ilosc) {
        double aktualna = getKasa(uuid);
        if (aktualna - ilosc >= 0) {
            setKasa(uuid, aktualna - ilosc);
        } else {
            setKasa(uuid, 0);
        }
    }

    public boolean maWystarczajaco(UUID uuid, double ilosc) {
        return getKasa(uuid) >= ilosc;
    }

    private void zapisz() {
        try { configEkonomii.save(plikEkonomii); }
        catch (IOException e) { plugin.getLogger().warning("Nie mozna zapisac ekonomii!"); }
    }
}