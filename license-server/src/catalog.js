// Katalog tego, co można kupić - źródło prawdy dla /api/catalog (appka to stąd czyta
// do zakładki Sklep) i dla mapowania webhooków LemonSqueezy z powrotem na to, co
// klient faktycznie kupił. TO TY EDYTUJESZ TEN PLIK jako operator:
//
// 1. Załóż w LemonSqueezy jeden Product na każdą pozycję poniżej (patrz README).
// 2. Dla pozycji z "billing: both" stwórz DWA warianty (jednorazowy + subskrypcja)
//    i wklej ich prawdziwe variant_id w polu `variantId`/`subscriptionVariantId`.
// 3. Restart serwera - katalog czytany jest raz przy starcie procesu.
//
// Zestawy (Starter/Pro/Ultimate) są SKUMULOWANE (Pro zawiera Starter + więcej, Ultimate
// zawiera wszystko) - to standardowa, łatwa do zrozumienia drabinka cenowa i łatwy
// upsell ("odblokuj więcej, dopłacając różnicę"), zaproponowana na bazie LOC/wartości
// handlowej z COMMERCIALIZATION.md. Popraw wedle uznania - to tylko punkt startowy.

export const INDIVIDUAL_PLUGINS = [
    { id: "tools", label: "Custom itemy i narzędzia", variantId: null },
    { id: "shop", label: "Sklep (ekonomia)", variantId: null },
    { id: "skyblock", label: "Skyblock", variantId: null },
    { id: "quests", label: "Questy", variantId: null },
    { id: "advancements", label: "Osiągnięcia", variantId: null },
    { id: "redstone", label: "Redstone-urządzenia", variantId: null },
    { id: "spawn", label: "Spawn / warpy / obszary", variantId: null },
    { id: "fishing", label: "Wędkarstwo", variantId: null },
    { id: "spawners", label: "Customowe spawnery", variantId: null },
    { id: "market", label: "Rynek graczy", variantId: null },
    { id: "dungeons", label: "Loch i boss", variantId: null },
    { id: "crates", label: "Skrzynki losowe", variantId: null },
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
        variantId: null,
        subscriptionVariantId: null,
    },
    {
        id: "pro",
        label: "Pro Pack",
        description: "Starter + questy, osiągnięcia, redstone-urządzenia, spawn/warpy - pełna progresja gracza.",
        plugins: ["crates", "market", "spawners", "dungeons", "quests", "advancements", "redstone", "spawn", "fishing"],
        variantId: null,
        subscriptionVariantId: null,
    },
    {
        id: "ultimate",
        label: "Ultimate (wszystko)",
        description: "Cały ekosystem Mainplugins, łącznie z tools/shop/skyblock - i z każdym kolejnym pluginem, jaki dojdzie później.",
        plugins: "*",
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
