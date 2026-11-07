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
|`data/auth/SecretVault.kt`|namespace별 Keystore AES-GCM 암호화 파일|
|`data/BleDiscovery.kt`|FD5A 검색, connectable/UWB capability 확인, 소유자 광고 매핑|
|`data/SmartTagCrypto.kt`|KDF, nonce proof, 광고 서명 검증, 카운터 포함 명령 암호화|
|`data/GattTransport.kt`|직렬 GATT 작업, CCCD, MTU, 인증, 암호화 UWB 활성화/중지|
|`data/SmartTagSessionConfig.kt`|35바이트 OOB 설정과 실제 세션 주소/ID 생성|
|`data/UwbProbe.kt`|플랫폼 Ranging / AndroidX UWB 세션을 Flow로 제공, 실제 UWB 결과만 통과|
|`data/MonitorSettings.kt`|거리·알림·자동화·재개 정책 및 태그별 이탈 latch 영속화|
|`data/ProbeStore.kt`|현재 UI StateFlow, 화면 가시성, 최대 300줄 저널·보고서 내보내기|
|`domain/ProbeModels.kt`|측정·상태 모델, 유효/신선한 수신 및 화면별 카운터|
|`domain/DistanceEventEngine.kt`|Android에 의존하지 않는 이탈·복귀 상태 머신|
|`domain/ResumePolicy.kt`|BOOT / 업데이트 / 프로세스 재시작의 저장 ON 복원 조건|
|`service/ProbeService.kt`|한 태그의 검색→인증→UWB→이벤트 전체 수명주기, 재시도, FGS|
|`service/BootReceiver.kt`|BOOT_COMPLETED / MY_PACKAGE_REPLACED에서 저장 의사 확인 후 재개|
|`automation/MonitorEvents.kt`|최근 100개 이벤트, 별도 채널 알림, WorkManager HTTPS Webhook|
|`automation/TaskerEvents.kt`|공식 Tasker 이벤트 플러그인의 설정·필터·변수·명시적 전달|

## 세션 흐름

1. 사용자 시작: Activity가 권한을 확인하고 `ProbeService`를 시작한다. 서비스가 모니터링 ON과 backend를 저장한다.
2. 복원: BOOT/업데이트 receiver 또는 START_STICKY의 null intent가 저장된 ON 상태를 확인한다. 부팅은 자동 재개 설정도 확인한다.
3. 암호화 계정 캐시에서 선택 태그를 읽는다. 소유자 광고 서명에 맞는 현재 BLE 주소를 최대 15초 검색한다.
4. GATT 연결·MTU 협상·두 indication 구독 후 nonce와 proof를 교환한다. 성공한 세션에서만 명령 키를 사용한다.
5. 로컬 UWB 세션의 설정을 태그에 암호화 전송하고 실제 콜백을 기다린다. 플랫폼 결과 technology=UWB를 확인한다.
6. 유효한 측정은 수신 증거, UI, 순수 거리 엔진으로 전달한다. UI의 ON/OFF는 서비스의 실제 실행 상태와 저장된 사용자 의사를 구분한다.
7. 상태 전환 시 태그별 이탈 latch를 저장하고 이벤트를 게시한다. 이벤트 dispatcher가 켜진 출력으로만 전송한다.
8. 일시적 오류는 소유자 광고부터 다시 검색한다. 2/5/15/60초 대기 후 최대 60초 간격을 유지한다. 인증·권한 등 영구 오류는 무한 재시도로 숨기지 않는다.
9. finally에서 태그 UWB 중지를 암호화 전송하고 로컬 세션·GATT를 해제한다. 명시적 중지는 저장 ON도 false로 만든다.

서비스의 첫 측정 watchdog은 20초다. 3초 넘게 유효 측정이 없으면 거리 표시를 지우며, 20초 넘는 stale 수신은 회복 경로로 들어간다. 콜백은 화면에 계속 반영하지만 저널의 거리 요약은 약 5초 간격, FGS 알림 갱신은 약 10초 간격이다.

## 거리·이벤트 계약

- 입력: 유한한 음수 아닌 실제 UWB 거리와 monotonic `elapsedMillis`.
- `meters > threshold`가 `dwellMillis` 이상 이어질 때 `TAG_DISTANCE_EXCEEDED` 한 번 발생.
- 이미 이탈한 상태에서는 `meters <= returnDistance`일 때 `TAG_RETURNED` 한 번 발생.
- 측정 간격이 1.5초를 초과하면 진행 중 이탈 debounce를 새로 계산한다. 이전 시각 이하 콜백은 제외한다.
- 연결 소실은 debounce를 지우지만 이탈 latch를 유지한다. 재접속·서비스 복원으로 동일 이탈을 반복하지 않는다.
- `TAG_DISCONNECTED`와 `UWB_MONITORING_ERROR`는 거리 이탈과 별개다. 거리를 모르면 JSON `distanceMeters`는 null이다.
- JSON 공통 필드: `id`, `event`, `distanceMeters`, `thresholdMeters`, `timestamp`, 선택적 `tagId`(앱별 해시 별칭). 원본 클라우드 ID는 출력하지 않는다.
- Tasker: `%uwb_event`, `%uwb_distance`, `%uwb_threshold`, `%uwb_timestamp`, `%uwb_tag`. distance를 모르면 빈 문자열.
- Webhook: HTTPS, redirect 금지, 연결/읽기 각각 5초 제한, 최대 3회, 네트워크/429/5xx 재시도. 이벤트 ID와 `Idempotency-Key`를 사용하며 수신 서버의 중복 처리도 필요하다.

## 영속 상태와 수명

|저장소|내용 / 주의점|
|---|---|
|`files/account.vault`|삼성 토큰·태그 정보·소유자 키·선택 ID. `uwbpulse.account.v1` Keystore 키로 암호화|
|`files/webhook.vault`|Webhook URL/Bearer. 별도 `uwbpulse.webhook.v1` 키|
|DataStore `monitor_settings`|정책, 알림, 자동화 opt-in, monitoring_enabled, resume_after_boot, backend, away_tag_aliases|
|DataStore `probe`|초기 진단 화면의 선택 BLE 주소. 서비스의 소유 태그 식별 기준으로 사용하지 않음|
|`files/monitor-events.json`|최근 100개 비식별 이벤트. 시각과 거리는 포함|
|`files/probe-journal.txt`|최근 300줄 상태 저널. 키·토큰·원본 식별자를 기록하지 않음|
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
