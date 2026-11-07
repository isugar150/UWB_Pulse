# SmartThings / 삼성 루틴 트리거 검토

검토일: **2026-11-07**. 목적은 `TAG_DISTANCE_EXCEEDED`, `TAG_RETURNED` 이벤트로 SmartThings 자동화나 삼성 Modes and Routines를 실행할 수 있는지 판단하는 것이다. SmartThings 부분은 문서·커뮤니티 조사만 했고 SmartThings 계정에서 실행해 확인한 항목은 없다. 삼성 루틴 접점은 기기의 매니페스트를 읽어 확인했다(아래 ‘이 기기에서 확인한 삼성 루틴 접점’). 어느 쪽도 실제로 트리거를 실행해 본 것은 아니다. 확인하지 못한 항목은 ‘미확인’으로 표시한다.

## 결론

|경로|판단|이유|
|---|---|---|
|**앱 알림 → 삼성 루틴의 ‘알림을 받으면’ 조건**|**가능성 높음, 종단 간 미검증**|루틴에 ‘알림을 받으면 → 특정 단어가 포함된 알림’ 조건이 있고, 우리 앱은 이벤트마다 알림을 올림. 루틴 액션에는 SmartThings 기기 제어도 있음. 사용자가 루틴 앱의 알림 접근을 한 번 켜야 함|
|Tasker 이벤트 플러그인|구현됨, 외부 수신 미검증|공식 플러그인 프로토콜. Tasker가 SmartThings·차량 앱·HTTP로 이어 줄 수 있음|
|HTTPS Webhook → 사용자 중계 → SmartThings API|구현 가능, 중계는 사용자가 준비|Webhook은 구현됨. 본문 형식이 SmartThings 명령 형식이 아니므로 중계가 필요|
|앱이 SmartThings API를 직접 호출|가능성 있음, 지금은 구현하지 않음|OAuth 앱 생성·사용자 승인·토큰 갱신이 필요하고 가상 기기 방식의 동작이 미확인|
|삼성 Modes and Routines를 앱이 직접 트리거|**불가(이 기기 기준)**|공개 SDK·intent·조건 등록 API가 없고, 내부 접점은 모두 `signature\|privileged` 권한으로 막혀 있음|

**사용자 확인(2026-11-07):** SmartThings 앱의 자동화와 삼성 Modes and Routines의 조건 목록에서 UWB Pulse를 찾을 수 없었다. 외부 앱이 스스로를 조건으로 등록하는 공식 방법이 없다는 위 결론과 일치하며, 앱의 설정 누락이나 오류가 아니다. 이전 README에 있던 "앱의 이벤트 알림을 루틴 조건으로 이용할 수 있는지는 조건 UI에서 설정" 문구는 처음 검토에서 근거가 없다고 보고 삭제했으나, 이후 기기 분석에서 알림 수신 조건이 실제로 있음을 확인해 **아래 ‘알림을 루틴 조건으로 쓰는 경로’로 되살렸다.** 직접 트리거와 달리 이 길은 루틴이 원래 지원하는 조건을 이용한다.

SmartThings 루틴(자동화)은 SmartThings 기기의 상태 변화가 조건이다. 따라서 앱이 직접 루틴을 ‘깨우는’ 대신, 이벤트를 SmartThings 쪽 **기기 상태 변화**로 바꿔 주는 방식을 쓰게 된다.

## 확인한 사실

**삼성 Modes and Routines**

- 공식 문서에서 외부 앱이 조건을 등록하거나 루틴을 실행하는 공개 API를 찾지 못했다(없다고 증명한 것은 아님).
- Tasker 개발자의 설명에 따르면 Samsung 앱은 비공개 content provider(`com.samsung.android.app.routines.externalprovider`)로 **수동(manual) 루틴의 시작·종료·토글**을 받는다. 자동 루틴은 수동 실행이 안 된다고 한다. 문서가 없고 Tasker 측도 언제든 깨질 수 있다고 밝혔다. 출처: [Tasker 요청 페이지](https://tasker.helprace.com/i1998-add-support-to-trigger-samsung-modes-routines).
- 위 provider가 요구하는 읽기 권한(`READ_ROUTINE_INFO`)은 이 기기 기준으로 일반 앱이 받을 수 없는 보호 수준이다(아래 ‘이 기기에서 확인한’ 절).
- Good Lock의 Routine+ 모듈이 임의 intent를 조건으로 받는다는 자료는 찾지 못했다.

**SmartThings 공개 API**

- REST 기반(`api.smartthings.com/v1`)이며 인증은 OAuth 또는 개인 액세스 토큰(PAT)이다.
- 개인 액세스 토큰은 24시간만 유효하다는 사용자 보고가 있다. 공식 안내로는 확인하지 못했다.
- 장기 사용 경로는 SmartThings CLI로 만드는 **OAuth-In 앱**이다. 스태프 답변에 따르면 액세스 토큰은 24시간, 리프레시 토큰은 사용하지 않으면 29일 후 만료된다. 범위(scope)는 앱 생성 시 지정하고 기본 설치 한도는 500이다. 출처: [SmartThings 커뮤니티](https://community.smartthings.com/t/how-to-get-access-token-to-access-apis-2025/298670).
- 토큰 교환에 클라이언트 비밀이 쓰이므로 이를 앱에 넣으면 APK에 비밀이 포함된다(추정, 문서 근거 없음).
- 가상 기기 API가 있다는 자료는 있으나, API로 만든 가상 스위치의 상태 변경이 **SmartThings 자동화를 실제로 실행하는지는 미확인**이다. 공식 개발자 문서 페이지는 이번 조사에서 내용을 읽지 못했다.

## 이 기기에서 확인한 삼성 루틴 접점 (2026-11-07)

대상: Modes and Routines 5.1.03.4(`com.samsung.android.app.routines`), SM-F971N / Android 17. 방법: `dumpsys package`와 시스템 APK 매니페스트를 읽기 전용으로 분석했다(APK는 저장소에 넣지 않음). 루틴을 실행하거나 바꾸는 호출은 하지 않았다.

|접점|요구 권한 (보호 수준)|
|---|---|
|`externalprovider`(수동 루틴 시작·종료, Tasker 문서의 경로)|`READ_ROUTINE_INFO` (`signature\|privileged`)|
|루틴 정보 provider|`READ_ROUTINE_INFO` / `WRITE_ROUTINE_INFO` (`signature\|privileged`)|
|액션 실행 provider|`EXECUTE_ACTION` (`signature\|privileged`)|
|조건·액션 제공 provider|`ROUTINE_HOST` (`signature\|privileged`)|
|Routines SDK v3 provider|`sdk.routines.v3.ACCESS_ROUTINES` (`signature\|privileged`)|

- `signature|privileged` 권한은 같은 서명이거나 시스템 권한 앱만 받을 수 있어, 일반 앱이 매니페스트에 선언해도 부여되지 않는다. 조건·액션을 공급하는 쪽도 `ROUTINE_HOST` 권한이 필요해 외부 앱이 스스로를 조건으로 등록할 수 없다.
- 권한 없이 외부에 열린 receiver는 6개뿐이며 삼성 자체 조건(RCS 메시지, 삼성 헬스)과 시스템 유지용이다. 루틴을 실행하는 입구는 없다. 삼성 앱의 시스템 인텐트를 위조하는 방식은 취약하고 지원되지 않아 검토 대상에서 제외한다.
- Routines SDK v3가 앱 안에 있으나 공개 문서를 찾지 못했고, 접근 권한이 위와 같아 협력사·시스템 앱 전용으로 보인다.
- Tasker 문서의 방식이 이 버전에서 일반 앱에 통한다는 근거는 찾지 못했다. 시험 기기에 Tasker가 없어 직접 확인하지 못했다.
- `adb shell run-as`로 우리 앱 uid에서 `content query`를 시도한 시험은 `content` 도구 자체의 프레임워크 권한(`ACCESS_CONTENT_PROVIDERS_EXTERNALLY`) 거부로 끝나 **삼성 provider의 판단을 보여 주지 못했다.** 결론은 위 매니페스트 분석에만 근거한다.

## 알림을 루틴 조건으로 쓰는 경로 (2026-11-07, 기기 분석)

직접 깨우는 대신, 루틴이 이미 지원하는 조건 하나를 우리 앱이 만들어 주는 방식이다.

**근거 (Modes and Routines 5.1.03.4, 매니페스트·문자열 분석):**
- 조건 구성 화면 `notification.notificationreceived`(앱 선택 `NotificationMultiAppPickerActivity`, 키워드 `NotificationSpecificKeywordActivity`)와 표준 `NotificationListenerService`(`PreloadNotificationListenerService`)가 있다.
- 화면 문구: ‘알림을 받으면’, ‘특정 단어가 포함된 알림’, 키워드 여러 개 추가, ‘키워드 중 하나라도 있을 때 / 모든 키워드가 있을 때’, ‘알림 제목’.
- 사용하려면 [설정 > 애플리케이션 > 옵션 더보기 > 특별한 접근 > 알림 접근]에서 모드 및 루틴을 켜야 한다는 안내 문구가 있다. 확인 시점에는 알림 접근 목록에 모드 및 루틴이 없었다(켜지 않은 상태).
- 루틴 액션에 `smartthings.actions.ControlSmartThingsConfigActivity`(SmartThings 기기 제어)가 있어, 알림 → 루틴 → SmartThings 기기 제어로 이어질 가능성이 있다. 어떤 기기를 제어할 수 있는지는 미확인.

**앱 쪽 현황:** 이벤트 알림은 이벤트 유형별 채널(`event_<TYPE>`, 중요도 기본)로 올라가며 제목이 `TAG_DISTANCE_EXCEEDED`, `TAG_RETURNED`, `TAG_DISCONNECTED`, `UWB_MONITORING_ERROR` 중 하나다. 본문은 `실제 UWB x.xx m · 설정 y.y m`이다. 설정의 이벤트 알림 스위치가 켜져 있어야 한다.

**사용자 설정 순서(예):**
1. 알림 접근에서 모드 및 루틴 허용.
2. 모드 및 루틴 → 루틴 추가 → 만약 ‘알림을 받으면’ → ‘특정 단어가 포함된 알림’ → 앱 UWB Pulse → 키워드 `TAG_DISTANCE_EXCEEDED`(이탈용) 또는 `TAG_RETURNED`(복귀용).
3. 그러면에 원하는 동작(예: SmartThings 기기 제어, 앱 실행 등)을 추가.
4. UWB Pulse 이벤트 알림 채널을 시스템 설정에서 **차단하지 않는다**(소리만 끄는 것은 괜찮을 수 있으나 미확인).

**미검증·위험:**
- 키워드가 알림 제목·본문 중 어디에 적용되는지, 같은 알림 ID를 갱신하는 경우(앱은 이벤트 유형별 고정 ID 사용) 조건이 다시 발동하는지, 화면 OFF·절전 중 지연은 확인하지 못했다.
- 알림 채널을 끄거나 알림 권한을 회수하면 루틴이 동작하지 않는다. 이벤트가 알림에 의존하므로 알림 지연·누락이 곧 자동화 지연·누락이다.
- 이탈·복귀 알림은 소리가 나는 기본 중요도 채널이다. 자동화 전용으로 쓰려면 조용한 채널이 필요할 수 있다.
- 기기 시험은 루틴을 새로 만들어야 하므로 사용자 동의 후에만 진행한다.

## 경로별 평가

1. **Tasker (현재 구현)**: 이벤트와 `%uwb_event`, `%uwb_distance` 등 변수를 제공한다. Tasker에서 차량 앱 호출·HTTP 요청·알림 등을 붙일 수 있다. 시험 기기에 Tasker가 없어 아직 수신을 확인하지 못했다.
2. **Webhook + 중계**: 사용자가 가진 서버나 Home Assistant 같은 곳이 이벤트를 받아 SmartThings API나 차량 API를 호출한다. 앱은 SmartThings 자격 증명을 갖지 않아 구조가 단순하고 안전하다. 서버 도착과 중복 처리는 별도 검증이 필요하다.
3. **앱 내 SmartThings 직접 연동**: 가상 스위치를 만들고 이벤트 때 on/off를 보내 SmartThings 자동화가 이를 조건으로 쓰는 방식을 생각할 수 있다. 하지만 OAuth-In 앱 생성, 사용자 승인 화면, 토큰 저장·갱신, 클라이언트 비밀 처리, 가상 기기 동작 확인이 모두 필요하다. 먼저 사용자의 SmartThings 계정에서 수동으로 같은 흐름을 검증한 뒤에만 구현할 가치가 있다.
4. **삼성 루틴 비공식 provider**: 수동 루틴만 대상이고 문서와 호환성 보장이 없으며, 이 기기에서는 권한(`signature\|privileged`) 때문에 일반 앱에 막혀 있다. 이 프로젝트의 원칙(비공개 경로 의존 금지)에 맞지 않아 구현하지 않는다.

## 권장 순서

0. 먼저 ‘알림을 받으면’ 조건 경로를 사용자 폰에서 시험한다(Tasker·서버 불필요). 키워드 적용 위치와 지연을 확인한 뒤 필요하면 앱의 알림 문구·채널을 자동화에 맞게 조정한다.
1. Tasker 설치 기기 또는 Webhook 수신 URL로 현재 이벤트 전달을 먼저 실측한다. 차량 잠금·해제는 이 경로에 연결하면 SmartThings 없이도 가능하다.
2. SmartThings 루틴이 꼭 필요하면 사용자가 가상 스위치를 만들고 REST 호출 한 번으로 루틴이 도는지 **수동으로** 확인한다(예: `curl`).
3. 위 확인이 성공하면 Webhook 본문 템플릿 또는 OAuth 직접 연동 중 하나를 선택한다.

## 앱이 하지 않는 것

- 앱이 보유한 삼성 계정 토큰·소유자 키를 SmartThings 공개 API 호출에 재사용하지 않는다. 용도와 권한 범위가 다르고 비밀 노출 위험을 키운다.
- SmartThings 앱 수정, 삼성 private Binder 접근, 태그 초기화에 의존하지 않는다.
- 삼성 루틴과 SmartThings 직접 트리거를 구현했다고 주장하지 않는다.
