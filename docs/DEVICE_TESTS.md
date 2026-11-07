# 실제 기기 시험 결과

시험일: **2026-11-07 (Asia/Seoul)**. 최종 설치 버전: **0.4.0 / versionCode 5**.
삼성 계정 로그인·소유자 인증을 완료한 실제 SmartTag2에서 측정했다. 계정 비밀·전체 BLE 주소·휴대폰 시리얼은 증거에 포함하지 않는다.

## 환경

|항목|값|
|---|---|
|휴대폰|Samsung SM-F971N / 사용자 기기 Galaxy Z Fold8|
|OS|Android 17, API 37, One UI 속성 90000|
|빌드|CP2A.260605.016.F971NKSU3AZJ8|
|태그|삼성 계정의 기존 SmartTag2 한 개, 소유자 광고 서명·상호 인증 확인|
|UWB 경로|플랫폼 Ranging RAW / technology=0(UWB), confidence=2(HIGH)|
|전원|USB 충전 연결|
|배터리 최적화|최초 화면 OFF 시험 시작 시 예외 false. 후속 시험에서 true 관측. 비충전·기본 절전 조건의 장시간 보장은 별도 검증 필요|

## 실측 결과

|시각(KST)|시험|결과|
|---|---|---|
|08:47:44~|소유자 인증·암호화 OOB|상호 proof 검증, AES 명령 ACK, 실제 거리 콜백 수신|
|08:47~08:48|태그 이동|사용자가 멀리 이동 후 복귀. 실제 거리 변화 및 HIGH 신뢰도 확인|
|08:48~|다른 화면|activityVisible=false 상태에서 실제 거리 콜백 지속|
|08:48:49~09:04|화면 OFF|약 15분 동안 screenOff 실제 콜백 3,549개. 잠금 해제 뒤 일괄 수신한 값으로 집계하지 않음|
|09:04:09|정상 중지|암호화 태그 UWB stop ACK, 로컬 세션·서비스 종료|
|09:09:35 / 09:09:50|이탈·복귀 1차|시험 임계값 1.5m에서 2.32m 이탈, 0.34m 복귀 이벤트|
|09:21:37 / 09:21:53|최종 버전 이탈·복귀|사용자 이동 후 2.46m 이탈, 0.46m 복귀. 각 상태 전환 한 번 발생|
|09:24~09:28|임계거리 UI|최대 10.0m 선택 확인 후 기본 5.0m / 복귀 4.0m / 지속 3초 복원|
|09:28:15~|추적 알림 숨기기|앱 설정에서 시스템 채널을 열어 OFF. channelImportance=0, FGS 유지, 실제 거리 수신 지속|
|09:28:44~|실제 휴대폰 재부팅|모니터링 ON·자동 재개 ON·추적 알림 OFF 상태에서 adb reboot|
|09:29:57.470|첫 잠금 해제 후 자동 시작|사용자가 첫 잠금만 해제하고 앱을 열지 않음. AUTO_RESUME_REQUEST reason=BOOT 관측|
|09:29:57.508|저장 설정 복원|MONITOR_RESTORE manual=false, backend=PLATFORM, threshold=5.0, dwell=3000|
|09:30:02~09:32:~|재부팅 후 실제 수신|자동 소유자 인증·암호화 OOB 성공. 앱 비표시 상태에서 실제 UWB 수신 지속, 앱을 다시 열기 전 background 콜백 655회|
|09:32:~|화면 재확인|자동 재개 증거 확보 후 앱을 열어 실제 약 0.22m, 임계값 5m / 복귀 4m 확인. 알림 채널 OFF도 재부팅 후 유지|
|10:00~10:07 (갱신 주기 추가 빌드)|UWB 갱신 주기 120/240/600ms|앱 UI로 정상 중지(암호화 stop ACK) 후 `adb install -r`, 설정에서 절전→빠름→기본 순으로 바꿔 각각 시작. `UWB_RATE requested=effective`, 태그가 interval을 받아들여 실제 콜백 수신. 콜백 간격 **600ms(1.67/s) / 120ms(8.33/s) / 240ms(4.17/s)**, 모두 신뢰도 HIGH, 수신 중 오류 없음. 기본값은 이전 0.4.0 빌드와 같은 21콜백/5초. 끝나고 기본 240ms·모니터링 ON으로 복원. [상세](../test-results/tracking-rate-validation.txt)|
|10:03|마지막 상태 저장·표시|중지 후 메인 카드에 `마지막 상태 · 범위 안 · 1.17 m · 시각` 표시 확인|
|13:53~13:56 (리뷰 수정 빌드)|업데이트·저널·재시작|실행 중 업데이트 설치 후 저장 ON 상태로 자동 재개(`MONITOR_RESTORE manual=false`). 저널은 추가 쓰기로 326~328줄, 형식·순서 정상, 줄바꿈으로 끝남. 정상 중지(암호화 stop ACK) 후 마지막 상태 표시, 중지 직후 재시작은 13.9초 만에 첫 수신, 이벤트 0건. 시작 시 일시 실패 1건은 `OUTAGE_NOT_PUBLISHED`로만 기록, 콜백 간격 240ms. 메인 화면의 수신 횟수 줄 제거 확인, 재탐색 슬라이더 1분→2분→1분 저장·복원 확인|
|13:58~14:00 (리뷰 수정 빌드)|화면 OFF 수신|화면을 끄고 앱을 숨긴 상태에서 약 76초 동안 수신 유지, 306콜백(4.05/s, 기준 4.17/s의 97%), 신뢰도 HIGH, 오류·이벤트 0건. 화면이 꺼진 직후 약 20초간 5초당 16~20콜백으로 일부 라운드가 빠졌으나 끊기지 않음. 앱이 보이지 않을 때 UI 상태를 초당 1회로 줄여도 모든 콜백이 처리됨|
|14:02~14:11 (리뷰 수정 빌드)|이탈·복귀·범위 밖 이동 시험|사용자가 폰을 들고 정지한 태그에서 멀어졌다 돌아옴(임계값은 사용자가 시험용으로 1.5m로 낮춤). `TAG_DISTANCE_EXCEEDED` 2회(2.35m, 2.31m), `TAG_RETURNED` 2회(0.97m, 0.15m), 범위 밖 중 `TAG_DISCONNECTED` 1회, `UWB_MONITORING_ERROR` 0건. 복귀 시 자동 재인증·UWB 재시작, 끊김 중에도 이탈 상태 유지 후 복귀 1회. 상세는 `test-results/outage-policy-validation.txt`|
|14:13~14:16 (리뷰 수정 빌드)|백그라운드 생존|최근 앱에서 앱 카드를 밀어 닫아도 같은 프로세스·포그라운드 서비스가 유지되고 수신이 계속됐다(45초에 189콜백). 프로세스를 SIGKILL로 죽이면 약 1초 만에 START_STICKY로 새 프로세스가 뜨고 저장 설정으로 복원, 태그의 이전 세션이 정리되기까지 약 45초 뒤 수신 재개, 이벤트 0건. 상세는 `test-results/background-survival.txt`|
|14:29~14:31 (슬라이더 빌드, 커밋 `2e27c4a`)|복귀 거리 슬라이더|정상 중지(암호화 stop ACK) 후 설치. 메인 화면에 `복귀 거리 1.0 m` 슬라이더와 범위 표시(0.5 m ~ 1.0 m, 임계값 1.5 m일 때 임계−0.5)가 나타나고, 양 끝으로 조작해 0.5 m·1.0 m로 바뀌며 1.0 m로 복원, 설정 화면에도 같은 슬라이더 확인. 다시 시작하자 6.7초 만에 첫 수신. 임계값이 1.0 m일 때의 고정 안내 문구와 값 변경 후 실제 복귀 판정은 미확인|

현재 휴대폰은 모니터링 ON, 자동 재개 ON, 임계거리 5m, 복귀 4m, 지속 3초이며 추적 알림 채널은 숨김 상태다. 이탈·복귀 알림 채널은 별도 설정이다.

## 자동 재개와 알림의 범위

재부팅 시 Activity 화면을 강제로 띄우지 않고 추적 서비스를 시작한다. 계정 키가 자격 증명 보호 저장소에 있으므로 첫 잠금 해제가 필요하다. 사용자가 모니터링을 중지하면 저장 ON 상태도 지워져 다음 부팅에서 시작하지 않는다. BOOT/업데이트/프로세스 재생성의 정책 분기는 단위 테스트로 검증했다. 앱 업데이트 후 복원과, 2026-11-07 14시대의 ‘최근 앱에서 닫기’·SIGKILL 후 자동 복구는 실기기에서 확인했다(`test-results/background-survival.txt`). 설정의 ‘강제 중지’와 삼성 절전 기능에 의한 중지는 시험하지 않았다.

Android의 Foreground Service에는 유효한 알림이 필요하다. 앱의 ‘추적 중 알림 표시’는 해당 시스템 알림 채널 설정을 열고 실제 표시 상태를 읽는다. 채널을 숨겨도 FGS 및 거리 수신이 유지되는 것을 검증했다. OS의 ‘실행 중인 앱’ 표시는 별도로 남을 수 있다. 사용자의 강제 중지·권한 회수는 우회하지 않는다.

## 빌드 / 단위 검증

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

- BUILD SUCCESSFUL, 단위 테스트 **47개 통과**, 실패·오류 0. (2026-11-07 갱신 주기·재탐색 설정 7개, 재연결 이벤트 정책 6개 추가분 포함.) 위 10:00~10:07 시험은 커밋 `0ccfeeb`의 갱신 주기 빌드로 수행했다(설정 안내 문구 한 줄만 다름). 재연결 이벤트 정책(커밋 `0d8af20`)은 13:33에, 리뷰 수정(커밋 `61a5bc9`의 코드)은 13:53에 설치해 일부만 확인했다(아래 표, `test-results/outage-policy-validation.txt`). 현재 설치본은 `61a5bc9`의 코드다.
- 암호화 고정 벡터, 유효 거리/신선도, 디바운스·히스테리시스·중복 방지, 10m 상한, Webhook 정책, 자동 재개 정책을 검사했다.
- Lint 오류 0, 경고 1: 사용자 원본 아이콘에 별도 monochrome layer가 없다. 컬러 원본은 그대로 유지했다.
- APK debug 서명 검증 결과와 SHA-256은 `test-results/apk-signature.txt`, `artifacts/SHA256SUMS.txt`에 보관한다.
- 아이콘 원본과 패키징 PNG SHA-256: `137ab93fe092e0846f024a48354a9998af6024a225163b2d3806158502a0934c`.

## 증거 파일

- `test-results/owner-auth-phase1.txt`: 최초 실제 인증·거리
- `test-results/owner-auth-background.txt`, `owner-auth-screen-off*.txt`: 백그라운드·화면 OFF 수신
- `test-results/event-validation.json`: 실제 이탈·복귀 4건의 비식별 이벤트
- `test-results/notification-hidden-monitoring.txt`: 채널 OFF 후 서비스·거리
- `test-results/reboot-auto-resume.txt`: 재부팅 전후 저널, BOOT 자동 재개·저장 임계값·앱 비표시 실제 수신
- `test-results/notification-channel-after-reboot.txt`: 재부팅 후 채널 importance=0 유지
- `test-results/device-ui.png`: 재부팅 자동 재개 검증 뒤 연 화면
- `test-results/unit-tests.xml`, `build-validation.txt`, `lint.txt`: 빌드 검증

초기 0.1 진단 빌드의 직접 쓰기 실패/암호화 미구현 기록은 개발 이력이다. 최종 0.4.0은 소유자 키 조회·상호 인증·암호화 명령을 구현했고 실제 콜백으로 검증했다. 초기 실패를 현재 기능 상태로 해석하지 않는다.

## 아직 실기기에서 검증하지 않은 범위

|항목|상태|
|---|---|
|Tasker 수신·프로필 실행|공식 SDK 플러그인 구현, 시험 휴대폰에 Tasker 미설치로 외부 수신 미검증|
|HTTPS Webhook 서버 도착|암호화 자격 증명·영속 큐·재시도 구현, 수신 URL 미제공으로 외부 전달 미검증|
|삼성 Modes and Routines 직접 거리 조건|공식 등록 SDK 미확보. 앱 자체의 직접 루틴 제어를 주장하지 않음. 조사 결과는 [AUTOMATION_REVIEW.md](AUTOMATION_REVIEW.md)|
|SmartThings 가상 스위치 연결|**확인(2026-11-07):** 가상 스위치 생성(CLI `virtualdevices:create`), SmartThings 앱 목록 표시, 자동화 ‘만약 → 기기 상태’에서 선택 가능, CLI 이벤트로 시험 자동화가 1~2초 만에 실행, 앱의 삼성 로그인 토큰으로 읽기 200·쓰기 200과 켜기/끄기 시험이 SmartThings 상태를 실제로 변경. **실제 이탈·복귀(15:35~15:36, 시험 임계 1.5m):** `TAG_DISTANCE_EXCEEDED` 1.87m → 0.23초 뒤 `ST_SWITCH_RESULT state=on httpStatus=200`, `TAG_RETURNED` 0.92m → 0.09초 뒤 `state=off httpStatus=200`, SmartThings에서 읽은 스위치도 꺼짐(06:36:04.133Z). 로그인 토큰만 사용, 서버·OAuth 앱·알림 없이 폰 잠금과 무관한 경로. **오프라인 후 재전송(15:43):** Wi-Fi·모바일 데이터를 `svc`로 끄고(USB adb 유지) ‘스위치 끄기 시험’을 누르자 30초 동안 `ST_` 로그가 0건이었고 JobScheduler에 `CONNECTIVITY` 미충족 작업이 대기했다. 네트워크 복구 약 1.3초 뒤 `ST_SWITCH_RESULT state=off httpStatus=200 attempt=1`이 기록됐다. 스위치가 이미 꺼져 있어 SmartThings 상태·타임스탬프는 변하지 않았으므로 도달 증거는 HTTP 200이다. 시험 후 Wi-Fi·데이터 복원 확인. 오프라인 중 ON이 쌓인 뒤 OFF로 교체되는 경로와 재시도 반복은 시험하지 않았다. **미확인:** 차량 잠금 자동화와 전체 지연(사용자가 구성), 토큰 장기 유효성(401 → 갱신 → 재시도 경로 포함)|
|SmartThings 자동화 트리거|CLI 이벤트로 시험 자동화(알림)가 1~2초 만에 실행됨을 확인하고 시험 자동화는 삭제함. 차량 잠금 자동화는 사용자가 구성하며 이 저장소에서는 만들지 않음|
|앱 알림 → 삼성 루틴 ‘알림을 받으면’ 조건|조건 화면·알림 리스너·문구는 기기 앱에서 확인했으나 **루틴을 만들어 실행해 보지 않음.** 알림 접근이 꺼져 있었음|
|UWB 갱신 주기 빠름 120ms / 절전 600ms의 배터리 효과|콜백 간격은 확인했다(위). **폰·태그의 배터리 소모 변화는 측정하지 않았다.** 비충전·장시간·다른 기기/태그는 미검증|
|이탈 후 재탐색 간격 10초~5분|계산 규칙을 단위 테스트함. 기본 1분 경로에서 재시도 사다리 동작만 관측. 다른 값으로의 실제 끊김·복귀 시험은 미실시|
|중지 직후 재시작의 지연|중지 직후 시작하면 `GATT_CONNECTION_STATUS_147` 또는 `OWNER_TAG_NOT_FOUND` 후 복구되어 첫 수신까지 21~73초 걸렸다. 원인 미조사, 이전 빌드와 비교하지 않음. 당시 빌드는 첫 실패마다 `UWB_MONITORING_ERROR` 이벤트를 발행했다|
|일시적 재연결 실패의 이벤트 억제|**확인:** 시작 직후 첫 실패 1회(`OWNER_TAG_NOT_FOUND`, 13:33:39 시작 +15초)가 저널의 `OUTAGE_NOT_PUBLISHED`로만 남고 외부 이벤트는 0건, +28초에 인증·UWB 복구. 이전 빌드는 같은 실패에 `UWB_MONITORING_ERROR`를 발행했다(12:53:21). **이동 시험(14:02~14:11):** 범위 밖 outage 14:05:02 시작, 5건의 실패 중 4건은 억제, 14:07:27(145초 뒤)에 `TAG_DISCONNECTED` 1회, `UWB_MONITORING_ERROR` 0건, 이후 실패(14:08:42)도 중복 발행 없음. **미확인:** 한 번도 연결된 적 없는 태그가 실행 내내 범위 밖인 경우|
|인증 실패 시 모니터링 자동 종료|12:49:20(설치본 `0d8af20`) 태그 재접속 직후 `GATT_BUSY_RETRY` 한 번 뒤 `TAG_AUTHENTICATION_FAILED`가 나 복구 불가로 분류돼 서비스가 스스로 종료됐다. 이후 연속 3번까지 재시도하도록 고친 빌드를 설치했다. 해당 인증 검증 실패를 이번 시험에서 강제로 재현하지는 않았다. 원인 미조사, 단발성인지 반복되는지 미확인|
|AndroidX UWB backend|비교 구현 존재, 이번 실측은 플랫폼 backend|
|비충전·깊은 절전·30분 이상·배터리 소모|추가 측정 필요|
|권한 회수·Bluetooth/UWB OFF·장거리 단절 후 재접속|Bluetooth 및 UWB OFF/ON은 아래 안정성 검토 빌드에서 각각 1회 자동 복구 확인. 권한 회수·장기 반복은 미검증|
|다른 휴대폰·태그 펌웨어|미검증|


## 안정성 검토 수정 빌드 (2026-11-07, 16:01 이후)

버전은 0.4.0 / versionCode 5 유지. 같은 서명으로 덮어쓰기 설치했다. 정상 중지 때 암호화 stop ACK를 확인했으며 계정·태그 등록·선택·사용자 거리 설정·알림 채널을 유지했다. 시험 시작 시 설정은 1.5m / 복귀 1.0m / 3초, NORMAL 240ms, 재검색 최대 60초, 추적 알림 OFF, SmartThings ON이었다. USB 충전·배터리 최적화 예외 조건이다.

|시험|이번 관측|
|---|---|
|단위 회귀|68개 통과(기존 47 + 신규 21), 실패 0. 재연결 freshness 초기화, nonce/proof timeout 재시도, 기능 조회 timeout과 호출자 취소 구분, 만료 30초 경계·교체·토큰 갱신 중 만료·취소된 HTTP 직렬화·키 스냅샷 병렬 조회 포함|
|Android 계측|별도 합성 데이터 vault로 Keystore 저장/재생성·토큰 업데이트 중 요청 보존·대기 제거·만료 전송 차단 3개 통과. 사용자 vault를 읽거나 수집하지 않으며 네트워크 전송 없음|
|수정 빌드 실제 거리|16:02:40 시작 → 16:02:47 HIGH UWB 수신. 플랫폼·240ms 유지|
|Bluetooth OFF/ON|16:03:06 OFF, 8초 후 ON. `GATT_DISCONNECTED_22`/`BLUETOOTH_DISABLED` 억제, 첫 연결의 `UWB_STOPPED_0`도 재시도. 16:04:07 앱 비표시·화면 OFF에서 HIGH 수신 재개. 오류/단절 이벤트 0건|
|장기 숨김 재검색|16:04:00 BALANCED 검색 시작 → 2.8초 뒤 소유 광고 발견 → 인증·실제 수신. 소비 전력 절감은 측정하지 않음|
|UWB OFF/ON|16:04:57 OFF, 8초 후 ON. 16:05:15 앱 비표시·화면 OFF HIGH 수신 재개(약 18.2초). `UWB_STOPPED_0`은 외부 이벤트 없이 복구|
|프로세스 종료 복구|SIGKILL 뒤 16:05:54 START_STICKY 저장 ON 복원. Activity를 열지 않음. 첫 `UWB_STOPPED_0` 재시도 후 16:06:23 HIGH 수신 재개. 오류/단절 이벤트 0건|
|수정된 SmartThings 전송|이후 사용 중 실제 이탈·복귀에서 ON/OFF 각각 HTTP 200 관측. 시험 버튼을 누르거나 별도 자동화를 만들지 않음. 30초 만료·401·서버 지연은 실제 서버에서 강제로 만들지 않고 회귀 테스트로 확인|
|화면 OFF·백그라운드|연속 80.6초 구간에 screenOff 콜백 +335, 4.15/s(기본 명목 4.17/s). HIGH 수신 유지. 비충전·깊은 절전 검증은 아님|
|최종 APK 갱신|16:12:46 암호화 정상 중지 → 최종 APK 덮어쓰기 → 16:13:15 재개 → 16:13:34 HIGH 수신. UI의 30초 만료 안내·최신 라이선스 asset 포함|
|실제 재부팅 복원|16:14:55 첫 잠금 해제 후 `AUTO_RESUME_REQUEST reason=BOOT` → `MONITOR_RESTORE manual=false`. 저장된 임계값 5m 복원. 초기 GATT 147 두 번은 외부 이벤트 없이 재시도, 16:15:48 HIGH 수신 재개. 앱을 열기 전 16:19:00까지 실제 background 콜백 798개, Activity 표시 기록 없음. 채널 importance=0 유지|

검토된 증거: `test-results/review-stability-validation.txt`, `instrumentation-review.txt`, `unit-tests.xml`, `build-validation.txt`, `lint.txt`, `apk-signature.txt`.

남은 실측: 폰·태그의 비충전 수 시간 소비 전력, 깊은 절전, Tasker 실수신, 외부 Webhook 서버, 실제 SmartThings 401/지연/만료 및 차량 자동화, 다른 기기/AndroidX backend. 단위/계측 테스트의 가짜 전송을 실제 서버 전달 증거로 해석하지 않는다.

최종 상태: 모니터링 ON·부팅 자동 재개 ON(기본), 플랫폼·240ms·재검색 최대 60초, 이탈 5m / 복귀 4.5m / 3초, 추적 채널 OFF, SmartThings ON. 재부팅 때 저장된 거리 5m/4.5m를 확인한 뒤 사용자가 그대로 유지하도록 확정했다. Bluetooth·UWB 모두 ON. 시험용 계측 APK는 제거하고 사용자 앱과 데이터는 유지했다.

## 스위치 방향 수정 (2026-11-07, 16:45 설치본)

최신 사용자 요청으로 가까움/복귀 ON·이탈 OFF로 변경했다. 위의 이탈 ON·복귀 OFF 관측은 이전 방향의 기록이다. 첫 실제 거리로 초기 상태를 한 번 맞추며, 임계값 밖에서 시작하면 이탈 지속 시간을 기다린다. Tasker/Webhook의 거리 이벤트는 바꾸지 않는다.

단위 테스트 72개 통과(방향·초기 동기화 회귀 4개 추가), Lint 오류 0 / 기존 monochrome 경고 1. 같은 서명으로 덮어쓰기 후 16:45:14.949 실제 HIGH 거리 0.90m(activityVisible=false, interactive=false) 수신 → 16:45:15.781 `ST_SWITCH_RESULT state=on httpStatus=200` 확인. 임계 5m·복귀 4.5m·3초·모니터링 ON 유지. 이 수정 뒤 실제 이동으로 이탈 OFF를 다시 만들지는 않았으며 거리 엔진/매핑 단위 테스트로 검증했다. SmartThings 차량 잠금 루틴은 꺼짐 조건으로 사용자가 설정해야 한다.
