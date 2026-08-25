package elo.mainplugins.tools.evolving;

/**
 * Kategoria przedmiotu na nowym, konfigurowalnym z YAML silniku (patrz EvolvingToolManager) -
 * decyduje, który wanilijski event liczy się jako "użycie" (exp) i gdzie przedmiot fizycznie
 * mieszka. Dwie RÓŻNE rodziny:
 * - NARZĘDZIA (PICKAXE/AXE/HOE/SWORD/SHOVEL) - "użycie" to kopanie/atak z ręki, przedmiot
 *   sprawdzany w main-hand.
 * - ZBROJA (HELMET/CHESTPLATE/LEGGINGS/BOOTS) - "użycie" to OTRZYMANIE OBRAŻEŃ podczas gdy
 *   przedmiot jest ZAŁOŻONY (patrz EvolvingToolManager#onEntityDamageAsVictim) - kopanie/atak
 *   nie ma tu znaczenia. Aury/enczanty synchronizują się z odpowiedniego slotu zbroi (patrz
 *   itemDlaKategorii), nie z main-hand.
 */
public enum Kategoria {
    PICKAXE, AXE, HOE, SWORD, SHOVEL,
    HELMET, CHESTPLATE, LEGGINGS, BOOTS;

    public boolean jestZbroja() {
        return this == HELMET || this == CHESTPLATE || this == LEGGINGS || this == BOOTS;
    }

    public static Kategoria zNazwy(String nazwa, Kategoria domyslna) {
        if (nazwa == null) return domyslna;
        try {
            return Kategoria.valueOf(nazwa.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return domyslna;
        }
    }
}
