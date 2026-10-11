package elo.mainplugins.coreminer.config;

import java.util.Map;
import java.util.Set;

/**
 * Ustawienia CoreMinera wspólne dla wszystkich grup (coreminer.yml, sekcja "ustawienia").
 *
 * @param tryb              KUCANIE (kucnij i kop), ZAWSZE, PRZELACZNIK (tylko po /coreminer on)
 * @param domyslnieWlaczony przy PRZELACZNIK: czy nowy gracz ma włączone; w pozostałych trybach gracz może wyłączyć /coreminer off
 * @param maxBlokow         ile bloków naraz dla zwykłego gracza (górny limit to i tak maxBlokow grupy)
 * @param limityUprawnien   uprawnienie -> wyższy limit (np. VIP 128); gracz dostaje najwyższy pasujący
 * @param maxOdleglosc      najdalej od pierwszego bloku (bloki) - żyła dalej się nie liczy
 * @param swiatyWlaczone    tylko te światy (pusty = wszystkie)
 * @param swiatyWylaczone   nigdy w tych światach
 * @param wKreatywnym       działa też w trybie kreatywnym
 * @param wytrzymalosc      ile wytrzymałości zużywa każdy blok żyły (1 = jak zwykłe kopanie, 0 = nic); Unbreaking działa
 * @param chronNarzedzie    zatrzymaj, gdy narzędziu zostanie tyle wytrzymałości (0 = kop do zniszczenia)
 * @param glod              wyczerpanie (głód) za każdy blok żyły (zwykłe kopanie ~0.005)
 * @param cooldownSekund    przerwa między żyłami
 * @param kosztZaBlok       pieniądze za każdy blok żyły (0 = za darmo)
 * @param drop              gdzie trafia drop
 * @param przetapianie      rudy i inne bloki od razu przetopione (jak w piecu)
 * @param xp                gdzie trafia doświadczenie
 * @param podglad           podświetlenie żyły cząsteczkami, zanim gracz ją wykopie
 * @param czasteczka        cząsteczka podglądu (nazwa z gry, np. END_ROD)
 * @param podgladMax        najwięcej podświetlonych bloków
 * @param dzwiek            dźwięk po wykopaniu żyły (klucz z gry, pusty = brak)
 * @param komunikat         "Wykopano 12 bloków" nad paskiem
 * @param opoznienieTickow  0 = cała żyła naraz; >0 = po kawałku (animacja), co tyle ticków
 * @param blokowNaRaz       ile bloków w jednym kawałku animacji
 */
public record MinerSettings(Tryb tryb, boolean domyslnieWlaczony, int maxBlokow, Map<String, Integer> limityUprawnien, int maxOdleglosc,
                            Set<String> swiatyWlaczone, Set<String> swiatyWylaczone, boolean wKreatywnym,
                            double wytrzymalosc, int chronNarzedzie, double glod, double cooldownSekund, double kosztZaBlok,
                            Drop drop, boolean przetapianie, Xp xp,
                            boolean podglad, String czasteczka, int podgladMax, String dzwiek, boolean komunikat,
                            int opoznienieTickow, int blokowNaRaz) {

    public enum Tryb { KUCANIE, ZAWSZE, PRZELACZNIK }

    public enum Drop { EKWIPUNEK, PIERWSZY_BLOK, NATURALNIE }

    public enum Xp { GRACZ, KULE, BRAK }

    /** Czy CoreMiner działa w tym świecie. */
    public boolean swiatOk(String swiat) {
        if (swiatyWylaczone.contains(swiat)) return false;
        return swiatyWlaczone.isEmpty() || swiatyWlaczone.contains(swiat);
    }
}
