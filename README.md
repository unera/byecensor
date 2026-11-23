# Bye, Censor!

A local SOCKS5 proxy for Android that runs an Xray kernel **without** `VpnService`.

## Why

Many apps now refuse to work while a VPN is on — banks, government services, some messengers.
They check whether the active network is a VPN transport, and a VPN is exactly what every usual
client creates. This app creates none: it starts the Xray kernel as a plain process, which listens
on loopback as a SOCKS5 proxy with a username and a password, and the apps that want it are pointed
at it. Nothing on the system sees a VPN, so nothing complains.

The trade is explicit: only apps that can be configured to use a proxy will use it. That is why the
help screen in the app says which apps can and which cannot — Chrome and YouTube have no proxy
setting of their own at all, and saying otherwise would waste an hour of somebody's evening.

## What it does

- runs the Xray kernel (`v26.x`, arm64) — VLESS, VMess, Trojan, Shadowsocks, TCP/WS/gRPC, TLS;
- serves a **SOCKS5 proxy on `127.0.0.1:1099`** with a generated username and password;
- optionally serves an **HTTP proxy** on a second port, for browsers that cannot speak SOCKS with
  a password;
- imports servers from `vless://`, `vmess://`, `trojan://` and `ss://` links, or from a subscription
  URL (plain list or base64); transports TCP, WebSocket, gRPC and HTTP/2, with TLS or REALITY;
- two domain lists decide the route: "direct" (always bypass the server) and "always through the
  server" (wins over the first one, so `ru` direct plus `2ip.ru` proxied works as written);
- a quick settings tile switches between "through the server" and "direct";
- counts the traffic it carries: this session on the left of the button, everything since the last
  reset on the right (long tap on the right number resets it);
- the proxy is up whenever the app is; the button chooses where traffic goes, not whether the
  proxy exists.

## How it works

The kernel binary is packaged as `lib/arm64-v8a/libxray.so`. That is not a trick for its own sake:
since Android 10 an app may not execute a file from its own data directory, and the native library
directory is the only place it may run from. `scripts/fetch-core.sh` downloads it before a build;
the binary is not committed.

The kernel is started by a foreground service and reads a config generated on the fly. Readiness is
"the port answers", not "a line appeared in the log" — with `loglevel: warning` the kernel never
announces itself.

## Traffic counter

The numbers are the kernel's own, not a guess made from socket reads. The config carries
`"stats": {}`, the API door on `127.0.0.1:12099` and a routing rule that sends that door to the
kernel's own `api` handler; the flags that actually switch counting on live in `policy.system`
(`statsInboundUplink`, `statsInboundDownlink`) — the per-level ones in `policy.levels` cover users
and leave the door counters silent, which answers every query with `{}` and looks like a broken
feature.

Reading is the kernel's own command line, `libxray.so api statsquery -pattern "inbound>>>" -reset`,
rather than a gRPC client: the alternative is a protobuf schema and a library in the APK for two
integers. `-reset` is the point — the kernel hands out what it counted and starts again from zero,
so the counters move into the app's own sums in one step.

The sums then live in two places on purpose: the session counter is this connection (it starts
where the clock does), and the lifetime counter is in `SharedPreferences`. Restarting the kernel
on a network change, installing an update or rebooting the phone therefore does not lose the count;
the last reading is taken before the kernel is stopped, and the API adds nothing to the total
between readings. Everything is summed across all doors (SOCKS, HTTP, both WhatsApp ports) except
the API door itself, which counts questions rather than traffic.

## Requirements

Android 13 (API 33) or newer. The app ships only `arm64-v8a`: the kernel is built for that
architecture, and carrying the others would double the download for nobody.

## Build

```sh
./scripts/fetch-core.sh          # Xray kernel + geoip/geosite, checksum verified
./gradlew assembleDebug          # needs an Android SDK
```

A signed release takes three environment variables and a keystore of your own:

```sh
export BYECENSOR_KEYSTORE=/path/to/your.jks
export BYECENSOR_KEYSTORE_PASS=…
export BYECENSOR_KEY_ALIAS=byecensor      # optional, defaults to byecensor
./gradlew assembleRelease
```

Before anything is published, the built file is checked:

```sh
./scripts/check-apk.sh app/build/outputs/apk/release/app-release.apk
```

It fails when the APK has no `lib/arm64-v8a/libxray.so`, no `geoip.dat`/`geosite.dat`, is suspiciously
small or is unsigned. This exists because 0.8.4 was released built without `fetch-core.sh`: the APK was
6.3 MB instead of 22, and the app answered "no kernel" on the phone.

CI builds a debug APK, runs the unit tests and runs the same check on every push to `main` or `master`
and on every pull request.

## Tests

```sh
./gradlew testDebugUnitTest
```

The tests cover what a phone cannot check for us: share-link parsing for all four protocols, the
generated kernel config (including that a proxy without a password is refused), and the priority of
the two domain lists.

## What cannot use it

The proxy is reachable only by apps that have their own proxy settings. Telegram does. Firefox does
with an extension (Proxy Toggle works with SOCKS5 and a password). Chrome has no proxy settings at
all on Android, and neither does YouTube; WhatsApp's proxy feature speaks its own protocol, not
SOCKS5. The app's help screen says all of this in plain words — a wrong promise costs an hour.

## Licence

The app code is MIT. The Xray kernel is downloaded at build time and carries its own licence
(MPL-2.0); see the Xray-core repository.
