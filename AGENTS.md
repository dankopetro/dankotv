# Danko TV Android — Contexto del Proyecto (para cualquier IA)

**Regla de oro:** leer este archivo COMPLETO antes de tocar nada. Todo lo documentado acá
fue verificado empíricamente (probado EN DEVICE contra IPTV real) o aprendido por errores
propios durante el desarrollo. NO repetir errores ya probados. Este archivo es la MEMORIA
del proyecto: si una sesión se corta, la próxima arranca desde acá sin perder nada.

Última actualización: 16/09/2026 (sesión "TV lenta + phone landscape + búsqueda": TODO resuelto y verificado en device — 0.1-f3.4)

---

## 0. Estado ACTUAL del proyecto

- **Carpeta:** `/home/claudio/AndroidStudioProjects/DankoTV` (PROYECTO SEPARADO del repo
  Linux `~/Projects/Dankoiptv`; git local inicializado el 16/09 — ver §12.5).
- **App:** `tv.danko.app`, versionName `0.1-f3.4`, versionCode 6, instalada en la TV **y**
  el teléfono. 28 tests JVM verdes.
- **Fases completas:** F0, F1, F2, F3 (paridad UI) + fixes de escala (344k canales).
- **16/09 — sesión "4 problemas del usuario", TODO RESUELTO y verificado en device:**
  1. TV lenta/mala calidad → `mediacodec_embed` directo en TV (framedrop=0, nítido).
  2. Auto-hide controles → 5s (pedido del usuario).
  3. Teléfono landscape estirado → `android-surface-size` + `keepaspect` + inmersivo
     (16:9 exacto verificado por screenshot).
  4. Búsqueda que escribía desordenado → debounce+cancelación+índice (verificado
     tipeando rápido en el teléfono con 78k canales).
  5. (extra, reportado en la sesión) TV en "cámara rápida" → `video-sync=audio`
     (resample + embed apuraba; usuario confirmó imagen+sonido OK).
  Detalle completo en §9.12.
- **Lista activa del usuario:** **SuperXL** = `http://superxlatino.com:8880/get.php?
  username=Naty2&password=tUq3py2tKhhT&type=m3u_plus` (78.200 canales; en el teléfono
  quedó con nombre "dddd"). La vieja "Fenix" (avefenix) sigue en la DB de la TV
  (344k filas, ~70MB): el proveedor sigue caído; OFRECER borrarla para liberar espacio
  (la TV tiene solo 4.2GB y se quedó sin lugar instalando: ver §9.12).
- **Colgado al cortar la sesión:** nada bloqueante. Próximo: **F4 grabación + captura**
  (§8.4). OJO F4+subs con `mediacodec_embed`: embed NO tiene OSD/subs mpv (solo la
  UI Compose por encima) — diseñar subs/captura en consecuencia.

### Qué se verificó de F3 en device (13/09, todo OK)
- Auto-hide de controles (3 bugs encadenados resueltos, ver §9.5).
- Selector de skin ⋮ con 12 temas, ✓ en el actual, persistencia DataStore (probado
  cambiando a Océano y volviendo).
- Grupo "★ Favoritos" en el filtro (filtra bien, se vacía al des-favoritar).
- Toggle favorito desde la lista Y desde el player (★→☆ en vivo, persiste en DB,
  verificado leyendo la DB por run-as).
- ⏮ ⏭ (mismo player, playlist_entry_id 3→4→5 en logs), ⏹ (stop + back a lista).
- Barra de botones scrolleable (LazyRow) — en teléfono los botones no caben fijos.
- last_list: abre directo en la última lista (verificado en teléfono y TV).
- last_group: se restaura al entrar y se guarda al cambiar chip (test JVM).
- ⟳ Recargar lista (test JVM con MockWebServer: baja de nuevo y reemplaza canales).
- Botón "Reload" con spinner (CircularProgressIndicator) en el header.
- TvFocus ahora usa `MaterialTheme.colorScheme.primary` (borde sigue el skin).
- Filtro grupo tras des-favoritar en "★ Favoritos" se refresca solo.
- Backoff de errores y cascada intactos (retry 1/10, 2/10, 3/10 con delays 1s/2s/4s
  visibles con URLs example.com; stream real mux.dev reproduce).

### PRÓXIMO PASO (F4)
Grabación (stream-record nativo de mpv → MediaStore) + captura (screenshot-to-file →
MediaStore.Images). Ver §8.4.

---

## 1. Qué es este proyecto

App Android **Danko TV**: espejo 1:1 del shell Linux `dankotv/` del repo
`~/Projects/Dankoiptv` (leer su AGENTS.md si hace falta el contexto del motor). Misma
filosofía: listas IPTV (M3U URL + Xtream login) → lista de canales con búsqueda/grupos/
favoritos → reproductor **seamless** sobre libmpv embebido (MISMA instancia mpv, sin
recrear ventana, keep-open + eof-reached + cascade).

Superficies del usuario:
1. **Teléfono** (Motorola edge 30 neo) — táctil.
2. **Android TV** (UnionTV) — D-pad (por eso `tvFocusBorder()`, foco inicial, D-pad
   muestra controles, banner `tv_banner.png` en el manifest).

El objetivo declarado por el usuario: "que esta app de Android funcione tal cual
funciona la que hicimos para Linux".

---

## 2. Stack y decisiones base (NO cambiar sin pedirle al usuario)

| Decisión | Valor | Motivo |
|---|---|---|
| Lenguaje | Kotlin | — |
| UI | Jetpack Compose Material3 (+ `androidx.tv.foundation/material` para D-pad) | — |
| applicationId | `tv.danko.app` | permanente para Play; el viejo `com.danko.iptv` queda libre |
| minSdk | 26 | Media3/leanback modernos |
| target/compile | 36 / 37 | baseline actual |
| JDK | 17 (`sourceCompatibility 17`, `jvmTarget JVM_17`) | requerido por AGP |
| Player | AAR `io.github.abdullahmehiz:mpv-android-lib` (wrapper Kotlin `is.xyz.mpv.MPV`, binario .so incluido, Maven Central) | evita compilar ffmpeg/NDK a mano |
| DB | Room (`dankotv.db` v1: lists, channels, favorites) | — |
| Settings | DataStore Preferences (`dankotv`) | espejo de config.py Linux |
| Red | OkHttp (timeouts: connect 10s / read 120s para playlists de 88 MB) | — |
| DI | Manual: `AppContainer` + `VmFactory` (sin Hilt) | simplicidad |
| Licencia | GPL-3.0 (libmpv GPLv2+ compatible) | igual que Linux |

Versionado: `versionName "0.1-f<Fase>"` (f0/f1/f2/…), subir `versionCode` de a 1.
Al completar F3 → `0.1-f3`.

---

## 3. Estructura del código (mapa completo)

```
DankoTV/
├── AGENTS.md                     ← este archivo
├── settings.gradle, build.gradle, gradle/libs.versions.toml (version catalog)
├── local.properties              ← sdk.dir (NO commitear)
└── app/
    ├── build.gradle              ← deps + namespace tv.danko.app + room.schemaLocation
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml   ← INTERNET, usesCleartextTraffic=true (IPTV http!),
        │   │                            leanback+touchscreen required=false, banner TV,
        │   │                            configChanges orientation|screenSize|…
        │   ├── assets-src/dankotv.svg
        │   ├── res/ (mipmap launcher, drawable/tv_banner.png, values)
        │   └── java/tv/danko/app/
        │       ├── MainActivity.kt        ← ComponentActivity + isTelevision(); DankoNav
        │       │                            (rutas: lists / channels/{name} /
        │       │                            player/{url}/{title}/{live}/{ua}, todo con
        │       │                            Uri.encode; PLAY_REQUEST companion para
        │       │                            onNewIntent; start directo a player si llega
        │       │                            url por intent = smoke test F2)
        │       ├── player/
        │       │   ├── SeamlessEngine.kt  ← LÓGICA PURA testeable: shouldReload,
        │       │   │                        nextCascade, cascadeDelayS, errorDelayS,
        │       │   │                        shouldRetryError, MAX_ERROR_RETRIES
        │       │   └── SeamlessPlayer.kt  ← MPV() + MpvSurfaceView + algoritmo §6
        │       ├── data/
        │       │   ├── M3uParser.kt       ← port de norm_m3u/M3UParser Linux
        │       │   ├── XtreamClient.kt     ← port de thirdparty/xtream.py (loadLive,
        │       │   │                        player_api.php, UA constante)
        │       │   ├── ListsRepository.kt ← loadSpec/testSpec/lists/delete/search/favs
        │       │   └── SettingsStore.kt   ← skin/font/last_list/last_group/cache_secs
        │       ├── data/db/
        │       │   ├── Entities.kt        ← ListEntity, ChannelEntity, FavoriteEntity,
        │       │   │                        PlaylistDao, escapeLike()
        │       │   └── AppDatabase.kt     ← Room v1, exportSchema (F6 usará migraciones)
        │       ├── data/model/Models.kt   ← Channel, PlaylistSpec(M3u|Xtream), LoadResult
        │       └── ui/
        │           ├── AppContainer.kt    ← DI manual + VmFactory
        │           ├── TvFocus.kt         ← Modifier.tvFocusBorder() (borde al foco)
        │           ├── lists/ListsScreen.kt   ← Mis listas + NewListDialog (tabs M3U/Xtream,
        │           │                            "Probar conexión" espejo de home.py)
        │           ├── lists/ListsViewModel.kt
        │           ├── channels/ChannelsScreen.kt ← buscador + FilterChip grupos +
        │           │                             LazyColumn canales + ★/☆ favorito
        │           ├── channels/ChannelsViewModel.kt ← query/group/favs, DISPLAY_LIMIT 2000
        │           ├── player/PlayerScreen.kt  ← video + barra controles + fullscreen
        │           │                             (BUG auto-hide pendiente, §9.5)
        │           └── theme/Theme.kt     ← 12 skins (DANKO_SKINS) + DankoTVTheme
        └── test/java/tv/danko/app/
            ├── data/M3uParserTest.kt      ← usa /resources/sample.m3u (9 canales)
            ├── data/XtreamClientTest.kt   ← fixtures xtream_{auth,categories,streams}.json
            │                                + mockwebserver3
            ├── player/SeamlessEngineTest.kt
            └── ui/ViewModelsTest.kt       ← FakeDao2 (PlaylistDao en memoria) + DataStore
                                             real en tmp + MockWebServer; NOTA: usa
                                             awaitTrue() con timeout porque viewModelScope
                                             corre en hilos de verdad
```

Puntos de paridad con Linux (mismos nombres/conceptos a propósito):
`SeamlessPlayer`↔player.py · `M3uParser`↔engine.py · `XtreamClient`↔xtream.py ·
`SettingsStore`↔config.py · `ChannelsScreen`↔mainview.py sin video · `ListsScreen`↔home.py ·
`DANKO_SKINS`↔skins.py · `DISPLAY_LIMIT=2000`↔mainview.py.

**Android VA ADELANTE del shell Linux** (anotado 21/09 a pedido del usuario):
el shell `dankotv/` 0.1 NO tiene guía EPG ni selector de audio/subs; Android
sí (F5 tira+grilla, F6 botones AUD/SUB). Si se portan al shell, mirar
`EpgRepository`/`GuideScreen` y `mediaTracks`/`cycleTrack` de acá.

---

## 4. Dispositivos y adb (el usuario AUTORIZÓ a entrar por adb a la TV y al teléfono para ver cómo funciona)

```
192.168.0.10:5555    UnionTV (LA TV, Android TV)          ← adb TCP fijo, estable
192.168.0.6:37103    motorola_edge_30_neo (miami)         ← teléfono, puerto de
                                                               Depuración inalámbrica,
                                                               CAMBIA con el tiempo
adb-ZY22G2WXC8-lM2AUg._adb-tls-connect._tcp               ← el MISMO Motorola por mDNS
```

- Si el celu aparece offline: pedirle al usuario el puerto nuevo (Ajustes → Opciones
  desarrollador → Depuración inalámbrica) y `adb connect 192.168.0.6:<puerto>`.
- Ambos dispositivos tienen instalado `tv.danko.app` Y el viejo `com.danko.iptv` (no
  confundirlos al desinstalar/limpiar).
- Ver la app en vivo: `adb -s <serial> shell am start -n tv.danko.app/.MainActivity`
- Logs del player: `adb -s <serial> logcat -s SeamlessPlayer`
- Smoke test del player sin tocar la UI (patrón F2, soportado por DankoNav):
```bash
adb -s 192.168.0.10:5555 shell am start -n tv.danko.app/.MainActivity \
  --es url "http://..." --es title "Canal X" --ez live true
```
  (`ua` opcional con `--es ua "..."`; title default "Prueba").

---

## 5. Build / test / instalación

```bash
cd /home/claudio/AndroidStudioProjects/DankoTV
./gradlew assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk
./gradlew test                   # tests JVM (parser, xtream, engine, viewmodels) — SIN emulador
adb -s 192.168.0.10:5555 install -r app/build/outputs/apk/debug/app-debug.apk   # TV
adb -s 192.168.0.6:37103  install -r app/build/outputs/apk/debug/app-debug.apk   # teléfono
```
- Requiere JDK 17 en el entorno de gradle (jvmTarget 17). Si Android Studio compila
  pero ./gradlew falla por java, setear `org.gradle.java.home` en `gradle.properties`
  o usar el JDK bundled de Studio.
- Verificar SIEMPRE antes de instalar: `./gradlew test` en verde + compile sin warnings
  de Kotlin que rompan (`assembleDebug` ya avisa).
- Release firmado: F7 (aún no configurado signingConfig).

---

## 6. Lecciones EMPÍRICAS del player Android (CRÍTICO — verificado en device)

### 6.1 Hilos: la lección del cartel de Linux, portada
Los callbacks de libmpv (eventos/propiedades) llegan en el **hilo de eventos de mpv**,
NO en el main thread. Todo lo que toca estado/UI se reenvía con
`Handler(Looper.getMainLooper()).post { }` (en `SeamlessPlayer` el método `post()`).
Patrón equivalente al `_Bridge` de home.py en Linux. NUNCA llamar mpv/UI desde otro hilo.

### 6.2 Opciones mpv — IGUALES reglas que Linux
| Opción | Veredicto | Motivo |
|---|---|---|
| `stream-lavf-o=reconnect*` | ❌ PROHIBIDA | ffmpeg reconecta y retoma atrás → rebobina 10-15s |
| `prefetch-playlist=yes` | ❌ PROHIBIDA | pre-abre 2ª conexión vieja → rebobina ~10s |
| `force-seekable=yes` en live | ❌ PROHIBIDA | con PTS rotos, seeks hacia atrás visibles |
| `keep-open=yes` + `keep-open-pause=no` en live | ✅ | EOF congela en último frame, dispara `eof-reached` |
| `loop`/`loop-playlist=no` + manejo propio | ✅ | loop sin control = cascadas |
| `hwdec=auto` (mediacodec) | ✅ ANDROID | **NO heredar hwdec=no de Linux**: verificado en device, software decode saturaba UN core al 97% y congelaba la UI (control remoto lento, BACK sin respuesta → el usuario salió de la app a los 2 BACK). Con auto: 35% CPU y OMX.realtek hw decoder 1080p. PENDIENTE re-probar canal de encoder roto del proveedor real con hwdec=auto (en Linux el no evitaba seeks raros) |
| `osc=no`, `ytdl=no` | ✅ | no hay OSC propia en Android |
| `vo=gpu` + `gpu-context=android` + `ao=audiotrack` | ✅ | salida Android del AAR |
| `demuxer-readahead-secs`/`cache-secs` = 45 (config) | ⚠️ | absorbe micro-cortes, no cortes de origen; retardo 1-3 min = live edge del proveedor (igual que Linux) |

### 6.3 Algoritmo seamless vigente (port exacto de dankotv/player.py)
1. `observeProperty("eof-reached", MPV_FORMAT_FLAG)` — con keep-open, EOF NO dispara
   `end-file`, solo la propiedad.
2. `eof-reached=true` → si `shouldReload(armed, isLive, url)`: cascade = +1 si el EOF
   anterior fue <20s (si no 0); delay = `min(cascade*2, 8)`s; recarga con
   `loadfile replace` en la MISMA instancia tras el delay.
3. `file-loaded` (id=8) → resetea errRetries y cascade, state=Playing.
4. `end-file` con `reason=error` → backoff 2^n cap 30s, máx 10 reintentos.
5. `stop()` → desarmar, limpiar callbacks del Handler, restaurar `keep-open=no`.
6. VOD (live=false): `keep-open=no` + `force-seekable=yes` (seek normal).

Constantes ABI estables (client.h, NO cambian entre versiones): `END_FILE=7`,
`FILE_LOADED=8`, `FORMAT_FLAG=3`.

### 6.4 Trampas de la AAR is.xyz.mpv verificadas en device
- **HTTPS:** sin `installCaCert()` (copiar `assets/cacert.pem` del AAR a cache y pasarlo
  como `tls-cert-file`) falla TODO https. Ya está implementado en SeamlessPlayer.init.
- **Surface:** hacer `loadfile` sin surface attach → "Missing surface pointer". Patrón
  `pendingLoad`: si `play()` llega antes de `surfaceCreated`, se guarda y se dispara al
  attach. El orden de init del player es: `MPV().create(ctx)` → installCaCert →
  setOptionString(...) → observeProperty → addObserver → `init()`.
- **end-file reason:** llega como `MPVNode.MapNode["reason"]` = `StringNode("error")`
  (NO int). `isErrorEnd()` ya lo maneja.
- En Kotlin el package se escribe `` `is`.xyz.mpv `` (backticks).
- `MpvSurfaceView` = SurfaceView mínimo; el ciclo surface lo maneja el player
  (attach/detach), no la vista.

### 6.5 D-pad / TV
- `Modifier.tvFocusBorder()` (ui/TvFocus.kt) dibuja borde al foco — en teléfono invisible.
- En pantallas: `FocusRequester` inicial (ListsScreen → botón Nueva lista; ChannelsScreen
  → primer canal; PlayerScreen → botón pausa cuando se muestran controles; NewListDialog
  → campo nombre).
- PlayerScreen: cualquier tecla direccional/enter con controles ocultos → los muestra.
- BackHandler del player: controles ocultos → mostrarlos; visibles → salir.

---

## 7. Data layer (esquema Room v1 + reglas)

- Tablas: `lists` (PK name; type m3u|xtream; credenciales Xtream guardadas),
  `channels` (PK autogen id; índices `listName` y `listName,group`; title/group/url/
  logo/epgId), `favorites` (PK listName+url).
- Búsqueda: `title LIKE '%'||:q||'%' ESCAPE '\'` con `escapeLike()` — literal, sin
  regex; LIMIT 2000 (DISPLAY_LIMIT, igual que Linux).
- `replaceChannels` = @Transaction clear+insert (recarga completa de lista).
- `ListsRepository.loadSpec`: M3U (download con UA de XtreamClient + parse) o Xtream
  (`xtream.loadLive`); guarda list+channels; `settings.setLastList(name)`.
  `testSpec` = igual sin guardar, devuelve cantidad.
- DataStore keys: `skin`, `font_family`, `last_list`, `last_group`, `cache_secs`
  (default 45). Defaults: skin "Naranja Oscuro", group "Todas".
- Tests: `FakeDao2` implementa PlaylistDao en memoria (mismos métodos); MockWebServer
  sirve sample.m3u y fixtures; DataStore real en directorio temporal.

---

## 8. Plan por fases (estado y detalle)

### 8.1 Completadas
- **F0 scaffolding** ✅ — proyecto Gradle (version catalog), manifest TV+telf, íconos,
  banner TV, navegación Compose, DI manual, firma debug, instala y abre en ambos devices.
- **F1 datos** ✅ — M3uParser + XtreamClient + Room + DataStore + tests JVM; carga la
  playlist real (347k canales en Linux con mismo parser) y búsqueda instantánea.
- **F2 player** ✅ — SeamlessPlayer + PlayerScreen; verificado EN DEVICE: canal sano +
  canal de encoder roto, cascade visible en logs, sin negro; smoke test por intent.

### 8.2 F3 — paridad UI con el shell Linux ✅ COMPLETADA (13/09/2026)
Entregado en `0.1-f3` (versionCode 2). Detalle de lo implementado y verificado en §0.
Archivos nuevos: `ui/player/PlayQueue.kt` (cola prev/next/favoritos), `ui/SkinMenu.kt`
(menú ⋮ 12 temas). Cambios: PlayerScreen (LazyRow de botones + auto-hide final),
ChannelsViewModel (FAV_GROUP + last_group + reload + settings), ChannelsScreen (⟳ ⋮ skin
+ ★ en rows), ListsScreen (⋮ skin), MainActivity (wiring PlayQueue + last_list con
`resolvedStart` — NavHost NO se compone hasta resolver, porque cambiar startDestination
en recomposición NO está soportado), AppContainer (sin helpers extra), TvFocus (color del
skin), ListsRepository (`specOf()` reconstruye spec para reload). Tests: 24 JVM verdes
(nuevos: FAV_GROUP filtra, reload baja de nuevo del server, last_group persiste).

### 8.4 Roadmap actualizado (decidido con el usuario 16/09 noche + 17/09 mañana)
Veredicto de conveniencia 17/09 con DATOS del proveedor (cuenta Naty2:
9.873 VOD, 2.220 series, EPG parcial —82/1970 con ID + XMLTV para match por
nombre, títulos base64—, logos en 99.9% de canales, 0 canales con catchup,
máx 3 conexiones). Comparativa completa Linux-vs-Android hecha el 16/09.
- **F5 EPG** (EN CURSO 17/09): guía en TV + tira ahora/siguiente en teléfono.
  Catchup NO se hace (el proveedor tiene 0 canales con archivo).
- **F6 VOD + series** (con selector audio/subs incluido).
- **F7 Logos + editor + aviso de conexiones + MediaSession + sleep**.
- **F8 Grabación en TV** (con lista "Mis grabaciones") + **Cast SOLO teléfono**
  (caso invitado: castear a TV ajena en la misma red; la TCL ya tiene la app,
  no se justifica en TV). Timeshift en espera (complejo, solo si se pide).
- **F9 release**: signingConfig release (keystore del usuario), App Bundle,
  GitHub Release. DESCARTADOS 17/09: catchup, i18n, PIN parental.

---

## 9. Errores YA cometidos y lecciones (NO repetir)

### 9.1 HTTPS fallaba en device
Sin `tls-cert-file` apuntando al `cacert.pem` bundleado, todo https muere. Fix:
`installCaCert()` en SeamlessPlayer (ya está). No quitar.

### 9.2 loadfile sin surface → "Missing surface pointer"
Fix: patrón `pendingLoad` (play() guarda; surfaceCreated dispara). Ya está.

### 9.3 end-file reason no es int
Es `StringNode` dentro de `MapNode`. `isErrorEnd()` ya lo maneja — no "simplificar".

### 9.4 Comportamiento de callbacks en hilo mpv
Ver §6.1. Cualquier feature nueva que surja de eventos mpv → `post {}` primero.

### 9.5 Bug del auto-hide de controles (RESUELTO 13/09 — 3 bugs encadenados)
Historial completo (la lección vale para Compose en general):
1. **Bug original:** `LaunchedEffect(controls, state)` con la condición MAL puesta:
   al pasar controls a false el efecto se reiniciaba y con state aún Playing
   reprogramaba otro timer → nunca se escondían.
2. **Bug 2 (fix incompleto):** cambiar a `LaunchedEffect(state)` mataba el
   re-arm: el usuario mostraba controles con tap y quedaban fijos para siempre
   (state no cambiaba → efecto no se re-ejecutaba).
3. **Bug 3 (el que el usuario vio "no se ocultaron"):** los dos fixes anteriores
   SÍ ponían `controls=false`, pero la visibilidad era `if (controls || !fullscreen)`
   → fuera de pantalla completa `!fullscreen` es SIEMPRE true → la barra quedaba
   visible aunque controls fuera false. Auto-hide invisible salvo en fullscreen.
**FIX FINAL (vigente en PlayerScreen.kt):**
```kotlin
LaunchedEffect(controls, state, paused) {
    if (controls && state is PlayerState.Playing && !paused) {
        delay(2000)
        controls = false
    }
    if (state !is PlayerState.Playing || paused) controls = true
}
// visibilidad: if (controls) { ... }   // SIN "|| !fullscreen"
```
Por qué funciona: keys = (controls, state, paused). controls→false reinicia el
efecto pero la condición `controls &&` ya es false → no reprograma (bug 1 muerto).
Tap/tecla pone controls=true → el efecto se re-ejecuta por el cambio de key → timer
nuevo → se esconden a los 2s (bug 2 muerto). Y la barra solo depende de `controls`
(bug 3 muerto). Verificado en device por el usuario (TV, stream real).
**Lección general:** en Compose, timer condicionado a estado visible = incluir el
flag de visibilidad EN la condición del efecto, no solo en las keys; y revisar TODAS
las condiciones de visibilidad del composable, no solo el flag.

### 9.7 Bugs de UX encontrados por el usuario (13/09 noche) — TODOS resueltos en 0.1-f3.1
1. **Control remoto lento + BACK sin respuesta** → hwdec (ver §6.2). CPU 97%→35%.
2. **La app arrancaba directo en la lista de canales** (feature last_list que hice):
   el usuario quiere SIEMPRE "Mis listas" al arrancar, y si no hay listas, auto-abrir
   el diálogo Nueva lista (como Linux app.py:74). Fix: start siempre "lists" +
   LaunchedEffect con guard `lists.loaded` (flag nuevo en ListsUiState para no
   auto-abrir antes del primer refresh de DB).
   **NO volver a hacer auto-navegación por last_list al start:** si channels queda
   como destino raíz, popBackStack() desde ahí es no-op.
3. **"← Listas" no andaba** → mismo root cause: channels era start destination.
   Fix: navegación EXPLÍCITA `nav.navigate("lists") { popUpTo(0) { inclusive = true } }`
   (limpia todo el backstack). patrón a seguir para cualquier "back" entre pantallas
   top-level.
4. **"✕ Cerrar" del player no respondía** (smoke tests por intent) → player era
   destino raíz → popBackStack no-op. Fix: `if (!nav.popBackStack()) navigate("lists")
   { popUpTo(0) }`. Verificado en teléfono: player→canales limpio en 2.5s.
5. **Lección de testing adb:** los taps "que no andaban" en TV eran un DropdownMenu
   ⋮ abierto (de un test anterior) que captaba los taps. Al testear UI por adb,
   verificar con `uiautomator dump` que NO haya un popup/dialog abierto antes de
   mandar taps. En el teléfono: el shade de notificaciones puede estar abierto y
   comerse todo — `input swipe` para cerrarlo o `cmd statusbar collapse`.

### 9.8 Lista REAL del usuario (avefenixtv) — verificado 13/09 noche
- La playlist real (344.381 canales, 313 grupos, 88MB) fue cargada por la app en
  AMBOS dispositivos vía server LAN temporal: `python3 -m http.server 8000` en la
  laptop sirviendo `~/Vídeos/IPTV/2026/playlist_Katherine999_plus.m3u` como
  `http://192.168.0.239:8000/real.m3u`. La app la lista como "Fenix".
- **EL PROVEEDOR RECHAZA LA CUENTA**: get.php, player_api.php y TODOS los streams
  responden **HTTP 401 "Invalid credentials"** (probado con curl desde la laptop,
  13/09 ~22:00). La app hace lo correcto: "loading failed" + backoff 2^n cap 30s
  (visto en TV: "Error — reintento 7 en 30s"). **NO es un bug de la app.** Preguntar
  al usuario si Linux también falla (cuenta vencida) o solo esta LAN (límite de
  conexiones del proveedor).
- Carga en teléfono (Moto edge 30 neo): ~35s total, sin OOM tras el fix streaming.
- Carga en TV (box TCL): descarga+parseo ~25s pico 90MB RAM, inserts 344k ~5 min
  (SQLite+GC lento del box; el proceso termina solo, NO está colgado).

### 9.9 Fixes de la sesión "no andan los canales / teclado raro" (13/09 noche)
1. **"Los canales no andan"** → causa: HTTP 401 del proveedor (§9.8). La app bien.
2. **"El buscador escribe mal / letras van a cualquier lado"** → causa real:
   `LaunchedEffect(ui.channels.firstOrNull())` re-pedía foco en el primer canal
   CADA VEZ que la lista cambiaba (cada letra refiltra → roba el teclado del
   OutlinedTextField). Fix: foco inicial UNA sola vez con flag
   `initialFocusDone` rememberSaveable (ChannelsScreen.kt).
3. **"59 canales"** → no era bug: grupo Argentina restaurado por last_group
   (tiene 59 canales; total real 344.381 verificado en DB).
4. **OOM al cargar 88MB/344k canales** (teléfono, verificado): body.string() +
   parse completo en RAM = 175MB request vs 256MB heap. Fix en capas:
   - `M3uParser.parseStreaming(reader, onChannel)`: línea a línea, canales por
     callback (test streamMatchesParse verifica paridad con parse()).
   - `ListsRepository.streamM3uIntoDao()`: streaming del body OkHttp + insert
     por lotes de 5000 (runBlocking en Dispatchers.IO OK).
   - **NO dedup de títulos " (k)" en streaming** (Set gigante en RAM; cosmético).
   - `loadSpec` m3u: clearChannels ANTES de stream (si falla a mitad, lista
     queda incompleta sin duplicados; recargar con ⟳).
5. **"0 canales" eterno al abrir lista de 344k** → causa: VM cargaba TODO
   (`channels()` sin límite + sort de 344k filas en el box lento). Fix:
   queries SQL limitadas + COUNT separado (channelsLimited/countChannels/
   channelsInGroup/countInGroup/searchInGroup/countSearch*/favoriteChannels/
   countFavorites/searchFavorites — DAO completo § Entities.kt). El VM usa
   páginas de 2000 + total por COUNT. LazyColumn key = PK `it.id` (títulos
   duplicados reales crasheaban con key group/title).
6. **Primera query de 344k en el box TCL tarda 5-10s** ("0 canales" → aparece
   solo). Normal del hardware; después queda cacheada. Considerar (F4+):
   spinner en el contador o query reactiva.

### 9.11 LA CAUSA RAÍZ de "los canales no andan" (14/09) — LECCIÓN DE ORO
**Síntoma:** la app Android daba 401/"loading failed" en todos los canales de una
playlist cargada, mientras la app Linux con la MISMA cuenta andaba perfecto.
**Diagnóstico equivocado primero (mío):** "el proveedor rechaza la cuenta" — NO. Eran
DOS cosas distintas superpuestas:
1. **El proveedor viejo (avefenix) había ROTADO las URLs de stream** (playlist vieja
   del 6-sep vs fresca del 13-sep: 344.381 vs 344.128 canales). Los .ts viejos dan
   401 "Invalid credentials" (mensaje engañoso del panel: la cuenta estaba BIEN).
   Además get.php rate-limitea por descargas repetidas del día.
2. **BUG REAL DE LA APP (el importante):** `SeamlessPlayer.play()` seteaba
   `user-agent` en mpv con el UA de DESCARGA (`Wget/1.20.3` de `XtreamClient.UA`)
   cuando el intent traía `ua`. Resultado: mpv pedía los streams con UA Wget.
   **El proveedor superxlatino RECHAZA 401 el UA Wget en streams pero acepta
   "libmpv"/"Mozilla/5.0"** (verificado con curl: 401 vs 302→200+12MB de video).
   Linux andaba porque su mpv usa el UA default `libmpv x.y` (nunca lo setea).
**Fixes aplicados (versionCode 5):**
- `play()`: NO setear user-agent por defecto; si un play anterior dejó uno, resetear
  a "libmpv". Solo setear UA si la playlist lo exige explícito (url-commands Kodi → F6).
- `XtreamClient.UA` = "Mozilla/5.0" (solo descarga de get.php/player_api; igual que
  el settings.json del Linux del usuario: `"ua": "Mozilla/5.0"`).
- **Corte de cascada en errores fatales:** `SeamlessEngine.isFatalStreamError()`
  (HTTP 401/403/404/410 del file_error del end-file) → `PlayerState.Dead(reason)` +
  `armed=false` + label "Canal caído (…) — recargá la lista (⟳)". Ya no martilla
  10 reintentos un 401 (test `erroresFatalesCortanCascada`).
- **Lección:** UA de DESCARGA ≠ UA de REPRODUCCIÓN. Nunca filtrar UA en streams
  salvo pedido explícito del proveedor vía playlist. Al diagnosticar "no anda",
  probar con curl CON DISTINTOS UAs antes de culpar la cuenta.
- **Lección adb:** `adb shell am start --es url "http://x?a=1&b=2"` — el quoting
  simple se pierde al re-ejecutar en el device shell → el `&` CORTA la URL (el
  extra llega hasta el primer `&`). Usar comillas simples EXTERIORES:
   `adb shell 'am start ... --es url "http://x?a=1&b=2"'` (costó un falso 401).

### 9.10 Lecciones de TESTING por adb (gold para la TV TCL)
- `input tap` NO llega a Compose en la TV TCL (los taps sintéticos no
  dispatchean); el D-pad SÍ (DPAD_CENTER sobre card navega). En el teléfono
  tap sí funciona.
- **screencap en la TCL MIENTE a veces (16/09, lección del usuario):** puede devolver
  frames NEGROS o viejos (app sana, tarjeta visible en el TV real, captura en negro).
  REGLA: captura negra/vacía = reintentar 2-3 veces con segundos entre medio, NUNCA
  asumir estado de la app por una sola captura. Prioridad de evidencia en la TV:
  1) DB por run-as + logs (ground truth instantáneo), 2) screencap repetido,
  3) preguntar al USUARIO que mira el TV (una pregunta ahorra 4 rondas adb).
  uiautomator en la TV devuelve jerarquía VIEJA/stale (y a veces "null
  root node"). NO confiar en él para TV. Verificar por: screencap + tesseract
  (spa) [tesseract instalado en la laptop], pixel-diff PIL, o preguntar al
  usuario. En el teléfono uiautomator SÍ funciona.
- `input text` con espacios: usar %s ("Canal%s26"). El IME de la TV no se
  activa por adb tras DPAD navigation (mInputShown=false): NO hay forma
  confiable de llenar el diálogo Nueva lista por adb en la TV.
- **Backdoor dev add_list** (MainActivity): `am start -n tv.danko.app/.MainActivity
  --es add_list_name X --es add_list_url Y` agrega la lista sin IME (onCreate +
  onNewIntent → addListRequest → ListsViewModel.addSpec). Mismo espíritu que el
  smoke test `--es url` del player. SOLO para dev/testing (el usuario final usa
  el teclado en pantalla real de su control).
- Estar en Plan Mode (read-only) NO impide: adb shell dumps, screencap, curl,
  tesseract local. Solo edits.
- D-pad + LazyColumn: el foco NO scrollea la lista cuando llega al último item
  visible (bringIntoView no se dispara). Pendiente de fix fino en F4+ si al
  usuario le molesta (Linux scrollea con rueda; en TV se busca/filtra).
- Al preguntar "¿dónde quedó la pantalla?": mejor OCR del screencap completo
  (tesseract -l spa) que dump — a veces el OCR de zona recortada con resize
  falla y el completo sí lee.

### 9.6 Decisiones NO negociadas con el usuario
No cambiar: applicationId, stack (Compose/Room/OkHttp/AAR mpv), algoritmo seamless,
opciones mpv §6.2, GPL-3.0. Todo eso fue decisión del usuario sobre el plan presentado.

### 9.12 Sesión 16/09: TV lenta + landscape + búsqueda + velocidad (TODO verificado)
**Contexto hardware (¡nuevo!): la TV es 32 BITS** — `ro.product.cpu.abi=armeabi-v7a`,
kernel armv7l (Realtek rtd284x, Android 9, panel 720p, GLES 2.0, HWC sin overlays:
todo compone por GPU `Client`). Es mucho más débil de lo supuesto. El teléfono
(Motorola edge 30 neo, 1080x2400, 90/120Hz) anda sobrado.

**TV "cámara lenta + <700p" → `mediacodec_embed` (CAUSA RAÍZ + FIX).**
Serie de experimentos E0-E13 con telemetría (STATS una vez + TICK 5s en
SeamlessPlayer + `MPV.LogObserver` puenteado a logcat tag "mpv" — QUEDA, es oro
para diagnóstico remoto): vo=gpu+mediacodec-copy perdía SIEMPRE ~la mitad de
frames (16/s de 30fps, mistimed≈drops, vsync-ratio colapsando), sin importar
profile=fast (E1, sin efecto), hwdec=no (E2, menos drops pero CPU 189%),
display-resample (E3, igual), direct forzado (E4, el build cae a SW: no hay
interop), gpu-next (E5: negro — libplacebo exige GLES3), swapinterval=0 (E6),
ao=null (E7), stream limpio de referencia (E8: TAMBIÉN 12/s → no era el
proveedor), desync (E9), mediacodec_embed mal configurado (E10: negro),
defaults de caché (E11: PEOR 22/s — el caché 45s ayuda), fbo rgba8 (E12: PEOR
23/s), opengl-pbo (E13: igual). Logs nativos: mpv v0.41.0, vo=gpu legacy (sin
interop directo: auto solo prueba `-copy`), GPU ancestral (dumb mode auto).
FIX (E10' bien configurado: el manual exige `hwdec=mediacodec` con embed):
`vo=mediacodec_embed` + `hwdec=mediacodec` = overlay directo del decoder
(OMX.realtek) al display, cero copy/GPU → **framedrop=0 sostenido**, imagen
nítida fullscreen. Gateado SOLO a TV (`UiModeManager.currentModeType`, en el
init de SeamlessPlayer): teléfono sigue vo=gpu+auto (fluido + conserva OSD/
subs mpv para F6+; embed NO tiene OSD/subs — solo UI Compose por encima).

**TV "apurado" (velocidad) → `video-sync=audio`.** Con embed+display-resample el
movimiento salía acelerado (time-pos a 1.19x) y el usuario lo confirmó con sus
ojos; con audio-sync tpos a 1.0x, acorr/vcorr=1.000, drops=0 y el usuario
confirmó "imagen y sonido muy bien". OJO: `time-pos` con embed MIENTE (corre
1.19-1.43x según stream aun con A/V en sync) — NO usarlo para medir velocidad;
valen SF --latency (presents a 60Hz) + ausencia de warnings desync + ojo humano.
Caché 45s: el usuario pidió DEJARLO (protege de microcortes aunque atrasa del
vivo ~45s).

**Landscape estirado + no-fullscreen (teléfono) → 3 fixes.**
Causa: mpv cachea el tamaño del primer surface (frame 1696x954 estirado a
1695x1080 = aspect 1.571, medido por PIL). (a) `setSurfaceSize()` en cada
surfaceChanged → `setPropertyString("android-surface-size", "WxH")` (el fix
oficial de BaseMPVView; verificado en el .so que la opción existe). NO
detach/attach (CORROMPE el VO: stats null + tira fina) ni setProperty("vo")
(no-op con igual valor). (b) `keepaspect=yes` explícito. (c) PlayerScreen entra
en INMERSIVO (hide systemBars, restore al salir; en TV no-op): sin esto el
surface queda en 2300x954 y el video "cuadrado". Verificado: 1920x1080 reales,
16:9 exacto, sin barras del sistema.

**Búsqueda desordenada → race + debounce.** `applyFilter()` capturaba el estado
viejo y lo escribía completo (`s.copy`), pisando el query con texto de teclas
anteriores. Fix: `setQuery` publica inmediato + `scheduleFilter(debounceMs)`
con Job cancelable (250ms prod, inyectable 0 en tests); el filtro solo escribe
channels/total/favs sobre estado fresco. + índice covering `(listName, title)`
(migración Room v1→v2 manual) + escape LIKE en searchPage & co (pasaban %_ sin
escapar). Test JVM `busquedaRapidaNoPisaElQuery` + tipeo real en teléfono
(78k canales, "mega" letra por letra → orden perfecto, 169 resultados).

**Infra de esta sesión (queda):** STATS (una línea por canal: hwdec/vo/video/
aspect/d/keepaspect/dispfps/audio-codec) + TICK 5s (avspeed/acorr/vcorr/
decdrop/framedrop/cache/mistimed/vsyncr/tpos/speed/asr/aid) + LogObserver mpv.
`abiFilters 'arm64-v8a','armeabi-v7a'` en build.gradle (el APK traía 4 ABIs =
174MB e installs eternos; con 2 = ~91MB. OJO: filtrar solo arm64 ROMPE la TV
32 bits — INSTALL_FAILED_NO_MATCHING_ABIS). TV con 4.2GB: se llena instalando;
`pm trim-caches` + DB propia 101MB (Fenix muerta 344k + SuperXL 78k) — ofrecer
borrar Fenix (HECHO 16/09: borradas Test y Fenix de ambos, TV 122MB→24MB).

### 9.13 Zoom llenar + pellizco + cutout (teléfono, 16/09 — 0.1-f3.5)
Pedido del usuario: pantalla completa en landscape aunque recorte (estilo VLC).
- Props mpv `video-zoom` (lineal: 0 = 1:1, verificado empírico) + `video-pan-x/y`
  ([-1,1]). Matemática pura en SeamlessEngine (`fillZoomProp`, `clampPan`) con
  tests JVM. `videoAspect` (flow, de video-params) + tamaño surface (de
  setSurfaceSize) en SeamlessPlayer; `setZoomProp/setPanProp/resetZoomGeometry`.
- UI (PlayerScreen, SOLO landscape no-TV): botón ⤢/⊡ (Ajustar↔Llenar) en la
  LazyRow + pellizco (`Modifier.transformable`, convive con tap: multi-touch no
  es tap) con pan (arrastrar pantalla = un pantallazo). Reset a ajustar al
  cambiar de canal u orientación. En TV se oculta (surface 16:9 + embed ignora
  zoom); en portrait también (pedido del usuario). Pellizco VERIFICADO por el
  usuario ("perfecto en la dimensión que quiera").
- Cutout (franja de la cámara no llenaba): `SHORT_EDGES` en el window + OBLIGADO
  `WindowCompat.setDecorFitsSystemWindows(false)` al entrar (restore true al
  salir) — sin esto las vistas siguen insetadas y la franja muestra el
  windowBackground #141414 (grisácea, confundible con barra de video). Título
  con padding de `WindowInsets.displayCutout` solo en landscape (esquiva cámara).
  Todo no-op en TV. Verificado por el usuario ("Golazo").
- `WindowCompat.getDecorFitsSystemWindows` NO existe en nuestro androidx.core
  (compilar falla): asumir default true con try/catch.

### 9.14 F4-rec: grabar + foto + compartir (solo teléfono, 16/09 — 0.1-f4)
Pedido: grabar lo que se ve y compartirlo en el momento (WhatsApp/IG). SOLO
teléfono (TV intacta: botones ocultos con isTv).
- Flujo: dump-cache continuo de mpv → .ts temporal → remux MediaMuxer → .mp4 →
  MediaStore (Movies/DankoTV) → ACTION_SEND automático. Foto: screenshot-to-file
  → Pictures/DankoTV → share. Botones ⏺/📷 en la LazyRow + timer REC + punto
  ROJO DIBUJADO (Box CircleShape; el glifo ⏺ U+23FA lo dibuja la fuente EMOJI
  en blanco e IGNORA el color del Text — trampa real, horas perdidas).
- stream-record PIERDE ~97% de paquetes en este build (0.5MB de 19MB): NO usar;
  dump-cache trae TODO (pero también ~45s previos: ver trim).
- mpv-mux directo a .mp4 escribe HEADERS ROTOS (tkhd 1280x1080, mdhd timescale
  1438908416 → duración 0.0s): WhatsApp tarda y muestra 5s. NO usar como salida.
- MediaMuxer + timestamps del proveedor = stop() revienta ("would have stopped
  already") aun con miles de writes OK. Causas halladas: (1) csd del extractor
  malformado → reconstruir SIEMPRE desde SPS/PPS escaneados (findSpsPps, test
  JVM); (2) buffer 512KB < IDR 1080p → loop abortaba dejando solo audio →
  4MB; (3) PTS basura → re-sellar (ver abajo).
- Timestamps: SINTETIZAR por conteo (video n/fps con videoFps=container-fps,
  audio n×1024/rate) + reorder PtsReorder (lookahead 16) porque el extractor
  da DECODE order y sellar secuencial rompe cadencia B-frames (duración exacta
  pero movimiento desparejo). NO usar PTS crudos ni siquiera rebaseados.
- Trim pre-roll por CONTEO (cuotas v/a proporcionales): seekTo NO se mueve con
  estos PTS (en silencio). fileUs=Vc/fps; skip; sin seeks en ningún lado.
- RecSpanMs (pared press→stop) y rec.log PERSISTENTE en cacheDir (el logcat rota
  en minutos y los tests del usuario llegan tarde — lección de método).
- Proceso anti-fantasmas (los installs mienten): versionCode por iteración +
  `clean` antes de build candidato + verificar vc en device + force-stop +
  proceso muerto, ANTES de pedir test al usuario. Sin esto se testea build viejo
  (pasó 3 veces: botón rojo, trim ausente, remux viejo).
- Negro al volver de background (audio sigue): surface destruido, VO no retoma
  → reload del canal en ON_RESUME (solo si estaba Playing/Reloading).
- time-pos MIENTE con embed (1.19-1.43x): velocidad se valida con SF --latency
  (presents a 60Hz) + ausencia de warnings desync + ojo del usuario.
- Limpieza galería del usuario: borrar solo archivos de prueba propios con su
  OK (se acumulan .ts/mp4 DankoTV_* de los tests).
- CORRECCIÓN 16/09 noche: dos "hallazgos" de §9.14 eran ERRORES MÍOS, no del
  stream: (1) el "mdhd timescale=1438908416" fue un parser python con +8 de
  offset (los mdhd estaban BIEN: 90000/48000); (2) todo el remux por copia
  (rebase/reseal/síntesis/reorder/inyección SPS) fue un callejón sin salida:
  cada fix arreglaba una cosa y rompía otra (duración exacta↔tartamudeo↔
  pixelado↔48s-por-7s↔congelado). Ver §9.15: la solución fue transcodificar.

### 9.15 ⭐ F4-rec v2 — LA MEJOR VERSIÓN (16/09 noche, versionCode 20, tag `f4-rec-v2-best`)
Verificado por el usuario: graba 11s → video de 11s, 4.24MB, fluido, a WhatsApp.
Pipeline NUEVO desde cero (`ClipTranscoder.kt`): dump .ts → decode HW
→ encode HW H264 + AAC → mux. NADA de timestamps del proveedor: el decoder
ordena a presentación y el encoder genera PTS limpios. Por eso anda.
- Presupuesto: video 2.5 Mbps + audio 128k ≈ 3.3–4.2MB cada 10s (pedido:
  10s ≤ 4MB, calidad de teléfono, se conserva resolución de origen).
- Duración exacta por CONTEO DE FRAMES decodificados (ventana al final +
  warmup 4s decodificado-y-tirado para arrancar limpio aunque el corte sea
  mid-GOP). `computeQuotas()` es matemática pura con tests JVM.
- Reglas MediaCodec que costaron sangre: (1) el extractor es COMPARTIDO: si
  un track termina, su alimentador debe SALTAR sus samples (si no, el otro
  se muere de hambre = cuelgue mudo); (2) el DECODER de audio se drena
  SIEMPRE hasta su EOS aunque el encoder ya haya terminado (misma causa);
  (3) drenajes con timeout 0 (poll) + watchdog STALL: sin progreso 20s →
  log de estado completo + abortar (un cuelgue mudo costó un día entero);
  (4) buffers CODEC_CONFIG del encoder NO se escriben al muxer; (5) color
  del encoder = color REAL del decoder (se lee del output, con fallback);
  (6) stopRecAndShare con try/catch externo → toast SIEMPRE (nunca silencio).
- Self-test sin UI: `TranscodeTestActivity` (exported, solo adb) + `am start`
  con extras src/dst/keepUs; resultado en rec.log. Así se validó E2E sin
  molestar al usuario (dump real 11.5MB → 10.03s, 3.76MB, decode ffmpeg con
  0 errores, frames nítidos verificados a ojo).
- Pendiente menor (usuario): el diálogo compartir tarda en aparecer porque
  el transcode procesa ~300+ frames (segundos). No es bug: es el costo del
  encode. Mejora posible (no hecha): toast "Procesando video…" inmediato.

### 9.16 EPG (F5): lecciones 17-19/09
- Proveedor INESTABLE por endpoints: get.php y auth pueden andar mientras
  get_live_streams/get_short_epg dan 404/vacío (pasó 17/09 mañana y tarde).
  El código debe degradar en silencio (tira y grilla vacías, sin carteles).
- Títulos/descripciones Xtream en base64; start/stop_timestamp epoch (UTC,
  sin adivinar TZ). Datos solapados y desprolijos: pick tolerante.
- stream_id viaja en la URL M3U Xtream (último segmento) → get_short_epg
  directo sin mapa previo. Credenciales M3U get.php se parsean (host/user).
- Grilla: UNA llamada bulk get_live_streams → mapa id→epgId para saber QUÉ
  consultar (probar 40 a ciegas casi nunca pega); si el bulk falla, fallback
  ciego con tope. Solo filas con datos (auto-detección).
- EPG por proveedor (verificado 19/09): superxlatino/Naty2 SÍ tenía datos
  (tira probada OK); redworld/cutisaca (reemplazo de dddd), Premium,
  Planet y Patricia NO tienen nada (short_epg vacío, xmltv 0 bytes,
  get_live_streams 404 o ids decorativos). La grilla/tira quedan vacías
  PORQUE NO HAY DATOS, no por bug — el pipeline está probado con datos
  reales (self-test EPG: 40 filas). Si aparece una lista con EPG, anda solo.
---

## 10. Proyecto viejo `/home/claudio/AndroidStudioProjects/DankoIPTV` (com.danko.iptv)

Reutilizable (con adaptación): `CastOptionsProvider.java` + deps cast-framework/
mediarouter (F5, tal cual); patrón ProxyService foreground+WakeLock → ADAPTAR a
MediaSessionService (no WakeLock eterno); idea StreamProxy (ver §8.4 F5).
NO reutilizar: el index.html WebView (no escala a 300k canales, exige internet por CDNs,
sin Xtream login/EPG/favs), el retry JS de 3 intentos (reemplazado por cascade).

---

## 11. Riesgos conocidos (ya identificados en el plan)

- JNI/eventos mpv en hilos nativos → §6.1 (patrón ya aplicado).
- Scoped storage (Android 10+): grabación/captura F4 a MediaStore, NO rutas libres.
- Batería/fondo: MediaSessionService para audio en background, NO WakeLock eterno.
- Tamaño APK: solo arm64-v8a + armeabi-v7a (~45-90 MB por el .so mpv).
- Room con 347k filas: ya cubierto por índices + LIMIT 2000; no hacer queries sin límite.

---

## 12. Entorno y proceso de trabajo con el usuario

1. **El usuario prueba SIEMPRE** los builds en teléfono + TV. Además el usuario
   AUTORIZÓ a la IA a entrar por adb a ambos dispositivos para ver la app funcionando
   (screenshots: `adb -s <serial> exec-out screencap -p > /tmp/x.png`; logs, am start).
2. Validación mínima por cambio: `./gradlew test` + `assembleDebug` + instalar en ambos.
3. El usuario prefiere **español**, respuestas **cortas y directas** (estilo rioplatense:
   "elegí", "pegá" — mantenerlo en la UI).
4. **Sin push a GitHub hasta que el usuario lo pida.** Git local inicializado 16/09
   (primer commit: 0.1-f3.4). Si se crea repo remoto, decidir con el usuario si
   vive en el repo Linux o en uno nuevo.
5. Commits estilo repo Linux, mensaje corto; **NUNCA** trailers
   `Co-authored-by:` de IA/herramientas; autor siempre `Danko Petro
   <87874066+dankopetro@users.noreply.github.com>`.
6. Ante cambios de comportamiento de reproducción o lecciones nuevas → ACTUALIZAR ESTE
   ARCHIVO (es la memoria del proyecto).
7. Máquina de desarrollo: **claudio** (Linux Mint 22.3, i9-11900H). Android Studio
   projects en `~/AndroidStudioProjects`. Red local: TV 192.168.0.10, celu 192.168.0.6,
   laptop danko 192.168.0.200 (donde corre el Linux contra el TV, ver AGENTS.md Linux).

---

## 13. Resumen de un vistazo (para arranque rápido de la próxima sesión)

- Ir a `/home/claudio/AndroidStudioProjects/DankoTV` (git local, rama master).
- **F0-F3 + fixes 16/09. Build actual: 0.1-f3.4 (versionCode 6), 28 tests verdes,
  instalado en TV y teléfono. TODO lo del usuario resuelto (§9.12).**
- **Próximo: F4 grabación + captura** (§8.4): stream-record nativo de mpv a
  MediaStore + screenshot-to-file a MediaStore.Images. Botones ⏺ 📷 en la LazyRow
  del player. OJO: en la TV rige `mediacodec_embed` (sin OSD/subs mpv) — el
  record/captura debe diseñarse sin depender del VO (screenshot-to-file NO anda
  con embed; alternativa: PixelCopy/API o frame del decoder — investigar).
  Al completar F4: versionName `0.1-f4`, versionCode 7.
- TV = 32 bits (armeabi-v7a), 720p, GLES2, Realtek rtd284x → `mediacodec_embed` +
  `audio-sync` (NO tocar: gpu-next/gpu+copy ya probados, pierden o negro).
  Teléfono = vo=gpu (NO embed: estira portrait + sin subs).
- Lista activa: **SuperXL** (superxlatino, 78.200; en teléfono se llama "dddd").
  Fenix muerta en DB de la TV (~70MB): ofrecer borrarla (TV con poco espacio).
- Ver la TV: `adb -s 192.168.0.10:5555 exec-out screencap -p > x.png`
  (uiautomator MIENTE en esa TV). Teléfono (192.168.0.6:37103 o mDNS):
  uiautomator OK + `input tap/text` llegan.
- Telemetría en logs: tag `SeamlessPlayer` (STATS+TICK) y tag `mpv` (nativo).
