package elo.mainplugins.spawners.config;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

/**
 * Jeden typ customowego spawnera wczytany z spawnery-typy.yml (patrz SpawnerConfigLoader).
 * Id jest dowolnym stringiem z YAML, więc dodanie typu nie wymaga rekompilacji.
 * Każde ustawienie poniżej "mnoznik" jest już policzone: wartość z typu albo - gdy typ jej nie
 * podaje - globalna z sekcji "ustawienia". Dzięki temu jeden spawner może być np. wolną farmą
 * zwierząt, a drugi szybkim spawnerem bossów, bez zmiany pozostałych.
 *
 * @param entityType  wanilijski mob; null, gdy spawner robi custom moba z Kreatora (customMob)
 * @param customMob   id moba z Kreatora mobów (mainplugins-mobs) albo null
 * @param ikona       ikona w oknie ulepszeń (domyślnie jajo spawnu tego moba / spawner)
 * @param mnoznik     ile razy droższe są ulepszenia tego spawnera od cen z ulepszenia.ceny-ilosc / ceny-szybkosc (1.0 = tyle samo)
 * @param stackowanie true = jedna żywa encja "Zombie x9" (lekko dla serwera); false = osobne moby, najwyżej maxNaRaz naraz
 * @param promienSpawnu jak daleko od spawnera (bloki) pojawiają się moby
 * @param ai          false = mob stoi w miejscu (farmy) - tylko moby wanilijskie
 * @param mnoznikDropu ile razy więcej dropu (1 = normalnie, 0 = bez dropu)
 * @param xp          stałe XP za zabicie; -1 = normalne
 * @param pora        kiedy spawner działa: ZAWSZE, DZIEN, NOC
 * @param ulepszenia  czy ten spawner ma ulepszenia w /spawnery (wymaga też globalnie włączonych)
 * @param listaUlepszen id ulepszeń z ulepszenia.lista, które ma ten spawner; null = wszystkie
 * @param limitNaWyspe ile spawnerów TEGO typu może stać na wyspie (0 = bez osobnego limitu)
 * @param nametag     napis "Zombie x9" nad mobem stosu
 * @param wKlatce     mob kręci się w klatce spawnera (wanilijski od gry, custom jako miniatura modelu)
 */
public record SpawnerTypeDef(String id, EntityType entityType, String customMob, String nazwaOdmieniona, String nazwaPojedyncza,
                             Material ikona, double mnoznik,
                             int interwalSekundBazowy, int interwalSekundNaPoziom, int iloscNaCyklBazowa, int iloscNaCyklNaPoziom,
                             int limitKolejki, boolean stackowanie, int maxNaRaz, int promienSpawnu, int promienAktywnosci,
                             boolean ai, double mnoznikDropu, int xp, Pora pora, boolean ulepszenia, java.util.List<String> listaUlepszen, int limitNaWyspe,
                             boolean nametag, boolean wKlatce) {

    public enum Pora { ZAWSZE, DZIEN, NOC }

    /** Prosty typ z samymi ustawieniami globalnymi (testy, stare wywołania). */
    public static SpawnerTypeDef prosty(String id, EntityType entityType, String nazwaOdmieniona, String nazwaPojedyncza,
                                        Material ikona, double mnoznik, SpawnerSettings u) {
        return new SpawnerTypeDef(id, entityType, null, nazwaOdmieniona, nazwaPojedyncza, ikona, mnoznik,
                u.interwalSekundBazowy(), u.interwalSekundNaPoziom(), u.iloscNaCyklBazowa(), u.iloscNaCyklNaPoziom(),
                u.limitKolejki(), true, 6, 3, u.promienAktywnosciGracza(), true, 1.0, -1, Pora.ZAWSZE, true, null, 0, true, true);
    }

    public boolean custom() {
        return customMob != null;
    }

    /** Sekundy między cyklami spawnu przy danym poziomie Szybkości. */
    public int interwalSekund(int poziom) {
        return Math.max(1, interwalSekundBazowy + interwalSekundNaPoziom * poziom);
    }

    /** Ile mobów daje jeden cykl przy danym poziomie Ilości. */
    public int iloscNaCykl(int poziom) {
        return Math.max(0, iloscNaCyklBazowa + iloscNaCyklNaPoziom * poziom);
    }

    /** Czy o tej porze dnia w świecie (ticki 0-23999) spawner działa. */
    public boolean dzialaO(long czasSwiata) {
        long t = Math.floorMod(czasSwiata, 24000L);
        boolean dzien = t < 12300 || t >= 23850;
        return switch (pora) {
            case ZAWSZE -> true;
            case DZIEN -> dzien;
            case NOC -> !dzien;
        };
    }
}
