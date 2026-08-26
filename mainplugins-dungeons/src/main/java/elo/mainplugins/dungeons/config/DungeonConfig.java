package elo.mainplugins.dungeons.config;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

/**
 * Cała konfiguracja lochu/bossa wczytana z dungeons-config.yml (patrz DungeonConfigLoader)
 * - jeden niemutowalny snapshot, podmieniany w całości przy /@reloaddungeons. Wpływa
 * tylko na PRZYSZŁE generowanie/spawny - już postawione platformy/żywe encje nie są
 * retroaktywnie aktualizowane.
 */
public record DungeonConfig(Miejsce miejsce, Pokoje pokoje, Boss boss) {

    public record Miejsce(
            int bazowyX, int bazowyY, int bazowyZ, int odstepPokoi, int liczbaPokoi,
            int promienPokoju, Material materialPodlogiPokoju, Material materialScianyPokoju, int wysokoscScianyPokoju,
            int promienArenyBossa, Material materialPodlogiAreny, Material materialScianyAreny, int wysokoscScianyAreny
    ) {}

    public record Pokoje(EntityType encja, int iloscBazowa, int iloscNaPokoj, double hpBazowe, double hpNaPokoj, double obrazeniaBazowe, double obrazeniaNaPokoj) {
        public int iloscDlaPokoju(int indeks) { return iloscBazowa + iloscNaPokoj * indeks; }
        public double hpDlaPokoju(int indeks) { return hpBazowe + hpNaPokoj * indeks; }
        public double obrazeniaDlaPokoju(int indeks) { return obrazeniaBazowe + obrazeniaNaPokoj * indeks; }
    }

    public record Boss(
            EntityType encja, EntityType encjaSlugi, double maxHp, double obrazeniaAtaku, double obrazeniaPocisku,
            long okresUmiejetnosciTicks, double progSlug1, double progSlug2, double progSzalu, double nagrodaMonety
    ) {}
}
