package elo.mainplugins.core.api;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Nazwy przedmiotów w języku serwera (names/pl.yml, names/en.yml w folderze MainpluginsCore).
 *
 * <p>Po co to jest: serwer widzi tylko {@code COBBLESTONE}. Napis "Bruk" dorysowuje klient gracza
 * ze swojego tłumaczenia, więc bez tego słownika żadna wyszukiwarka nie znajdzie przedmiotu wpisanego
 * po polsku, a na czacie zamiast "Bruk" leci "COBBLESTONE".
 *
 * <p>Pliki są zwykłym yml - właściciel serwera może poprawić każdą nazwę albo dopisać własną.
 */
public interface ItemNameService {

    /** Nazwa materiału w języku serwera ("Bruk"). Gdy słownik jej nie ma - czytelna nazwa angielska ("Cobblestone"). */
    String name(Material material);

    /** Nazwa przedmiotu: własna nazwa nadana przedmiotowi, a jak jej nie ma - nazwa ze słownika. */
    String name(ItemStack item);

    /**
     * Po czym da się wyszukać ten przedmiot - do wyszukiwarek Sklepu i Targu. Zawsze małymi literami:
     * nazwa ze słownika, nazwa angielska (z podkreśleniami i spacjami) oraz własna nazwa przedmiotu.
     */
    List<String> searchTerms(ItemStack item);

    /** To samo dla samego materiału (pozycje sklepu, które nie mają jeszcze przedmiotu). */
    List<String> searchTerms(Material material);

    /** Wczytuje słownik od nowa (robi to /@reloadlang). */
    void reload();
}
