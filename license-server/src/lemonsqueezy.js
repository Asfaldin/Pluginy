import { createHmac, timingSafeEqual } from "node:crypto";
import { resolveVariant } from "./catalog.js";
import { findCustomerByEmail, findCustomerById } from "./customers.js";
import { createLicense, findByOrderId, findBySubscriptionId, setStatus } from "./db.js";
import { generateLicenseKey } from "./keys.js";
import { sendEmail } from "./mailer.js";

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
    // customer_id z linku checkout przyjmujemy tylko, gdy takie konto naprawdę istnieje
    // (ktoś mógł dopisać do URL-a dowolną wartość).
    const fromCustomData = payload?.meta?.custom_data?.customer_id;
    if (fromCustomData && findCustomerById(String(fromCustomData))) return String(fromCustomData);
    const byEmail = buyerEmail ? findCustomerByEmail(buyerEmail) : null;
    return byEmail?.id ?? null;
}

async function sendLicenseEmail(toEmail, pluginLabel, key) {
    await sendEmail(
        toEmail,
        `Your license key - ${pluginLabel}`,
        `Thank you for your purchase!\n\n${pluginLabel}\nLicense key: ${key}\n\nLog in on the Shop tab in RSMC Manager to see your licenses, or paste this key manually into license.yml on the server.`
    );
}

async function handleOrderCreated(payload) {
    const attrs = payload?.data?.attributes;
    const variantId = attrs?.first_order_item?.variant_id;
    const buyerEmail = attrs?.user_email;
    const orderId = payload?.data?.id;

    if (!variantId || !buyerEmail) {
        console.error("order_created without variant_id/user_email - full payload:", JSON.stringify(payload));
        return { handled: false, reason: "missing variant_id or user_email" };
    }

    // LemonSqueezy ponawia webhook przy błędzie/timeoucie - to samo zamówienie drugi raz
    // nie może dać drugiego klucza.
    const existing = orderId != null ? findByOrderId(orderId) : null;
    if (existing) {
        return { handled: true, duplicate: true, key: existing.key };
    }

    const resolved = resolveVariant(variantId);
    if (!resolved) {
        console.error(`No catalog entry for variant_id=${variantId} (order ${orderId}, buyer ${buyerEmail}) - add it to src/catalog.js.`);
        return { handled: false, reason: `unmapped variant_id: ${variantId}` };
    }

    const customerId = resolveCustomerId(payload, buyerEmail);
    const license = createLicense({
        key: generateLicenseKey(),
        plugin: resolved.plugin,
        note: `LemonSqueezy order ${orderId} - ${buyerEmail}`,
        customerId,
        billingType: "one-time",
        orderId,
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
        console.error("subscription_created without variant_id/user_email/id - full payload:", JSON.stringify(payload));
        return { handled: false, reason: "missing variant_id, user_email or subscription id" };
    }

    // Powtórzony webhook - subskrypcja ma już licencję.
    const existingSub = findBySubscriptionId(subscriptionId);
    if (existingSub) {
        return { handled: true, duplicate: true, key: existingSub.key };
    }

    const resolved = resolveVariant(variantId);
    if (!resolved || resolved.billingType !== "subscription") {
        console.error(`No catalog entry (subscription) for variant_id=${variantId} - add it to src/catalog.js (subscriptionVariantId).`);
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
        console.error(`${eventName} without a subscription id - full payload:`, JSON.stringify(payload));
        return { handled: false, reason: "missing subscription id" };
    }

    const license = findBySubscriptionId(subscriptionId);
    if (!license) {
        console.error(`${eventName}: nie znaleziono licencji dla subscriptionId=${subscriptionId} (nie utworzona przez subscription_created?).`);
        return { handled: false, reason: `no license for subscription ${subscriptionId}` };
    }

    // Status z LemonSqueezy decyduje (nie nazwa zdarzenia):
    //  - active, on_trial        -> działa,
    //  - cancelled               -> klient anulował, ale okres jest opłacony do końca (ends_at)
    //                               - działa, aż przyjdzie "expired",
    //  - past_due                -> płatność się nie udała, LemonSqueezy ponawia - dajemy czas,
    //  - expired, unpaid, paused -> odbieramy.
    const KEEP = new Set(["active", "on_trial", "cancelled", "past_due"]);
    if (!status) {
        console.error(`${eventName} without attributes.status - full payload:`, JSON.stringify(payload));
        return { handled: false, reason: "missing status" };
    }
    const nowActive = KEEP.has(status);
    setStatus(license.key, nowActive ? "active" : "revoked");
    return { handled: true, key: license.key, newStatus: nowActive ? "active" : "revoked", lemonSqueezyStatus: status };
}

/** Zwrot pieniędzy za zamówienie - licencja z tego zamówienia przestaje działać. */
function handleOrderRefunded(payload) {
    const orderId = payload?.data?.id;
    const license = orderId != null ? findByOrderId(orderId) : null;
    if (!license) {
        console.error(`order_refunded: no license for order ${orderId} - full payload:`, JSON.stringify(payload));
        return { handled: false, reason: `no license for order ${orderId}` };
    }
    setStatus(license.key, "revoked");
    return { handled: true, key: license.key, newStatus: "revoked" };
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
        case "subscription_paused":
        case "subscription_unpaused":
        case "subscription_resumed":
            return handleSubscriptionStatusChange(payload, eventName);
        case "order_refunded":
            return handleOrderRefunded(payload);
        default:
            // Pozostałe zdarzenia nie zmieniają licencji.
            return { handled: false, reason: `ignored event: ${eventName}` };
    }
}
