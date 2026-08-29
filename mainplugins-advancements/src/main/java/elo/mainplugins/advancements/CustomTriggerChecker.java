package elo.mainplugins.advancements;

import elo.mainplugins.advancements.model.AchievementDef;
import elo.mainplugins.advancements.model.TriggerSource;
import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.Rank;
import elo.mainplugins.core.api.RankService;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Statistic;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;

import java.util.Iterator;

/**
 * Cyklicznie (co {@code sprawdzanie-co-sekund}) sprawdza WŁASNE warunki osiągnięć
 * dla graczy online - kasa, ranga, czas gry, statystyki, liczba advancementów.
 * Vanilla advancementy łapie na żywo {@link elo.mainplugins.advancements.listener.VanillaAdvancementListener};
 * tu są sprawdzane dodatkowo, żeby wyrównać stan, gdy plugin był wyłączony w chwili zdobycia.
 */
public final class CustomTriggerChecker implements Runnable {

    private final AchievementManager manager;

    public CustomTriggerChecker(AchievementManager manager) {
        this.manager = manager;
    }

    @Override
    public void run() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            sprawdz(p, true);
        }
    }

    /**
     * Ciche wyrównanie na wejściu gracza - zalicza osiągnięcia, których warunek
     * już jest spełniony (np. vanilla advancement zdobyty, zanim osiągnięcie
     * powstało), bez tytułu/dźwięku. Nagroda i tak czeka w GUI.
     */
    public void synchronizujNaWejsciu(Player player) {
        sprawdz(player, false);
    }

    private void sprawdz(Player player, boolean powiadamiaj) {
        for (AchievementDef def : manager.config().osiagniecia()) {
            if (manager.ukonczone(player.getUniqueId(), def.id())) {
                continue;
            }
            if (spelniony(player, def.zrodlo())) {
                manager.oznaczUkonczone(player, def, powiadamiaj);
            }
        }
    }

    public boolean spelniony(Player player, TriggerSource src) {
        return switch (src) {
            case TriggerSource.Vanilla v -> {
                Advancement adv = pobierzAdvancement(v.key());
                yield adv != null && player.getAdvancementProgress(adv).isDone();
            }
            case TriggerSource.Balance b -> CoreAPI.getEconomyService().getKasa(player.getUniqueId()) >= b.amount();
            case TriggerSource.RankAtLeast r -> {
                RankService rs = CoreAPI.getRankService();
                Rank aktualna = rs != null ? rs.getRank(player.getUniqueId()) : Rank.GRACZ;
                yield aktualna.ordinal() >= r.rank().ordinal();
            }
            case TriggerSource.Playtime p -> {
                long ticki = player.getStatistic(Statistic.PLAY_ONE_MINUTE);
                yield ticki >= (long) p.hours() * 60L * 60L * 20L;
            }
            case TriggerSource.StatisticThreshold st -> wartoscStatystyki(player, st) >= st.amount();
            case TriggerSource.AdvancementCount ac -> policzUkonczoneAdvancementy(player) >= ac.amount();
        };
    }

    /**
     * Bieżąca liczbowa wartość postępu dla danego źródła (pod pasek "x / y" w GUI).
     * Dla {@link TriggerSource.Vanilla} zwraca -1 - postępu cząstkowego nie pokazujemy
     * (Minecraft i tak ma własny ekran advancementów).
     */
    public long wartoscBiezaca(Player player, TriggerSource src) {
        return switch (src) {
            case TriggerSource.Vanilla ignored -> -1;
            case TriggerSource.Balance ignored -> (long) CoreAPI.getEconomyService().getKasa(player.getUniqueId());
            case TriggerSource.RankAtLeast ignored -> {
                RankService rs = CoreAPI.getRankService();
                yield rs != null ? rs.getRank(player.getUniqueId()).ordinal() : Rank.GRACZ.ordinal();
            }
            case TriggerSource.Playtime ignored -> player.getStatistic(Statistic.PLAY_ONE_MINUTE) / (60L * 60L * 20L);
            case TriggerSource.StatisticThreshold st -> wartoscStatystyki(player, st);
            case TriggerSource.AdvancementCount ignored -> policzUkonczoneAdvancementy(player);
        };
    }

    /** Wartość docelowa (próg) dla danego źródła - -1 dla {@link TriggerSource.Vanilla}. */
    public long wartoscDocelowa(TriggerSource src) {
        return switch (src) {
            case TriggerSource.Vanilla ignored -> -1;
            case TriggerSource.Balance b -> (long) b.amount();
            case TriggerSource.RankAtLeast r -> r.rank().ordinal();
            case TriggerSource.Playtime p -> p.hours();
            case TriggerSource.StatisticThreshold st -> st.amount();
            case TriggerSource.AdvancementCount ac -> ac.amount();
        };
    }

    @SuppressWarnings("deprecation")
    private static Advancement pobierzAdvancement(NamespacedKey key) {
        return Bukkit.getAdvancement(key);
    }

    private static long wartoscStatystyki(Player player, TriggerSource.StatisticThreshold st) {
        try {
            Statistic.Type typ = st.statistic().getType();
            if (typ == Statistic.Type.BLOCK || typ == Statistic.Type.ITEM) {
                return player.getStatistic(st.statistic(), st.material());
            }
            if (typ == Statistic.Type.ENTITY) {
                return player.getStatistic(st.statistic(), st.entity());
            }
            return player.getStatistic(st.statistic());
        } catch (Exception e) {
            return 0;
        }
    }

    private static int policzUkonczoneAdvancementy(Player player) {
        int n = 0;
        Iterator<Advancement> it = Bukkit.advancementIterator();
        while (it.hasNext()) {
            Advancement adv = it.next();
            NamespacedKey k = adv.getKey();
            // Pomijamy ukryte advancementy-przepisy (minecraft:recipes/...) - to nie są "prawdziwe" osiągnięcia.
            if (k.getKey().startsWith("recipes/")) {
                continue;
            }
            if (player.getAdvancementProgress(adv).isDone()) {
                n++;
            }
        }
        return n;
    }
}
