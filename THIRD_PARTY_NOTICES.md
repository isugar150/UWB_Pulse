# Third-party notices

## uTag

Author: Kieron Quinn. Repository: https://github.com/KieronQuinn/uTag

Inspected and adapted revision: `6152f89b02d41476cbf923fb411e6a62a1303881` (retrieved 2026-11-07).

License: GNU GPL version 3; complete text in `LICENSE`.

Derived files in this project:

- `SmartTagSessionConfig.kt`: adapted from `app/src/main/java/com/kieronquinn/app/utag/components/uwb/UwbConfig.kt` and `utils/LengthByteArray.kt`. Replaced FiRa builder dependency with explicit observed defaults, added bounds checks and SecureRandom, and made the 2-byte ranging interval (default 240 ms) a validated parameter. Preserves 35-byte tag packet layout, channel 9, static STS and legacy OOB parameters. These are protocol/session parameters, not fabricated measurements or Samsung ownership keys.
- `UwbProbe.kt`: adapted from `repositories/JetpackUwbRepository.kt` and `repositories/PlatformUwbRepository.kt`. Added backend comparison, UWB-only result check, coroutine-owned lifecycle, confidence, actual callback evidence and a selectable ranging update rate (default NORMAL, as upstream's automatic rate). Its OOB callback is implemented by `GattTransport.kt`, which performs owner mutual authentication and encrypted tag activation/session configuration. Earlier diagnostic-only builds stopped at an unimplemented encryption boundary; current builds implement that path and receive actual UWB reports.
- `BleDiscovery.kt`: the advertised UWB bit follows `SmartTagRepository.decodeServiceData` (byte 12 bit 2); only the capability flag and payload length are retained, never the privacy identifier.
- `gradlew`, `gradlew.bat`, `gradle-wrapper.jar`: copied from upstream; generated Gradle wrapper components, Apache-2.0. Distribution changed to Gradle 9.9.0 with SHA-256 verification.

No SmartThings APK, Samsung proprietary native library, Xposed module or account credential is bundled.

Additional derived code in version 0.3.0: `SamsungLogin.kt` adapts uTag `SignInUtils`, `AuthService`, `AuthResponseViewModel` and the documented account authentication wire format. `SamsungHttp.kt` uses the corresponding OSP/client headers. Changes include JCA PBKDF2, strict HTTPS/host validation, bounded responses, no HTTP logging, PKCE and single-use callback handling, browser/native account login, and Android Keystore AES-GCM storage. Public client identifiers are protocol identifiers, not user credentials. This is an unofficial compatibility implementation, not a Samsung partner SDK.

## Published SmartTag protocol research

The owner authentication, SHA-256 KDF and AES-CBC command framing in `SmartTagCrypto.kt`/`GattTransport.kt` are independently implemented from the protocol described by Tingfeng Yu, James Henderson, Alwen Tiu and Thomas Haines, *Security and Privacy Analysis of Samsung's Crowd-Sourced Bluetooth Location Tracking System*, USENIX Security 2024, sections 3.1–3.2: https://www.usenix.org/conference/usenixsecurity24/presentation/yu-tingfeng . Protocol details were also checked against the user's installed SmartThings app locally. No decompiled proprietary source or APK is included in this project.

## Tasker Plugin Library

`com.joaomgcd:taskerpluginlibrary:0.4.10`, Joao Dias, GPL-3.0. Source and examples: https://github.com/joaomgcd/TaskerPluginSample (reference revision `5e16316f66516008abbb86431f29dba6c494cc8e`). `TaskerEvents.kt` follows its event/filter/output sample. Event dispatch is restricted to the Tasker package; output contains only event data and a local tag alias. The unused action receiver/service are excluded from the merged manifest.

## Android / Kotlin dependencies

AndroidX libraries and Gradle are Apache-2.0; Kotlin is Apache-2.0. Their transitive dependencies retain their original licenses and embedded notices. Android SDK/platform components are provided by the user's installed SDK. The Gradle wrapper's Apache-2.0 license is included in `licenses/Apache-2.0.txt`.

## User artwork

`app/src/main/res/drawable-nodpi/app_icon.png` was supplied by the user on 2026-11-07. No ownership or relicensing of this image is asserted. It is packaged unchanged inside an Android adaptive icon.
