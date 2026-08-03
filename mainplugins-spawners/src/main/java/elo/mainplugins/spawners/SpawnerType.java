package elo.mainplugins.spawners;

import org.bukkit.entity.EntityType;

/**
 * 5 typów customowych spawnerów. Levelowanie (patrz IslandManager.otworzMenuWzrostuDropow
 * w mainplugins-skyblock) wpływa tylko na tempo/ilość spawnu - same dropy to zwykłe,
 * wanilijskie dropy tych mobków po zabiciu (złote samorodki z piglina, wełna/baranina
 * z owcy itd.) - nic nie trzeba tu symulować ręcznie.
 */
public enum SpawnerType {

    PIGLIN(EntityType.PIGLIN, "Piglinów"),
    SHEEP(EntityType.SHEEP, "Owiec"),
    RABBIT(EntityType.RABBIT, "Królików"),
    BREEZE(EntityType.BREEZE, "Breeze'ów"),
    GLOW_SQUID(EntityType.GLOW_SQUID, "Świetlistych Kałamarnic");

    public static final int MAX_LEVEL = 5;

    private final EntityType entityType;
    private final String nazwaOdmieniona;

    SpawnerType(EntityType entityType, String nazwaOdmieniona) {
        this.entityType = entityType;
        this.nazwaOdmieniona = nazwaOdmieniona;
    }

    public EntityType getEntityType() { return entityType; }

    /** Np. "Piglinów" - do napisów w GUI/sklepie ("Spawner Piglinów", "Poziom Piglinów"). */
    public String getNazwaOdmieniona() { return nazwaOdmieniona; }

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