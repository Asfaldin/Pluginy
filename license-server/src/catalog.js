// Katalog tego, co można kupić - źródło prawdy dla /api/catalog (appka to stąd czyta
// do zakładki Sklep) i dla mapowania webhooków LemonSqueezy z powrotem na to, co
// klient faktycznie kupił. TO TY EDYTUJESZ TEN PLIK jako operator:
//
// 1. Załóż w LemonSqueezy jeden Product na każdą pozycję poniżej (patrz README).
// 2. Dla pozycji z "billing: both" stwórz DWA warianty (jednorazowy + subskrypcja)
//    i wklej ich prawdziwe variant_id w polu `variantId`/`subscriptionVariantId`.
// 3. `price`/`subscriptionPrice` to WYŁĄCZNIE liczby wyświetlane w appce (informacyjne) -
//    PRZYKŁADOWE wartości poniżej, ustawione wg złożoności/LOC z COMMERCIALIZATION.md.
//    Prawdziwą cenę i tak ustawiasz w LemonSqueezy przy tworzeniu produktu - trzymaj
//    obie wartości zsynchronizowane ręcznie, nic tego nie robi automatycznie.
// 4. Restart serwera - katalog czytany jest raz przy starcie procesu.
//
// Zestawy (Starter/Pro/Ultimate) są SKUMULOWANE (Pro zawiera Starter + więcej, Ultimate
// zawiera wszystko) - to standardowa, łatwa do zrozumienia drabinka cenowa i łatwy
// upsell ("odblokuj więcej, dopłacając różnicę"), zaproponowana na bazie LOC/wartości
// handlowej z COMMERCIALIZATION.md. Popraw wedle uznania - to tylko punkt startowy.

export const CATEGORIES = [
    { id: "economy", label: "Economy" },
    { id: "progression", label: "Player progression" },
    { id: "automation", label: "Automation" },
    { id: "gamemode", label: "Game modes" },
    { id: "server", label: "Server" },
];

export const INDIVIDUAL_PLUGINS = [
    {
        id: "tools",
        label: "Custom items and tools",
        description: "Evolving tools with levels, enchants and custom effects - the flagship feature of the item creator.",
        category: "progression",
        price: 69,
        variantId: null,
    },
    {
        id: "shop",
        label: "Shop (economy)",
        description: "A full server shop with categories, dynamic prices and search.",
        category: "economy",
        price: 59,
        variantId: null,
    },
    {
        id: "skyblock",
        label: "Skyblock",
        description: "A whole game mode: player islands, upgrades, spawners, ranking.",
        category: "gamemode",
        price: 59,
        variantId: null,
    },
    {
        id: "quests",
        label: "Quests",
        description: "An extensive quest system with categories, requirements and rewards.",
        category: "progression",
        price: 39,
        variantId: null,
    },
    {
        id: "redstone",
        label: "Redstone devices",
        description: "Planting/harvesting drones, item-carrying golems - redstone-powered farm automation.",
        category: "automation",
        price: 35,
        variantId: null,
    },
    {
        id: "spawn",
        label: "Spawn, warps, regions",
        description: "Managing the spawn point, warps and protected regions.",
        category: "server",
        price: 29,
        variantId: null,
    },
    {
        id: "fishing",
        label: "Fishing",
        description: "Fish species, a skill minigame while fishing, bonus crates.",
        category: "gamemode",
        price: 29,
        variantId: null,
    },
    {
        id: "spawners",
        label: "Custom spawners",
        description: "Your own spawner types with a cost curve and a per-island/player limit.",
        category: "automation",
        price: 25,
        variantId: null,
    },
    {
        id: "market",
        label: "Player market",
        description: "Players list their own offers - player-to-player trading.",
        category: "economy",
        price: 19,
        variantId: null,
    },
    {
        id: "dungeons",
        label: "Dungeon and boss",
        description: "A procedurally generated dungeon with a phased boss fight.",
        category: "gamemode",
        price: 25,
        variantId: null,
    },
    {
        id: "crates",
        label: "Loot crates",
        description: "Crates with a reward pool, weights and a chat announcement of the win.",
        category: "economy",
        price: 19,
        variantId: null,
    },
];

// `plugins: "*"` (Ultimate) obejmuje WSZYSTKO, łącznie z pluginami dodanymi w
// przyszłości - patrz dopasowanie w server.js (licencja z plugin="*" przechodzi
// każde zapytanie /api/validate niezależnie od pluginId).
//
// Pakiety z `mode: true` (niżej) to DRUGI wymiar obok Starter/Pro/Ultimate - nie
// skumulowana drabinka cenowa, tylko dobór pluginów pod konkretny typ serwera, jaki
// ktoś prowadzi (Skyblock/Prison/RPG). Appka pokazuje je w osobnej sekcji "Pakiety pod
// tryb gry" (patrz ShopPage.tsx) - mogą się nakładać z Pro/Ultimate, to naturalne,
// klient wybiera to, co dla NIEGO ma sens, nie musi znać różnicy między "cenowym" a
// "tematycznym" pakietem.
// Pakiety pod tryb gry (mode: true) są JEDNORAZOWE, tak jak Ultimate - nie mają
// subscriptionPrice, więc appka (patrz isSubscriptionPackage w lib/pricing.ts) sama nie
// pokazuje im suwaka miesięcznie/rocznie, tylko zwykłą cenę + "Kup". Cenowa drabinka
// Starter/Pro (niżej) sprzedawana jest jako SUBSKRYPCJA - miesięcznie albo rocznie (appka
// pokazuje suwak przełączający, patrz billing w ShopPage.tsx). Rocznie ma ok. 20% zniżki
// względem 12x cena miesięczna (standard w SaaS - "2 miesiące gratis"), stąd
// subscriptionPriceYearly to NIE jest subscriptionPrice*12.
export const PACKAGES = [
    {
        // Skład z analizy kodu: kilof z Bonusem z Bruku (tools) to jedyne źródło rud na
        // Skyblocku, Skyblock sam korzysta ze Spawnu, spawnery liczą limit na wyspę, klucze z
        // kopania idą do skrzynek. Osobno 279 zł. Darmowe generatory/HUD/menu/uprawy dochodzą
        // bez licencji - patrz ECOSYSTEM_SETUPS w desktop-app/src/lib/pluginEcosystem.ts.
        id: "mode-skyblock",
        label: "Skyblock Bundle",
        description:
            "Everything for a Skyblock server: islands, a pickaxe with Cobble Bonus (the only source of ores), spawners with a per-island limit, a shop and market for trading the output, crates with keys from mining and a way back to spawn.",
        plugins: ["skyblock", "tools", "shop", "spawners", "crates", "spawn", "market"],
        price: 199,
        subscriptionPrice: null,
        subscriptionPriceYearly: null,
        variantId: null,
        subscriptionVariantId: null,
        subscriptionVariantIdYearly: null,
        mode: true,
    },
    {
        id: "mode-prison",
        label: "Prison / Automation Bundle",
        description: "Redstone farms, custom spawners and evolving tools (pickaxes with levels) - a shop to sell the output, crates as a reward for the grind.",
        plugins: ["redstone", "spawners", "tools", "shop", "crates"],
        price: 169,
        subscriptionPrice: null,
        subscriptionPriceYearly: null,
        variantId: null,
        subscriptionVariantId: null,
        subscriptionVariantIdYearly: null,
        mode: true,
    },
    {
        id: "mode-adventure",
        label: "Adventure / RPG Bundle",
        description: "Quests, a dungeon with a boss and fishing - player progression through quests and exploration, plus evolving tools and crates as rewards.",
        plugins: ["quests", "dungeons", "fishing", "tools", "crates"],
        price: 149,
        subscriptionPrice: null,
        subscriptionPriceYearly: null,
        variantId: null,
        subscriptionVariantId: null,
        subscriptionVariantIdYearly: null,
        mode: true,
    },
    {
        id: "starter",
        label: "Starter Pack",
        description: "Crates, a market, custom spawners, a dungeon - a light start for a smaller server.",
        plugins: ["crates", "market", "spawners", "dungeons"],
        price: 59,
        subscriptionPrice: 19,
        subscriptionPriceYearly: 179,
        variantId: null,
        subscriptionVariantId: null,
        subscriptionVariantIdYearly: null,
    },
    {
        id: "pro",
        label: "Pro Pack",
        description: "Starter + quests, redstone devices, spawn/warps, fishing - full player progression.",
        plugins: ["crates", "market", "spawners", "dungeons", "quests", "redstone", "spawn", "fishing"],
        price: 149,
        subscriptionPrice: 39,
        subscriptionPriceYearly: 369,
        variantId: null,
        subscriptionVariantId: null,
        subscriptionVariantIdYearly: null,
    },
    {
        id: "ultimate",
        label: "Ultimate (everything)",
        description: "The whole Mainplugins ecosystem, including tools/shop/skyblock, custom 3D mobs and blocks - and every plugin added later.",
        plugins: "*",
        // Wyłącznie jednorazowo (bez abonamentu, patrz decyzja w COMMERCIALIZATION.md) -
        // 399 zł zamiast pierwotnych 249 zł, bo ta paczka oddaje też WSZYSTKIE przyszłe
        // pluginy za tę samą cenę; bez rekompensaty w postaci stałego abonamentu 249 zł
        // byłoby zbyt tanie względem tego, ile w przyszłości dojdzie do środka. Jedyny
        // pakiet, który appka pokazuje BEZ suwaka miesięcznie/rocznie - patrz ShopPage.tsx.
        price: 399,
        subscriptionPrice: null,
        subscriptionPriceYearly: null,
        variantId: null,
        subscriptionVariantId: null,
        subscriptionVariantIdYearly: null,
    },
];

/** variant_id (string, tak jak przychodzi z LemonSqueezy) -> {plugin, billingType} albo null jeśli nieznany. */
export function resolveVariant(variantId) {
    if (variantId == null || variantId === "") return null;
    const id = String(variantId);
    const same = (v) => v != null && String(v) === id; // nieuzupełnione variantId: null nigdy nie pasuje
    for (const p of INDIVIDUAL_PLUGINS) {
        if (same(p.variantId)) return { plugin: p.id, billingType: "one-time" };
    }
    for (const pkg of PACKAGES) {
        if (same(pkg.variantId)) {
            return { plugin: Array.isArray(pkg.plugins) ? pkg.plugins.join(",") : pkg.plugins, billingType: "one-time" };
        }
        if (same(pkg.subscriptionVariantId) || same(pkg.subscriptionVariantIdYearly)) {
            return { plugin: Array.isArray(pkg.plugins) ? pkg.plugins.join(",") : pkg.plugins, billingType: "subscription" };
        }
    }
    return null;
}
