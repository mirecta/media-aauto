# Médiá Auto

Android aplikácia pre Android Auto na prehrávanie lokálnej hudby a videí a filmov a seriálov zo Sosac
(stream zo Streamuj.tv). Protokol je prevzatý zo [sosac-tizen](../sosac-tizen) (pôvodne z Kodi doplnku
`plugin.video.sosac-2`). Potrebujete účet na oboch službách.

## Čo vie

- **Android Auto** (štandardná media šablóna), 4 karty:
  - **Hudba** – interpreti → albumy → skladby, všetky skladby (prehrá sa celý album)
  - **Videá** – lokálne videá (len zvuk)
  - **Filmy** – môj zoznam, rozpozerané, videné, novinky, rebríčky, A–Z, žáner, kvalita
  - **Seriály** – to isté + epizódy; v seriáli položka „▶ Pokračovať“ a automaticky ďalšia epizóda
- Hľadanie (aj hlasové „pusti X v Médiá Auto“) – filmy, seriály, lokálna hudba a videá
- Pokračovanie od poslednej pozície, priebeh sa ukladá na Sosac (každých 30 s a pri pauze)
- V aute sa používa samostatná (nižšia) kvalita streamu, lebo sa prehráva len zvuk
- **Telefón**: ten istý strom, nastavenia účtov a prehrávač s obrazom

### Obmedzenie: video v aute

Android Auto neumožňuje aplikáciám tretích strán zobrazovať video na displeji auta (media šablóna je
len pre zvuk). Filmy a seriály sa preto v aute **počúvajú**, obraz je len v telefóne.

## Zostavenie a inštalácia

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Pri sideloadnutej aplikácii treba v Android Auto zapnúť neznáme zdroje:
Android Auto nastavenia → 10× ťuknúť na *Verzia* (vývojársky režim) → ⋮ → Nastavenia pre vývojárov →
**Neznáme zdroje**. Potom aplikáciu zapnúť v *Prispôsobiť spúšťač*.

Testovanie bez auta: [Desktop Head Unit](https://developer.android.com/training/cars/testing/dhu)
(`$ANDROID_HOME/extras/google/auto/desktop-head-unit`).

## Prvé spustenie

1. Povoliť prístup k hudbe, videám a notifikáciám.
2. ⚙ Nastavenia → Sosac a Streamuj.tv meno a heslo → *Uložiť a overiť* (heslá sa ukladajú len ako hash).

## Štruktúra

- `sosac/SosacApi.kt` – klient Sosac a Streamuj.tv API
- `sosac/StreamResolver.kt` – výber dabingu/kvality a získanie adresy súboru; položky v rade majú URI
  `sosac://…` a skutočná adresa sa zistí až pri prehratí (`ResolvingDataSource`)
- `library/Library.kt` – strom položiek pre Auto aj telefón, zostavenie radu, pokračovanie
- `library/LocalMedia.kt` – MediaStore; `library/ArtworkProvider.kt` – obrázky cez `content://` pre Auto
- `PlaybackService.kt` – `MediaLibraryService` (Media3/ExoPlayer), ukladanie pozície
- `ui/` – prehliadač, nastavenia, prehrávač s obrazom

Chýba oproti Tizen verzii: titulky, ručný výber streamu, pridanie/odobranie zo zoznamu.
