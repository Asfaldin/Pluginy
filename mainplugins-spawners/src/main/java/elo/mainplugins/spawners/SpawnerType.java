package elo.mainplugins.spawners;

import org.bukkit.entity.EntityType;

/**
 * 9 typów customowych spawnerów. Levelowanie (patrz IslandManager.otworzMenuWzrostuDropow
 * w mainplugins-skyblock) wpływa tylko na tempo/ilość spawnu - same dropy to zwykłe,
 * wanilijskie dropy tych mobków po zabiciu (proch z creepera, kości ze szkieleta,
 * skóra z krowy itd.) - nic nie trzeba tu symulować ręcznie.
 */
public enum SpawnerType {

    COW(EntityType.COW, "Krów", "Krowa"),
    SHEEP(EntityType.SHEEP, "Owiec", "Owca"),
    PIG(EntityType.PIG, "Świń", "Świnia"),
    CHICKEN(EntityType.CHICKEN, "Kur", "Kura"),
    SPIDER(EntityType.SPIDER, "Pająków", "Pająk"),
    ZOMBIE(EntityType.ZOMBIE, "Zombie", "Zombie"),
    SKELETON(EntityType.SKELETON, "Szkieletów", "Szkielet"),
    CREEPER(EntityType.CREEPER, "Creeperów", "Creeper"),
    BREEZE(EntityType.BREEZE, "Breeze'ów", "Breeze");

    public static final int MAX_LEVEL = 5;

    private final EntityType entityType;
    private final String nazwaOdmieniona;
    private final String nazwaPojedyncza;

    SpawnerType(EntityType entityType, String nazwaOdmieniona, String nazwaPojedyncza) {
        this.entityType = entityType;
        this.nazwaOdmieniona = nazwaOdmieniona;
        this.nazwaPojedyncza = nazwaPojedyncza;
    }

    public EntityType getEntityType() { return entityType; }

    /** Np. "Piglinów" - do napisów w GUI/sklepie ("Spawner Piglinów", "Poziom Piglinów"). */
    public String getNazwaOdmieniona() { return nazwaOdmieniona; }

    /** Np. "Krowa" (mianownik, l. pojedyncza) - do nametaga stosu, np. "Krowa x5". */
    public String getNazwaPojedyncza() { return nazwaPojedyncza; }

    /** Sekundy między cyklami spawnu przy danym poziomie (1-5) - im wyższy poziom, tym częściej. 32/28/24/20/16. */
    public static int interwalSekund(int level) {
        return 36 - level * 4;
    }

    /** Ile mobków dorzuca do kolejki jeden cykl spawnu przy danym poziomie. 5/6/7/8/9. */
    public static int iloscNaCykl(int level) {
        return level + 4;
    }

    /**
     * Sufit kolejki spawnera - stały, NIE zależy od poziomu Ilości/Szybkości - powyżej tego
     * przestaje dorzucać, dopóki gracz nie przetrzebi. Bezpiecznie wysoko, bo stackowanie
     * (patrz SpawnerManager) i tak trzyma najwyżej 1 żywą encję na spawner niezależnie od
     * tego, ile jest w kolejce.
     */
    public static final int LIMIT_KOLEJKI = 50;
}
