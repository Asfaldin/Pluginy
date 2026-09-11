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
    { id: "economy", label: "Ekonomia" },
    { id: "progression", label: "Progresja gracza" },
    { id: "automation", label: "Automatyzacja" },
    { id: "gamemode", label: "Tryby gry" },
    { id: "server", label: "Serwer" },
];

export const INDIVIDUAL_PLUGINS = [
    {
        id: "tools",
        label: "Custom itemy i narzędzia",
        description: "Ewoluujące narzędzia z poziomami, enczantami i własnymi efektami - flagowa funkcja kreatora itemów.",
        category: "progression",
        price: 69,
        variantId: null,
    },
    {
        id: "shop",
        label: "Sklep (ekonomia)",
        description: "Pełny sklep serwerowy z kategoriami, dynamicznymi cenami i wyszukiwarką.",
        category: "economy",
        price: 59,
        variantId: null,
    },
    {
        id: "skyblock",
        label: "Skyblock",
        description: "Cały tryb gry: wyspy graczy, ulepszenia, spawnery, ranking.",
        category: "gamemode",
        price: 59,
        variantId: null,
    },
    {
        id: "quests",
        label: "Questy",
        description: "Rozbudowany system zadań z kategoriami, wymaganiami i nagrodami.",
        category: "progression",
        price: 39,
        variantId: null,
    },
    {
        id: "advancements",
        label: "Osiągnięcia",
        description: "System osiągnięć na bazie natywnych advancementów Minecrafta, z własnymi nagrodami.",
        category: "progression",
        price: 39,
        variantId: null,
    },
    {
        id: "redstone",
        label: "Redstone-urządzenia",
        description: "Drony sadzące/zbierające, golemy przenoszące itemy - automatyzacja farm zasilana redstonem.",
        category: "automation",
        price: 35,
        variantId: null,
    },
    {
        id: "spawn",
        label: "Spawn, warpy, obszary",
        description: "Zarządzanie punktem spawnu, warpami i chronionymi obszarami.",
        category: "server",
        price: 29,
        variantId: null,
    },
    {
        id: "fishing",
        label: "Wędkarstwo",
        description: "Gatunki ryb, minigra zręcznościowa przy łowieniu, bonusowe skrzynki.",
        category: "gamemode",
        price: 29,
        variantId: null,
    },
    {
        id: "spawners",
        label: "Customowe spawnery",
        description: "Własne typy spawnerów z krzywą kosztu i limitem na wyspę/gracza.",
        category: "automation",
        price: 25,
        variantId: null,
    },
    {
        id: "market",
        label: "Rynek graczy",
        description: "Gracze wystawiają własne oferty sprzedaży - handel graczy z graczami.",
        category: "economy",
        price: 19,
        variantId: null,
    },
    {
        id: "dungeons",
        label: "Loch i boss",
        description: "Proceduralnie generowany loch z walką z bossem i fazami.",
        category: "gamemode",
        price: 25,
        variantId: null,
    },
    {
        id: "crates",
        label: "Skrzynki losowe",
        description: "Skrzynki z pulą nagród, wagami i ogłoszeniem wygranej na czacie.",
        category: "economy",
        price: 19,
        variantId: null,
    },
];

// `plugins: "*"` (Ultimate) obejmuje WSZYSTKO, łącznie z pluginami dodanymi w
// przyszłości - patrz dopasowanie w server.js (licencja z plugin="*" przechodzi
// każde zapytanie /api/validate niezależnie od pluginId).
export const PACKAGES = [
    {
        id: "starter",
        label: "Starter Pack",
        description: "Skrzynki, rynek, customowe spawnery, loch - lekki start dla mniejszego serwera.",
        plugins: ["crates", "market", "spawners", "dungeons"],
        price: 59,
        subscriptionPrice: null,
        variantId: null,
        subscriptionVariantId: null,
    },
    {
        id: "pro",
        label: "Pro Pack",
        description: "Starter + questy, osiągnięcia, redstone-urządzenia, spawn/warpy, wędkarstwo - pełna progresja gracza.",
        plugins: ["crates", "market", "spawners", "dungeons", "quests", "advancements", "redstone", "spawn", "fishing"],
        price: 149,
        subscriptionPrice: null,
        variantId: null,
        subscriptionVariantId: null,
    },
    {
        id: "ultimate",
        label: "Ultimate (wszystko)",
        description: "Cały ekosystem Mainplugins, łącznie z tools/shop/skyblock - i z każdym kolejnym pluginem, jaki dojdzie później.",
        plugins: "*",
        // Wyłącznie jednorazowo (bez abonamentu, patrz decyzja w COMMERCIALIZATION.md) -
        // 399 zł zamiast pierwotnych 249 zł, bo ta paczka oddaje też WSZYSTKIE przyszłe
        // pluginy za tę samą cenę; bez rekompensaty w postaci stałego abonamentu 249 zł
        // byłoby zbyt tanie względem tego, ile w przyszłości dojdzie do środka.
        price: 399,
        subscriptionPrice: null,
        variantId: null,
        subscriptionVariantId: null,
    },
];

/** variant_id (string, tak jak przychodzi z LemonSqueezy) -> {plugin, billingType} albo null jeśli nieznany. */
export function resolveVariant(variantId) {
    const id = String(variantId);
    for (const p of INDIVIDUAL_PLUGINS) {
        if (String(p.variantId) === id) return { plugin: p.id, billingType: "one-time" };
    }
    for (const pkg of PACKAGES) {
        if (String(pkg.variantId) === id) {
            return { plugin: Array.isArray(pkg.plugins) ? pkg.plugins.join(",") : pkg.plugins, billingType: "one-time" };
        }
        if (String(pkg.subscriptionVariantId) === id) {
            return { plugin: Array.isArray(pkg.plugins) ? pkg.plugins.join(",") : pkg.plugins, billingType: "subscription" };
        }
    }
    return null;
}
