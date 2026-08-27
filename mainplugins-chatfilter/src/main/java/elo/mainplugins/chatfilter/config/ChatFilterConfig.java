package elo.mainplugins.chatfilter.config;

import elo.mainplugins.core.api.Rank;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Cała konfiguracja filtrów czatu wczytana z chatfilter-config.yml (patrz
 * ChatFilterConfigLoader) - jeden niemutowalny snapshot, podmieniany w całości przy
 * /@reloadchatfilter. Permisja mainplugins.chatfilter.bypass (anty-spam) i wzorzec
 * regexu reklam (budowany z koncowkiDomen) zostają identyfikatorami/pochodnymi, nie
 * osobnymi polami konfiguracji.
 */
public record ChatFilterConfig(
        AntiSpam antySpam,
        AntyCaps antyCaps,
        DlugoscWiadomosci dlugoscWiadomosci,
        AntyReklama antyReklama,
        PowtorzonaWiadomosc powtorzonaWiadomosc,
        PowtarzajaceZnaki powtarzajaceZnaki
) {
    public record AntiSpam(boolean enabled, long cooldownMillis) {}

    public record AntyCaps(boolean enabled, int minDlugosc, double progUlamek, Set<Rank> exemptRangi) {}

    public record DlugoscWiadomosci(boolean enabled, int limitZnakow, Set<Rank> exemptRangi) {}

    public record AntyReklama(boolean enabled, List<String> koncowkiDomen, Pattern wzorzec, Set<Rank> exemptRangi) {}

    public record PowtorzonaWiadomosc(boolean enabled, Set<Rank> exemptRangi) {}

    public record PowtarzajaceZnaki(boolean enabled, int minPowtorzen, Pattern wzorzec, Set<Rank> exemptRangi) {}
}
