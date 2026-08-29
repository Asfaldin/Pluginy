import { createHmac, timingSafeEqual } from "node:crypto";
import { resolveVariant } from "./catalog.js";
import { findCustomerByEmail } from "./customers.js";
import { createLicense, findBySubscriptionId, setStatus } from "./db.js";
import { generateLicenseKey } from "./keys.js";

// Integracja z LemonSqueezy (merchant-of-record - obsługuje płatność/podatki za Ciebie,
// nie musisz zakładać firmy). Dwa niezależne strumienie zdarzeń:
//  - "order_created"          -> zakup jednorazowy (plugin pojedynczy albo pakiet)
//  - "subscription_*"         -> cykl życia subskrypcji pakietu (Starter/Pro/Ultimate)
//
// UWAGA: kształt payloadu poniżej odpowiada oficjalnej dokumentacji LemonSqueezy na
// dzień napisania tego kodu - nie było jak przetestować na żywo bez prawdziwego konta/
// produktu/subskrypcji. Przy pierwszym realnym webhooku sprawdź logi (loguje cały
// payload przy nierozpoznanym kształcie) i popraw ścieżki pól poniżej, jeśli trzeba.

/** Weryfikuje podpis HMAC-SHA256 nagłówka X-Signature względem SUROWEGO body (przed JSON.parse). */
export function verifyLemonSqueezySignature(rawBody, signatureHeader, secret) {
    if (!signatureHeader || !secret) return false;
    const expected = createHmac("sha256", secret).update(rawBody).digest("hex");
    const expectedBuf = Buffer.from(expected, "utf8");
    const actualBuf = Buffer.from(signatureHeader, "utf8");
    if (expectedBuf.length !== actualBuf.length) return false;
    return timingSafeEqual(expectedBuf, actualBuf);
}

/**
 * Klienta łączymy z zakupem na dwa sposoby (w tej kolejności):
 * 1. `meta.custom_data.customer_id` - appka dokłada to do URL-a checkout, gdy klient
 *    jest zalogowany (patrz ShopPage.tsx) - LemonSqueezy odsyła to z powrotem w webhooku.
 * 2. dopasowanie po e-mailu kupującego do istniejącego konta - fallback, gdyby ktoś
 *    kupił bez przechodzenia przez appkę (np. bezpośredni link do checkout).
 * Zwraca null, jeśli żaden sposób się nie powiódł (licencja i tak powstaje, tylko bez
 * przypisania do konta - klient może się zgłosić, żeby ją ręcznie dowiązać).
 */
function resolveCustomerId(payload, buyerEmail) {
    const fromCustomData = payload?.meta?.custom_data?.customer_id;
    if (fromCustomData) return fromCustomData;
    const byEmail = buyerEmail ? findCustomerByEmail(buyerEmail) : null;
    return byEmail?.id ?? null;
}

async function sendLicenseEmail(toEmail, pluginLabel, key) {
    const apiKey = process.env.RESEND_API_KEY;
    const fromEmail = process.env.FROM_EMAIL;
    if (!apiKey || !fromEmail) {
        console.warn(`RESEND_API_KEY/FROM_EMAIL nieskonfigurowane - klucz ${key} dla ${toEmail} (${pluginLabel}) NIE został wysłany mailem, tylko zapisany.`);
        return;
    }
    try {
        const resp = await fetch("https://api.resend.com/emails", {
            method: "POST",
            headers: { Authorization: `Bearer ${apiKey}`, "Content-Type": "application/json" },
            body: JSON.stringify({
                from: fromEmail,
                to: toEmail,
                subject: `Twój klucz licencyjny - ${pluginLabel}`,
                text: `Dziękujemy za zakup!\n\n${pluginLabel}\nKlucz licencyjny: ${key}\n\nZaloguj się w zakładce Sklep w PluginManagerze, żeby zobaczyć swoje licencje, albo wklej ten klucz ręcznie do license.yml na serwerze.`,
            }),
        });
        if (!resp.ok) {
            console.error(`Resend zwrócił HTTP ${resp.status} przy wysyłce klucza do ${toEmail}: ${await resp.text()}`);
        }
    } catch (e) {
        console.error(`Nie udało się wysłać maila z kluczem do ${toEmail}:`, e);
    }
}

async function handleOrderCreated(payload) {
    const attrs = payload?.data?.attributes;
    const variantId = attrs?.first_order_item?.variant_id;
    const buyerEmail = attrs?.user_email;
    const orderId = payload?.data?.id;

    if (!variantId || !buyerEmail) {
        console.error("order_created bez variant_id/user_email - pełen payload:", JSON.stringify(payload));
        return { handled: false, reason: "missing variant_id or user_email" };
    }

    const resolved = resolveVariant(variantId);
    if (!resolved) {
        console.error(`Brak pozycji katalogu dla variant_id=${variantId} (order ${orderId}, kupujący ${buyerEmail}) - uzupełnij src/catalog.js.`);
        return { handled: false, reason: `unmapped variant_id: ${variantId}` };
    }

    const customerId = resolveCustomerId(payload, buyerEmail);
    const license = createLicense({
        key: generateLicenseKey(),
        plugin: resolved.plugin,
        note: `LemonSqueezy order ${orderId} - ${buyerEmail}`,
        customerId,
        billingType: "one-time",
    });

    await sendLicenseEmail(buyerEmail, resolved.plugin, license.key);
    return { handled: true, plugin: resolved.plugin, key: license.key, customerId };
}

async function handleSubscriptionCreated(payload) {
    const attrs = payload?.data?.attributes;
    const variantId = attrs?.variant_id;
    const buyerEmail = attrs?.user_email;
    const subscriptionId = payload?.data?.id;

    if (!variantId || !buyerEmail || !subscriptionId) {
        console.error("subscription_created bez variant_id/user_email/id - pełen payload:", JSON.stringify(payload));
        return { handled: false, reason: "missing variant_id, user_email or subscription id" };
    }

    const resolved = resolveVariant(variantId);
    if (!resolved || resolved.billingType !== "subscription") {
        console.error(`Brak pozycji katalogu (subskrypcja) dla variant_id=${variantId} - uzupełnij src/catalog.js (subscriptionVariantId).`);
        return { handled: false, reason: `unmapped subscription variant_id: ${variantId}` };
    }

    const customerId = resolveCustomerId(payload, buyerEmail);
    const license = createLicense({
        key: generateLicenseKey(),
        plugin: resolved.plugin,
        note: `LemonSqueezy subscription ${subscriptionId} - ${buyerEmail}`,
        customerId,
        subscriptionId: String(subscriptionId),
        billingType: "subscription",
    });

    await sendLicenseEmail(buyerEmail, resolved.plugin, license.key);
    return { handled: true, plugin: resolved.plugin, key: license.key, customerId };
}

/** subscription_updated/cancelled/expired/payment_failed - zmienia status ISTNIEJĄCEJ licencji, nic nowego nie tworzy. */
function handleSubscriptionStatusChange(payload, eventName) {
    const subscriptionId = payload?.data?.id;
    const status = payload?.data?.attributes?.status; // "active" | "cancelled" | "expired" | "past_due" | "unpaid" | ...
    if (!subscriptionId) {
        console.error(`${eventName} bez id subskrypcji - pełen payload:`, JSON.stringify(payload));
        return { handled: false, reason: "missing subscription id" };
    }

    const license = findBySubscriptionId(subscriptionId);
    if (!license) {
        console.error(`${eventName}: nie znaleziono licencji dla subscriptionId=${subscriptionId} (nie utworzona przez subscription_created?).`);
        return { handled: false, reason: `no license for subscription ${subscriptionId}` };
    }

    const nowActive = status === "active" || eventName === "subscription_payment_success";
    setStatus(license.key, nowActive ? "active" : "revoked");
    return { handled: true, key: license.key, newStatus: nowActive ? "active" : "revoked", lemonSqueezyStatus: status };
}

/** Obsługa webhooka - `rawBody` to Buffer (patrz express.raw() w server.js), nie sparsowany JSON. */
export async function handleLemonSqueezyWebhook(rawBody) {
    let payload;
    try {
        payload = JSON.parse(rawBody.toString("utf8"));
    } catch {
        throw new Error("invalid JSON body");
    }

    const eventName = payload?.meta?.event_name;
    switch (eventName) {
        case "order_created":
            return handleOrderCreated(payload);
        case "subscription_created":
            return handleSubscriptionCreated(payload);
        case "subscription_updated":
        case "subscription_cancelled":
        case "subscription_expired":
        case "subscription_payment_success":
        case "subscription_payment_failed":
            return handleSubscriptionStatusChange(payload, eventName);
        default:
            // Inne eventy (order_refunded, itd.) - celowo ignorowane na razie.
            return { handled: false, reason: `ignored event: ${eventName}` };
    }
}
