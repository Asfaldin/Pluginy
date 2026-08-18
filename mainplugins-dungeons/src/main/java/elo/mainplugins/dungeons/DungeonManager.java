package elo.mainplugins.dungeons;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.CrateService;
import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.util.CustomItemKeys;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Lochy/Bossy - proof-of-concept, świadomie "proste" (patrz rozmowa z userem): platformy
 * generowane kodem (żadnych schematów/WorldEdit), custom moby = wanilijskie encje z
 * podbitym HP/obrażeniami + własną nazwą, boss = jedna silna encja z BossBarem i garścią
 * umiejętności odpalanych cyklicznie (BukkitRunnable), bez realnej AI.
 *
 * Dwa tryby, każdy z WŁASNĄ komendą:
 * - /tpboss - od razu arena z bossem (starcie 1v1).
 * - /tpdun - sekwencja {@link #LICZBA_POKOI} pokoi z rosnącą liczbą/mocą mobów do
 *   wyczyszczenia, na końcu ten sam boss co /tpboss.
 *
 * Wszystko generowane WYSOKO w niebie (Y={@link #BAZOWY_Y}) w pierwszym załadowanym
 * świecie, żeby nie ryzykować nadpisania realnych bloków graczy - jeśli to zły świat/
 * wysokość dla tego serwera, zmień STAŁE na górze pliku.
 *
 * Sesje są PER GRACZ (nie globalne) - każdy, kto wejdzie komendą, dostaje własne moby/
 * bossa otagowane jego UUID (PDC), więc kilku graczy może korzystać z tej samej fizycznej
 * areny naraz bez mieszania sobie postępu/nagród.
 *
 * Zabicie bossa wręcza CUSTOM_ID_TROFEUM_LOCHU (Serce Władcy Lochu, patrz trofeumLochu())
 * obok monet/klucza - mainplugins-quests czyta ten tag bez twardej zależności między
 * modułami (patrz CustomItemKeys), żeby quest Głównej Ścieżki "Pierwszy Loch" mógł go
 * przyjąć jako dowód zwycięstwa.
 */
public class DungeonManager implements Listener {

    // ==================================================== Konfiguracja miejsca ====

    private static final int BAZOWY_Y = 250; // wysoko ponad typowym terenem/budowlami
    private static final int BAZOWY_X = 0;
    private static final int BAZOWY_Z = 5000; // daleko od spawnu, żeby nie kolidować z niczym
    private static final int ODSTEP_POKOI = 40; // odległość między platformami pokoi (blocks)

    private static final int LICZBA_POKOI = 4; // pokoje z mobami PRZED bossem (patrz też SLOT bossa = LICZBA_POKOI)

    // ==================================================== Boss - balans ====

    private static final double BOSS_MAX_HP = 200.0;
    private static final double BOSS_ATTACK_DMG = 8.0;
    private static final double BOSS_PROJECTILE_DMG = 6.0;
    private static final long BOSS_SKILL_OKRES_TICKS = 60L; // co ile ticków (3s) odpala się cykl umiejętności
    private static final double NAGRODA_MONETY = 500.0;

    // Tag CustomItemKeys.CUSTOM_ITEM_ID trofeum bossa - odczytywany przez mainplugins-quests
    // (QuestManager, quest Głównej Ścieżki "Pierwszy Loch") bez twardej zależności między modułami.
    private static final String CUSTOM_ID_TROFEUM_LOCHU = "DUNGEON_TROFEUM_WLADCA_LOCHU";

    private final Plugin plugin;
    private final NamespacedKey pkDungeonOwner;
    private final NamespacedKey pkBossOwner;
    private final NamespacedKey pkProjectile;

    private final Map<UUID, DungeonSession> aktywneLochy = new HashMap<>();
    private final Map<UUID, BossSession> aktywniBossowie = new HashMap<>();

    private boolean platformyWygenerowane = false;

    public DungeonManager(Plugin plugin) {
        this.plugin = plugin;
        this.pkDungeonOwner = new NamespacedKey(plugin, "dungeon_owner");
        this.pkBossOwner = new NamespacedKey(plugin, "boss_owner");
        this.pkProjectile = new NamespacedKey(plugin, "boss_projectile");
    }

    // ==================================================== Sesje ====

    /** Stan przejścia lochu jednego gracza (patrz /tpdun) - moby aktualnego pokoju + który to pokój. */
    private static final class DungeonSession {
        int pokoj; // 0..LICZBA_POKOI-1 = pokoje z mobami, LICZBA_POKOI = pokój bossa
        final Set<UUID> zywMoby = new HashSet<>();
    }

    /** Stan jednej walki z bossem (wołane zarówno z /tpboss, jak i z ostatniego pokoju lochu). */
    private static final class BossSession {
        LivingEntity boss;
        BossBar pasek;
        BukkitTask skillTask;
        boolean sludzy66;
        boolean sludzy33;
        boolean szal;
        final Set<UUID> summony = new HashSet<>();
    }

    // ==================================================== Komendy ====

    public boolean onTpBoss(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Tylko gracz może użyć tej komendy.");
            return true;
        }
        upewnijSiePlatformy();
        zakonczLoch(player); // czysty start, jeśli był w trakcie lochu
        Location arena = arenaBossaLokacja();
        player.teleport(arena.clone().add(0.5, 1, 0.5));
        player.sendMessage(Component.text("Teleportowano do areny bossa!", NamedTextColor.GOLD, TextDecoration.BOLD));
        rozpocznijWalkeZBossem(player, arena);
        return true;
    }

    public boolean onTpDun(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Tylko gracz może użyć tej komendy.");
            return true;
        }
        upewnijSiePlatformy();
        zakonczBossa(player, false); // czysty start, jeśli walczył z bossem osobno
        DungeonSession session = new DungeonSession();
        aktywneLochy.put(player.getUniqueId(), session);
        player.sendMessage(Component.text("Wchodzisz do lochu - " + LICZBA_POKOI + " pokoi, na końcu boss!", NamedTextColor.GOLD, TextDecoration.BOLD));
        wejdzDoPokoju(player, session, 0);
        return true;
    }

    // ==================================================== Generowanie platform ====

    private void upewnijSiePlatformy() {
        if (platformyWygenerowane) return;
        platformyWygenerowane = true;

        for (int i = 0; i < LICZBA_POKOI; i++) {
            zbudujPlatforme(pokojLokacja(i), 6, Material.DEEPSLATE_TILES, Material.DEEPSLATE_BRICKS, 4);
        }
        zbudujPlatforme(arenaBossaLokacja(), 10, Material.BLACKSTONE, Material.POLISHED_BLACKSTONE_BRICKS, 5);
    }

    private void zbudujPlatforme(Location center, int promien, Material podloga, Material sciana, int wysokoscSciany) {
        World world = center.getWorld();
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();

        for (int x = -promien; x <= promien; x++) {
            for (int z = -promien; z <= promien; z++) {
                double dist = Math.sqrt(x * (double) x + z * (double) z);
                if (dist <= promien) {
                    world.getBlockAt(cx + x, cy - 1, cz + z).setType(podloga);
                    world.getBlockAt(cx + x, cy, cz + z).setType(Material.AIR);
                }
                if (dist > promien - 1.5 && dist <= promien) {
                    for (int h = 0; h < wysokoscSciany; h++) {
                        world.getBlockAt(cx + x, cy + h, cz + z).setType(sciana);
                    }
                }
            }
        }
    }

    private World swiat() {
        return Bukkit.getWorlds().get(0);
    }

    private Location pokojLokacja(int index) {
        return new Location(swiat(), BAZOWY_X + index * ODSTEP_POKOI, BAZOWY_Y, BAZOWY_Z);
    }

    private Location arenaBossaLokacja() {
        return new Location(swiat(), BAZOWY_X + LICZBA_POKOI * ODSTEP_POKOI, BAZOWY_Y, BAZOWY_Z);
    }

    // ==================================================== Pokoje lochu (/tpdun) ====

    private void wejdzDoPokoju(Player player, DungeonSession session, int indeksPokoju) {
        session.pokoj = indeksPokoju;

        if (indeksPokoju >= LICZBA_POKOI) {
            // Ostatni "pokój" to arena bossa - ten sam mechanizm co /tpboss.
            Location arena = arenaBossaLokacja();
            player.teleport(arena.clone().add(0.5, 1, 0.5));
            player.sendMessage(Component.text("Ostatni pokój - tu czeka boss lochu!", NamedTextColor.DARK_RED, TextDecoration.BOLD));
            rozpocznijWalkeZBossem(player, arena);
            return;
        }

        Location pokoj = pokojLokacja(indeksPokoju);
        player.teleport(pokoj.clone().add(0.5, 1, 0.5));
        player.sendMessage(Component.text("Pokój " + (indeksPokoju + 1) + "/" + LICZBA_POKOI + " - pokonaj strażników!", NamedTextColor.YELLOW));

        session.zywMoby.clear();
        int ilosc = 3 + indeksPokoju;
        double hp = 20 + indeksPokoju * 10;
        double dmg = 3 + indeksPokoju;
        for (int i = 0; i < ilosc; i++) {
            double angle = (2 * Math.PI / ilosc) * i;
            Location spawn = pokoj.clone().add(3 * Math.cos(angle), 0.1, 3 * Math.sin(angle));
            LivingEntity mob = (LivingEntity) pokoj.getWorld().spawnEntity(spawn, EntityType.ZOMBIE);
            mob.customName(Component.text("Strażnik Lochu " + (indeksPokoju + 1), NamedTextColor.RED));
            mob.setCustomNameVisible(true);
            var maxHealthAttr = mob.getAttribute(Attribute.MAX_HEALTH);
            if (maxHealthAttr != null) maxHealthAttr.setBaseValue(hp);
            mob.setHealth(hp);
            var attackAttr = mob.getAttribute(Attribute.ATTACK_DAMAGE);
            if (attackAttr != null) attackAttr.setBaseValue(dmg);
            mob.getPersistentDataContainer().set(pkDungeonOwner, PersistentDataType.STRING, player.getUniqueId().toString());
            session.zywMoby.add(mob.getUniqueId());
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        PersistentDataContainer pdc = entity.getPersistentDataContainer();

        String dungeonOwnerStr = pdc.get(pkDungeonOwner, PersistentDataType.STRING);
        if (dungeonOwnerStr != null) {
            obslugujSmiercMobaLochu(UUID.fromString(dungeonOwnerStr), entity.getUniqueId());
        }

        String bossOwnerStr = pdc.get(pkBossOwner, PersistentDataType.STRING);
        if (bossOwnerStr != null) {
            UUID ownerId = UUID.fromString(bossOwnerStr);
            BossSession session = aktywniBossowie.get(ownerId);
            if (session != null && entity.getUniqueId().equals(session.boss.getUniqueId())) {
                obslugujSmiercBossa(ownerId, session);
            }
        }
    }

    private void obslugujSmiercMobaLochu(UUID ownerId, UUID mobId) {
        DungeonSession session = aktywneLochy.get(ownerId);
        if (session == null) return;
        session.zywMoby.remove(mobId);
        if (session.zywMoby.isEmpty()) {
            Player player = Bukkit.getPlayer(ownerId);
            if (player == null) return;
            player.sendMessage(Component.text("Pokój wyczyszczony!", NamedTextColor.GREEN, TextDecoration.BOLD));
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
            int nastepny = session.pokoj + 1;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                DungeonSession wciazAktywna = aktywneLochy.get(ownerId);
                if (wciazAktywna == null || wciazAktywna != session) return; // gracz zdążył zrestartować/wyjść
                wejdzDoPokoju(player, session, nastepny);
            }, 60L);
        }
    }

    // ==================================================== Walka z bossem ====

    private void rozpocznijWalkeZBossem(Player player, Location arena) {
        Component nazwaBossa = Component.text("Władca Lochu", NamedTextColor.DARK_RED, TextDecoration.BOLD);

        LivingEntity boss = (LivingEntity) arena.getWorld().spawnEntity(arena.clone().add(0, 1, 0), EntityType.PIGLIN_BRUTE);
        boss.customName(nazwaBossa);
        boss.setCustomNameVisible(true);
        var maxHealthAttr = boss.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealthAttr != null) maxHealthAttr.setBaseValue(BOSS_MAX_HP);
        boss.setHealth(BOSS_MAX_HP);
        var attackAttr = boss.getAttribute(Attribute.ATTACK_DAMAGE);
        if (attackAttr != null) attackAttr.setBaseValue(BOSS_ATTACK_DMG);
        boss.getPersistentDataContainer().set(pkBossOwner, PersistentDataType.STRING, player.getUniqueId().toString());

        BossSession session = new BossSession();
        session.boss = boss;
        session.pasek = BossBar.bossBar(nazwaBossa, 1.0f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);
        player.showBossBar(session.pasek);
        aktywniBossowie.put(player.getUniqueId(), session);

        session.skillTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> cyklUmiejetnosciBossa(player, session), BOSS_SKILL_OKRES_TICKS, BOSS_SKILL_OKRES_TICKS);
    }

    /**
     * Trzy umiejętności bossa (patrz ustalenia z userem):
     * 1) Pocisk - co cykl, śnieżka z customowym obrażeniem lecąca w gracza.
     * 2) Przywołanie sług - jednorazowo przy 66% i 33% HP, po 2 zwykłe zombie.
     * 3) Faza szału - jednorazowo poniżej 30% HP, Szybkość+Siła dla bossa.
     */
    private void cyklUmiejetnosciBossa(Player player, BossSession session) {
        LivingEntity boss = session.boss;
        if (!boss.isValid() || boss.isDead() || !player.isOnline()) {
            zakonczBossa(player, false);
            return;
        }

        var maxHealthAttr = boss.getAttribute(Attribute.MAX_HEALTH);
        double maxHp = maxHealthAttr != null ? maxHealthAttr.getBaseValue() : BOSS_MAX_HP;
        double pct = boss.getHealth() / maxHp;
        session.pasek.progress((float) Math.max(0, Math.min(1, pct)));

        // 1) Pocisk
        Vector kierunek = player.getLocation().toVector().subtract(boss.getLocation().toVector()).normalize();
        Snowball pocisk = boss.launchProjectile(Snowball.class, kierunek.multiply(1.3));
        pocisk.getPersistentDataContainer().set(pkProjectile, PersistentDataType.STRING, player.getUniqueId().toString());

        // 2) Przywołanie sług
        if (pct <= 0.66 && !session.sludzy66) {
            session.sludzy66 = true;
            przywolajSlugi(player, session, boss.getLocation());
        }
        if (pct <= 0.33 && !session.sludzy33) {
            session.sludzy33 = true;
            przywolajSlugi(player, session, boss.getLocation());
        }

        // 3) Faza szału
        if (pct <= 0.30 && !session.szal) {
            session.szal = true;
            boss.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 999999, 1, true, true));
            boss.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 999999, 0, true, true));
            player.showTitle(Title.title(
                    Component.text("Boss wpada w szał!", NamedTextColor.DARK_RED, TextDecoration.BOLD),
                    Component.text("Uważaj - jest szybszy i mocniejszy!", NamedTextColor.RED)
            ));
            player.playSound(player.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.6f, 1.4f);
        }
    }

    private void przywolajSlugi(Player player, BossSession session, Location przy) {
        player.sendMessage(Component.text("★ Władca Lochu przywołuje sługi!", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
        for (int i = 0; i < 2; i++) {
            double angle = ThreadLocalRandom.current().nextDouble(0, Math.PI * 2);
            Location spawn = przy.clone().add(2 * Math.cos(angle), 0, 2 * Math.sin(angle));
            LivingEntity sluga = (LivingEntity) przy.getWorld().spawnEntity(spawn, EntityType.ZOMBIE);
            sluga.customName(Component.text("Sługa Lochu", NamedTextColor.RED));
            sluga.setCustomNameVisible(true);
            sluga.getPersistentDataContainer().set(pkBossOwner, PersistentDataType.STRING, player.getUniqueId().toString());
            session.summony.add(sluga.getUniqueId());
        }
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        String ownerStr = projectile.getPersistentDataContainer().get(pkProjectile, PersistentDataType.STRING);
        if (ownerStr == null) return;
        if (event.getHitEntity() instanceof Player player && player.getUniqueId().equals(UUID.fromString(ownerStr))) {
            player.damage(BOSS_PROJECTILE_DMG);
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_HURT, 1f, 1f);
        }
    }

    private void obslugujSmiercBossa(UUID ownerId, BossSession session) {
        Player player = Bukkit.getPlayer(ownerId);
        if (player != null) {
            player.sendMessage(Component.text("★★★ Pokonałeś Władcę Lochu! ★★★", NamedTextColor.GOLD, TextDecoration.BOLD));
            player.showTitle(Title.title(
                    Component.text("ZWYCIĘSTWO!", NamedTextColor.GOLD, TextDecoration.BOLD),
                    Component.text("Władca Lochu pokonany.", NamedTextColor.YELLOW)
            ));
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);

            EconomyService economyService = CoreAPI.getEconomyService();
            economyService.dodajKase(ownerId, NAGRODA_MONETY);
            player.sendMessage(Component.text("Nagroda: +" + (long) NAGRODA_MONETY + " $", NamedTextColor.GREEN));

            CrateService crateService = CoreAPI.getCrateService();
            if (crateService != null) {
                var nieZmieszczone = player.getInventory().addItem(crateService.stworzKlucz());
                nieZmieszczone.values().forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));
                player.sendMessage(Component.text("Nagroda: 1x Klucz do Skrzynki", NamedTextColor.GREEN));
            }

            var nieZmieszczoneTrofeum = player.getInventory().addItem(trofeumLochu());
            nieZmieszczoneTrofeum.values().forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));
            player.sendMessage(Component.text("Nagroda: 1x Serce Władcy Lochu", NamedTextColor.GREEN));

            // Zwykła łopata - gwarantowany drop (nie loot na farmienie jak reszta nagród bossa,
            // tylko jednorazowe "pierwsze narzędzie tego typu w grze", patrz mainplugins-quests
            // quest Głównej Ścieżki #11 "Piaskowy Mozół", który uczy kopania piasku/żwiru nią).
            var nieZmieszczonaLopata = player.getInventory().addItem(new ItemStack(Material.WOODEN_SHOVEL));
            nieZmieszczonaLopata.values().forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));
            player.sendMessage(Component.text("Nagroda: 1x Zwykła Łopata", NamedTextColor.GREEN));
        }
        zakonczBossa(player, true);
        aktywneLochy.remove(ownerId); // jeśli to był finał /tpdun, kończymy też sesję lochu
    }

    /**
     * Trofeum bossa - wręczane przy KAŻDYM zabiciu (nie tylko pierwszym), tak jak monety i
     * klucz do skrzynki wyżej. Pierwsza sztuka zamyka quest Głównej Ścieżki "Pierwszy Loch"
     * (mainplugins-quests), kolejne to zwykły, niewymagany bonus - loch zostaje w pełni
     * powtarzalnym źródłem dochodu na długo po ukończeniu tego questu.
     */
    private static ItemStack trofeumLochu() {
        ItemStack item = new ItemStack(Material.HEART_OF_THE_SEA);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Serce Władcy Lochu", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
        meta.lore(java.util.List.of(
                Component.text("Wciąż bije mrocznym rytmem.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Dowód pokonania Władcy Lochu.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        meta.getPersistentDataContainer().set(CustomItemKeys.CUSTOM_ITEM_ID, PersistentDataType.STRING, CUSTOM_ID_TROFEUM_LOCHU);
        meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
        return item;
    }

    // ==================================================== Sprzątanie ====

    private void zakonczBossa(Player player, boolean bossJuzMartwy) {
        if (player == null) return;
        BossSession session = aktywniBossowie.remove(player.getUniqueId());
        if (session == null) return;
        if (session.skillTask != null) session.skillTask.cancel();
        player.hideBossBar(session.pasek);
        if (!bossJuzMartwy && session.boss != null && session.boss.isValid()) {
            session.boss.remove();
        }
        for (UUID id : session.summony) {
            var e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
    }

    private void zakonczLoch(Player player) {
        DungeonSession session = aktywneLochy.remove(player.getUniqueId());
        if (session == null) return;
        for (UUID id : session.zywMoby) {
            var e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        zakonczBossa(player, false);
        zakonczLoch(player);
    }

    /** Wołane z MainpluginsDungeons#onDisable - sprząta wszystkie aktywne sesje przy wyłączaniu pluginu. */
    public void wyczyscWszystko() {
        for (UUID id : new HashSet<>(aktywniBossowie.keySet())) {
            zakonczBossa(Bukkit.getPlayer(id), false);
        }
        for (UUID id : new HashSet<>(aktywneLochy.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) zakonczLoch(player);
        }
    }
}
