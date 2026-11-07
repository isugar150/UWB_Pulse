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
|`domain/TrackingPolicy.kt`|UWB 갱신 주기(120/240/600ms)와 거기서 파생되는 공백·신선도 한계, 재탐색 대기 규칙|
|`domain/FailurePolicy.kt`|연결 실패 분류(재시도 가능·링크 손실)와 `OutageGate`: 일시적 재연결 실패를 외부 이벤트에서 제외|
|`domain/ResumePolicy.kt`|BOOT / 업데이트 / 프로세스 재시작의 저장 ON 복원 조건|
|`domain/ConnectionTimeout.kt`|개별 연결 시간 초과만 오류로 변환하고 호출자 취소는 그대로 전달|
|`domain/SwitchPresenceSync.kt`|시작/연동 활성화 후 첫 실제 거리로 스위치 상태를 한 번 동기화. 이탈 debounce와 복귀 latch 유지|
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

초기 3번의 검색과 화면에 보이는 검색은 LOW_LATENCY를 유지한다. 숨겨진 상태에서 3번 이상 실패한 재검색은 BALANCED로 낮춘다. 15초 검색 창·사용자의 재탐색 최대 간격·기본 UWB 240ms는 유지한다. FGS 알림은 내용이 달라질 때만 다시 게시한다. 이 변경의 실제 소비 전력과 BALANCED 장기 재진입 지연은 별도 측정 대상이다.

계정 로그인·갱신·로그아웃은 IO 스레드의 공통 잠금으로 직렬화하고, 광고 검증/인증용 키는 독립 불변 스냅샷에서 읽는다. 토큰 갱신 네트워크 지연이 메인 스레드의 키 조회를 막지 않는다.

이벤트 dispatcher는 단일 Channel 소비자로 발행 순서를 유지한다. SmartThings는 최신 `SwitchRequest`(발생 시각·대상·시험 여부 포함)를 기존 암호화 vault에 보관하고 WorkManager에는 요청 ID만 넣는다. 전송 직렬화 잠금은 취소된 작업의 동기 HTTP가 실제 반환할 때까지 유지한다. 30초 이상/미래 시각/교체·비활성화·대상 변경 요청은 폐기하며, 401 갱신 후와 연결 설정 후 body 전송 직전에 재검사한다. 만료는 `ST_SWITCH_DROPPED`에 남긴다. 기기에 도착하기 전에 이미 서버에 전송된 동작의 취소나 서버 내부 처리 순서는 보장하지 않는다.

## 거리·이벤트 계약

- 입력: 유한한 음수 아닌 실제 UWB 거리와 monotonic `elapsedMillis`.
- `meters > threshold`가 `dwellMillis` 이상 이어질 때 `TAG_DISTANCE_EXCEEDED` 한 번 발생.
- 이미 이탈한 상태에서는 `meters <= returnDistance`일 때 `TAG_RETURNED` 한 번 발생.
- 측정 간격이 `TrackingRate.maxGapMillis`(기본 1.5초, 절전 600ms는 1.8초)를 초과하면 진행 중 이탈 debounce를 새로 계산한다. 이전 시각 이하 콜백은 제외한다.
- 연결 소실은 debounce를 지우지만 이탈 latch를 유지한다. 재접속·서비스 복원으로 동일 이탈을 반복하지 않는다.
- `TAG_DISCONNECTED`와 `UWB_MONITORING_ERROR`는 거리 이탈과 별개다. 거리를 모르면 JSON `distanceMeters`는 null이다.
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
|DataStore `monitor_settings`|정책, 알림, 자동화 opt-in, monitoring_enabled, resume_after_boot, backend, away_tag_aliases, tracking_rate, rescan_max_millis, last_meters / last_seen_epoch(마지막 상태 한 건)|
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
