# Stato progetto — Blocco App

> File di avanzamento: da mobile si riparte da qui senza rispiegare tutto.
> **Ultimo aggiornamento: 2026-11-23**

## Cos'è
App Android (Kotlin + Compose) per **non usare** le app che fanno perdere tempo. Funziona da **Device Owner**:
le app vengono *sospese* dal sistema (non si aprono), non si possono disinstallare, e Blocco App stessa non si toglie.

## Funzioni
- **Bloccate**: app sospese sempre. Attivare libero, togliere con password.
- **Limiti**: minuti al giorno per app. Si impostano UNA volta, poi non si cambiano più (neanche con password). Reset a mezzanotte. Se si toglie "Accesso dati di utilizzo" le app con limite restano bloccate (fail-closed).
- **Mai installare**: se un'app in lista viene installata, viene disinstallata subito. Lista solo in aggiunta. Si aggiunge anche da Play Store → Condividi → Blocco App.
- **Protezioni**: blocco modalità provvisoria, ripristino da Impostazioni, utenti/Ospite, data/ora, (opz.) debug ADB. Accendere libero, spegnere con password.
- **Password**: hash PBKDF2, attesa crescente dopo 3 errori (5 min, 10, 20… max 24 h).
- **Uscita d'emergenza**: richiesta con password → attesa **24 h** → conferma con password → toglie tutto e permette la disinstallazione.
- **Servizio sempre attivo** (notifica silenziosa) per limiti e installazioni; worker ogni 6 h come riserva.

## Modalità base (senza Device Owner)
- Pulsante "Usa modalità base" nella schermata iniziale: blocco tramite **Accessibilità** (app bloccata → home + schermata rossa). Limiti e "Mai installare" (= non si aprono) funzionano uguale.
- Aggirabile (disattivando l'accessibilità o disinstallando). Protezioni e silenziamento update solo da Device Owner. Da "Altro" → "Passa a Device Owner".
- Android 13+: se l'interruttore Accessibilità è grigio → Info app → ⋮ → "Consenti impostazioni con restrizioni".

## Build e aggiornamento automatico
- Ogni push su `main` → GitHub Actions compila l'APK firmato e lo carica nella release **`latest`** insieme a `version.json`.
- L'app controlla `releases/download/latest/version.json` (repo pubblico, nessun token) all'apertura (max 1/h) e ogni 6 h in background; da Device Owner installa **in silenzio**.
- Download manuale: https://github.com/rocco92rc-png/blocco-app/releases/download/latest/app-release.apk
- Chiave di firma dedicata `app/bloccoapp.p12` nel repo (serve solo a mantenere la stessa firma tra versioni).

## Attivazione (una volta)
1. Rimuovi tutti gli account (Impostazioni → Account), poi li riaggiungi.
2. Debug USB attivo (Xiaomi: anche "Debug USB (impostazioni di sicurezza)").
3. `adb shell dpm set-device-owner com.roccoapps.bloccoapp/.AdminReceiver` (da PC o da telefono con LADB / Shizuku+aShell via Debug wireless).
4. Apri l'app → password scelta da un'altra persona.

## Limiti noti
- Unica via senza password: ripristino di fabbrica dalla **recovery** (cancella il telefono).
- Le versioni web (instagram.com nel browser) non sono bloccate: valutare blocco browser o filtro DNS.
- Xiaomi "App doppie"/Second space potrebbero sfuggire.

## Prossimi passi
- Prima build verde e test su telefono.
