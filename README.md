# OpenCode Mobile — Native Android (Kotlin + Compose)

Native Android app (this repo's main project). Same app ID
(`com.logicedge.opencodemobile`), same server protocol, no WebView:
the chat UI talks to the OpenCode HTTP API directly.

The previous Capacitor/Vue app is preserved in `legacy/`.

## Build

```bash
export ANDROID_HOME=~/Android/Sdk   # or set sdk.dir in local.properties
./gradlew :app:testDebugUnitTest    # JVM unit tests (no device needed)
./gradlew :app:assembleDebug        # APK at app/build/outputs/apk/debug/
```

Requires JDK 17+, Android SDK with platform 35 + build tools.

## Layout

```
app/src/main/java/com/logicedge/opencodemobile/
  MainActivity.kt                  entry point, builds ServerRepository
  data/
    ServerModels.kt                ServerProfile, ServerStatus, ConnectionState, HealthResult
    UrlUtils.kt                    port of src/services/opencode/url.ts
    Auth.kt                        port of src/services/opencode/auth.ts (Basic header)
    Health.kt                      status classifier + timeout const (port of health.ts)
    ApiModels.kt                   SessionInfo, ChatMessage sealed types, MessageParser
    OpenCodeApi.kt                 OkHttp client: health, sessions, messages, prompt,
                                   wait/interrupt, permissions, SSE /api/event
    Permissions.kt                 permission request parser + reply decisions
    ProfileStore.kt                DataStore prefs, key opencode_server_profiles
    SecretStore.kt                 EncryptedSharedPreferences, key opencode_pw_<id>
    ServerRepository.kt            CRUD + single-default + embedded profile +
                                   parallel check-all (port of the Pinia stores' I/O)
    DemoMode.kt                    offline demo credentials + keyword responder
  server/
    BootstrapInstaller.kt          Termux bootstrap extraction (ported 1:1)
    OpenCodeServerManager.kt       CLI install, config/auth, serve lifecycle (ported,
                                   JSON via kotlinx.serialization)
    OpenCodeForegroundService.kt   foreground service + status notification (ported 1:1,
                                   now declared in the manifest)
    LocalServerController.kt       start/stop/status facade (replaces Capacitor plugin)
  notify/
    NotificationPrefs.kt           opencode_native_notifications prefs + emit/ignore/ask
    SessionNotifier.kt             opencode-web-notifications channel, completion/error/
                                   permission alerts
  ui/
    ServerViewModel.kt             profiles state (port of serverStore)
    ConnectionViewModel.kt         connect / poll(10s) / reconnect(1,2,4,8,15,30s)
    ChatViewModel.kt               sessions, messages, send, SSE live refresh, wait+poll,
                                   stop, permission approve/deny, demo, notifications
    Nav.kt                         routes: landing, servers, new, edit, settings,
                                   help, connect/:id, chat/:serverId/:sessionId
    Theme.kt                       Material3 light/dark
    screens/                       Landing, ServerList, ServerForm, Settings
                                   (connection + notifications + on-device server),
                                   Help, Connect, Chat (session menu, permission +
                                   notification-ask dialogs)
app/src/test/...                   UrlUtilsTest, Auth/health classifier,
                                   DemoMode, MessageParser, Permissions,
                                   NotifyDecision, SSE session-id tests
```

## API mapping (verified live)

Works against **v1 and v2** servers. The version is auto-detected on first
connect (`GET /api/info` → v2, `GET /global/health` → v1) and stored per
profile; all paths, envelopes, and payloads adapt. Auth on both is HTTP Basic
(`opencode` + `OPENCODE_SERVER_PASSWORD`).

| App need            | v2 (`/api/…`)                              | v1 (unprefixed)                              |
|---------------------|--------------------------------------------|----------------------------------------------|
| Health              | `GET /api/info` (`{"version"}`)            | `GET /global/health` (`{"healthy"}`)         |
| List/create/delete  | `GET/POST/DELETE /api/session…` (`{data}`) | `GET/POST/DELETE /session…` (raw)            |
| Messages            | `GET /api/session/{id}/message` (flat)     | `GET /session/{id}/message` (`{info,parts}`) |
| Send                | `POST …/prompt {"text"}`                   | `POST …/message {"parts":[{"type":"text"}]}` |
| Completion          | `POST …/experimental/session/{id}/wait`    | re-poll only (no wait endpoint)              |
| Stop                | `POST …/interrupt`                         | `POST …/abort`                               |
| Permissions         | list/reply under `…/permission…`           | reply via `…/permissions/{id}` (`response` + `remember`); listing is best-effort |
| Live events         | `GET /api/event` SSE                       | `GET /global/event` SSE                      |
| Pairing             | `POST /api/pair` → redeem `/auth/connect/{code}` → token-as-password | n/a (manual URL + password) |

**Pairing (v2):** run `opencode pair` on the server, then in Add Server →
"Pair with code/link" either **scan the QR code** it prints (camera) or paste
the link (`http://host:port/auth/connect/<code>`, single-use, 5 min expiry).
The QR encodes exactly that URL. The redeemed token is saved as the
password — no typing credentials. Verified live: the token authenticates as
the Basic password for user `opencode`.

Message union is discriminated by `type`: `user` (`text` or `payload.text`),
`assistant` (`content[]` with `text`/`tool` parts, `finish`, `error`),
`idle`/`system` (mapped to system rows).

## Intentional gaps vs the Capacitor app

- **Webview shell is gone by design**: the legacy app embedded the OpenCode web
  UI in a WebView; this app talks to the OpenCode HTTP API directly and renders
  sessions natively (chat, permissions, notifications). iframe
  credential-in-URL, frame-blocked detection, the injected JS bridge, the
  in-app-browser fallback, and the custom pull physics do not apply. Lists use
  Material3 `PullToRefreshBox`; health auto-polls every 5s on Landing/Servers.
- **On-device server needs the bootstrap asset**: `BootstrapInstaller`,
  `OpenCodeServerManager`, and `OpenCodeForegroundService` are ported 1:1 and the
  service is properly declared (the old manifest never declared it). Like the old
  app, `bootstrap-aarch64.zip` is downloaded at build time
  (see `.github/workflows/build-native-apk.yml`) into
  `app/src/main/assets`. Without it, Settings shows
  "Bootstrap asset is not bundled" and local start is unavailable.
- **Profile list self-repairs on launch**: duplicate "Local OpenCode" entries
  from an old seeding bug are removed automatically, and the first real server
  is promoted to default when none is set (the landing screen only shows the
  default server). Rules live in `ProfilePlan` with unit tests.
- **iOS**: dropped per decision; `legacy/` keeps the Capacitor app (web + iOS).
