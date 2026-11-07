# UWB Pulse

Galaxy SmartTag2의 **실제 UWB 거리**를 추적하고, 임계거리 이탈·복귀를 Android 자동화에 전달하는 Kotlin / Compose Material 3 앱입니다. 현재 버전 **0.4.0**. 사용자 제공 아이콘을 적용했습니다.

개발을 이어갈 때는 [인계 문서](docs/HANDOFF.md) → [구조 안내](docs/ARCHITECTURE.md) → [실기기 검증](docs/DEVICE_TESTS.md) 순서로 읽으세요. 에이전트 작업 기준은 [AGENTS.md](AGENTS.md), 원본 및 후속 요구는 [요구사항](docs/REQUIREMENTS.ko.md)에 있습니다. 저장소: [isugar150/UWB_Pulse](https://github.com/isugar150/UWB_Pulse).

## 확인된 동작

Samsung SM-F971N / Android 17(API 37) / One UI 9에서 다음을 실기기로 확인했습니다.

- 삼성 계정 로그인과 기존 등록 태그 목록·소유자 키 조회
- 광고 서명으로 계정 태그와 회전 BLE 주소 매핑
- 태그와 소유자 상호 인증, 세션별 AES 명령 암호화, 실제 UWB 세션
- 사용자가 이동했을 때 실제 거리 변화와 HIGH 신뢰도 콜백
- 앱이 보이지 않거나 화면을 끈 상태의 수신: 약 15분 시험에서 화면 OFF 콜백 3,549개
- 실제 이탈 2.46m / 복귀 0.46m 이벤트, 상태별 한 번 발생 및 알림
- 실제 휴대폰 재부팅 후 첫 잠금 해제만으로 자동 추적 재개: 앱을 열기 전 실제 UWB 콜백 655회, 저장 임계값 5m 복원
- 추적 알림 채널 숨김 상태에서도 실제 거리 수신 지속, 숨김 설정 재부팅 후 유지

배포 파일은 `artifacts/UWB-Pulse.apk`와 대응 소스 `artifacts/UWB-Pulse-source.zip`입니다. 기존 `UWB-Pulse-diagnostic.apk` 파일도 같은 최신 APK로 갱신했습니다.

APK와 ZIP은 생성물이므로 Git에는 포함하지 않습니다. 새 clone에서 검증 빌드 후 `python3 scripts/package-release.py`로 생성하세요. 체크섬은 `artifacts/SHA256SUMS.txt`에 기록합니다.

USB 충전 환경에서의 결과입니다. 비충전·깊은 절전·장시간 배터리 소비·다른 기기/펌웨어는 별도 검증 대상입니다. 재부팅·알림 표시 제어의 실제 결과와 원본 증거는 [기기 시험 기록](docs/DEVICE_TESTS.md)에 구분합니다. Tasker 앱은 시험 기기에 설치되지 않았으며, Webhook 수신 서버도 제공되지 않아 외부 수신까지의 검증은 미완료입니다.

## 처음 사용하기

1. APK를 설치하고 **삼성 계정으로 내 태그 연결**을 누릅니다. 삼성 로그인 화면에서 본인이 로그인·동의합니다. 앱 선택 화면에서는 UWB Pulse를 선택합니다.
2. 기존 계정의 UWB 태그를 선택합니다. 태그를 초기화하거나 기존 Samsung Find/SmartThings 등록을 삭제하지 않습니다.
3. 휴대폰의 Bluetooth와 UWB를 켜고 **모니터링 시작**을 누릅니다. 근처 기기·UWB 권한을 허용합니다. 이벤트 알림이 필요하면 알림 권한도 허용합니다.
4. 태그를 자동 검색해 소유자 인증 후 실제 거리를 표시합니다. 실패 시 오류와 재연결 상태를 표시하며 임의 거리를 생성하지 않습니다.
5. **모니터링 중지**를 누르면 저장된 ON 상태도 OFF로 바뀌어 다음 재부팅에서 자동 시작하지 않습니다.

네트워크는 최초 로그인·태그 정보 조회·토큰 갱신과 선택한 Webhook 전송에 사용합니다. 이미 저장된 소유자 키로 UWB를 시작하는 경로는 클라우드 요청 없이 동작하도록 구현했습니다. 다른 앱의 ‘가까이 찾기’와 동시에 같은 태그의 정밀 찾기를 사용하면 세션이 충돌할 수 있습니다.

## 거리 설정

- 임계거리: **1~10m**, 0.5m 단위, 기본 **5m**
- 복귀 거리: 기본 **4m**. 임계거리보다 작게 설정합니다. 슬라이더 변경 시 가능한 범위에서 히스테리시스 폭을 유지합니다.
- 이탈 지속 시간: 기본 **3초**, 설정에서 조정
- 실제 거리 > 임계값이 지속된 경우에만 이탈 발생. 이탈 중에는 중복 실행하지 않고, 실제 거리 ≤ 복귀 거리일 때 복귀합니다.
- 유효 측정 간격이 1.5초 이상 벌어지면 진행 중인 디바운스를 초기화합니다(절전 주기에서는 주기에 비례해 1.8초). 3초 초과한 측정값은 숨깁니다. 신호가 없다는 이유로 거리 이탈을 만들지 않습니다.
- 이탈 상태는 태그별 별칭으로 저장해 정상적인 앱/서비스 재시작에서도 중복 이탈을 억제합니다. 진행 중인 디바운스는 재시작 후 새 실제 측정으로 다시 계산합니다.

설정은 DataStore에 저장되며 모니터링 도중에도 반영됩니다. 실제 UWB 수신이 중단된 상태는 `— m`으로 표시합니다. BLE RSSI는 거리로 사용하지 않습니다.

## 추적 주기와 배터리

설정의 **추적 주기**에서 두 값을 바꿀 수 있습니다. 기본값은 차량 접근·이탈 자동화를 위한 기존 동작과 같습니다.

|설정|선택지|기본|
|---|---|---|
|UWB 갱신 주기|빠름 120ms / **기본 240ms** / 절전 600ms|기본 240ms|
|이탈 후 태그 재탐색 간격(최대)|10초 / 30초 / **1분** / 2분 / 5분|1분|

- 갱신 주기는 폰의 Android Ranging 갱신율과 태그에 보내는 암호화 OOB의 ranging interval에 함께 들어갑니다. 따라서 모니터링을 중지한 상태에서 바꾸고 다음 시작부터 적용합니다. 기기가 해당 갱신율을 지원 목록에 올리지 않으면 기본으로 되돌려 시작하고 저널에 `UWB_RATE`로 남깁니다.
- 재탐색 간격은 연결이 끊긴 뒤 태그를 다시 찾는 최대 대기 시간입니다. 2초 → 5초 → 15초 → 60초로 늘어나다 이 값에서 멈추며 선택값이 더 짧으면 그 값으로 제한합니다. 추적 중 갱신 주기보다 항상 깁니다. 바로 적용됩니다.
- 화면이 꺼졌거나 앱이 보이지 않을 때는 UI 상태를 초당 1회로 줄입니다. 거리 판정과 이벤트는 모든 실제 수신에 대해 그대로 처리합니다.
- 실기기(SM-F971N 한 대, SmartTag2 한 개, 플랫폼 backend, 정지 상태)에서 **콜백 간격은 120ms / 240ms / 600ms로 확인**했습니다(8.33 / 4.17 / 1.67회/초, 신뢰도 HIGH). 폰·태그의 **실제 배터리 절감은 측정하지 않았습니다**. 장시간·비충전·다른 기기에서의 동작도 미검증입니다. [근거](test-results/tracking-rate-validation.txt)

추적 횟수 카운터는 사용자 기능이 아니므로 화면에서 뺐습니다(진단 저널의 `UWB_REPORT`에만 남김). 대신 모니터링이 멈춰 있을 때 **마지막 상태**(이탈/범위 안, 마지막 거리, 시각)를 한 줄로 보여 줍니다. 이 값은 추적 중 1분에 한 번, 이벤트 시, 중지 시에만 저장합니다. 이벤트 기록(최근 100개)은 Tasker 검증과 자동화 점검용으로 유지합니다.

## 재부팅 / 백그라운드

마지막 모니터링 ON/OFF, 선택 태그, UWB backend, 임계거리·복귀 거리·지속 시간과 자동화 설정을 저장합니다.

- 설정의 **재부팅 후 자동 모니터링 재개**는 기본 ON입니다. 마지막 모니터링 상태도 ON이었을 때만 자동 재개합니다.
- 계정 키는 자격 증명 보호 저장소에 있으므로 **재부팅 후 첫 잠금 해제 이후** BOOT_COMPLETED에서 추적 서비스를 재개합니다.
- 앱 업데이트 후에도 ON 상태를 복원하며, OS가 서비스를 다시 생성하면 START_STICKY 경로에서 저장 상태를 확인합니다.
- 일시적인 태그/무선 연결 오류는 2초 → 5초 → 15초 → 최대 60초(설정 가능) 대기로 소유자 서명을 다시 검색합니다. 인증·권한 문제는 오류로 구분합니다.
- Android가 허용하는 `connectedDevice` Foreground Service를 사용합니다. UWB용 wake lock이나 배터리 최적화 강제 해제는 사용하지 않습니다.

Android의 ‘강제 중지’나 권한 회수까지 우회하지 않습니다. 강제 중지 후에는 앱을 다시 열어야 합니다. OS·삼성 절전 정책이 서비스를 제한할 수 있으므로 상태와 진단 로그를 확인하세요. 기기 검증 없이 모든 Android 버전의 백그라운드 동작을 보장하지 않습니다.

## 추적 알림 표시

**설정 → 추적 중 알림 표시**에서 Android의 해당 알림 채널 설정을 엽니다. 해당 채널을 끄면 상태표시줄/알림 서랍의 추적 알림을 숨길 수 있고, 추적 서비스는 계속 유지합니다. 실제 표시 상태는 시스템 설정에서 읽으며 Android가 재부팅 이후에도 보관합니다.

Foreground Service에는 유효한 알림이 필요하므로 서비스 알림 자체를 제거하는 방식은 사용하지 않습니다. Android의 ‘실행 중인 앱’ 표시는 별도로 남을 수 있습니다. 이탈·복귀 등 이벤트 알림은 각각 따로 켜고 끌 수 있습니다.

## 자동화

공통 이벤트:

- `TAG_DISTANCE_EXCEEDED`
- `TAG_RETURNED`
- `TAG_DISCONNECTED`
- `UWB_MONITORING_ERROR`

시각은 ISO 8601이며 거리 신호를 모르는 이벤트에는 `distanceMeters: null`을 사용합니다. 수신 중단은 이탈과 별개입니다.

`TAG_DISCONNECTED`와 `UWB_MONITORING_ERROR`는 **일시적 재연결 실패로는 발행하지 않습니다.** 재연결이 2분 넘게 이어질 때만 내보내며(`TAG_DISCONNECTED`는 한 번 연결됐던 태그에 한해), 태그가 범위 밖이어서 못 찾는 상태는 오류 이벤트를 내지 않습니다. 권한·로그인 같은 복구 불가 오류는 바로 발행합니다. 같은 outage에서는 각 이벤트를 한 번만 보냅니다. 이 규칙은 단위 테스트로만 검증했고 실기기에서는 아직 확인하지 않았습니다.

### Tasker

설정에서 Tasker 이벤트 플러그인을 켠 뒤 Tasker → 프로필 → 이벤트 → 플러그인 → **UWB Pulse 이벤트**를 선택합니다. ALL 또는 개별 이벤트로 필터링할 수 있습니다. `%uwb_event`, `%uwb_distance`, `%uwb_threshold`, `%uwb_timestamp`, `%uwb_tag` 변수를 제공합니다. 거리를 모르면 distance는 빈 문자열입니다.

공식 Tasker Plugin Library 0.4.10의 이벤트 프로토콜을 사용합니다. 이벤트 전달은 Tasker 패키지로 제한하며 계정 토큰·소유자 키·원본 클라우드 태그 ID를 보내지 않습니다. 삼성 Modes and Routines의 사용자 정의 거리 조건 등록용 공식 SDK는 확보하지 못했습니다. SmartThings 루틴과 삼성 Modes and Routines를 직접 트리거할 수 있는지에 대한 검토는 [자동화 연동 검토](docs/AUTOMATION_REVIEW.md)에 있습니다. 직접 루틴 제어 API를 구현했다고 주장하지 않습니다. 다만 루틴에는 ‘알림을 받으면’(앱·키워드 지정) 조건이 있어, UWB Pulse의 이벤트 알림(제목 `TAG_DISTANCE_EXCEEDED` / `TAG_RETURNED` 등)을 조건으로 다른 루틴을 실행할 가능성이 있습니다. 모드 및 루틴의 알림 접근을 켜야 하며 **종단 간 동작은 아직 검증하지 않았습니다.**

### HTTPS Webhook (선택)

설정에서 HTTPS URL과 필요한 Bearer 토큰을 저장한 뒤 전송을 켭니다. 토큰은 화면에 다시 표시하지 않고 Keystore로 암호화합니다. 태그 별칭 포함은 기본 OFF이며, 켜도 원본 계정 장치 ID 대신 앱별 해시 별칭을 사용합니다.

WorkManager의 네트워크 조건과 영속 큐를 사용합니다. 연결·읽기 타임아웃 각각 5초, 최대 3회 전송, 429·5xx·네트워크 오류에 지수 백오프를 적용합니다. 다른 호스트로 리디렉션하지 않습니다. `Idempotency-Key`/이벤트 ID로 수신 측 중복 방지를 지원합니다. 서버의 멱등 처리는 별도로 필요합니다. Webhook을 끄면 대기 작업도 전송을 건너뜁니다. 실제 도착 시점은 OS 작업 스케줄·네트워크 상태에 영향을 받습니다.

## 빌드 / 설치

JDK 25, Android SDK Platform 37 / Build Tools 36.0.0. `gradle/gradle-daemon-jvm.properties`가 daemon JVM 25를 지정합니다. Android Studio에서 SDK 경로를 지정하거나 개인 `local.properties`에 `sdk.dir`를 설정합니다.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.uwbpulse.monitor/.MainActivity
```

결과 APK: `app/build/outputs/apk/debug/app-debug.apk`. 제공 APK는 debug 서명입니다. 앱 데이터와 Keystore 키를 유지하려면 같은 서명의 APK로 업데이트하고 앱을 삭제하지 마세요.

버전 고정: AGP 9.5.1 / Gradle 9.9.0(SHA-256 검증) / Kotlin·Compose compiler 2.4.21 / Compose BOM 2026.09.00 / Activity 1.13.0 / Lifecycle 2.11.0 / DataStore 1.3.0 / AndroidX UWB 1.0.0 / WorkManager 2.12.0 / Tasker Plugin Library 0.4.10. minSdk 31, compileSdk·targetSdk 37.

## 데이터 / 권한 / 구조

삼성 계정 비밀번호는 삼성 로그인 화면에서만 입력합니다. 토큰·태그 키·선택 계정 태그 정보는 Android Keystore AES-GCM envelope에 저장하며 백업·기기 전송 대상에서 제외합니다. 로그는 키·토큰·nonce·전체 BLE 주소·클라우드 장치 ID를 기록하지 않습니다. 거리·시각·기기 빌드와 오류는 로컬 진단에 포함됩니다. 보고서 공유는 사용자가 선택한 앱에만 임시 읽기 권한을 부여합니다.

Bluetooth scan/connect는 태그 검색·OOB에, RANGING 또는 UWB_RANGING은 선택 API에, POST_NOTIFICATIONS는 알림에 사용합니다. RECEIVE_BOOT_COMPLETED는 자동 재개용입니다. WorkManager/Tasker가 큐 및 플러그인 동작에 필요한 서비스 권한을 선언합니다. 위치·백그라운드 위치·삼성 private Binder 권한은 요구하지 않습니다.

`domain`의 순수 이벤트 엔진, `data`의 계정·암호화·무선·설정 저장소, `service`의 세션 수명주기, `ui`의 ViewModel/Compose, `automation`의 출력 어댑터로 분리했습니다. 선택한 한 태그를 모니터링하며 계정 모델·설정은 여러 태그를 구분할 수 있게 구성했습니다.

이 앱의 삼성 로그인/장치 정보 접근은 **비공식 호환 구현**입니다. uTag 문서와 공개 프로토콜 연구를 이용했고, 삼성 앱을 교체하거나 수정하지 않습니다. 서버 정책·펌웨어 변경 시 호환성 작업이 필요할 수 있습니다.

소스는 GPL-3.0-only입니다. 배포 시 대응 소스와 빌드 파일을 함께 제공하세요. [라이선스](LICENSE), [오픈소스 고지](THIRD_PARTY_NOTICES.md), [기술 분석](docs/FEASIBILITY.md), [실기기 결과](docs/DEVICE_TESTS.md)를 참조하세요.
