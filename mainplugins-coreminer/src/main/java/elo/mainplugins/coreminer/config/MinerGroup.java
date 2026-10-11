package elo.mainplugins.coreminer.config;

import org.bukkit.Material;

import java.util.Locale;
import java.util.Set;

/**
 * Grupa bloków kopanych całą żyłą (coreminer.yml, sekcja "grupy"), np. rudy, drewno, piasek.
 *
 * @param narzedzia  jakim narzędziem trzeba kopać (pusty = dowolnym, także ręką)
 * @param maxBlokow  najwięcej bloków jednej żyły w tej grupie (gracz może mieć mniej - limity w ustawieniach)
 * @param poPrzekatnej szukanie także po przekątnych (26 sąsiadów) - drzewa rosną na ukos, rudy zwykle nie
 * @param laczenie   TEN_SAM = tylko ten sam blok (rudy żelaza z żelazem, wersje deepslate razem); GRUPA = każdy blok z grupy
 * @param uprawnienie potrzebne do tej grupy (null = każdy z mainplugins.coreminer.use)
 */
public record MinerGroup(String id, String nazwa, boolean wlaczona, Material ikona, Set<Material> bloki, Set<ToolKind> narzedzia,
                         int maxBlokow, boolean poPrzekatnej, Laczenie laczenie, String uprawnienie) {

    public enum Laczenie { TEN_SAM, GRUPA }

    public boolean zawiera(Material m) {
        return bloki.contains(m);
    }

    /** Czy tym narzędziem w ręce można kopać tę grupę. */
    public boolean narzedzieOk(Material wReku) {
        if (narzedzia.isEmpty()) return true;
        for (ToolKind k : narzedzia) if (k.pasuje(wReku)) return true;
        return false;
    }

    /** Czy blok b łączy się w jedną żyłę z blokiem startowym (przy TEN_SAM: ten sam rodzaj, deepslate = zwykła). */
    public boolean laczy(Material start, Material b) {
        if (!bloki.contains(b)) return false;
        if (laczenie == Laczenie.GRUPA) return true;
        return rodzaj(start).equals(rodzaj(b));
    }

    /** "DEEPSLATE_IRON_ORE" i "IRON_ORE" to ta sama żyła. */
    static String rodzaj(Material m) {
        return m.name().replace("DEEPSLATE_", "").toLowerCase(Locale.ROOT);
    }
}
