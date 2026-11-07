# SmartTag2 독립 앱 구현 검증 — 2026-11-07

## 현재 결론

**기술적으로 구현 가능하며, 실제 기기에서 성공했다.** Samsung SM-F971N / Android 17 / One UI 9와 사용자의 기존 등록 태그에서 소유자 상호 인증, 명령 암호화, UWB 세션, 실제 거리 변화 및 화면 OFF 수신을 확인했다. 최초 진단 빌드에서 미구현으로 남겼던 암호화 OOB는 0.2.0 이후 실제 구현으로 교체했다.

기존 SmartThings/Samsung Find의 앱 데이터나 Keystore를 꺼내거나, 수정 앱을 설치하거나, 태그를 재등록할 필요가 없었다. 사용자 본인이 삼성 로그인 화면에서 인증한 세션으로 자신의 장치 정보와 키를 조회했다. 시스템 무결성을 변경하지 않았다.

## 독립 구현 경로

1. uTag에 문서화된 삼성 계정 웹/네이티브 로그인 호환 흐름: 임의 state, PKCE, 암호화된 응답 검증 후 `iot.client` 토큰 획득.
2. 로그인한 소유자의 `client.smartthings.com/devices/` 및 필요 시 `device/<id>`에서 기존 태그와 `bleD2D.encryptionKey`, privacy IV를 조회.
3. URL-safe Base64로 공유 비밀을 해독하고 첫 16바이트를 owner master secret으로 사용. 광고 서명으로 회전 BLE 주소를 매핑.
4. Authentication Service에 양쪽 nonce 교환 및 AES 기반 상호 증명. 동일 nonce 반사와 잘못된 증명은 거절.
5. 태그 nonce로 세션 command key를 파생. 단조 증가하는 little-endian 명령 카운터와 명령을 AES-CBC/PKCS5Padding으로 암호화.
6. UWB 활성화와 35바이트 세션 설정을 암호화해 전송. Android의 실제 ranging 결과만 거리로 사용.

알고리즘과 프레이밍은 [Yu et al., USENIX Security 2024](https://www.usenix.org/conference/usenixsecurity24/presentation/yu-tingfeng)의 프로토콜 연구를 기반으로 독립 구현했다. 사용자의 설치된 SmartThings 1.8.52.23에서도 KDF·nonce·command counter 구조를 정적 분석해 대조했다. 해당 APK/디컴파일 소스는 배포하지 않는다. 테스트의 고정 암호화 값은 Python hashlib와 OpenSSL로 별도 계산했다.

[uTag BLE Commands](https://github.com/KieronQuinn/uTag/wiki/BLE-Commands)가 명령 암호화를 SmartThings에 맡긴다는 사실은 **독립 구현이 불가능하다는 뜻이 아니었다.** 추가 조사로 위 프로토콜과 소유자 API 경로를 확보해 구현했다. 초기의 `SAMSUNG_ENCRYPTED_OOB_REQUIRED` 중단 경계는 현재 사용하지 않는다.

## uTag 재사용과 One UI 비호환 원인

uTag 참조 revision: `6152f89b02d41476cbf923fb411e6a62a1303881`.

- `UwbConfig`/`LengthByteArray`: SmartTag OOB 비트 배치, 채널 9, 프리앰블 9~12, static STS, responder/controlee 설정을 이용했다.
- `JetpackUwbRepository`/`PlatformUwbRepository`: AndroidX와 API 36+ RAW responder 세션을 비교하는 기반으로 사용했다.
- `ConnectedTagConnection`: FD5A 및 UWB activation/parameters 특성을 확인했다. upstream의 수정 SmartThings Binder 서비스는 사용하지 않는다.
- 로그인 포맷과 client 헤더는 uTag 계정 인증 구현을 참조·수정했다. GPL 고지는 소스와 APK에 포함한다.

uTag `isOneUICompatible()`은 숫자 One UI 버전이 아니라 `com.sec.android.permission.PERSONAL_MEDIA`의 선언자를 검사한다. 사용자 기기에서는 MediaShare 시스템 앱이 해당 권한을 선언해 false 조건을 재현했다. 설치된 SmartThings의 `BIND_SMARTTAG_SUPPORT_SERVICE`도 signature|privileged라 일반 앱의 직접 Binder 접근 경로로 사용할 수 없었다. 본 앱은 이 Binder 의존성을 제거한 독립 소유자 프로토콜을 사용한다.

초기 평문 GATT 실험에서는 147 연결 시간 초과와 132(GATT_BUSY)를 관측했다. 이 상태 코드만으로 암호 인증 실패를 확정하지 않았다. 이후 상호 인증·암호 명령을 구현한 경로에서 실제 ranging 성공을 확인했다.

## Android API 선택 / 백그라운드

기본은 **Android 16+ Ranging RAW UWB 전용**이다. RSSI·다른 ranging 기술로 fallback하지 않으며 callback technology도 UWB인지 확인한다. AndroidX UWB 1.0.0은 비교용 backend로 남겼지만 이 기기의 실측 성공 기록은 플랫폼 backend다.

[Android UWB 문서](https://developer.android.com/develop/connectivity/uwb)는 세션 유지와 백그라운드 보고 전달이 별개임을 설명한다. [Ranging 문서](https://developer.android.com/develop/connectivity/ranging)는 지원 기기의 UWB 백그라운드 ranging을 다룬다. 문서의 가능성만으로 판단하지 않고 `activityVisible=false`, `interactive=false`인 동안 실제 callback과 수신 횟수가 증가하는 것을 기기로 검증했다.

사용한 FGS 유형은 `connectedDevice`다. 잠금 화면 위 Activity, private API, UWB용 wake lock, 루팅으로 정책을 우회하지 않았다. 첫 잠금 해제 후 BOOT_COMPLETED 복구와 START_STICKY 재생성도 저장된 사용자의 ON 의사에 따라 동작한다. 앱의 명시적 중지는 저장된 ON 상태를 지운다.

[공식 FGS 시작 제약](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)과 [알림 채널 제어](https://developer.android.com/develop/ui/compose/notifications/channels)를 따른다. 추적 알림을 숨기려면 사용자가 시스템 채널을 끈다. Foreground Service를 알림 없이 다른 서비스 유형으로 위장하지 않는다.

## 자동화와 현재 한계

순수 Kotlin 거리 엔진은 실제 유효 거리와 monotonic 수신 시간을 입력으로 받는다. 이탈 3초 유지, 복귀 히스테리시스, 긴 수신 간격의 debounce 초기화, 이탈 상태 저장을 적용했다. 실제 2.46m 이탈과 0.46m 복귀 이벤트를 기록했다. 연결 중단과 OS 오류로 거리 이탈을 만들어내지 않는다.

Tasker는 공식 Plugin Library 0.4.10의 이벤트 확장으로 구현했다. 임의 앱의 로컬 거리 조건을 삼성 Modes and Routines에 공식 등록하는 SDK는 확보하지 못했으며 직접 루틴 제어를 지원한다고 주장하지 않는다. 알림과 선택적 HTTPS Webhook도 제공한다.

미검증 항목: 비충전·깊은 Doze·절전 모드·수 시간의 배터리 소비, 삼성의 다른 펌웨어/지역, 다른 UWB backend, 타 앱과 동시에 같은 태그를 정밀 찾기, Tasker/실제 Webhook 수신 서버에서의 전체 실행. 사용자 서버나 Tasker 앱이 없어서 외부 수신 검증은 수행하지 않았다. 서버 인증은 비공식 호환 구현이라 향후 삼성 정책 변경의 영향을 받을 수 있다.

현재의 성공과 나머지 검증 범위를 [DEVICE_TESTS.md](DEVICE_TESTS.md)에 구분해 기록한다.
