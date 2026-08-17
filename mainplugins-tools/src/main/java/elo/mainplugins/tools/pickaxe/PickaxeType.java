package elo.mainplugins.tools.pickaxe;

import elo.mainplugins.tools.skilltree.Rarity;
import elo.mainplugins.tools.skilltree.SkillBranch;
import elo.mainplugins.tools.skilltree.SkillCard;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;

import java.util.List;

/**
 * Odrębny "typ" kilofa - zastępuje dawny system tierów (drewno→netheryt jednej linii).
 * Każdy typ ma STAŁY Material (nie zmienia się z poziomem) + własną, dedykowaną pulę
 * kart (patrz {@link #branch()}) zwracaną przez PickaxeSkillManager#branchesFor - reszta
 * silnika (ToolSkillManager) o tym nie wie, patrz materialOverride/branchesFor.
 *
 * customModelData - pod przyszłą custom teksturę w resourcepacku (patrz
 * mainplugins-core/resourcepack/), analogicznie do wzorca z sesji "kilof drewniany".
 */
public enum PickaxeType {

    /** Auto: rosnąca Aura Pośpiechu (Haste) + rosnąca szansa na dodatkowy drop - patrz PickaxeSkillManager#applyAutoStats. */
    WYDAJNOSCIOWY("Wydajnościowy", Material.DIAMOND_PICKAXE, 2001, NamedTextColor.AQUA,
            new SkillBranch("WYD", "Wydajność", NamedTextColor.AQUA, Material.DIAMOND_PICKAXE, List.of(
                    new SkillCard("WYD_ZASIEG", "Rozszerzony Zasięg", Material.IRON_PICKAXE,
                            "Szansa na skruszenie dodatkowego sąsiedniego bloku tego samego typu.", Rarity.COMMON, 3),
                    new SkillCard("WYD_AUTOFORTUNE", "Auto-Fortuna", Material.EMERALD,
                            "Nadaje kilofowi prawdziwy enchant Fortuna I.", Rarity.RARE, 1),
                    new SkillCard("WYD_STREAK", "Passa Górnika", Material.SUGAR,
                            "Kilka bloków z rzędu bez przerwy - chwilowy dodatkowy Pośpiech.", Rarity.COMMON, 3),
                    new SkillCard("WYD_MAGNES", "Magnetyczne Dłonie", Material.MAGMA_CREAM,
                            "Przyciąga do gracza przedmioty wypadające w pobliżu.", Rarity.RARE, 1)
            ))),

    /** Auto: rosnąca szansa/kwota bonusowych monet za wykopany blok - patrz PickaxeSkillManager#applyAutoStats. */
    PIENIEZNY("Pieniężny", Material.GOLDEN_PICKAXE, 2002, NamedTextColor.GOLD,
            new SkillBranch("PIE", "Pieniądz", NamedTextColor.GOLD, Material.GOLDEN_PICKAXE, List.of(
                    new SkillCard("PIE_SZANSA", "Chciwa Ręka", Material.GOLD_NUGGET,
                            "Zwiększa szansę na bonusową wypłatę monet za wykopany blok.", Rarity.COMMON, 5),
                    new SkillCard("PIE_MNOZNIK", "Kupiecki Instynkt", Material.GOLD_INGOT,
                            "Zwiększa kwotę bonusowych wypłat z kilofa.", Rarity.RARE, 3),
                    new SkillCard("PIE_JACKPOT", "Żyła Złota", Material.RAW_GOLD,
                            "Rzadka szansa na dużą, jednorazową wypłatę.", Rarity.RARE, 1),
                    new SkillCard("PIE_BRUK_BONUS", "Cenny Traf", Material.EMERALD,
                            "Cenny surowiec z Bonusu z Bruku dorzuca też trochę monet.", Rarity.COMMON, 3)
            ))),

    /** Auto: rosnący bonus do szansy na rzadki surowiec z Bruku - patrz PickaxeSkillManager#applyAutoStats. */
    SZCZESCIA("Szczęścia", Material.IRON_PICKAXE, 2003, NamedTextColor.LIGHT_PURPLE,
            new SkillBranch("SZCZ", "Szczęście", NamedTextColor.LIGHT_PURPLE, Material.IRON_PICKAXE, List.of(
                    new SkillCard("SZCZ_SZCZESCIE", "Szósty Zmysł", Material.RABBIT_FOOT,
                            "Zwiększa szansę na trigger Bonusu z Bruku.", Rarity.COMMON, 5),
                    new SkillCard("SZCZ_KLUCZ", "Łowca Skarbów", Material.TRIPWIRE_HOOK,
                            "Szansa na klucz do skrzyni przy rzadkim znalezisku.", Rarity.RARE, 3),
                    new SkillCard("SZCZ_PODWOJNY", "Podwójny Traf", Material.AMETHYST_SHARD,
                            "Szansa na podwojenie rzadkiego surowca z Bruku.", Rarity.RARE, 1),
                    new SkillCard("SZCZ_PASSA", "Passa Szczęścia", Material.CLOCK,
                            "Im dłużej bez rzadkiego trafienia, tym wyższa szansa.", Rarity.COMMON, 3)
            ))),

    /** Auto: rosnący zasięg AoE (skruszenie sąsiednich bloków tego samego typu) - patrz PickaxeSkillManager#applyAutoStats. */
    OBSZAROWY("Obszarowy", Material.NETHERITE_PICKAXE, 2004, NamedTextColor.RED,
            new SkillBranch("OBS", "Obszar", NamedTextColor.RED, Material.NETHERITE_PICKAXE, List.of(
                    new SkillCard("OBS_TUNEL", "Tunelowanie", Material.PISTON,
                            "Zwiększa liczbę sąsiednich bloków kruszonych naraz.", Rarity.COMMON, 3),
                    new SkillCard("OBS_AUTOPOMIN", "Selektywny Wybuch", Material.HOPPER,
                            "Blok z Obszaru trafia od razu do ekwipunku, bez fizyki dropu.", Rarity.RARE, 1),
                    new SkillCard("OBS_WIEKSZY_PROMIEN", "Szeroki Front", Material.TNT,
                            "Dodatkowo zwiększa promień skruszenia obszarowego.", Rarity.RARE, 1),
                    new SkillCard("OBS_ECHO", "Echo Wybuchu", Material.COBBLESTONE,
                            "Bruk skruszony obszarowo też rzuca Bonusem z Bruku.", Rarity.COMMON, 3)
            ))),

    /**
     * Testowy - NOWY, dodatkowy, piąty custom item (własne pk_type/CustomModelData/model/
     * tekstura), NIE podmienia/nie współdzieli identyczności z żadnym z 4 powyższych -
     * pod testy resourcepacka (custom tekstura). Dzieli Material z Wydajnościowym
     * (DIAMOND_PICKAXE) celowo - jeden bazowy item wanilijski może nosić dowolnie wiele
     * customowych "skinów" naraz, rozróżnianych WYŁĄCZNIE przez customModelData (patrz
     * assets/minecraft/models/item/diamond_pickaxe.json - kilka wpisów w "overrides").
     * Mechanika = 1:1 kopia Wydajnościowego (ta sama gałąź kart), żeby nie projektować
     * osobnego systemu tylko pod item testowy.
     */
    TESTOWY("Testowy", Material.DIAMOND_PICKAXE, 2005, NamedTextColor.WHITE, WYDAJNOSCIOWY.branch());

    private final String displayName;
    private final Material material;
    private final int customModelData;
    private final NamedTextColor color;
    private final SkillBranch branch;

    PickaxeType(String displayName, Material material, int customModelData, NamedTextColor color, SkillBranch branch) {
        this.displayName = displayName;
        this.material = material;
        this.customModelData = customModelData;
        this.color = color;
        this.branch = branch;
    }

    public String displayName() { return displayName; }
    public Material material() { return material; }
    public int customModelData() { return customModelData; }
    public NamedTextColor color() { return color; }
    public SkillBranch branch() { return branch; }

    /** Rozpoznaje typ po Material (używane przy odczycie starych/nieoznaczonych itemów) - fallback WYDAJNOSCIOWY, jeśli nic nie pasuje. */
    public static PickaxeType odMaterialu(Material material) {
        for (PickaxeType type : values()) {
            if (type.material == material) return type;
        }
        return WYDAJNOSCIOWY;
    }
}
