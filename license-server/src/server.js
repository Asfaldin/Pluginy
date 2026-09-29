import "dotenv/config";
import express from "express";
import { bindServer, createLicense, findByCustomer, findByKey, licenseGrants, listLicenses, recordValidation, revokeLicense } from "./db.js";
import { generateLicenseKey } from "./keys.js";
import { handleLemonSqueezyWebhook, verifyLemonSqueezySignature } from "./lemonsqueezy.js";
import {
    authenticateCustomer,
    changePassword,
    createPasswordResetToken,
    findCustomerById,
    registerCustomer,
    resetPasswordWithToken,
} from "./customers.js";
import { createSession, deleteSession, deleteSessionsForCustomer, resolveSession } from "./sessions.js";
import { CATEGORIES, INDIVIDUAL_PLUGINS, PACKAGES } from "./catalog.js";
import { sendEmail } from "./mailer.js";
import { allowDownload, findJar, listJars, logDownload } from "./pluginJars.js";
import { issueToken, loadSigningKey } from "./signing.js";
import { hit, limitByIp } from "./rateLimit.js";
import { createHash, timingSafeEqual } from "node:crypto";
import {
    addAdminReply as addTicketReply,
    CATEGORIES as TICKET_CATEGORIES,
    createTicket,
    listAll as listAllTickets,
    listByCustomer as listTicketsByCustomer,
    PRIORITIES as TICKET_PRIORITIES,
    setStatus as setTicketStatus,
} from "./tickets.js";

const app = express();

// Za Caddy (reverse proxy na tym samym VPS) - req.ip ma być adresem klienta, nie 127.0.0.1,
// inaczej limity prób liczyłyby wszystkich razem.
app.set("trust proxy", "loopback");
app.disable("x-powered-by");
app.use((_req, res, next) => {
    res.set({
        "X-Content-Type-Options": "nosniff",
        "X-Frame-Options": "DENY",
        "Referrer-Policy": "no-referrer",
    });
    next();
});

// Bez klucza podpisu pluginy nie przyjmą żadnej licencji - lepiej nie wystartować wcale
// (widać od razu przy wdrożeniu) niż po cichu wyłączyć pluginy wszystkim klientom.
if (!loadSigningKey()) {
    console.error("LICENSE_SIGNING_KEY is missing from the environment (.env) - generate it with: node deploy/gen-signing-key.mjs");
    process.exit(1);
}

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
        console.error("LemonSqueezy webhook processing error:", e);
        res.status(400).json({ error: String(e) });
    }
});

app.use(express.json());

const ADMIN_SECRET = process.env.ADMIN_SECRET;
if (!ADMIN_SECRET) {
    console.error("ADMIN_SECRET is missing from the environment (.env) - the server won't start without it, so the admin panel isn't left open.");
    process.exit(1);
}

/** Porównanie w stałym czasie (po skrócie) - długość/treść sekretu nie wycieka przez czas odpowiedzi. */
function safeEqual(a, b) {
    const ha = createHash("sha256").update(String(a ?? "")).digest();
    const hb = createHash("sha256").update(String(b ?? "")).digest();
    return timingSafeEqual(ha, hb);
}

function requireAdmin(req, res, next) {
    if (!hit(`admin:${req.ip}`, 60, 15 * 60 * 1000)) {
        return res.status(429).json({ error: "too many requests" });
    }
    if (!safeEqual(req.get("x-admin-key"), ADMIN_SECRET)) {
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
// Ważna licencja dostaje podpisany token (patrz src/signing.js) - pluginy przyjmują tylko
// podpisane odpowiedzi, więc fałszywy serwer licencji / podmieniony Core nic nie da.
// Pola valid/reason zostają dla starszych wersji Core.
const isShortString = (v, max) => typeof v === "string" && v.length > 0 && v.length <= max;

app.post("/api/validate", limitByIp("validate", 600, 60 * 60 * 1000), (req, res) => {
    const { key, plugin, serverId, nonce } = req.body ?? {};
    if (!isShortString(key, 64) || !isShortString(plugin, 32) || !isShortString(serverId, 64) || (nonce != null && !isShortString(nonce, 64))) {
        return res.status(400).json({ valid: false, reason: "missing key/plugin/serverId" });
    }
    const signed = () => {
        recordValidation(key, req.ip);
        return issueToken({ plugin, serverId, nonce });
    };

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
        return res.json({ valid: true, reason: "bound to this server", ...signed() });
    }
    if (license.serverId !== serverId) {
        return res.json({ valid: false, reason: "key already bound to a different server" });
    }
    return res.json({ valid: true, reason: "ok", ...signed() });
});

// --- Panel admina (chroniony x-admin-key) - na razie bez UI, tylko API do curl/skryptu ---
app.post("/api/admin/licenses", requireAdmin, (req, res) => {
    const { plugin, note } = req.body ?? {};
    if (!plugin) {
        return res.status(400).json({ error: "plugin is required (plugin name, e.g. 'tools', or '*' for the all-in-one bundle)" });
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

app.post("/api/auth/register", limitByIp("register", 10, 60 * 60 * 1000), (req, res) => {
    const { email, password } = req.body ?? {};
    if (!email || !password || password.length < 8) {
        return res.status(400).json({ error: "Email and password (at least 8 characters) are required." });
    }
    const customer = registerCustomer(email, password);
    if (!customer) {
        return res.status(409).json({ error: "An account with this email already exists." });
    }
    const token = createSession(customer.id);
    res.status(201).json({ token, customer });
});

app.post("/api/auth/login", limitByIp("login", 30, 15 * 60 * 1000), (req, res) => {
    const { email, password } = req.body ?? {};
    // Dodatkowo limit na konto - zgadywanie hasła jednego konta z wielu adresów IP.
    if (!hit(`login:email:${String(email ?? "").toLowerCase()}`, 10, 15 * 60 * 1000)) {
        return res.status(429).json({ error: "Too many login attempts for this account - wait 15 minutes." });
    }
    const customer = authenticateCustomer(email ?? "", password ?? "");
    if (!customer) {
        return res.status(401).json({ error: "Wrong email or password." });
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

// --- Płatne jary pluginów (patrz src/pluginJars.js) - tylko dla konta z aktywną licencją ---

/** Czy konto ma aktywną licencję obejmującą plugin. */
function customerOwns(customerId, pluginId) {
    return findByCustomer(customerId).some((l) => l.status === "active" && licenseGrants(l, pluginId));
}

// Lista jarów, które to konto może pobrać (rozmiar + sha1 - appka porównuje z tym, co jest
// na serwerze Minecrafta, i sprawdza pobrany plik).
app.get("/api/me/plugins", requireCustomer, (req, res) => {
    res.json(listJars().filter((j) => customerOwns(req.customerId, j.id)).map(({ id, filename, size, sha1 }) => ({ id, filename, size, sha1 })));
});

app.get("/api/me/plugins/:id/download", requireCustomer, (req, res) => {
    const jar = findJar(req.params.id);
    if (!jar) return res.status(404).json({ error: "This plugin isn't available for download." });
    if (!customerOwns(req.customerId, jar.id)) {
        return res.status(403).json({ error: "You don't have an active license for this plugin." });
    }
    if (!allowDownload(req.customerId)) {
        return res.status(429).json({ error: "Too many downloads in a short time - try again in an hour." });
    }
    logDownload({ customerId: req.customerId, plugin: jar.id, filename: jar.filename, ip: req.ip });
    res.set({
        "Content-Type": "application/java-archive",
        "Cache-Control": "no-store",
        "X-Jar-Filename": jar.filename,
        "X-Jar-Sha1": jar.sha1,
    });
    res.sendFile(jar.path);
});

// Testowe "kup" bez prawdziwej płatności - WYŁĄCZONE domyślnie (musisz jawnie ustawić
// DEV_LICENSE_GRANTS=1 w .env), żeby nie dało się tego przypadkiem zostawić włączonego
// na produkcji. Appka pokazuje przycisk, który tu trafia, tylko w trybie deweloperskim
// (import.meta.env.DEV, patrz ShopPage.tsx) - to druga, niezależna warstwa zabezpieczenia.
app.post("/api/me/dev-grant", requireCustomer, (req, res) => {
    if (process.env.DEV_LICENSE_GRANTS !== "1") {
        return res.status(404).json({ error: "not found" });
    }
    const { plugin } = req.body ?? {};
    if (!plugin) {
        return res.status(400).json({ error: "plugin is required" });
    }
    const license = createLicense({ key: generateLicenseKey(), plugin, note: "TEST (dev-grant)", customerId: req.customerId });
    res.status(201).json(license);
});

// Zawsze { ok: true } niezależnie od tego, czy e-mail istnieje w systemie - inaczej
// formularz zdradzałby, które adresy mają konto (enumeracja kont). Kod trafia mailem
// tylko wtedy, gdy konto istnieje.
app.post("/api/auth/forgot-password", limitByIp("forgot", 5, 60 * 60 * 1000), async (req, res) => {
    const { email } = req.body ?? {};
    if (!email) {
        return res.status(400).json({ error: "Email is required." });
    }
    const token = createPasswordResetToken(email);
    if (token) {
        await sendEmail(
            email,
            "Password reset - RSMC Manager",
            `Your password reset code: ${token}\n\nPaste it in the app on the "Forgot password" screen together with your new password. The code is valid for 30 minutes.\n\nIf you didn't request a reset, ignore this message - your password won't change.`
        );
    }
    res.json({ ok: true });
});

app.post("/api/auth/reset-password", limitByIp("reset", 10, 60 * 60 * 1000), (req, res) => {
    const { token, newPassword } = req.body ?? {};
    if (!token || !newPassword || newPassword.length < 8) {
        return res.status(400).json({ error: "The code and a new password (at least 8 characters) are required." });
    }
    const customerId = resetPasswordWithToken(token, newPassword);
    if (!customerId) {
        return res.status(400).json({ error: "The code is invalid or has expired." });
    }
    // Reset hasła = ktoś mógł przejąć konto: wylogowujemy wszystkie urządzenia.
    deleteSessionsForCustomer(customerId);
    res.json({ ok: true });
});

app.patch("/api/me/password", requireCustomer, limitByIp("password", 10, 15 * 60 * 1000), (req, res) => {
    const { currentPassword, newPassword } = req.body ?? {};
    if (!currentPassword || !newPassword || newPassword.length < 8) {
        return res.status(400).json({ error: "The current password and a new password (at least 8 characters) are required." });
    }
    const ok = changePassword(req.customerId, currentPassword, newPassword);
    if (!ok) {
        return res.status(401).json({ error: "The current password is incorrect." });
    }
    // Zmiana hasła wylogowuje pozostałe urządzenia (skradziony token przestaje działać).
    const header = req.get("authorization") ?? "";
    deleteSessionsForCustomer(req.customerId, header.slice("Bearer ".length));
    res.json({ ok: true });
});

// --- Zgłoszenia (Wsparcie w appce) ---

app.get("/api/tickets/meta", (_req, res) => {
    res.json({ categories: TICKET_CATEGORIES, priorities: TICKET_PRIORITIES });
});

app.post("/api/tickets", requireCustomer, (req, res) => {
    const { subject, category, priority, serverProfile, message } = req.body ?? {};
    if (!subject || !category || !priority || !message) {
        return res.status(400).json({ error: "Subject, category, priority and message are required." });
    }
    const ticket = createTicket({ customerId: req.customerId, subject, category, priority, serverProfile, message });
    res.status(201).json(ticket);
});

app.get("/api/tickets", requireCustomer, (req, res) => {
    res.json(listTicketsByCustomer(req.customerId));
});

// Odpowiadanie na zgłoszenia na razie tylko przez curl (x-admin-key) - ten sam wzorzec co
// wystawianie licencji, patrz README.md.
app.get("/api/admin/tickets", requireAdmin, (_req, res) => {
    res.json(listAllTickets());
});

app.post("/api/admin/tickets/:id/reply", requireAdmin, (req, res) => {
    const { message } = req.body ?? {};
    if (!message) return res.status(400).json({ error: "message is required" });
    const ticket = addTicketReply(req.params.id, message);
    if (!ticket) return res.status(404).json({ error: "not found" });
    res.json(ticket);
});

app.post("/api/admin/tickets/:id/close", requireAdmin, (req, res) => {
    const ticket = setTicketStatus(req.params.id, "closed");
    if (!ticket) return res.status(404).json({ error: "not found" });
    res.json(ticket);
});

// Publiczny - katalog tego, co można kupić (patrz src/catalog.js). storeUrl + variantId
// wystarczą appce do zbudowania linku checkout LemonSqueezy bez dodatkowego zapytania.
app.get("/api/catalog", (_req, res) => {
    res.json({
        storeUrl: process.env.LEMONSQUEEZY_STORE_URL ?? null,
        categories: CATEGORIES,
        individualPlugins: INDIVIDUAL_PLUGINS,
        packages: PACKAGES,
    });
});

app.get("/health", (_req, res) => res.json({ ok: true }));

const port = process.env.PORT || 3000;
app.listen(port, () => console.log(`Mainplugins license server listening on port ${port}`));
