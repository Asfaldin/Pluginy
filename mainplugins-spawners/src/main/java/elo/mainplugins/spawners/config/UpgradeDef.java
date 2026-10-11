package elo.mainplugins.spawners.config;

import org.bukkit.Material;

import java.util.List;

/**
 * Jedno ulepszenie spawnerów z spawnery-typy.yml (sekcja ulepszenia.lista) - administrator może
 * dodać dowolnie wiele, każde z innym efektem, liczbą poziomów i cenami.
 *
 * Poziomy liczą się od 1 (nic nie kupione). Ilość i Szybkość działają jak dawniej: poziom wchodzi
 * do wzoru typu (interwal/ilosc "na poziom" - krok Szybkość w aplikacji albo własne ustawienia typu),
 * więc naPoziom jest dla nich ignorowane. Pozostałe efekty dodają naPoziom za każdy kupiony poziom.
 *
 * @param naPoziom zmiana za poziom dla efektów DROP (+×), XP (+XP), MAX_NARAZ, STOS, ZASIEG (+bloki)
 * @param ceny     cena podstawowa z poziomu 1, 2, 3... na kolejny (dalsze = ostatnia), × mnożnik spawnera
 */
public record UpgradeDef(String id, String nazwa, Material ikona, Efekt efekt, double naPoziom, int maxPoziom, List<Integer> ceny) {

    /** Na co działa ulepszenie. */
    public enum Efekt {
        /** Więcej mobów na cykl. */
        ILOSC(1),
        /** Krótszy czas między cyklami. */
        SZYBKOSC(-4),
        /** Mnożnik dropu (+0,25× za poziom). */
        DROP(0.25),
        /** Dodatkowe XP za zabicie. */
        XP(2),
        /** Więcej osobnych mobów naraz (spawnery bez stackowania). */
        MAX_NARAZ(1),
        /** Większy stos (spawnery ze stackowaniem). */
        STOS(10),
        /** Większy zasięg aktywności (bloki). */
        ZASIEG(4);

        /** Domyślna zmiana za poziom, gdy w pliku jej nie ma. */
        public final double domyslnie;

        Efekt(double domyslnie) {
            this.domyslnie = domyslnie;
        }

        public static Efekt z(String raw) {
            if (raw == null) return null;
            String v = raw.trim().toLowerCase(java.util.Locale.ROOT).replace('-', '_');
            return switch (v) {
                case "ilosc", "ilość", "amount" -> ILOSC;
                case "szybkosc", "szybkość", "speed" -> SZYBKOSC;
                case "drop", "drops" -> DROP;
                case "xp", "exp" -> XP;
                case "max_naraz", "max_mobow_naraz", "max_mobs" -> MAX_NARAZ;
                case "stos", "stack", "limit_kolejki" -> STOS;
                case "zasieg", "zasięg", "range" -> ZASIEG;
                default -> null;
            };
        }

        /** Domyślna ikona w oknie ulepszeń. */
        public Material ikona() {
            return switch (this) {
                case ILOSC -> Material.CHEST;
                case SZYBKOSC -> Material.CLOCK;
                case DROP -> Material.GOLD_INGOT;
                case XP -> Material.EXPERIENCE_BOTTLE;
                case MAX_NARAZ -> Material.ZOMBIE_HEAD;
                case STOS -> Material.HOPPER;
                case ZASIEG -> Material.ENDER_EYE;
            };
        }
    }

    /** Cena z poziomu obecnyPoziom na kolejny dla spawnera z danym mnożnikiem (jak SpawnerSettings.kosztUlepszenia). */
    public int koszt(double mnoznik, int obecnyPoziom) {
        return SpawnerSettings.kosztUlepszenia(ceny, mnoznik, obecnyPoziom);
    }
}
