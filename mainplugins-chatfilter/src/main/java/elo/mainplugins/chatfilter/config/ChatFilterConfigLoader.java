package elo.mainplugins.chatfilter.config;

import elo.mainplugins.core.api.Rank;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Wczytuje chatfilter-config.yml do niemutowalnego {@link ChatFilterConfig}. Ten sam
 * wzorzec co MenuGuiLoader/IslandConfigLoader: plik kopiowany z zasobu TYLKO przy
 * pierwszym uruchomieniu, każda zła/brakująca wartość dostaje warning i pada na sensowny
 * domyślny odpowiednik dawnej hardkodowanej stałej, zamiast crashować cały serwer przy starcie.
 */
public final class ChatFilterConfigLoader {

    private ChatFilterConfigLoader() {}

    public static ChatFilterConfig load(Plugin plugin) {
        File file = new File(plugin.getDataFolder(), "chatfilter-config.yml");
        if (!file.exists()) {
            plugin.saveResource("chatfilter-config.yml", false);
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        Logger log = plugin.getLogger();

        boolean antySpamEnabled = cfg.getBoolean("anti-spam.enabled", true);
        double cooldownSekundy = cfg.getDouble("anti-spam.cooldown-sekundy", 5.0);
        if (cooldownSekundy < 0) {
            log.warning("chatfilter-config.yml: 'anti-spam.cooldown-sekundy' ujemne - uzywam 5.0.");
            cooldownSekundy = 5.0;
        }
        var antySpam = new ChatFilterConfig.AntiSpam(antySpamEnabled, Math.round(cooldownSekundy * 1000.0));

        boolean antyCapsEnabled = cfg.getBoolean("anti-caps.enabled", true);
        int minDlugosc = cfg.getInt("anti-caps.min-dlugosc", 8);
        int progProcent = cfg.getInt("anti-caps.prog-procent", 60);
        if (progProcent < 0 || progProcent > 100) {
            log.warning("chatfilter-config.yml: 'anti-caps.prog-procent' = " + progProcent + " poza zakresem 0-100 - uzywam 60.");
            progProcent = 60;
        }
        var antyCaps = new ChatFilterConfig.AntyCaps(antyCapsEnabled, minDlugosc, progProcent / 100.0,
                parseExemptRangi(cfg.getStringList("anti-caps.exempt-rangi"), "anti-caps", log));

        boolean dlugoscEnabled = cfg.getBoolean("dlugosc-wiadomosci.enabled", true);
        int limitZnakow = cfg.getInt("dlugosc-wiadomosci.limit-znakow", 128);
        var dlugoscWiadomosci = new ChatFilterConfig.DlugoscWiadomosci(dlugoscEnabled, limitZnakow,
                parseExemptRangi(cfg.getStringList("dlugosc-wiadomosci.exempt-rangi"), "dlugosc-wiadomosci", log));

        boolean antyReklamaEnabled = cfg.getBoolean("anti-reklama.enabled", true);
        List<String> koncowkiDomen = cfg.getStringList("anti-reklama.koncowki-domen");
        if (koncowkiDomen.isEmpty()) {
            log.warning("chatfilter-config.yml: 'anti-reklama.koncowki-domen' puste - uzywam wbudowanej domyslnej listy.");
            koncowkiDomen = List.of("pl", "com", "net", "org", "gg", "io", "eu", "de", "co", "xyz", "info", "tv", "me", "shop", "site", "online", "club", "top", "biz");
        }
        Pattern wzorzecReklamy = zbudujWzorzecReklamy(koncowkiDomen, log);
        var antyReklama = new ChatFilterConfig.AntyReklama(antyReklamaEnabled, koncowkiDomen, wzorzecReklamy,
                parseExemptRangi(cfg.getStringList("anti-reklama.exempt-rangi"), "anti-reklama", log));

        boolean powtorzonaEnabled = cfg.getBoolean("powtorzona-wiadomosc.enabled", true);
        var powtorzonaWiadomosc = new ChatFilterConfig.PowtorzonaWiadomosc(powtorzonaEnabled,
                parseExemptRangi(cfg.getStringList("powtorzona-wiadomosc.exempt-rangi"), "powtorzona-wiadomosc", log));

        boolean powtarzajaceEnabled = cfg.getBoolean("powtarzajace-znaki.enabled", true);
        int minPowtorzen = cfg.getInt("powtarzajace-znaki.min-powtorzen", 5);
        if (minPowtorzen < 2) {
            log.warning("chatfilter-config.yml: 'powtarzajace-znaki.min-powtorzen' = " + minPowtorzen + " za male (min 2) - uzywam 5.");
            minPowtorzen = 5;
        }
        Pattern wzorzecPowtorzen = Pattern.compile("(.)\\1{" + (minPowtorzen - 1) + ",}");
        var powtarzajaceZnaki = new ChatFilterConfig.PowtarzajaceZnaki(powtarzajaceEnabled, minPowtorzen, wzorzecPowtorzen,
                parseExemptRangi(cfg.getStringList("powtarzajace-znaki.exempt-rangi"), "powtarzajace-znaki", log));

        log.info("chatfilter-config.yml: wczytano konfiguracje filtrow czatu.");
        return new ChatFilterConfig(antySpam, antyCaps, dlugoscWiadomosci, antyReklama, powtorzonaWiadomosc, powtarzajaceZnaki);
    }

    private static Set<Rank> parseExemptRangi(List<String> raw, String sekcja, Logger log) {
        Set<Rank> rangi = new LinkedHashSet<>();
        for (String s : raw) {
            try {
                rangi.add(Rank.valueOf(s.trim().toUpperCase()));
            } catch (IllegalArgumentException e) {
                log.warning("chatfilter-config.yml: '" + sekcja + ".exempt-rangi' ma nieznana range '" + s + "' - pomijam.");
            }
        }
        return rangi.isEmpty() ? EnumSet.of(Rank.ADMIN) : rangi;
    }

    private static Pattern zbudujWzorzecReklamy(List<String> koncowkiDomen, Logger log) {
        String koncowki = String.join("|", koncowkiDomen);
        try {
            return Pattern.compile("(?i)(https?://\\S+|www\\.\\S+|\\b[a-z0-9-]+\\.(?:" + koncowki + ")\\b|\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b)");
        } catch (PatternSyntaxException e) {
            log.warning("chatfilter-config.yml: 'anti-reklama.koncowki-domen' zawiera znak lamiacy wzorzec - uzywam wbudowanej domyslnej listy.");
            return Pattern.compile("(?i)(https?://\\S+|www\\.\\S+|\\b[a-z0-9-]+\\.(?:pl|com|net|org|gg|io|eu|de|co|xyz|info|tv|me|shop|site|online|club|top|biz)\\b|\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b)");
        }
    }
}
