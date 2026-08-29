# Mainplugins License Server

Minimalny serwer licencyjny: wydaje i waliduje klucze dla płatnych pluginów
z ekosystemu Mainplugins. Model: **jeden klucz = jeden serwer Minecraft**
(klucz przypisuje się na stałe do serwera przy pierwszym użyciu).

Bez płatności na razie (patrz `COMMERCIALIZATION.md` w repo głównym) - klucze
wydaje się ręcznie z linii komend (`curl`) po otrzymaniu zapłaty poza systemem
(Discord/przelew). Automatyzację płatności (Stripe/LemonSqueezy/Paddle) da się
dołożyć później bez zmiany reszty architektury - dojdzie tylko webhook, który
sam woła `POST /api/admin/licenses`.

## Uruchomienie lokalnie

```
cd license-server
npm install
cp .env.example .env
# wpisz do .env losowy ADMIN_SECRET
npm start
```

Serwer wystartuje na `http://localhost:3000`. Dane licencji trzymane są w
`data/licenses.json` (plik, nie baza danych - w sam raz na tę skalę; patrz
komentarz w `src/db.js` jeśli kiedyś trzeba to wymienić na prawdziwą bazę).

## Wystawienie klucza (ręcznie, dopóki nie ma automatycznych płatności)

```bash
curl -X POST http://localhost:3000/api/admin/licenses \
  -H "x-admin-key: <TWÓJ_ADMIN_SECRET>" \
  -H "Content-Type: application/json" \
  -d '{"plugin": "tools", "note": "Discord: przykładowy_klient"}'
```

`plugin` to identyfikator pluginu tak, jak podaje go `LicenseManager` w grze
(patrz `mainplugins-core` - na razie np. `tools`). `plugin: "*"` tworzy klucz
ważny dla **dowolnego** płatnego pluginu (paczka "wszystko w jednym").

Odpowiedź zawiera wygenerowany klucz w polu `key` - ten klucz trafia do
klienta, a on wkleja go do `license.yml` w folderze danych `MainpluginsCore`
na swoim serwerze.

## Podgląd i cofanie licencji

```bash
curl http://localhost:3000/api/admin/licenses -H "x-admin-key: <SECRET>"
curl -X POST http://localhost:3000/api/admin/licenses/<KLUCZ>/revoke -H "x-admin-key: <SECRET>"
```

## Automatyczna sprzedaż przez LemonSqueezy (opcjonalne)

Zamiast ręcznie wystawiać klucze po sprzedaży poza systemem, można podłączyć
**LemonSqueezy** (merchant of record - obsługuje płatność i podatki za Ciebie,
nie musisz zakładać firmy) tak, żeby klucz wystawiał się i wysyłał do klienta
mailem automatycznie, od razu po zakupie.

**Konfiguracja (jednorazowo):**
1. Załóż konto na [lemonsqueezy.com](https://www.lemonsqueezy.com), stwórz
   Store, a w nim jeden **Product** z osobnym **Variant** per płatny plugin/
   pakiet (np. wariant "mainplugins-tools" za 49 zł).
2. Zanotuj **variant_id** każdego wariantu (widoczny w URL/API panelu) i wpisz
   mapowanie do `.env`:
   ```
   LEMONSQUEEZY_VARIANT_MAP={"123456":"tools","123457":"shop"}
   ```
3. W Settings → Webhooks dodaj webhook wskazujący na
   `https://twoj-license-server.example.com/webhooks/lemonsqueezy`, event
   `order_created`. LemonSqueezy poda **Signing secret** - wklej go do `.env`
   jako `LEMONSQUEEZY_WEBHOOK_SECRET`.
4. (Opcjonalnie, ale zalecane) załóż konto na [resend.com](https://resend.com)
   (darmowy tier), zweryfikuj domenę nadawcy, wpisz `RESEND_API_KEY` i
   `FROM_EMAIL` do `.env` - bez tego klucz i tak się wystawi, ale trzeba go
   będzie ręcznie znaleźć w `/api/admin/licenses` i wysłać klientowi samemu.
5. Link/przycisk "Kup" do wariantu (checkout URL z panelu LemonSqueezy)
   wklejasz na stronę sprzedażową.

**Ważne:** kod webhooka (`src/lemonsqueezy.js`) napisany jest wg oficjalnej
dokumentacji LemonSqueezy, ale nie było możliwości przetestować go na żywo
bez prawdziwego konta/sprzedaży. Przy pierwszym realnym zamówieniu sprawdź
logi serwera - jeśli struktura payloadu się nie zgadza (LemonSqueezy czasem
zmienia szczegóły API), payload trafia do logu w całości, żeby łatwo
poprawić ścieżki pól w `handleLemonSqueezyWebhook`.

## Deploy za darmo (na czas przed kupnem VPS)

Dowolny hosting node.js z darmowym tierem wystarczy, np. **Render.com** (Web
Service, free plan):

1. Wrzuć ten folder do osobnego repo (albo skonfiguruj Render na subfolder
   `license-server/` tego repo).
2. Build command: `npm install`, Start command: `npm start`.
3. Ustaw zmienną środowiskową `ADMIN_SECRET` w panelu Render (nie w pliku -
   sekrety środowiskowe hostingu, nigdy w repo).
4. Render nada darmowy adres `https://twoja-nazwa.onrender.com` - to wpisujesz
   jako `api.url` w `license.yml` po stronie Minecraft-serwerów klientów.

Uwaga: darmowy plan Render usypia serwis po ~15 min bezczynności (pierwsze
zapytanie po uśpieniu trwa kilka-kilkanaście sekund) - `LicenseManager` ma na
to grace period, więc nie zablokuje pluginu klientowi przy jednorazowym wolnym
requeście. Gdy pojawi się VPS - wystarczy `git pull && npm install && npm
start` na nim, bez zmian w kodzie.
