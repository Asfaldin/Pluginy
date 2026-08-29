package elo.mainplugins.advancements.model;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;

import java.util.List;

/**
 * Jedno osiągnięcie wczytane z osiagniecia.yml (sekcja pod {@code osiagniecia:}).
 * Niemutowalne - cała lista jest budowana od zera przy każdym przeładowaniu
 * (patrz AchievementsConfigLoader), a stan graczy (zdobyte / odebrane) trzyma
 * osobno AchievementManager, więc reload treści nie kasuje postępu.
 *
 * @param id           stabilny klucz osiągnięcia (używany też w pliku danych graczy
 *                     i jako nazwa advancementu w datapacku: {@code <ns>:<kategoria>/<id>})
 * @param kategoriaId  id {@link AchievementCategory}, do której należy
 * @param zrodlo       jak plugin wykrywa zdobycie (patrz {@link TriggerSource})
 * @param ikona        materiał ikony w GUI i w natywnym ekranie
 * @param nazwa        wyświetlana nazwa (Component bez kursywy)
 * @param opis         linie opisu pod nazwą (Component-y bez kursywy)
 * @param ramka        kształt ramki w natywnym ekranie: {@code task} / {@code goal} / {@code challenge}
 * @param ukryte       gdy true - widoczne dopiero po zdobyciu (wcześniej "???")
 * @param spektakularne gdy true - zdobycie odpala widowiskowy efekt (jak najrzadsze ryby)
 * @param auto         gdy true - nagrody lecą od razu przy zdobyciu, bez klikania w GUI
 * @param nagrody      lista nagród do wręczenia przy odbiorze (może być pusta)
 */
public record AchievementDef(String id, String kategoriaId, TriggerSource zrodlo, Material ikona,
                             Component nazwa, List<Component> opis, String ramka, boolean ukryte,
                             boolean spektakularne, boolean auto, List<Reward> nagrody) {

    public AchievementDef {
        opis = List.copyOf(opis);
        nagrody = List.copyOf(nagrody);
    }

    /** Osiągnięcie bez nagród - samo zdobycie jest "nagrodą" (wpis w kolekcji). */
    public boolean maNagrody() {
        return !nagrody.isEmpty();
    }
}
