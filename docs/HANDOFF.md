# 다음 개발자를 위한 인계

기준: **2026-11-07 KST / UWB Pulse 0.4.0 (versionCode 5)** + 갱신 주기·재탐색·마지막 상태 설정(버전 미변경, 콜백 간격은 기기 검증, 배터리 효과는 미측정).
이 문서는 다음 에이전트가 대화 기록이나 `/tmp`의 조사 파일 없이 현재 소스에서 작업하기 위한 시작점이다.

## 현재 상태

현재 앱은 실제 소유자 인증과 SmartTag2 UWB 거리 추적이 동작한다. 초기 0.1 진단 빌드의 ‘암호화 미구현’ 상태는 해소됐다. `Probe`, `diagnostic`이라는 일부 파일명·문구는 개발 이력이며 현재 기능을 진단 전용으로 해석하면 안 된다.

|항목|현재 구현 / 증거|
|---|---|
|삼성 계정|사용자가 삼성 화면에서 로그인. 기존 등록 태그·소유자 키를 받아 Keystore 암호화 저장|
|태그 식별|소유자 광고 서명으로 회전하는 BLE 주소와 계정 태그를 매핑|
|태그 세션|nonce 상호 인증, 세션별 AES 명령, 35바이트 UWB 설정을 암호화 전송|
|실제 거리|Android 플랫폼 Ranging UWB 경로에서 HIGH 신뢰도 수신·사용자 이동 확인|
|백그라운드|앱 비표시 수신 및 화면 OFF 약 15분 / 3,549 콜백 확인|
|거리 이벤트|최종 버전에서 2.46m 이탈 / 0.46m 복귀 확인. 당시 시험 임계값 1.5m|
|영속 설정|모니터링 ON/OFF, 태그, backend, 임계거리·복귀거리·지속시간, 자동화 설정, UWB 갱신 주기, 재탐색 최대 간격, 마지막 상태 저장|
|부팅 복원|실제 재부팅 후 첫 잠금만 해제. 앱을 열기 전 655 콜백, 저장된 임계값 5m 복원|
|추적 알림|시스템 채널을 숨겨도 FGS와 실제 수신 지속. 재부팅 후 숨김 유지|
|Tasker / Webhook|전송 구현 있음. 외부 수신 환경이 없어 전체 실행은 미검증|

상세 시각과 증거 파일은 [DEVICE_TESTS.md](DEVICE_TESTS.md)에 있다. 전원 연결 환경의 결과를 비충전·깊은 절전에서의 보장으로 확대하지 않는다.

## 사용자 확정 요구와 마지막 기기 상태

- 이탈 임계값 **1~10m / 0.5m 단위**, 기본 **5m**. 복귀 기본 **4m**, 이탈 지속 기본 **3초**.
- 휴대폰을 재부팅해도 이전 설정으로 추적을 재개한다. 마지막 모니터링 ON과 ‘재부팅 후 자동 재개’ ON이 모두 필요하다.
- 첫 잠금 해제 후 credential-protected 계정 키를 사용할 수 있을 때 서비스가 시작한다. 앱 화면을 자동으로 띄우는 기능은 필요하지 않다.
- 설정의 ‘추적 중 알림 표시’는 OS의 해당 채널 화면을 연다. FGS 유효 알림은 유지하고 상태표시줄/알림 서랍에서의 표시를 사용자가 제어한다. OS의 ‘실행 중인 앱’ 표시는 남을 수 있다.
- 사용자 제공 아이콘을 그대로 적용했다. 원본 경로에 의존하지 않고 `app/src/main/res/drawable-nodpi/app_icon.png`에 들어 있다.
- **추적 주기(2026-11-07 사용자 요청)**: UWB 갱신 주기 빠름 120 / 기본 240 / 절전 600ms, 이탈 후 재탐색 최대 간격 10초~5분(기본 1분). **기본값은 기존 동작과 같게 유지**한다(240ms, 2→5→15→60초). 실제 콜백은 기본 240ms였다(500ms 아님; 15분 3,549콜백). 120/600ms의 콜백 간격은 기기에서 확인했지만(`test-results/tracking-rate-validation.txt`) 배터리 효과는 측정 전이므로 기본값을 바꾸지 않는다.
- **이벤트 정책(2026-11-07 사용자 요청)**: 일시적 재연결 실패(120초 미만)는 외부 자동화로 내보내지 않는다. 범위 밖 태그는 오류가 아니라 `TAG_DISCONNECTED`로만 표현한다. 규칙은 `docs/ARCHITECTURE.md` 거리·이벤트 계약 참고. 기기 미검증.
- **히스토리 정리**: 추적 횟수 카운터는 화면·상태에서 제거하고 진단 저널(`UWB_REPORT`)에만 남긴다. 사용자에게는 마지막 상태(이탈/범위 안, 거리, 시각)만 영속·표시한다. 이벤트 기록 100개는 Tasker 검증용으로 유지한다.
- 마지막 확인 시 휴대폰: 모니터링 ON, 자동 재개 ON, 플랫폼 backend, 5m / 4m / 3초, 추적 알림 채널 OFF. 이후 사용자가 바꿀 수 있으므로 다음 작업 때 현재 상태를 다시 확인한다.

## 빌드 시작

저장소 origin은 `https://github.com/isugar150/UWB_Pulse.git`이다. 인계 시점에는 원격 refs가 없었고 로컬 `main`에서 초기 커밋을 구성했다. 사용자 요청 없이 푸시는 수행하지 않는다.

1. JDK 25와 Android SDK Platform 37 / Build Tools 36.0.0을 준비한다.
2. Android Studio SDK 설정 또는 개인 `local.properties`에 `sdk.dir`를 지정한다. 현재 개발 Mac의 절대 경로를 다른 환경에 강제하지 않는다.
3. 아래 명령으로 빌드·단위 테스트·Lint를 실행한다.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

기준 결과: 37개 통과, Lint 오류 0 / 경고 1(사용자 컬러 아이콘의 monochrome layer 권고). APK는 `app/build/outputs/apk/debug/app-debug.apk`에 생성된다. minSdk 31은 설치 하한이며, 모든 지원 버전에서 백그라운드 동작을 실측한 것은 아니다. 현재 성공 기기는 SM-F971N / Android 17 API 37이고 기본 backend는 API 36 이상에서 플랫폼이다.

## 실기기 수정·확인 절차

개인 휴대폰과 태그를 사용한다. 기존 시험의 승인을 향후 새 재부팅·계정 조작에 대한 포괄 승인으로 해석하지 말고 현재 사용자의 작업 범위를 따른다. 기존 세션이 실행 중이면 정상 중지 후 업데이트하고, 시험이 끝나면 의도한 ON/OFF 상태로 둔다.

```sh
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -W -n dev.uwbpulse.monitor/.MainActivity
./scripts/device-evidence.sh
```

- 설치 업데이트에는 같은 applicationId와 서명이 필요하다. debug 키는 개발 머신마다 다를 수 있다. 서명 불일치 시 앱을 삭제해 해결하면 계정·Keystore·설정을 잃으므로 먼저 처리 방법을 정한다. 키는 저장소에 넣지 않는다.
- 삼성 로그인은 사용자가 직접 완료한다. 비밀번호·인증 코드를 채팅이나 로그로 받지 않는다. 이미 로그인돼 있으면 불필요한 재로그인을 시키지 않는다.
- 태그를 근처에 두고 다른 앱의 ‘가까이 찾기’를 닫는다. 앱에서 소유 태그 선택 후 모니터링을 시작한다.
- 로그의 `TAG_AUTHENTICATED` → `UWB_STARTED technology=0` → `OOB_ENCRYPTED_ACKNOWLEDGED` → `UWB_REPORT`를 확인한다. 로컬 UWB 세션 open이나 ATT ACK만으로 측정 성공이라고 판단하지 않는다.
- 화면 OFF/앱 비표시는 각 콜백의 `interactive=false` / `activityVisible=false`와 카운터 증가로 확인한다. USB 연결 및 배터리 최적화 조건도 함께 기록한다.
- 재부팅 시험은 ON 상태를 저장한 뒤 재부팅하고 사용자가 첫 잠금을 해제한다. **앱을 열기 전에** `AUTO_RESUME_REQUEST reason=BOOT`, `MONITOR_RESTORE manual=false`, 실제 `UWB_REPORT activityVisible=false`를 확인한다. 앱을 열면 foreground 복원 경로가 실행되므로 부팅 복구 증거와 섞일 수 있다.
- 추적 알림을 숨긴 시험에서는 `uwb_probe` importance=0과 실제 수신 지속을 함께 확인한다. 앱이 직접 채널을 재활성화하면 안 된다.
- 거리 이벤트는 실제 태그 이동으로 이탈·복귀 각각 한 번 발생하는지 확인한다. 단절만으로 이탈이 나와서는 안 된다. 시험 임계값을 바꿨다면 사용자 기본값으로 복원한다.

원본 기기 저널은 최대 300줄이라 새 수신으로 오래된 증거가 사라진다. 중요한 구간은 바로 저장한다. `elapsed`는 재부팅하면 초기화되며 세션 수신 카운터 역시 새 세션 기준이다. Activity를 여는 `APP_START`만으로 부팅 자동 시작을 주장하지 않는다.

## 다음 작업 후보와 주의점

|후속 작업|관련 파일 / 확인할 점|
|---|---|
|화면 개선|`MainActivity.kt`, `ProbeViewModel.kt`. 현재 단일 Activity의 Compose 화면. 거리 처리와 무선 세션은 서비스에 유지|
|외부 자동화 검증|Tasker 설치 기기 또는 사용자가 지정한 HTTPS 수신 서버 필요. 서버 도착 시각·중복 이벤트 처리 확인|
|회복 경로 실측|태그 단절·재진입, Bluetooth/UWB OFF/ON, 권한 회수, 실제 프로세스 재생성·앱 업데이트 시험|
|갱신 주기 배터리 측정|콜백 간격(120/240/600ms)은 확인됨. 비충전에서 같은 조건으로 수 시간씩 폰·태그 배터리 소모를 비교해야 절전 효과를 주장할 수 있다. 시험 후 기본 240ms로 복원|
|중지 직후 재시작 지연|중지 후 곧바로 시작하면 `GATT_..._147`/`OWNER_TAG_NOT_FOUND`로 첫 수신까지 21~73초. 원인 조사 필요(이전 빌드와 비교 안 함)|
|일시적 재연결 이벤트 억제 검증|`FailurePolicy`/`OutageGate`를 구현하고 단위 테스트함(기기 미검증). 중지 후 재시작에서 저널에 `OUTAGE_NOT_PUBLISHED`만 남고 `UWB_MONITORING_ERROR`/`TAG_DISCONNECTED` 이벤트가 나가지 않는지, 태그를 일부러 멀리 둔 2분 뒤 `TAG_DISCONNECTED`가 한 번만 나가는지 확인|
|SmartThings/루틴|[AUTOMATION_REVIEW.md](AUTOMATION_REVIEW.md) 참고. 가상 스위치 수동 REST 호출로 SmartThings 자동화가 도는지 먼저 확인|
|배터리·장기 안정성|비충전·기본 최적화·깊은 절전·수 시간 시험. 15분 충전 중 결과와 구분|
|호환 기기 확대|AndroidX backend 및 다른 OS/펌웨어. 지원 feature와 실제 background callback을 별도 검증|
|다중 태그|계정에 여러 태그를 보관할 수 있으나 추적 서비스는 선택한 한 태그만 관리|
|유지보수|`ProbeStore.selectedAddress` 등 초기 진단 경로가 일부 남아 있음. 서비스 식별 기준은 회전 MAC이 아니라 계정 태그+광고 서명|
|테마 아이콘|사용자 원본을 보존하며 별도 monochrome 레이어를 설계할 경우에만 Lint 경고 해소|

삼성 인증과 소유자 API는 비공식 호환 구현이다. 서버 정책 변화 시 `data/auth`를 확인한다. 공개 클라이언트 ID는 프로토콜 상수이며 사용자 비밀과 구분한다. 삼성 private Binder, 수정 SmartThings, 태그 초기화에 의존하는 경로로 바꿀 필요가 없다.

## 문서·배포 갱신

- 구조 변경은 `ARCHITECTURE.md`, 사용자 기능은 `README.md`, 관측 결과는 `DEVICE_TESTS.md`, 현재 상태는 이 문서에 반영한다.
- 자동화 연동 검토는 `AUTOMATION_REVIEW.md`에 있다.
- 프로토콜 출처는 `FEASIBILITY.md`와 `THIRD_PARTY_NOTICES.md`에 있다. `/tmp` 참조 checkout·디컴파일 결과는 재현에 필수인 소스가 아니며 배포하지 않는다.
- 루트 `THIRD_PARTY_NOTICES.md`와 APK의 `app/src/main/assets/licenses/THIRD_PARTY_NOTICES.md`를 일치시킨다.
- 검증 빌드 후 `python3 scripts/package-release.py`로 APK·대응 소스 ZIP·SHA-256을 재생성한다. `artifacts/`의 APK/ZIP은 Git에 넣지 않는다. 새 clone에서는 직접 생성한다.
- 초기 진단 파일명 `UWB-Pulse-diagnostic.apk`도 현재 APK의 호환 복사본이다. 최종 설치본은 `UWB-Pulse.apk`를 기준으로 안내한다.
