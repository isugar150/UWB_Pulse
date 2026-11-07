# 구현 구조

패키지: `dev.uwbpulse.monitor`. Kotlin / Compose Material 3, `connectedDevice` FGS, DataStore, Android Keystore, WorkManager를 사용한다.

## 파일과 책임

|경로 (`app/src/main/java/dev/uwbpulse/monitor/` 기준)|역할|
|---|---|
|`MonitorApplication.kt`|계정·설정·상태 저장소·이벤트 dispatcher의 application 범위 소유|
|`MainActivity.kt`|모니터/설정 Compose 화면, 런타임 권한, 서비스 명령, OS 알림 설정 화면|
|`ui/ProbeViewModel.kt`|화면 상태 노출, 사용자 설정·계정 작업 위임|
|`AuthCallbackActivity.kt`|삼성 로그인 callback 소비 및 화면 복귀|
|`data/auth/SamsungLogin.kt`|삼성 로그인 포맷, PKCE/state, 암호화 로그인 응답 처리|
|`data/auth/SamsungHttp.kt`|HTTPS/host 검증, 응답 크기·시간 제한, 삼성 API 요청|
|`data/auth/SamsungAccountRepository.kt`|토큰 갱신, 기존 소유 태그·키 조회/캐시, 태그 선택, 광고 매핑|
|`data/auth/OwnerCredentialCache.kt`|불변 소유자 키 스냅샷. BLE/서비스의 키 조회가 계정 네트워크 잠금을 기다리지 않도록 분리|
|`data/auth/SecretVault.kt`|namespace별 Keystore AES-GCM 암호화 파일|
|`data/BleDiscovery.kt`|FD5A 검색, connectable/UWB capability 확인, 소유자 광고 매핑|
|`data/SmartTagCrypto.kt`|KDF, nonce proof, 광고 서명 검증, 카운터 포함 명령 암호화|
|`data/GattTransport.kt`|직렬 GATT 작업, CCCD, MTU, 인증, 암호화 UWB 활성화/중지|
|`data/SmartTagSessionConfig.kt`|35바이트 OOB 설정과 실제 세션 주소/ID 생성|
|`data/UwbProbe.kt`|플랫폼 Ranging / AndroidX UWB 세션을 Flow로 제공, 실제 UWB 결과만 통과|
|`data/MonitorSettings.kt`|거리·알림·자동화·재개 정책 및 태그별 이탈 latch 영속화|
|`data/ProbeStore.kt`|현재 UI StateFlow, 화면 가시성, 최근 300줄 저널(추가 쓰기, 600줄에서 정리)·보고서 내보내기|
|`domain/ProbeModels.kt`|측정·상태 모델, 유효/신선한 수신 및 화면별 카운터|
|`domain/DistanceEventEngine.kt`|Android에 의존하지 않는 이탈·복귀 상태 머신|
|`domain/TrackingPolicy.kt`|UWB 갱신 주기(120/240/600ms, 기본 600ms)와 거기서 파생되는 공백·신선도 한계, 재탐색 대기 규칙|
|`domain/FailurePolicy.kt`|연결 실패 분류(재시도 가능·링크 손실)와 `OutageGate`: 일시적 재연결 실패를 외부 이벤트에서 제외. 연결 상태 콜백의 status 8(감독 타임아웃)은 영구 오류가 아니라 링크 손실|
|`domain/ResumePolicy.kt`|BOOT / 업데이트 / 프로세스 재시작의 저장 ON 복원 조건|
|`domain/ConnectionTimeout.kt`|개별 연결 시간 초과만 오류로 변환하고 호출자 취소는 그대로 전달|
|`domain/SwitchPresenceSync.kt`|시작/연동 활성화 후 첫 실제 거리로 스위치 상태를 한 번 동기화. 이탈 debounce와 복귀 latch 유지. 연결 소실이 `PresencePolicy` 기준을 넘으면 스위치만 한 번 끄고, 복귀 거리 안의 실제 거리로 다시 켠다(거리 엔진은 건드리지 않음)|
|`domain/CarMode.kt`|차량 블루투스 모드의 순수 로직: `CarLink`(ACL·A2DP·HFP를 하나의 연결 여부로 합침. ACL 끊김은 프로파일도 모두 끊김으로 처리), `CarModeMachine`(CAR_CONNECTED → TRACKING → AWAY, 차량 없이 시작하면 WAITING, 차량 재연결이 항상 우선)|
|`debug/DebugCommandReceiver.kt`|adb 전용 개발 명령: 추적 주기, 차량 기기·모드, SmartThings 연동·펄스·연결 소실 끄기, 모니터링 시작·중지. `android.permission.DUMP`로 보호(shell만 보냄), 자격 증명은 다루지 않음|
|`data/CarBluetooth.kt`|등록된 기기 목록, 차량 연결 여부 초기 조회(프로파일 프록시), ACL·프로파일 상태 수신기(반드시 `RECEIVER_EXPORTED`: 블루투스 스택은 uid 1002로 보내 비공개 수신기는 받지 못한다). LE ACL은 무시하고 주소·이름은 로그에 남기지 않음|
|`domain/DepartureJudge.kt`|차량 모드 창의 이탈 판정(마지막 실제 거리 또는 창 시작부터의 무음 시간 + `PresencePolicy` 한계). 시간선 단위 테스트용 순수 클래스|
|`domain/PresencePolicy.kt`|‘멀어짐으로 본다’의 기준: 마지막 실제 거리 후 30초, 링크 손실 outage, Bluetooth ON. 순수 함수|
|`domain/SwitchDelivery.kt`|SmartThings 요청 30초 만료·최신 요청·전송 직렬화와 취소 확인|
|`service/ProbeService.kt`|한 태그의 검색→인증→UWB→이벤트 전체 수명주기, 재시도, FGS|
|`service/BootReceiver.kt`|BOOT_COMPLETED / MY_PACKAGE_REPLACED에서 저장 의사 확인 후 재개|
|`automation/MonitorEvents.kt`|최근 100개 이벤트, 별도 채널 알림, WorkManager HTTPS Webhook|
|`automation/SmartThingsSync.kt`|가상 스위치 이벤트 전송 Worker(저장된 삼성 로그인 토큰 사용, 401이면 한 번 갱신 후 재시도), 선택 사항인 OAuth 토큰 저장·갱신과 일일 갱신 Worker|
|`domain/SmartThingsSwitch.kt`|가상 스위치 요청·토큰 응답 처리(순수 함수), 이벤트→ON/OFF 매핑|
|`automation/TaskerEvents.kt`|공식 Tasker 이벤트 플러그인의 설정·필터·변수·명시적 전달|

## 세션 흐름

1. 사용자 시작: Activity가 권한을 확인하고 `ProbeService`를 시작한다. 서비스가 모니터링 ON과 backend를 저장한다.
2. 복원: BOOT/업데이트 receiver 또는 START_STICKY의 null intent가 저장된 ON 상태를 확인한다. 부팅은 자동 재개 설정도 확인한다.
3. 암호화 계정 캐시에서 선택 태그를 읽는다. 소유자 광고 서명에 맞는 현재 BLE 주소를 최대 15초 검색한다.
4. GATT 연결·MTU 협상·두 indication 구독 후 nonce와 proof를 교환한다. 성공한 세션에서만 명령 키를 사용한다.
5. 로컬 UWB 세션의 설정을 태그에 암호화 전송하고 실제 콜백을 기다린다. 플랫폼 결과 technology=UWB를 확인한다.
6. 유효한 측정은 수신 증거, UI, 순수 거리 엔진으로 전달한다. UI의 ON/OFF는 서비스의 실제 실행 상태와 저장된 사용자 의사를 구분한다.
7. 상태 전환 시 태그별 이탈 latch를 저장하고 이벤트를 게시한다. 이벤트 dispatcher가 켜진 출력으로만 전송한다.
8. 일시적 오류는 소유자 광고부터 다시 검색한다. 2/5/15/60초로 늘리며 설정한 재탐색 최대 간격(기본 60초, 10초~5분)에서 멈춘다. 갱신 주기는 세션 시작 때 한 번 읽어 OOB 설정에 넣으므로 서비스 실행 중에는 바뀌지 않는다. 인증·권한 등 영구 오류는 무한 재시도로 숨기지 않는다.
9. finally에서 태그 UWB 중지를 암호화 전송하고 로컬 세션·GATT를 해제한다. 명시적 중지는 저장 ON도 false로 만든다.

서비스의 첫 측정 watchdog은 20초다. 3초(절전 600ms에서도 3초) 넘게 유효 측정이 없으면 거리 표시를 지우며, 20초 넘는 stale 수신은 회복 경로로 들어간다. 앱이 보이면 콜백마다 UI 상태를 갱신하고, 화면 OFF·비표시에서는 상태가 바뀔 때를 빼고 초당 1회만 갱신한다. 저널의 거리 요약은 약 5초 간격, FGS 알림 갱신은 약 10초 간격, 마지막 상태 저장은 1분 간격·이벤트·중지 시다. 거리 이벤트 판정은 모든 유효 콜백에 적용한다.

새 무선 세션은 수신 freshness 시각을 초기화하고 자체 첫 측정 20초를 갖는다(누적 진단 카운터는 유지). 이전 세션의 오래된 수신 때문에 새 세션이 1초 만에 종료되지 않는다. nonce/proof 응답 시간 초과는 링크 손실로 재시도하며 proof 검증 실패의 연속 3회 제한과 구분한다. 플랫폼 기능 조회의 5초 초과는 `UWB_CAPABILITIES_TIMEOUT` 재시도로 분류한다. 사용자/OS 취소는 재시도로 바꾸지 않는다.

초기 3번의 검색과 화면에 보이는 검색은 LOW_LATENCY를 유지한다. 숨겨진 상태에서 3번 이상 실패한 재검색은 BALANCED로 낮춘다. 15초 검색 창·사용자의 재탐색 최대 간격은 유지한다. 기본 UWB 갱신은 2026-11-08에 240ms에서 600ms로 낮췄다(저장된 선택은 유지). 첫 실제 거리 뒤에는 BLE 연결 우선순위를 LOW_POWER로 요청해 태그의 연결 이벤트를 줄인다(`GATT_LOW_POWER` 저널; 태그 소비 전력 효과는 미측정). FGS 알림은 내용이 달라질 때만 다시 게시한다. 이 변경의 실제 소비 전력과 BALANCED 장기 재진입 지연은 별도 측정 대상이다.

계정 로그인·갱신·로그아웃은 IO 스레드의 공통 잠금으로 직렬화하고, 광고 검증/인증용 키는 독립 불변 스냅샷에서 읽는다. 토큰 갱신 네트워크 지연이 메인 스레드의 키 조회를 막지 않는다.

이벤트 dispatcher는 단일 Channel 소비자로 발행 순서를 유지한다. SmartThings는 최신 `SwitchRequest`(발생 시각·대상·시험 여부 포함)를 기존 암호화 vault에 보관하고 WorkManager에는 요청 ID만 넣는다. 전송 직렬화 잠금은 취소된 작업의 동기 HTTP가 실제 반환할 때까지 유지한다. 30초 이상/미래 시각/교체·비활성화·대상 변경 요청은 폐기하며, 401 갱신 후와 연결 설정 후 body 전송 직전에 재검사한다. 만료는 `ST_SWITCH_DROPPED`에 남긴다. 기기에 도착하기 전에 이미 서버에 전송된 동작의 취소나 서버 내부 처리 순서는 보장하지 않는다.

## 차량 블루투스 모드 (2026-11-08, 기기 미검증)

설계·규칙·값·검증 현황·위험을 한곳에 정리한 문서는 [CAR_MODE.md](CAR_MODE.md)다. 아래는 구조 관점의 요약이다.

설정 `car_mode`와 선택한 기기(`car_address`, `car_name`)가 모두 있을 때만 켜지며, 아니면 지금까지의 연속 추적이다. `ProbeService.monitor`는 서비스 수준(저장 ON, FGS, 종료)만 맡고 실제 추적은 `track`(한 번의 추적 창)이 한다. 연속 모드는 창 하나가 서비스 수명과 같다.

1. 차량이 폰에 연결돼 있으면(`CAR_PHASE car linked`) 태그 검색·GATT·UWB를 모두 하지 않는다. 스위치를 한 번 켜고 `awayTagAliases`의 이탈 latch를 지운다.
2. 차량이 **연결돼 있다가** 끊기면 소유자 광고 검색을 3초 확인과 함께 시작한다. 3초 뒤에도 끊겨 있으면 추적 창을 열고 준비한 결과를 첫 연결에만 사용한다. 이후 재시도는 새 광고를 찾는다. 확인 중 차량 재연결·서비스 중지 시 검색을 취소하며 GATT·인증·UWB·이벤트는 확인 뒤에만 진행한다. 서비스를 시작할 때 이미 차량이 연결돼 있지 않았다면(`CAR_PHASE started without the car link`) 이탈이 아니므로 검색도 하지 않고 첫 차량 연결을 기다린다(스위치도 바꾸지 않는다). 창은 기존 `track`과 같다. 연결 소실 판정의 기준 시각은 3초 확인 뒤 창이 열린 순간이다.
3. 이탈은 실제 이탈 이벤트(임계거리 초과 + 지속 시간) 또는 측정 후 8초 소실로 확정한다. 한 번도 측정하지 못한 채 `NEVER_MEASURED_MILLIS`(60초)가 지나면 기본적으로 창만 닫고 스위치 상태를 유지한다. 설정 `car_unmeasured_departure`(기본 OFF)를 켰을 때만 60초 미측정 이탈 신호와 안내를 보낸다. 미측정 창은 오류로 종료되어도 이 옵션을 우회할 수 없고, 옵션 ON이어도 60초 전 신호는 없다. 실제 거리 이벤트와 측정 후 8초 소실·연속 모드 30초는 그대로다. 창을 닫은 뒤에는 차량이 다시 연결될 때까지 태그에 접근하지 않는다.
4. 영구 오류로 추적 창이 끝나도 차량 모드 서비스는 유지한다. 미측정이면 위의 별도 옵션·60초 기준에 따라 신호를 제한한다.
5. 창이 도는 중 차량이 다시 연결되면(블루투스 순간 끊김) 창을 취소하고 1로 돌아간다.

**서비스 재시작(2026-11-08)**: 차량 단계를 저장해(`car_phase`, `car_phase_at`) 재시작 때 놓친 이탈을 되살린다. 단계가 바뀔 때와, 차량이 연결돼 있거나 창이 열려 있는 동안 1분마다(`CarRestore.HEARTBEAT_MILLIS`) 저장한다. 재시작하면 `CarRestore.start`가 판단한다.
- 저장이 `CAR_CONNECTED`/`TRACKING`이고 15분(`FRESH_MILLIS`) 안이면 ‘재시작 직전까지 차량이 연결돼 있었다’고 본다. 지금 차량이 연결돼 있지 않으면 놓친 이탈로 보고 추적 창을 연다(기준 시각은 지금; 이미 60초가 지난 것으로 보지 않는다). 연결돼 있으면 그냥 대기한다.
- 저장이 `AWAY`면 나이와 무관하게 이탈 상태로 시작해 차량이 연결될 때까지 쉰다.
- 저장이 오래됐거나 없으면(재부팅이 오래 뒤, 첫 시작 등) 판단하지 않고 ‘차량 없이 시작’처럼 대기한다. 주차 뒤 한참 있다가 재부팅해도 이탈 신호가 나가지 않는다.
- 사용자가 직접 중지하면(`STOP`) 저장을 지운다. 차량 모드를 끄거나 차량 기기를 바꿔도 지운다. 충돌·OS 종료·업데이트만 저장을 이어받는다.
- 기기 확인: 대기 상태에서 프로세스를 죽이면 `START_STICKY`로 되살아나 `CAR_RESTORE saved=WAITING`을 읽고 다시 대기했다. 놓친 이탈을 되살리는 경로는 단위 테스트만(실제 차량 연결 필요).

이 모드의 한계: 차량이 연결돼 있는 동안은 거리를 재지 않으므로 Tasker·Webhook의 `TAG_RETURNED`는 만들어지지 않는다(차량 재연결은 스위치만 켠다). 차 안에서 시동만 끄고 머무르면 창이 열려 측정한다. 태그를 한 번도 측정하지 못하면 기본적으로 60초 뒤 창만 닫으므로 잠금 신호는 없다. 저널: `CAR_LINK`, `CAR_PHASE`, `CAR_TRACKING_STOPPED`, `CAR_UNMEASURED_NO_SIGNAL`.

## 태그가 UWB를 거부할 때 (2026-11-08)

소유자 인증까지는 되는데 태그가 UWB를 켜지 못하는 경우(11-07 17시대의 ACTIVATE `GATT_WRITE_STATUS_133` 반복)는 태그 배터리 부족일 가능성이 있다고 사용자가 전했다. 확정은 아니다.

- 판정: 한 연결에서 인증 성공 뒤 거리를 한 번도 못 받고 `FailurePolicy.isUwbRefused`(GATT 쓰기 상태 오류, 쓰기 재시도 소진, `UWB_STOPPED_*`, `UWB_OPEN_FAILED_*`, `UWB_CLOSED_*`)로 끝나면 연속 거부로 센다. 거리를 받으면 0으로 돌아간다. `NO_UWB_REPORTS_20S`·`UWB_REPORTS_STALE_20S`는 세지 않는다(닫힌 문도 같은 증상).
- 연속 2회(`UWB_REFUSALS_BEFORE_NOTICE`)면 저널 `TAG_UWB_UNAVAILABLE`과 폰 알림(‘태그가 UWB를 시작하지 못합니다 … 배터리 확인’)을 한 번 낸다.
- 재시도 간격을 `UWB_REFUSED_BACKOFF_MILLIS`(5분) 이상으로 늘린다. 실패한 활성화도 약한 배터리를 더 쓰기 때문이다(사용자 재탐색 한계가 더 길면 그 값).
- 차량 모드 창은 미측정 60초에 종료한다. 이탈 신호·잠금 안내는 별도 미측정 옵션 ON일 때만 적용한다. UWB 거부 자체의 상태 안내는 유지한다.
- 태그 배터리 잔량은 읽을 수 없다. 삼성 클라우드 태그 기록과 공개 API 상태 모두 `battery` 기능 이름만 있고 값이 없다(`TAG_CLOUD_FIELDS` 저널, `smartthings devices:status` 확인). 그래서 실패 신호로만 안내한다.
- 미구현 후보: UWB는 포기하고 인증된 BLE 연결을 유지해 연결이 끊기면 이탈로 보는 대체 모드(거리 환산 없음, 연결 유지 시간만 증거).

## 이탈 신호: 켰다 끄기

이탈이 확정되면(실제 이탈 이벤트, 연결 소실 규칙, 차량 모드의 8초·60초 규칙) SmartThings에 `pulse`를 보낸다: `on` → 1.5초(`SwitchPulse.GAP_MILLIS`) → `off`. 이미 꺼진 스위치에 `off`만 보내면 상태가 바뀌지 않아 ‘꺼지면’ 자동화가 다시 실행되지 않기 때문이다. 한 요청(`SwitchRequest.state = pulse`)이 두 단계를 모두 맡아 새 요청이 대기 중인 `on`을 교체하지 못하게 하며, 각 단계는 기존 30초 만료·최신 요청·직렬화 검사를 거친다. `on`이 실패하면 `off`를 보내지 않고 재시도한다(재시도는 `on`부터 다시). 시작 동기화(`on`/`off`)와 차량 재연결(`on`)은 펄스가 아니다. 설정 `smartthings_pulse_on_departure`(기본 ON)를 끄면 `off`만 보낸다. 켜짐 조건의 자동화가 있으면 이탈마다 한 번 실행된다. 기기 미검증(단위 테스트 `SwitchPulseTests`).

## 거리·이벤트 계약

- 입력: 유한한 음수 아닌 실제 UWB 거리와 monotonic `elapsedMillis`.
- `meters > threshold`가 `dwellMillis` 이상 이어질 때 `TAG_DISTANCE_EXCEEDED` 한 번 발생.
- 이미 이탈한 상태에서는 `meters <= returnDistance`일 때 `TAG_RETURNED` 한 번 발생.
- 측정 간격이 `TrackingRate.maxGapMillis`(기본 1.5초, 절전 600ms는 1.8초)를 초과하면 진행 중 이탈 debounce를 새로 계산한다. 이전 시각 이하 콜백은 제외한다.
- 연결 소실은 debounce를 지우지만 이탈 latch를 유지한다. 재접속·서비스 복원으로 동일 이탈을 반복하지 않는다.
- `TAG_DISCONNECTED`와 `UWB_MONITORING_ERROR`는 거리 이탈과 별개다. 거리를 모르면 JSON `distanceMeters`는 null이다.
- **연결 소실과 스위치(2026-11-08)**: 차 문이 닫히면 전파가 막혀 마지막 거리가 가까운 채 끊기므로 거리 엔진만으로는 이탈이 나오지 않는다. 마지막 실제 거리 뒤 `PresencePolicy.LOST_AFTER_MILLIS`(30초) 동안 측정이 없고, 현재 outage가 링크 손실(`FailurePolicy.isLinkLoss`)이며, Bluetooth가 켜져 있고, 엔진이 이미 이탈 상태가 아니면 SmartThings 스위치만 한 번 끈다(`PRESENCE_LINK_LOST`). 이후 실제 거리가 복귀 거리 이하이면 한 번 켠다(`PRESENCE_RESTORED`). 그 사이 실제 이탈/복귀 이벤트가 나오면 이벤트가 스위치를 다시 맡는다. 설정 `smartthings_off_on_link_loss`(기본 ON)로 끈다. `TAG_DISTANCE_EXCEEDED`·`TAG_RETURNED`·Tasker·Webhook·알림은 이 규칙의 영향을 받지 않으며 `TAG_DISCONNECTED`는 여전히 120초 뒤에만 나간다. 기기 미검증.
- 연결 실패는 모두 저널·UI에 남기지만 외부 이벤트는 `OutageGate`가 정한다. outage는 첫 실패에서 시작해 다음 유효 측정에서 끝나며 이벤트는 outage당 최대 한 번이다. 복구 가능한 실패가 `FailurePolicy.GRACE_MILLIS`(120초) 미만이면 아무것도 내보내지 않는다.
  - `TAG_DISCONNECTED`: 이번 실행에서 태그 인증을 마친 적이 있고 outage가 120초 이상 이어질 때. 복구 불가 실패는 즉시.
  - `UWB_MONITORING_ERROR`: 사용자가 조치할 실패(Bluetooth/UWB OFF 등)가 120초 이상 이어질 때, 복구 불가 실패는 즉시. 태그가 범위 밖이거나 링크가 끊긴 경우(`OWNER_TAG_NOT_FOUND`, `GATT_*`, UWB 수신 중단)는 오류 이벤트를 내지 않는다.
  - 억제된 실패는 저널의 `OUTAGE_NOT_PUBLISHED`로 확인한다.
  - 링크 손실로 보는 것은 태그 미발견, 연결·쓰기·탐색 상태 오류, 연결 해제, UWB 수신 중단뿐이다. MTU 부족·탐색 거부 같은 다른 GATT 실패는 사용자 조치 대상으로 보아 120초 뒤 오류 이벤트를 낸다.
  - `TAG_AUTHENTICATION_FAILED`는 연속 3번까지 재시도한다. 4번째 연속 실패는 복구 불가로 즉시 발행하고 서비스가 멈춘다. 다음 유효 측정에서 횟수를 초기화한다.
- JSON 공통 필드: `id`, `event`, `distanceMeters`, `thresholdMeters`, `timestamp`, 선택적 `tagId`(앱별 해시 별칭). 원본 클라우드 ID는 출력하지 않는다.
- Tasker: `%uwb_event`, `%uwb_distance`, `%uwb_threshold`, `%uwb_timestamp`, `%uwb_tag`. distance를 모르면 빈 문자열.
- Webhook: HTTPS, redirect 금지, 연결/읽기 각각 5초 제한, 최대 3회, 네트워크/429/5xx 재시도. 이벤트 ID와 `Idempotency-Key`를 사용하며 수신 서버의 중복 처리도 필요하다.

## 영속 상태와 수명

|저장소|내용 / 주의점|
|---|---|
|`files/account.vault`|삼성 토큰·태그 정보·소유자 키·선택 ID. `uwbpulse.account.v1` Keystore 키로 암호화|
|`files/webhook.vault`|Webhook URL/Bearer. 별도 `uwbpulse.webhook.v1` 키|
|`files/smartthings.vault`|SmartThings 클라이언트 ID·비밀, 액세스/갱신 토큰, 가상 스위치 기기 ID. 별도 `uwbpulse.smartthings.v1` 키|
|DataStore `monitor_settings`|정책, 알림, 자동화 opt-in, monitoring_enabled, resume_after_boot, backend, away_tag_aliases, tracking_rate(미설정이면 SAVER), rescan_max_millis, smartthings_off_on_link_loss, last_meters / last_seen_epoch(마지막 상태 한 건)|
|DataStore `probe`|초기 진단 화면의 선택 BLE 주소. 서비스의 소유 태그 식별 기준으로 사용하지 않음|
|`files/monitor-events.json`|최근 100개 비식별 이벤트. 시각과 거리는 포함|
|`files/probe-journal.txt`|최근 300줄 상태 저널(줄을 덧붙이다 600줄이 되면 300줄로 정리). 키·토큰·원본 식별자를 기록하지 않음|
|cache reports|사용자 요청 시 FileProvider로 공유하는 보고서|
|WorkManager DB|선택한 Webhook의 영속 전송 작업. 실제 전달 시점은 OS/네트워크에 의존|

manifest에서 backup/기기 전송을 제외한다. 재부팅 전 첫 잠금 해제 이전에는 계정 캐시에 의존하는 추적을 시작하지 않는다. 앱 삭제 또는 다른 서명 설치는 기존 Keystore와 데이터를 유지하는 업데이트가 아니다.

## 프로토콜 수정 시 확인할 불변 조건

- 소유자 API의 URL-safe Base64 공유 비밀은 16 또는 32바이트를 허용하고 **첫 16바이트**를 master로 사용한다.
- KDF: `SHA256(master16 || 00 00 00 01 || label)[0:16]`. auth label은 `bleAuthentication`, command label은 태그 nonce, 광고 서명 label은 `signing`이다.
- 상호 proof의 IV 방향과 동일 nonce 반사 거절, proof 재사용 거절을 유지한다.
- 명령 cleartext는 `LE32(counter) || command`; 세션 키/태그 nonce IV로 AES-CBC/PKCS5Padding한다. 첫 counter=1이며 ATT 성공 후 증가한다. busy 재시도는 같은 ciphertext를 사용한다.
- 35바이트 OOB의 채널·프리앰블·주소/ID 배치를 임의 변경하지 않는다. 암호문은 48바이트이므로 MTU 제약을 유지한다. 단순 쓰기 성공과 실제 거리 수신을 구분한다.
- 인증 후·해제 시 비밀 배열을 정리하고 암호화 고정 벡터 테스트를 유지한다.

정확한 출처와 GPL 파생 범위는 [FEASIBILITY.md](FEASIBILITY.md), [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)를 참고한다.

## SmartThings 존재 상태 (최신 사용자 요청)

가까움/복귀는 ON, 이탈은 OFF다. Tasker/Webhook의 이벤트 이름과 거리 판정은 유지한다. 시작/연동 활성화 뒤 첫 유효 측정에서 한 번 초기 상태를 전송하되, 최초 측정이 임계값 밖이고 아직 이탈하지 않았으면 지속 시간을 기다린다. 초기 동기화는 거리 이벤트·알림·Tasker/Webhook을 새로 만들지 않고 동일 dispatcher Channel에 넣어 상태 전환과 전송 순서를 지킨다. 현재 세션 안의 재연결마다 중복 동기화하지 않는다. Worker는 매핑 revision=2인 작업만 허용해 이전 방향으로 만든 대기를 폐기한다.
