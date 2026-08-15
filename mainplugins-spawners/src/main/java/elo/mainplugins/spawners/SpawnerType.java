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

    /** Sekundy między cyklami spawnu przy danym poziomie (1-5) - im wyższy poziom, tym częściej. */
    public static int interwalSekund(int level) {
        return Math.max(60 - (level - 1) * 10, 20);
    }

    /** Ile mobków naraz spawnuje się w jednym cyklu przy danym poziomie. */
    public static int iloscNaCykl(int level) {
        return 1 + level / 2;
    }

    /** Limit mobków tego typu w pobliżu, powyżej którego spawner czeka aż gracz je przetrzebi. */
    public static int limitPobliskich(int level) {
        return 4 + level;
    }
}