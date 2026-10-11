package elo.mainplugins.coreminer;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.api.LangService;
import elo.mainplugins.core.util.AsyncConfigSaver;
import elo.mainplugins.core.util.MoneyFormat;
import elo.mainplugins.coreminer.config.MinerConfig;
import elo.mainplugins.coreminer.config.MinerGroup;
import elo.mainplugins.coreminer.config.MinerSettings;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Kopanie całych żył. Gracz kopie zwykle pierwszy blok; jeśli to blok z włączonej grupy, ma dobre narzędzie,
 * tryb pozwala (kucanie / zawsze / przełącznik) i nie ma cooldownu - kopiemy resztę żyły.
 *
 * Każdy dodatkowy blok przechodzi przez własny BlockBreakEvent: działki, wyspy, WorldGuard i inne ochrony
 * mogą go zablokować (wtedy go pomijamy), a questy/joby liczą go jak zwykłe kopanie. Wytrzymałość zużywamy
 * przez damageItemStack (Unbreaking działa), drop z getDrops(narzędzie) - Fortune i Silk Touch działają.
 */
final class MinerManager implements Listener {

    private final Plugin plugin;
    private final LangService lang;
    private volatile MinerConfig config;

    /** Gracze, którym właśnie kopiemy żyłę - ich BlockBreakEvent-y z żyły nie startują kolejnej żyły. */
    private final Set<UUID> wTrakcie = new HashSet<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();
    /** Wyłączony/włączony przez /coreminer (brak wpisu = domyślnie). */
    private final YamlConfiguration przelaczniki;
    private final AsyncConfigSaver saver;
    /** Przetopione wersje przedmiotów (z receptur pieca) - liczone raz na przedmiot. */
    private final Map<Material, Optional<ItemStack>> wytopione = new HashMap<>();

    MinerManager(Plugin plugin, MinerConfig config, LangService lang) {
        this.plugin = plugin;
        this.config = config;
        this.lang = lang;
        File f = new File(plugin.getDataFolder(), "przelaczniki.yml");
        if (!f.exists()) {
            f.getParentFile().mkdirs();
            try { f.createNewFile(); } catch (IOException ignored) {}
        }
        this.przelaczniki = YamlConfiguration.loadConfiguration(f);
        this.saver = new AsyncConfigSaver(plugin, przelaczniki, f, 30);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickPodglad, 20L, 8L);
    }

    MinerConfig config() {
        return config;
    }

    void ustawConfig(MinerConfig nowy) {
        this.config = nowy;
        wytopione.clear();
    }

    void zamknij() {
        saver.zamknij();
    }

    // ---- przełącznik /coreminer ----

    boolean wlaczony(Player p) {
        Object v = przelaczniki.get(p.getUniqueId().toString());
        if (v instanceof Boolean b) return b;
        return config.ustawienia().tryb() != MinerSettings.Tryb.PRZELACZNIK || config.ustawienia().domyslnieWlaczony();
    }

    void ustawWlaczony(Player p, boolean on) {
        przelaczniki.set(p.getUniqueId().toString(), on);
        saver.oznaczZmiane();
    }

    /** Czy tryb pozwala teraz kopać żyłą (kucanie/zawsze/przełącznik) - bez sprawdzania bloku. */
    private boolean trybPozwala(Player p) {
        if (!wlaczony(p)) return false;
        return config.ustawienia().tryb() != MinerSettings.Tryb.KUCANIE || p.isSneaking();
    }

    /** Grupa, którą ten gracz może teraz kopać żyłą z tego bloku, albo null. */
    private MinerGroup grupaGracza(Player p, Block b) {
        MinerSettings u = config.ustawienia();
        if (!p.hasPermission("mainplugins.coreminer.use")) return null;
        if (p.getGameMode() == GameMode.CREATIVE && !u.wKreatywnym()) return null;
        if (p.getGameMode() == GameMode.SPECTATOR || p.getGameMode() == GameMode.ADVENTURE) return null;
        if (!u.swiatOk(b.getWorld().getName())) return null;
        MinerGroup g = config.grupaDla(b.getType());
        if (g == null) return null;
        if (g.uprawnienie() != null && !p.hasPermission(g.uprawnienie())) return null;
        if (!g.narzedzieOk(p.getInventory().getItemInMainHand().getType())) return null;
        return g;
    }

    /** Limit bloków dla gracza: zwykły albo wyższy z uprawnień, nie więcej niż limit grupy. */
    int limitGracza(Player p, MinerGroup g) {
        MinerSettings u = config.ustawienia();
        int limit = u.maxBlokow();
        for (Map.Entry<String, Integer> e : u.limityUprawnien().entrySet()) {
            if (p.hasPermission(e.getKey())) limit = Math.max(limit, e.getValue());
        }
        return Math.min(limit, g.maxBlokow());
    }

    private List<Block> zyla(Player p, Block start, MinerGroup g, int max) {
        World w = start.getWorld();
        Material typ = start.getType();
        List<VeinFinder.Pos> pos = VeinFinder.znajdz(new VeinFinder.Pos(start.getX(), start.getY(), start.getZ()), max - 1,
                config.ustawienia().maxOdleglosc(), g.poPrzekatnej(),
                q -> q.y() >= w.getMinHeight() && q.y() < w.getMaxHeight() && w.isChunkLoaded(q.x() >> 4, q.z() >> 4)
                        && g.laczy(typ, w.getBlockAt(q.x(), q.y(), q.z()).getType()));
        List<Block> out = new ArrayList<>(pos.size());
        for (VeinFinder.Pos q : pos) out.add(w.getBlockAt(q.x(), q.y(), q.z()));
        return out;
    }

    // ---- kopanie ----

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player p = event.getPlayer();
        if (wTrakcie.contains(p.getUniqueId())) return; // blok z naszej żyły - nie startujemy kolejnej
        if (!trybPozwala(p)) return;
        Block start = event.getBlock();
        MinerGroup g = grupaGracza(p, start);
        if (g == null) return;

        MinerSettings u = config.ustawienia();
        long teraz = System.currentTimeMillis();
        Long koniec = cooldown.get(p.getUniqueId());
        if (koniec != null && teraz < koniec && !p.hasPermission("mainplugins.coreminer.bypass.cooldown")) {
            if (u.komunikat()) p.sendActionBar(lang.msg(plugin, "cooldown", Map.of("seconds", String.valueOf((koniec - teraz + 999) / 1000))));
            return;
        }

        List<Block> reszta = zyla(p, start, g, limitGracza(p, g));
        if (reszta.isEmpty()) return;
        if (u.cooldownSekund() > 0) cooldown.put(p.getUniqueId(), teraz + (long) (u.cooldownSekund() * 1000));

        ItemStack narzedzie = p.getInventory().getItemInMainHand();
        Location gdzieDrop = start.getLocation().add(0.5, 0.5, 0.5);
        Zbiorka zbiorka = new Zbiorka(p, gdzieDrop, g);

        // Pierwszy blok: przy dropie do ekwipunku / przetapianiu przejmujemy jego drop, inaczej zostawiamy grze.
        if (u.drop() == MinerSettings.Drop.EKWIPUNEK || u.przetapianie()) {
            if (event.isDropItems()) {
                for (ItemStack d : start.getDrops(narzedzie, p)) zbiorka.dodaj(d, start.getLocation());
                event.setDropItems(false);
            }
        }
        if (u.xp() != MinerSettings.Xp.KULE) {
            zbiorka.xp += event.getExpToDrop();
            event.setExpToDrop(0);
        }

        if (u.opoznienieTickow() <= 0) {
            kop(p, reszta, zbiorka);
            zbiorka.zakoncz();
        } else {
            // Animacja: kilka bloków co kilka ticków. Nasz BlockBreakEvent z pierwszego bloku już się skończył,
            // więc pierwszy kawałek leci tickiem później.
            Iterator<Block> it = reszta.iterator();
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (!p.isOnline()) {
                        zbiorka.zakoncz();
                        cancel();
                        return;
                    }
                    List<Block> kawalek = new ArrayList<>();
                    for (int i = 0; i < u.blokowNaRaz() && it.hasNext(); i++) kawalek.add(it.next());
                    boolean dalej = kop(p, kawalek, zbiorka) && it.hasNext();
                    if (!dalej) {
                        zbiorka.zakoncz();
                        cancel();
                    }
                }
            }.runTaskTimer(plugin, 1L, u.opoznienieTickow());
        }
    }

    /** Kopie podane bloki; false = przerwano (narzędzie, pieniądze, gracz zmienił przedmiot). */
    private boolean kop(Player p, List<Block> bloki, Zbiorka zbiorka) {
        MinerSettings u = config.ustawienia();
        EconomyService eko = u.kosztZaBlok() > 0 ? CoreAPI.getEconomyService() : null;
        wTrakcie.add(p.getUniqueId());
        try {
            for (Block b : bloki) {
                ItemStack narzedzie = p.getInventory().getItemInMainHand();
                if (!zbiorka.grupa.narzedzieOk(narzedzie.getType())) return false; // zmienił przedmiot w trakcie
                if (!zbiorka.grupa.zawiera(b.getType())) continue; // już wykopany (ktoś inny / animacja)
                if (narzedzieNaWykonczeniu(narzedzie, u.chronNarzedzie())) {
                    zbiorka.powod = "tool";
                    return false;
                }
                if (eko != null && !eko.pobierzGrosze(p.getUniqueId(), Math.round(u.kosztZaBlok() * 100))) {
                    zbiorka.powod = "money";
                    return false;
                }
                BlockBreakEvent e = new BlockBreakEvent(b, p);
                Bukkit.getPluginManager().callEvent(e);
                if (e.isCancelled()) continue; // działka / wyspa / ochrona - pomijamy ten blok
                if (e.isDropItems()) for (ItemStack d : b.getDrops(narzedzie, p)) zbiorka.dodaj(d, b.getLocation());
                zbiorka.xp += e.getExpToDrop();
                Location gdzie = b.getLocation().add(0.5, 0.5, 0.5);
                b.getWorld().spawnParticle(Particle.BLOCK, gdzie, 12, 0.3, 0.3, 0.3, b.getBlockData());
                b.setType(Material.AIR);
                zbiorka.wykopane++;
                zuzyj(p, u.wytrzymalosc());
                if (u.glod() > 0) p.setExhaustion(p.getExhaustion() + (float) u.glod());
            }
            return true;
        } finally {
            wTrakcie.remove(p.getUniqueId());
        }
    }

    /** Wytrzymałość za blok; ułamek = szansa (0,5 = co drugi blok). Unbreaking i Mending jak w grze. */
    private static void zuzyj(Player p, double ile) {
        if (ile <= 0 || p.getGameMode() == GameMode.CREATIVE) return;
        int calosc = (int) Math.floor(ile);
        if (ThreadLocalRandom.current().nextDouble() < ile - calosc) calosc++;
        if (calosc > 0) p.damageItemStack(EquipmentSlot.HAND, calosc);
    }

    private static boolean narzedzieNaWykonczeniu(ItemStack narzedzie, int zapas) {
        if (zapas <= 0 || !(narzedzie.getItemMeta() instanceof Damageable d)) return false;
        int max = narzedzie.getType().getMaxDurability();
        if (max <= 0 || narzedzie.getItemMeta().isUnbreakable()) return false;
        return max - d.getDamage() <= zapas;
    }

    /** Przetopiona wersja przedmiotu (surowe żelazo -> sztabka) albo null, gdy piec nic z tym nie robi. */
    private ItemStack wytop(ItemStack item) {
        Optional<ItemStack> wynik = wytopione.computeIfAbsent(item.getType(), m -> {
            ItemStack probka = new ItemStack(m);
            Iterator<Recipe> it = Bukkit.recipeIterator();
            while (it.hasNext()) {
                if (it.next() instanceof FurnaceRecipe r && r.getInputChoice().test(probka)) return Optional.of(r.getResult());
            }
            return Optional.empty();
        });
        if (wynik.isEmpty()) return null;
        ItemStack out = wynik.get().clone();
        out.setAmount(Math.min(out.getMaxStackSize(), out.getAmount() * item.getAmount()));
        return out;
    }

    /** Drop i XP z całej żyły - oddawane na koniec (albo na bieżąco przy dropie naturalnym). */
    private final class Zbiorka {
        final Player p;
        final Location start;
        final MinerGroup grupa;
        final List<ItemStack> drop = new ArrayList<>();
        int xp;
        int wykopane;
        String powod;

        Zbiorka(Player p, Location start, MinerGroup grupa) {
            this.p = p;
            this.start = start;
            this.grupa = grupa;
        }

        void dodaj(ItemStack d, Location blok) {
            MinerSettings u = config.ustawienia();
            ItemStack item = d;
            if (u.przetapianie()) {
                ItemStack w = wytop(d);
                if (w != null) item = w;
            }
            if (u.drop() == MinerSettings.Drop.NATURALNIE) blok.getWorld().dropItemNaturally(blok.clone().add(0.5, 0.5, 0.5), item);
            else drop.add(item);
        }

        void zakoncz() {
            MinerSettings u = config.ustawienia();
            if (u.drop() == MinerSettings.Drop.EKWIPUNEK && p.isOnline()) {
                for (ItemStack reszta : p.getInventory().addItem(drop.toArray(new ItemStack[0])).values()) {
                    p.getWorld().dropItemNaturally(p.getLocation(), reszta);
                }
            } else {
                for (ItemStack d : drop) start.getWorld().dropItemNaturally(start, d);
            }
            drop.clear();
            if (xp > 0) {
                switch (u.xp()) {
                    case GRACZ -> {
                        if (p.isOnline()) p.giveExp(xp, true);
                    }
                    case KULE -> start.getWorld().spawn(start, ExperienceOrb.class, o -> o.setExperience(xp));
                    case BRAK -> { }
                }
            }
            if (!p.isOnline()) return;
            if (powod != null && u.komunikat()) {
                p.sendActionBar(lang.msg(plugin, "stopped." + powod, Map.of("count", String.valueOf(wykopane + 1),
                        "price", MoneyFormat.zWaluta(u.kosztZaBlok()))));
            } else if (u.komunikat() && wykopane > 0) {
                p.sendActionBar(lang.msg(plugin, "mined", Map.of("count", String.valueOf(wykopane + 1), "group", grupa.nazwa())));
            }
            if (wykopane > 0 && u.dzwiek() != null && !u.dzwiek().isBlank()) {
                try {
                    p.playSound(p.getLocation(), u.dzwiek(), 0.6f, 1.2f);
                } catch (IllegalArgumentException ignored) {
                    // zła nazwa dźwięku w configu - bez dźwięku
                }
            }
        }
    }

    // ---- podgląd żyły ----

    /** Co 8 ticków: graczom, którzy celują w blok z grupy (i mogą teraz kopać żyłą), podświetlamy całą żyłę. */
    private void tickPodglad() {
        MinerSettings u = config.ustawienia();
        if (!u.podglad()) return;
        Particle czastka = czasteczka(u.czasteczka());
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!trybPozwala(p)) continue;
            Block cel = p.getTargetBlockExact(5);
            if (cel == null) continue;
            MinerGroup g = grupaGracza(p, cel);
            if (g == null) continue;
            int max = Math.min(u.podgladMax(), limitGracza(p, g));
            p.spawnParticle(czastka, cel.getLocation().add(0.5, 0.5, 0.5), 2, 0.25, 0.25, 0.25, 0);
            for (Block b : zyla(p, cel, g, max)) {
                p.spawnParticle(czastka, b.getLocation().add(0.5, 0.5, 0.5), 2, 0.25, 0.25, 0.25, 0);
            }
        }
    }

    private static Particle czasteczka(String nazwa) {
        try {
            Particle p = Particle.valueOf(nazwa.trim().toUpperCase(java.util.Locale.ROOT));
            // cząsteczki z dodatkowymi danymi (kolor, blok) potrzebują ich przy spawnie - zostajemy przy prostych
            return p.getDataType() == Void.class ? p : Particle.END_ROD;
        } catch (IllegalArgumentException | NullPointerException e) {
            return Particle.END_ROD;
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cooldown.remove(event.getPlayer().getUniqueId());
        wTrakcie.remove(event.getPlayer().getUniqueId());
    }
}
