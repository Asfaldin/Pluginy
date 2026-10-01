package elo.mainplugins.mobs;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/** To, czego mob potrzebuje od pluginu: klucze tagów, przywoływanie innych mobów z paczki. */
interface MobHost {

    Plugin plugin();

    /** Tag na ciele moba: id moba z Kreatora (po nim mob wraca po restarcie). */
    NamespacedKey mobKey();

    /** Tag na pocisku: obrażenia z umiejętności. */
    NamespacedKey damageKey();

    /** Nowy mob z paczki (null = nie ma takiego id). summoned = przywołany (bez umiejętności "spawn" - inaczej rój klonowałby się bez końca). */
    LiveMob spawn(String id, Location at, boolean summoned);

    /** Żywe moby z paczki w promieniu (sojusznicy dla uzdrowiciela / szamana). */
    java.util.List<LiveMob> nearby(Location at, double radius);
}
