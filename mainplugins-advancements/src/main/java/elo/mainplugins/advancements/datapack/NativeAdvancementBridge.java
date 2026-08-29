package elo.mainplugins.advancements.datapack;

import elo.mainplugins.advancements.config.AchievementsConfig;
import elo.mainplugins.advancements.model.AchievementDef;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Łącznik między stanem pluginu a natywnym ekranem ESC → Postępy. Gdy datapack
 * jest włączony i wczytany, przyznanie osiągnięcia = zaliczenie kryteriów
 * odpowiadającego mu advancementu z datapacka (Bukkit API {@code awardCriteria}) -
 * to wanilia rysuje toast i drzewko, my tylko mówimy "ten gracz to ma".
 *
 * Gdy datapack jeszcze nie jest wczytany (świeża instalacja przed {@code /reload}) -
 * wszystkie metody są nieszkodliwym no-opem; stan i tak siedzi w osiagniecia-gracze.yml,
 * a przy najbliższym wejściu gracza po {@code /reload} zostanie wyrównany.
 */
public final class NativeAdvancementBridge {

    private volatile AchievementsConfig config;

    public NativeAdvancementBridge(AchievementsConfig config) {
        this.config = config;
    }

    public void aktualizujKonfiguracje(AchievementsConfig config) {
        this.config = config;
    }

    /** Czy w configu w ogóle włączono generowanie datapacka. */
    public boolean aktywny() {
        return config.datapack().wlaczony();
    }

    /** Czy serwer FAKTYCZNIE wczytał datapack (po /reload) - sprawdzamy root pierwszej kategorii. */
    @SuppressWarnings("deprecation")
    public boolean datapackWczytany() {
        if (!aktywny() || config.kategorie().isEmpty()) {
            return false;
        }
        NamespacedKey root = new NamespacedKey(config.datapack().namespace(),
                sanitize(config.kategorie().get(0).id()) + "/root");
        return pobierz(root) != null;
    }

    public void przyznaj(Player player, AchievementDef def) {
        if (!aktywny()) return;
        Advancement adv = pobierz(DatapackGenerator.klucz(config.datapack().namespace(), def));
        if (adv == null) return;
        AdvancementProgress pr = player.getAdvancementProgress(adv);
        if (pr.isDone()) return;
        for (String kryt : new ArrayList<>(pr.getRemainingCriteria())) {
            pr.awardCriteria(kryt);
        }
    }

    public void odbierz(Player player, AchievementDef def) {
        if (!aktywny()) return;
        Advancement adv = pobierz(DatapackGenerator.klucz(config.datapack().namespace(), def));
        if (adv == null) return;
        AdvancementProgress pr = player.getAdvancementProgress(adv);
        for (String kryt : new ArrayList<>(pr.getAwardedCriteria())) {
            pr.revokeCriteria(kryt);
        }
    }

    @SuppressWarnings("deprecation")
    private static Advancement pobierz(NamespacedKey key) {
        return Bukkit.getAdvancement(key);
    }

    private static String sanitize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
    }
}
