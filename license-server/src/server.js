import "dotenv/config";
import express from "express";
import { bindServer, createLicense, findByCustomer, findByKey, licenseGrants, listLicenses, revokeLicense } from "./db.js";
import { generateLicenseKey } from "./keys.js";
import { handleLemonSqueezyWebhook, verifyLemonSqueezySignature } from "./lemonsqueezy.js";
import { authenticateCustomer, findCustomerById, registerCustomer } from "./customers.js";
import { createSession, deleteSession, resolveSession } from "./sessions.js";
import { INDIVIDUAL_PLUGINS, PACKAGES } from "./catalog.js";

const app = express();

// Musi być zarejestrowany PRZED app.use(express.json()) i z express.raw() (nie .json()) -
// weryfikacja podpisu HMAC potrzebuje dokładnie surowych bajtów body, tak jak je podpisało
// LemonSqueezy. Gdyby to poszło przez express.json() jak reszta endpointów, ciało zostałoby
// sparsowane/zserializowane inaczej i podpis by się nie zgadzał.
app.post("/webhooks/lemonsqueezy", express.raw({ type: "application/json" }), async (req, res) => {
    const signature = req.get("x-signature");
    const secret = process.env.LEMONSQUEEZY_WEBHOOK_SECRET;
    if (!secret) {
        console.error("LEMONSQUEEZY_WEBHOOK_SECRET nieskonfigurowany w .env - webhook odrzucony.");
        return res.status(500).json({ error: "webhook not configured" });
    }
    if (!verifyLemonSqueezySignature(req.body, signature, secret)) {
        return res.status(401).json({ error: "invalid signature" });
    }
    try {
        const result = await handleLemonSqueezyWebhook(req.body);
        res.json(result);
    } catch (e) {
        console.error("Błąd przetwarzania webhooka LemonSqueezy:", e);
        res.status(400).json({ error: String(e) });
    }
});

app.use(express.json());

const ADMIN_SECRET = process.env.ADMIN_SECRET;
if (!ADMIN_SECRET) {
    console.error("Brak ADMIN_SECRET w środowisku (.env) - serwer nie wystartuje bez tego, żeby nie zostawić panelu admina otwartego.");
    process.exit(1);
}

function requireAdmin(req, res, next) {
    if (req.get("x-admin-key") !== ADMIN_SECRET) {
        return res.status(401).json({ error: "unauthorized" });
    }
    next();
}

/** Autoryzacja klienta (nie admina) - token z Authorization: Bearer <token>, wydany przez /api/auth/login. */
function requireCustomer(req, res, next) {
    const header = req.get("authorization") ?? "";
    const token = header.startsWith("Bearer ") ? header.slice("Bearer ".length) : null;
    const customerId = resolveSession(token);
    if (!customerId) {
        return res.status(401).json({ error: "unauthorized" });
    }
    req.customerId = customerId;
    next();
}

// --- Publiczny endpoint: wywoływany przez pluginy (LicenseManager w mainplugins-core) ---
app.post("/api/validate", (req, res) => {
    const { key, plugin, serverId } = req.body ?? {};
    if (!key || !plugin || !serverId) {
        return res.status(400).json({ valid: false, reason: "missing key/plugin/serverId" });
    }

    const license = findByKey(key);
    if (!license) {
        return res.json({ valid: false, reason: "unknown key" });
    }
    if (license.status === "revoked") {
        return res.json({ valid: false, reason: "revoked" });
    }
    if (!licenseGrants(license, plugin)) {
        return res.json({ valid: false, reason: "key not valid for this plugin" });
    }

    if (!license.serverId) {
        // Pierwsze użycie klucza - przypisujemy go na stałe do tego serwera
        // (model "jeden klucz = jeden serwer").
        bindServer(key, serverId);
        return res.json({ valid: true, reason: "bound to this server" });
    }
    if (license.serverId !== serverId) {
        return res.json({ valid: false, reason: "key already bound to a different server" });
    }
    return res.json({ valid: true, reason: "ok" });
});

// --- Panel admina (chroniony x-admin-key) - na razie bez UI, tylko API do curl/skryptu ---
app.post("/api/admin/licenses", requireAdmin, (req, res) => {
    const { plugin, note } = req.body ?? {};
    if (!plugin) {
        return res.status(400).json({ error: "plugin is required (nazwa pluginu, np. 'tools', albo '*' dla paczki wszystko-w-jednym)" });
    }
    const license = createLicense({ key: generateLicenseKey(), plugin, note });
    res.status(201).json(license);
});

app.get("/api/admin/licenses", requireAdmin, (_req, res) => {
    res.json(listLicenses());
});

app.post("/api/admin/licenses/:key/revoke", requireAdmin, (req, res) => {
    const license = revokeLicense(req.params.key);
    if (!license) return res.status(404).json({ error: "not found" });
    res.json(license);
});

// --- Konta klientów (sklep w PluginManagerze) ---

app.post("/api/auth/register", (req, res) => {
    const { email, password } = req.body ?? {};
    if (!email || !password || password.length < 8) {
        return res.status(400).json({ error: "email i hasło (min. 8 znaków) są wymagane" });
    }
    const customer = registerCustomer(email, password);
    if (!customer) {
        return res.status(409).json({ error: "konto z tym e-mailem już istnieje" });
    }
    const token = createSession(customer.id);
    res.status(201).json({ token, customer });
});

app.post("/api/auth/login", (req, res) => {
    const { email, password } = req.body ?? {};
    const customer = authenticateCustomer(email ?? "", password ?? "");
    if (!customer) {
        return res.status(401).json({ error: "błędny e-mail lub hasło" });
    }
    const token = createSession(customer.id);
    res.json({ token, customer });
});

app.post("/api/auth/logout", requireCustomer, (req, res) => {
    const header = req.get("authorization") ?? "";
    deleteSession(header.slice("Bearer ".length));
    res.json({ ok: true });
});

app.get("/api/me", requireCustomer, (req, res) => {
    const customer = findCustomerById(req.customerId);
    if (!customer) return res.status(404).json({ error: "not found" });
    const { passwordHash, ...pub } = customer;
    void passwordHash;
    res.json(pub);
});

app.get("/api/me/licenses", requireCustomer, (req, res) => {
    res.json(findByCustomer(req.customerId));
});

// Publiczny - katalog tego, co można kupić (patrz src/catalog.js). storeUrl + variantId
// wystarczą appce do zbudowania linku checkout LemonSqueezy bez dodatkowego zapytania.
app.get("/api/catalog", (_req, res) => {
    res.json({
        storeUrl: process.env.LEMONSQUEEZY_STORE_URL ?? null,
        individualPlugins: INDIVIDUAL_PLUGINS,
        packages: PACKAGES,
    });
});

app.get("/health", (_req, res) => res.json({ ok: true }));

const port = process.env.PORT || 3000;
app.listen(port, () => console.log(`Serwer licencyjny Mainplugins nasłuchuje na porcie ${port}`));
