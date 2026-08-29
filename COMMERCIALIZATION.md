# Plan komercjalizacji Mainplugins + PluginManager

Ustalone 2026-08-28 z Claude (sesja PluginManager). Kontekst: [PluginManager
product direction](../PluginManager - patrz auto-memory "pluginmanager-product-direction")
— docelowo PluginManager ma być sprzedawany razem z tym zestawem pluginów
innym właścicielom serwerów.

## Kolejność faz (ustalone)

1. **Audyt i fix bugów/crashy** w istniejących pluginach (ten dokument, sekcja niżej)
2. **System manifestów** (`pluginmanager.yml`) — każdy plugin deklaruje własny
   config/reload command, żeby PluginManager działał generycznie, nie tylko
   na hardkodowanych założeniach o Mainplugins
3. **Licencjonowanie** — mechanizm weryfikacji klucza dla pluginów płatnych,
   budowany już na tym etapie (nie odkładany na później)
4. **Pakowanie dystrybucji** — osobne buildy free/paid + integracja z
   PluginManagerem do zarządzania kluczami klientów

## Podział free / paid

**Zdecydowane (2026-08-28):** tylko `mainplugins-announcer` jest darmowy na
razie. Reszta podziału odłożona — użytkownik zdecyduje później, moduł po
module albo hurtowo. Nie zakładać żadnego innego pluginu jako free/paid, dopóki
nie padnie wyraźna decyzja.

| Plugin | LOC | Status |
|---|---|---|
| `mainplugins-announcer` | 154 | **FREE — potwierdzone** |
| pozostałe 17 modułów | ~28 800 łącznie | **do ustalenia później** |

Poniższa tabela to wcześniejsza propozycja robocza (kryteria: LOC, zależność
od `core`, wartość handlowa) — zachowana jako punkt wyjścia do przyszłej
rozmowy, ale nie jest już aktualną decyzją poza wierszem announcer.

### Poprzednia propozycja robocza (nieaktualna poza announcer)

| Plugin | LOC | Uwagi |
|---|---|---|
| `mainplugins-teleport` | 157 | podstawowe komendy teleportacji |
| `mainplugins-farming` | 175 | usprawnienia farmienia |
| `mainplugins-hud` | 470 | |
| `mainplugins-menu` | 536 | |
| `mainplugins-ranks` | 425 | |
| `mainplugins-chatfilter` | 792 | |
| `mainplugins-tools` | 4814 | custom itemy — flagowa funkcja kreatora z PluginManagera |
| `mainplugins-shop` | 3645 | ekonomia/sklep |
| `mainplugins-skyblock` | 3639 | cały tryb gry |
| `mainplugins-quests` | 2460 | system questów |
| `mainplugins-advancements` | 2449 | rozbudowany system osiągnięć |
| `mainplugins-redstone` | 1724 | custom redstone-itemy |
| `mainplugins-spawn` | 1345 | zarządzanie spawnem |
| `mainplugins-fishing` | 863 | rozbudowane wędkarstwo |
| `mainplugins-spawners` | 754 | customowe spawnery |
| `mainplugins-market` | 631 | rynek graczy |
| `mainplugins-dungeons` | 644 | lochy |
| `mainplugins-crates` | 462 | skrzynki losowe — mocny hit monetyzacyjny |

### Fundament
`mainplugins-core` (2145 LOC, 38 plików) — wymagany przez prawie wszystkie
powyższe (poza announcer/teleport/farming). Musi być darmowy/dołączany do
każdej instalacji — nie sprzedajemy go osobno. To tu docelowo trafi wspólny
mechanizm weryfikacji licencji (jeden punkt walidacji zamiast kopiowania
logiki do każdego pluginu płatnego).

## Znane problemy środowiskowe (nie blokują fazy 1)

W korzeniu repo leży kilka `hs_err_pid*.log` — sprawdzony najnowszy (`hs_err_pid29104.log`)
to `OutOfMemoryError` na poziomie JVM/systemu (brak RAM/swap), nie wygląda na
wyciek pamięci w konkretnym pluginie. Do obserwacji, jeśli się powtarza przy
normalnym obciążeniu serwera — wtedy warto sprawdzić pod kątem memory leak.

## Faza 3 — licencjonowanie (ZBUDOWANE, 2026-08-28)

Model: jeden klucz = jeden serwer, przypisywany na stałe przy pierwszym użyciu.
Płatności ręczne na razie (patrz `license-server/README.md`) — wystawiasz klucz
przez `curl` po zebraniu zapłaty poza systemem (Discord/przelew).

- **`license-server/`** — backend Node.js/Express (bez zewnętrznej bazy danych,
  prosty plik JSON — patrz `src/db.js`). Endpointy: `POST /api/validate`
  (publiczny, woła go plugin), `POST /api/admin/licenses` + `GET
  /api/admin/licenses` + `POST /api/admin/licenses/:key/revoke` (chronione
  nagłówkiem `x-admin-key`). Przetestowany ręcznie: bind, odrzucenie innego
  serwera, zła nazwa pluginu, revoke, brak autoryzacji — wszystko działa.
  Instrukcja deployu na darmowym hostingu w README tego folderu.
- **`mainplugins-core`** — nowy `LicenseService`/`LicenseManager`
  (`elo.mainplugins.core.license`), dostępny jak `CoreAPI.getLicenseService()`.
  Konfiguracja w `license.yml` (folder danych Core): `server.id` (generowany
  automatycznie, unikalny UUID tego serwera), `api.url` (adres
  license-server), `keys.<pluginId>` (klucz per płatny plugin). Cache w
  pamięci (6h) + okres karencji na dysku (72h) na wypadek chwilowej
  niedostępności serwera licencyjnego (np. usypianie na darmowym hostingu) -
  jawna odpowiedź "invalid" z serwera (revoked/zły serwer) NIE korzysta z
  karencji. Okresowe odświeżanie w tle co 6h (żeby cofnięcie licencji
  zadziałało bez restartu serwera klienta).
- **Wszystkie 12 płatnych pluginów** (tools, shop, skyblock, quests,
  advancements, redstone, spawn, fishing, spawners, market, dungeons, crates)
  mają na samej górze `onEnable()` sprawdzenie `CoreAPI.getLicenseService().isLicensed("<id>")`
  — bez ważnej licencji plugin wyłącza się sam z czytelnym komunikatem w
  logu. `announcer` (darmowy) nie ma żadnego sprawdzenia.
- Cała paczka (`compile` na całym repo) buduje się bez błędów po tej zmianie.

**Zrobione też (2026-08-28, ten sam dzień):**
- **Zakładka "Licencje" w PluginManagerze** (desktop-app) — panel admina: wpisz
  URL + admin-secret serwera licencyjnego (zapisywane jak sekrety SFTP/RCON, w
  keychain OS), wystaw nowy klucz (dowolny plugin z listy albo `*` na pakiet),
  podgląd/odwoływanie istniejących licencji. Backend: `src-tauri/src/license.rs`.
  Klient (kupujący) tego panelu nigdy nie widzi — to narzędzie operatora.
- **Automatyczna sprzedaż przez LemonSqueezy** — `license-server` ma teraz
  webhook `/webhooks/lemonsqueezy` (`src/lemonsqueezy.js`, HMAC-zweryfikowany):
  po zakupie (`order_created`) automatycznie wystawia klucz i wysyła go
  mailem przez Resend. Zero ręcznej pracy po stronie sprzedaży. Nie
  przetestowane na żywo (brak konta LemonSqueezy) — logika HMAC/mapowania/
  tworzenia licencji sprawdzona na syntetycznych danych, dokładny kształt
  webhooka do zweryfikowania przy pierwszej realnej sprzedaży (patrz
  README sekcja LemonSqueezy).

**Rozbudowane o konta klientów + subskrypcje + pakiety (2026-08-28, jeszcze ten sam dzień):**
Sklep żyje WEWNĄTRZ appki PluginManager (zakładka "Sklep", osobna od "Licencje" -
klient nigdy nie widzi panelu admina). Logowanie e-mail+hasło (scrypt, bez
dodatkowej zależności). Architektura:
- `license-server/src/customers.js` + `sessions.js` — konta i tokeny sesji (pliki
  JSON, ten sam wzorzec co licenses.json).
- `license-server/src/catalog.js` — **TU edytujesz co jest na sprzedaż**: 12
  pojedynczych pluginów + 3 skumulowane pakiety (Starter ⊂ Pro ⊂ Ultimate,
  propozycja do zatwierdzenia/korekty). Każda pozycja ma `variantId` (i
  `subscriptionVariantId` dla pakietów) do uzupełnienia PO założeniu produktów
  w LemonSqueezy — start jako `null` = przycisk "Kup" wyszarzony w appce.
- Nowe endpointy: `/api/auth/register`, `/api/auth/login`, `/api/auth/logout`,
  `/api/me`, `/api/me/licenses`, `/api/catalog` (publiczny).
- `/api/validate` rozumie teraz pakiety (`plugin` = lista po przecinku, np.
  `"crates,market,spawners,dungeons"`) przez `licenseGrants()` w db.js.
- Webhook LemonSqueezy rozszerzony o `subscription_created/updated/cancelled/
  expired/payment_success/payment_failed` — subskrypcja aktualizuje status
  ISTNIEJĄCEJ licencji (znalezionej po `subscriptionId`), nie tworzy nowej.
  Klienta z zakupem łączy `custom_data.customer_id` przekazywany w linku
  checkout (appka dokleja go automatycznie, patrz `shop_checkout_url` w
  `src-tauri/src/shop.rs`), z fallbackiem po e-mailu.
- Appka: `src-tauri/src/shop.rs` (rejestracja/logowanie/token w keychain jak
  reszta sekretów appki) + `src/pages/ShopPage.tsx` (logowanie, katalog,
  "moje licencje", przycisk Kup/Subskrybuj otwiera checkout w przeglądarce
  systemowej przez `@tauri-apps/plugin-opener`).
- **Przetestowane end-to-end na syntetycznych danych** (rejestracja, logowanie,
  zakup pakietu z realnym HMAC-podpisanym webhookiem, subskrypcja
  utworzona→anulowana ze zmianą statusu) — realny test dopiero z prawdziwym
  kontem LemonSqueezy.

**Do zrobienia zanim to zacznie sprzedawać:** założyć konto LemonSqueezy,
stworzyć produkty/warianty wg `catalog.js`, wkleić prawdziwe `variantId` tam
i `LEMONSQUEEZY_STORE_URL`/`LEMONSQUEEZY_WEBHOOK_SECRET` w `.env` na
serwerze produkcyjnym, zweryfikować dokładny kształt webhooków przy
pierwszej prawdziwej transakcji (pola mogą się różnić od dokumentacji).

**Nie zrobione jeszcze (reszta):** klient wciąż wkleja klucz do `license.yml` ręcznie
(albo przez istniejący edytor configów po SFTP w PluginManagerze) — appka
sama tego pliku po stronie klienta nie edytuje zdalnie w kontekście zakupu
(nie ma takiej potrzeby - klucz i tak dociera mailem, klient go wkleja raz).
Strona sprzedażowa z przyciskiem "Kup" (checkout LemonSqueezy) jeszcze nie
zbudowana. Repo PluginManagera nie ma jeszcze commitów/zdalnego repo GitHub —
w trakcie zakładania.

## Status

Faza 1 (audyt/fix bugów) jeszcze nie rozpoczęta. Faza 3 (licencjonowanie) ma
działający szkielet (patrz wyżej) mimo że w kolejności miała być po Fazie 2
(system manifestów) — zbudowana wcześniej na wyraźną prośbę użytkownika.
Faza 2 (manifesty) nadal nie rozpoczęta.
