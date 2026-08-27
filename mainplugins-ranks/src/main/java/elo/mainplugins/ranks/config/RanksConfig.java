package elo.mainplugins.ranks.config;

import elo.mainplugins.core.api.Rank;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Map;

/**
 * Wygląd wszystkich rang (prefiks + kolor nicku) wczytany z ranks-config.yml (patrz
 * RanksConfigLoader) - jeden niemutowalny snapshot, podmieniany w całości przy
 * /@reloadrangi. Sam ZESTAW rang (GRACZ/VIP/ADMIN) zostaje na stałe wbudowany w
 * mainplugins-core's Rank enum - to nie jest tu edytowalne, tylko ich prezentacja.
 */
public record RanksConfig(Map<Rank, Wyglad> wyglad) {

    public record Wyglad(Component prefix, NamedTextColor kolorNicku) {}

    public Wyglad dla(Rank rank) {
        return wyglad.get(rank);
    }
}
