// Plany subskrypcji (Free / Plus / Pro / Network) - jedna subskrypcja odblokowuje funkcje we
// WSZYSTKICH pluginach. Każdy plugin działa za darmo w wersji podstawowej; plan zdejmuje limity
// i włącza funkcje zaawansowane.
//
// Plan to zwykła licencja z polem plugin = "plan:<id>" (np. "plan:pro") - dzięki temu cały
// istniejący mechanizm (webhooki LemonSqueezy, statusy, anulowanie, dev-grant) działa bez zmian.
// Stara licencja "*" (Ultimate) = plan Network, żeby nikt nic nie stracił.
//
// !!! Ta sama lista jest w appce: PluginManager/desktop-app/src/lib/plans.ts - zmieniaj obie.

export const PLAN_ORDER = ["free", "plus", "pro", "network"];

/** Funkcja -> najniższy plan, który ją ma. Klucze czytają appka i pluginy (LicenseGuard). */
export const FEATURES = {
    // aplikacja
    "app.templates.save": "plus",
    "app.commands.ingame": "plus",
    "app.schedule": "pro",
    "app.backups": "pro",
    "app.team": "network",
    "app.sync": "network",
    // skrzynki
    "crates.roulette": "plus",
    "crates.announce": "plus",
    "crates.sharedKeys": "plus",
    // sklep
    "shop.dynamicPrices": "plus",
    "shop.events": "pro",
    "shop.stats": "pro",
    // questy
    "quests.chains": "plus",
    "quests.daily": "plus",
    // wyspy
    "islands.upgrades": "plus",
    "islands.bank": "pro",
    // kreatory 3D
    "creator.mobs": "pro",
    "creator.blocks": "pro",
};

/** Limity planów (null = bez limitu). */
export const LIMITS = {
    free: { servers: 1, crates: 2, quests: 10, statsDays: 1 },
    plus: { servers: 3, crates: null, quests: null, statsDays: 7 },
    pro: { servers: null, crates: null, quests: null, statsDays: 30 },
    network: { servers: null, crates: null, quests: null, statsDays: 90 },
};

/**
 * Do wyświetlania w appce i na stronie. Ceny informacyjne (USD) - prawdziwą kwotę ustala
 * wariant w LemonSqueezy. variantId* uzupełnij prawdziwymi ID wariantów subskrypcji.
 */
export const PLANS = [
    { id: "free", name: "Free", monthly: 0, yearly: 0, variantIdMonthly: null, variantIdYearly: null },
    { id: "plus", name: "Plus", monthly: 9, yearly: 79, variantIdMonthly: null, variantIdYearly: null },
    { id: "pro", name: "Pro", monthly: 19, yearly: 169, variantIdMonthly: null, variantIdYearly: null },
    { id: "network", name: "Network", monthly: 39, yearly: 349, variantIdMonthly: null, variantIdYearly: null },
];

const rank = (id) => Math.max(0, PLAN_ORDER.indexOf(id));

/** Plan z licencji: najwyższy aktywny "plan:<id>", "*" = network, reszta = free. */
export function planOfLicenses(licenses) {
    let best = "free";
    for (const l of licenses) {
        if (l.status !== "active") continue;
        const id = l.plugin === "*" ? "network" : l.plugin.startsWith("plan:") ? l.plugin.slice(5) : null;
        if (id && PLAN_ORDER.includes(id) && rank(id) > rank(best)) best = id;
    }
    return best;
}

/** Co daje plan - lista funkcji i limity (dla /api/me/plan, a później tokenu dla pluginów). */
export function entitlements(planId) {
    const r = rank(planId);
    return {
        plan: planId,
        features: Object.entries(FEATURES)
            .filter(([, min]) => rank(min) <= r)
            .map(([f]) => f),
        limits: LIMITS[planId] ?? LIMITS.free,
    };
}

/** Wariant LemonSqueezy -> licencja planu (dla webhooka, patrz catalog.js#resolveVariant). */
export function resolvePlanVariant(variantId) {
    const id = String(variantId);
    for (const p of PLANS) {
        if ((p.variantIdMonthly != null && String(p.variantIdMonthly) === id) || (p.variantIdYearly != null && String(p.variantIdYearly) === id)) {
            return { plugin: `plan:${p.id}`, billingType: "subscription" };
        }
    }
    return null;
}
