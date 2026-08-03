package elo.mainplugins.core.economy;

import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.api.TopGracz;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class EconomyManager implements EconomyService {

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

    @Override
    public double getKasa(UUID uuid) {
        return configEkonomii.getDouble(uuid.toString(), 0.0); // 0.0 to kasa na start
    }

    @Override
    public void setKasa(UUID uuid, double ilosc) {
        configEkonomii.set(uuid.toString(), ilosc);
        zapisz();
    }

    @Override
    public void dodajKase(UUID uuid, double ilosc) {
        setKasa(uuid, getKasa(uuid) + ilosc);
    }

    @Override
    public void odejmijKase(UUID uuid, double ilosc) {
        double aktualna = getKasa(uuid);
        if (aktualna - ilosc >= 0) {
            setKasa(uuid, aktualna - ilosc);
        } else {
            setKasa(uuid, 0);
        }
    }

    @Override
    public boolean maWystarczajaco(UUID uuid, double ilosc) {
        return getKasa(uuid) >= ilosc;
    }

    @Override
    public List<TopGracz> getTop(int limit) {
        List<TopGracz> wynik = new ArrayList<>();
        for (String key : configEkonomii.getKeys(false)) {
            double kasa = configEkonomii.getDouble(key, 0.0);
            if (kasa <= 0) continue; // gracze bez kasy nie mają czego szukać w topce

            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                continue;
            }

            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
            String nick = offlinePlayer.getName() != null ? offlinePlayer.getName() : uuid.toString().substring(0, 8);
            wynik.add(new TopGracz(uuid, nick, kasa));
        }

        wynik.sort((a, b) -> Double.compare(b.kasa(), a.kasa()));
        return wynik.size() > limit ? wynik.subList(0, limit) : wynik;
    }

    private void zapisz() {
        try { configEkonomii.save(plikEkonomii); }
        catch (IOException e) { plugin.getLogger().warning("Nie mozna zapisac ekonomii!"); }
    }
}