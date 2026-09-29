// Limity prób w pamięci procesu (okno przesuwne). Wystarczają przy jednym procesie Node
// za Caddy; restart serwera je zeruje, co przy tej skali jest w porządku.
// Chronią przed zgadywaniem haseł (logowanie), kluczy licencji (validate) i zasypywaniem
// skrzynek pocztowych (forgot-password).

const buckets = new Map();

/** true = wolno; false = limit przekroczony. `key` np. "login:ip:1.2.3.4". */
export function hit(key, max, windowMs) {
    const now = Date.now();
    const list = (buckets.get(key) ?? []).filter((t) => now - t < windowMs);
    const allowed = list.length < max;
    if (allowed) list.push(now);
    buckets.set(key, list);
    return allowed;
}

/** Middleware Express: limit na IP dla danej trasy. */
export function limitByIp(name, max, windowMs) {
    return (req, res, next) => {
        if (!hit(`${name}:${req.ip}`, max, windowMs)) {
            return res.status(429).json({ error: "Too many attempts - wait a moment and try again." });
        }
        next();
    };
}

// Sprzątanie pustych/starych wpisów, żeby mapa nie rosła bez końca.
setInterval(() => {
    const now = Date.now();
    for (const [key, list] of buckets) {
        const fresh = list.filter((t) => now - t < 24 * 60 * 60 * 1000);
        if (fresh.length === 0) buckets.delete(key);
        else buckets.set(key, fresh);
    }
}, 10 * 60 * 1000).unref();
