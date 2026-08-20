package elo.mainplugins.shop;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * /@sklep — administracyjna edycja cen i mnożników.
 *
 * Ceny zapisywane są wprost do plików w categories/, więc zmiana przeżywa
 * restart serwera. Po każdej zmianie sklep przeładowuje się automatycznie.
 */
public class SklepAdminCommand implements CommandExecutor, TabCompleter {

    private final ShopManager shopManager;

    /**
     * Kto na co czeka z potwierdzeniem. Klucz to UUID gracza (albo "KONSOLA"),
     * wartość to opis operacji i moment jej zgłoszenia.
     */
    private final Map<String, OczekujaceP> oczekujace = new HashMap<>();

    /** Ile sekund ważne jest zapytanie o potwierdzenie. */
    private static final long WAZNOSC_SEKUND = 30;

    private record OczekujaceP(String akcja, String item, long czas) {}

    private String idNadawcy(CommandSender s) {
        return (s instanceof Player p) ? p.getUniqueId().toString() : "KONSOLA";
    }

    public SklepAdminCommand(ShopManager shopManager) {
        this.shopManager = shopManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            pomoc(sender);
            return true;
        }

        DynamicPriceManager ceny = shopManager.getCeny();

        switch (args[0].toLowerCase()) {

            case "tak", "potwierdzam" -> {
                OczekujaceP p = oczekujace.remove(idNadawcy(sender));
                if (p == null) {
                    blad(sender, "Nie ma nic do potwierdzenia.");
                    return true;
                }
                if (System.currentTimeMillis() - p.czas() > WAZNOSC_SEKUND * 1000) {
                    blad(sender, "Potwierdzenie wygaslo. Wpisz komende jeszcze raz.");
                    return true;
                }
                wykonajPotwierdzone(sender, p, ceny);
                return true;
            }

            case "nie", "anuluj" -> {
                if (oczekujace.remove(idNadawcy(sender)) != null) {
                    sender.sendMessage(szary("Anulowano."));
                } else {
                    blad(sender, "Nie ma nic do anulowania.");
                }
                return true;
            }

            case "resetall" -> {
                int ile = ceny.getWszystkieMnozniki().size();
                oczekujace.put(idNadawcy(sender),
                        new OczekujaceP("resetall", null, System.currentTimeMillis()));

                sender.sendMessage(Component.text("UWAGA: to zresetuje ceny WSZYSTKICH itemow.",
                        NamedTextColor.RED, TextDecoration.BOLD));
                sender.sendMessage(szary("Dotyczy " + ile + " pozycji. Zniknie cala historia "
                        + "wahan cen, blokady eventowe tez zostana zdjete."));
                sender.sendMessage(szary("Statystyki sprzedazy NIE zostana skasowane."));
                sender.sendMessage(Component.text("Wpisz /@sklep tak, zeby potwierdzic "
                        + "(masz " + WAZNOSC_SEKUND + " sekund).", NamedTextColor.GRAY));
                return true;
            }

            case "info" -> {
                if (args.length < 2) { blad(sender, "Uzycie: /@sklep info <item>"); return true; }
                pokazInfo(sender, args[1], ceny);
                return true;
            }

            case "reset" -> {
                if (args.length < 2) { blad(sender, "Uzycie: /@sklep reset <item>"); return true; }
                String item = args[1].toUpperCase();

                if (shopManager.znajdzItem(item) == null) {
                    blad(sender, "Nie znaleziono itemu: " + item);
                    return true;
                }

                double m = ceny.getMnoznik(item);
                oczekujace.put(idNadawcy(sender),
                        new OczekujaceP("reset", item, System.currentTimeMillis()));

                sender.sendMessage(Component.text("Czy na pewno zresetowac cene ",
                        NamedTextColor.YELLOW)
                        .append(Component.text(item, NamedTextColor.WHITE, TextDecoration.BOLD))
                        .append(Component.text(" do bazowej?", NamedTextColor.YELLOW)));
                sender.sendMessage(szary("Obecny mnoznik: " + String.format("%.2f", m)
                        + "  ->  wroci do 1.00"));
                sender.sendMessage(Component.text("Wpisz /@sklep tak, zeby potwierdzic "
                        + "(masz " + WAZNOSC_SEKUND + " sekund).", NamedTextColor.GRAY));
                return true;
            }

            case "event" -> {
                if (args.length < 2) {
                    blad(sender, "Uzycie: /@sklep event <item> <0.5-1.5|off>  albo  /@sklep event lista");
                    return true;
                }

                if (args[1].equalsIgnoreCase("lista")) {
                    Map<String, Double> zabl = ceny.getZablokowane();
                    if (zabl.isEmpty()) {
                        sender.sendMessage(szary("Zaden item nie ma zablokowanego mnoznika."));
                        return true;
                    }
                    sender.sendMessage(Component.text("=== Zablokowane mnozniki ===",
                            NamedTextColor.GOLD, TextDecoration.BOLD));
                    zabl.forEach((k, v) -> sender.sendMessage(
                            Component.text("  " + k, NamedTextColor.YELLOW)
                                    .append(Component.text("  x" + String.format("%.2f", v),
                                            NamedTextColor.AQUA))));
                    return true;
                }

                if (args.length < 3) {
                    blad(sender, "Uzycie: /@sklep event <item> <0.5-1.5|off>");
                    return true;
                }

                String item = args[1].toUpperCase();
                if (shopManager.znajdzItem(item) == null) {
                    blad(sender, "Nie znaleziono itemu: " + item);
                    return true;
                }

                if (args[2].equalsIgnoreCase("off")) {
                    if (!ceny.czyZablokowany(item)) {
                        blad(sender, item + " nie ma zablokowanego mnoznika.");
                        return true;
                    }
                    ceny.odblokujMnoznik(item);
                    sender.sendMessage(zielony("Zakonczono event dla " + item + "."));
                    sender.sendMessage(szary("Cena wrocila do bazowej (mnoznik 1.00) "
                            + "i od teraz znowu zmienia sie sama."));
                    return true;
                }

                try {
                    double w = Double.parseDouble(args[2].replace(',', '.'));
                    if (w < 0.5 || w > 1.5) {
                        blad(sender, "Mnoznik musi byc miedzy 0.5 a 1.5.");
                        return true;
                    }
                    ceny.zablokujMnoznik(item, w);
                    int procent = (int) Math.round((w - 1.0) * 100);
                    sender.sendMessage(zielony("EVENT: " + item + " ma teraz mnoznik "
                            + String.format("%.2f", w)
                            + " (" + (procent >= 0 ? "+" : "") + procent + "%)."));
                    sender.sendMessage(szary("Cena jest ZABLOKOWANA - nie zmieni sie sama."));
                    sender.sendMessage(szary("Zakoncz: /@sklep event " + item + " off"));
                } catch (NumberFormatException e) {
                    blad(sender, "To nie jest liczba: " + args[2]);
                }
                return true;
            }

            case "mnoznik" -> {
                if (args.length < 3) { blad(sender, "Uzycie: /@sklep mnoznik <item> <0.5-1.5>"); return true; }
                try {
                    double w = Double.parseDouble(args[2].replace(',', '.'));
                    ceny.ustawMnoznik(args[1].toUpperCase(), w);
                    sender.sendMessage(zielony("Mnoznik " + args[1] + " ustawiony na " + w + "."));
                } catch (NumberFormatException e) {
                    blad(sender, "To nie jest liczba: " + args[2]);
                }
                return true;
            }

            case "cena" -> {
                if (args.length < 4) {
                    blad(sender, "Uzycie: /@sklep cena <item> <kupno|skup> <kwota>");
                    return true;
                }
                zmienCene(sender, args[1], args[2], args[3]);
                return true;
            }

            default -> {
                pomoc(sender);
                return true;
            }
        }
    }

    // =========================================================================

    private void zmienCene(CommandSender sender, String item, String typ, String kwotaStr) {
        ShopManager.LokalizacjaItemu lok = shopManager.znajdzItem(item);
        if (lok == null) {
            blad(sender, "Nie znaleziono itemu: " + item);
            return;
        }

        int kwota;
        try {
            kwota = Integer.parseInt(kwotaStr);
        } catch (NumberFormatException e) {
            blad(sender, "Kwota musi byc liczba calkowita: " + kwotaStr);
            return;
        }
        if (kwota < 1) {
            blad(sender, "Kwota musi byc wieksza od zera.");
            return;
        }

        String pole = switch (typ.toLowerCase()) {
            case "kupno" -> "buy-price";
            case "skup"  -> "sell-price";
            default -> null;
        };
        if (pole == null) {
            blad(sender, "Typ musi byc 'kupno' albo 'skup'.");
            return;
        }

        // Walidacja: skup nie może przebić kupna, bo to natychmiastowa maszynka
        // do pieniędzy. Sprawdzamy PRZED zapisem, żeby nie trzeba było tego
        // odkręcać po fakcie.
        String ostrzezenie = sprawdzMarze(lok, pole, kwota);
        if (ostrzezenie != null) {
            blad(sender, ostrzezenie);
            return;
        }

        String err = shopManager.zmienCeneWPliku(lok, pole, kwota);
        if (err != null) {
            blad(sender, err);
            return;
        }

        sender.sendMessage(zielony("Zmieniono " + typ + " dla " + item + " na " + kwota + " $."));
        sender.sendMessage(szary("Zapisano w categories/" + lok.kategoria() + ".yml, sklep przeladowany."));

        // Mnożnik zostaje nietknięty — admin może chcieć zmienić cenę bazową
        // bez kasowania historii rynkowej. Ale warto o tym przypomnieć.
        double m = shopManager.getCeny().getMnoznik(item.toUpperCase());
        if (Math.abs(m - 1.0) > 0.02) {
            sender.sendMessage(szary("Uwaga: mnoznik tego itemu to " + String.format("%.2f", m)
                    + " - realna cena skupu bedzie inna niz wpisana."));
            sender.sendMessage(szary("Zrob /@sklep reset " + item + ", zeby to wyzerowac."));
        }
    }

    private void wykonajPotwierdzone(CommandSender sender, OczekujaceP p, DynamicPriceManager ceny) {
        switch (p.akcja()) {
            case "reset" -> {
                ceny.resetujItem(p.item());
                sender.sendMessage(zielony("Cena " + p.item() + " wrocila do bazowej."));
            }
            case "resetall" -> {
                ceny.wymusReset();
                sender.sendMessage(zielony("Zresetowano ceny wszystkich itemow do bazowych."));
            }
        }
    }

    /** Zwraca komunikat błędu, gdy zmiana rozwaliłaby marżę. */
    private String sprawdzMarze(ShopManager.LokalizacjaItemu lok, String pole, int kwota) {
        int buy  = shopManager.getSklepConfig().getInt(lok.path() + "buy-price", -1);
        int sell = shopManager.getSklepConfig().getInt(lok.path() + "sell-price", -1);
        int lotBuy  = shopManager.getSklepConfig().getInt(lok.path() + "amount", 1);
        int lotSell = shopManager.getSklepConfig().getInt(lok.path() + "sell-amount", lotBuy);

        if (pole.equals("buy-price")) buy = kwota; else sell = kwota;
        if (buy < 0 || sell < 0) return null;   // item jednostronny, nie ma czego porównywać

        double zaSztukeBuy  = (double) buy / lotBuy;
        double zaSztukeSell = (double) sell / lotSell;

        if (zaSztukeSell >= zaSztukeBuy) {
            return "ODRZUCONO: skup (" + String.format("%.2f", zaSztukeSell)
                    + " $/szt) bylby >= kupna (" + String.format("%.2f", zaSztukeBuy)
                    + " $/szt). To maszynka do pieniedzy.";
        }
        return null;
    }

    private void pokazInfo(CommandSender sender, String item, DynamicPriceManager ceny) {
        ShopManager.LokalizacjaItemu lok = shopManager.znajdzItem(item);
        if (lok == null) {
            blad(sender, "Nie znaleziono itemu: " + item);
            return;
        }
        String klucz = item.toUpperCase();
        var cfg = shopManager.getSklepConfig();

        int buy  = cfg.getInt(lok.path() + "buy-price", -1);
        int sell = cfg.getInt(lok.path() + "sell-price", -1);
        int lotBuy  = cfg.getInt(lok.path() + "amount", 1);
        int lotSell = cfg.getInt(lok.path() + "sell-amount", lotBuy);
        double m = ceny.getMnoznik(klucz);

        sender.sendMessage(Component.text("=== " + item + " ===", NamedTextColor.GOLD, TextDecoration.BOLD));
        sender.sendMessage(szary("Kategoria: " + lok.kategoria() + ".yml"));
        sender.sendMessage(szary("Kupno:  " + (buy < 0 ? "brak" : buy + " $ za " + lotBuy + " szt.")));
        sender.sendMessage(szary("Skup:   " + (sell < 0 ? "brak" : sell + " $ za " + lotSell + " szt. (bazowo)")));
        if (sell >= 0) {
            int realny = ceny.policzCeneSkupu(klucz, sell,
                    buy < 0 ? -1 : (int) Math.round((double) buy / lotBuy * lotSell));
            sender.sendMessage(Component.text("Skup realny: " + realny + " $ za " + lotSell + " szt.",
                    NamedTextColor.AQUA));
        }
        sender.sendMessage(szary("Mnoznik: " + String.format("%.3f", m)
                + (m > 1.02 ? "  (podwyzszony)" : m < 0.98 ? "  (obnizony)" : "  (bazowy)")));
        if (ceny.czyZablokowany(klucz)) {
            sender.sendMessage(Component.text("ZABLOKOWANY (event) - cena nie zmienia sie sama",
                    NamedTextColor.LIGHT_PURPLE));
        }
        sender.sendMessage(szary("Norma sprzedazy: " + String.format("%.1f", ceny.getNorma(klucz)) + " szt./h"));
        sender.sendMessage(szary("Susza: " + ceny.getLicznikSuszy(klucz) + " cykli"));
        sender.sendMessage(szary("Do globalnego resetu: " + ceny.cykliDoResetu() + " cykli"));
    }

    private void pomoc(CommandSender s) {
        s.sendMessage(Component.text("=== /@sklep ===", NamedTextColor.GOLD, TextDecoration.BOLD));
        s.sendMessage(szary("/@sklep info <item>                 stan itemu"));
        s.sendMessage(szary("/@sklep cena <item> kupno <kwota>   zmien cene kupna"));
        s.sendMessage(szary("/@sklep cena <item> skup <kwota>    zmien cene skupu"));
        s.sendMessage(szary("/@sklep reset <item>                mnoznik -> 1.0"));
        s.sendMessage(szary("/@sklep resetall                    wszystkie mnozniki -> 1.0"));
        s.sendMessage(szary("/@sklep mnoznik <item> <0.5-1.5>    reczne ustawienie"));
        s.sendMessage(szary("/@sklep event <item> <0.5-1.5>    ustaw i zablokuj (event)"));
        s.sendMessage(szary("/@sklep event <item> off          odblokuj"));
        s.sendMessage(szary("/@sklep event lista               co jest zablokowane"));
    }

    private static Component zielony(String t) { return Component.text(t, NamedTextColor.GREEN); }
    private static Component szary(String t)   { return Component.text(t, NamedTextColor.GRAY); }
    private static void blad(CommandSender s, String t) {
        s.sendMessage(Component.text(t, NamedTextColor.RED));
    }

    // =========================================================================
    //  TAB-COMPLETION
    // =========================================================================

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> wynik = new ArrayList<>();

        if (args.length == 1) {
            for (String s : List.of("info", "cena", "reset", "resetall", "mnoznik",
                                    "event", "tak", "nie")) {
                if (s.startsWith(args[0].toLowerCase())) wynik.add(s);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("event")) {
            wynik.add("lista");
            String pref = args[1].toUpperCase();
            for (String id : shopManager.wszystkieIdentyfikatory()) {
                if (id.startsWith(pref)) wynik.add(id);
            }
        } else if (args.length == 2 && !args[0].equalsIgnoreCase("resetall")) {
            String pref = args[1].toUpperCase();
            for (String id : shopManager.wszystkieIdentyfikatory()) {
                if (id.startsWith(pref)) wynik.add(id);
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("cena")) {
            for (String s : List.of("kupno", "skup")) {
                if (s.startsWith(args[2].toLowerCase())) wynik.add(s);
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("event")) {
            for (String s : List.of("off", "1.2", "1.5", "0.8")) {
                if (s.startsWith(args[2].toLowerCase())) wynik.add(s);
            }
        }
        return wynik;
    }
}
