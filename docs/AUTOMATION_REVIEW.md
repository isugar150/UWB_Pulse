# SmartThings / 삼성 루틴 트리거 검토

검토일: **2026-11-07**. 목적은 `TAG_DISTANCE_EXCEEDED`, `TAG_RETURNED` 이벤트로 SmartThings 자동화나 삼성 Modes and Routines를 실행할 수 있는지 판단하는 것이다. 이 문서는 문서·커뮤니티 조사 결과이며 **실기기나 SmartThings 계정에서 실행해 확인한 항목은 없다.** 확인하지 못한 항목은 ‘미확인’으로 표시한다.

## 결론

|경로|판단|이유|
|---|---|---|
|Tasker 이벤트 플러그인|구현됨, 외부 수신 미검증|공식 플러그인 프로토콜. Tasker가 SmartThings·차량 앱·HTTP로 이어 줄 수 있음|
|HTTPS Webhook → 사용자 중계 → SmartThings API|구현 가능, 중계는 사용자가 준비|Webhook은 구현됨. 본문 형식이 SmartThings 명령 형식이 아니므로 중계가 필요|
|앱이 SmartThings API를 직접 호출|가능성 있음, 지금은 구현하지 않음|OAuth 앱 생성·사용자 승인·토큰 갱신이 필요하고 가상 기기 방식의 동작이 미확인|
|삼성 Modes and Routines를 앱이 직접 트리거|**공식 경로 없음**|공개 SDK·intent·조건 등록 API를 찾지 못함. 비공식 content provider만 확인|

SmartThings 루틴(자동화)은 SmartThings 기기의 상태 변화가 조건이다. 따라서 앱이 직접 루틴을 ‘깨우는’ 대신, 이벤트를 SmartThings 쪽 **기기 상태 변화**로 바꿔 주는 방식을 쓰게 된다.

## 확인한 사실

**삼성 Modes and Routines**

- 공식 문서에서 외부 앱이 조건을 등록하거나 루틴을 실행하는 공개 API를 찾지 못했다(없다고 증명한 것은 아님).
- Tasker 개발자의 설명에 따르면 Samsung 앱은 비공개 content provider(`com.samsung.android.app.routines.externalprovider`)로 **수동(manual) 루틴의 시작·종료·토글**을 받는다. 자동 루틴은 수동 실행이 안 된다고 한다. 문서가 없고 Tasker 측도 언제든 깨질 수 있다고 밝혔다. 출처: [Tasker 요청 페이지](https://tasker.helprace.com/i1998-add-support-to-trigger-samsung-modes-routines).
- 외부 앱이 위 provider에 필요한 읽기 권한(`READ_ROUTINE_INFO`)을 받을 수 있는지는 미확인.
- Good Lock의 Routine+ 모듈이 임의 intent를 조건으로 받는다는 자료는 찾지 못했다.

**SmartThings 공개 API**

- REST 기반(`api.smartthings.com/v1`)이며 인증은 OAuth 또는 개인 액세스 토큰(PAT)이다.
- 개인 액세스 토큰은 24시간만 유효하다는 사용자 보고가 있다. 공식 안내로는 확인하지 못했다.
- 장기 사용 경로는 SmartThings CLI로 만드는 **OAuth-In 앱**이다. 스태프 답변에 따르면 액세스 토큰은 24시간, 리프레시 토큰은 사용하지 않으면 29일 후 만료된다. 범위(scope)는 앱 생성 시 지정하고 기본 설치 한도는 500이다. 출처: [SmartThings 커뮤니티](https://community.smartthings.com/t/how-to-get-access-token-to-access-apis-2025/298670).
- 토큰 교환에 클라이언트 비밀이 쓰이므로 이를 앱에 넣으면 APK에 비밀이 포함된다(추정, 문서 근거 없음).
- 가상 기기 API가 있다는 자료는 있으나, API로 만든 가상 스위치의 상태 변경이 **SmartThings 자동화를 실제로 실행하는지는 미확인**이다. 공식 개발자 문서 페이지는 이번 조사에서 내용을 읽지 못했다.

## 경로별 평가

1. **Tasker (현재 구현)**: 이벤트와 `%uwb_event`, `%uwb_distance` 등 변수를 제공한다. Tasker에서 차량 앱 호출·HTTP 요청·알림 등을 붙일 수 있다. 시험 기기에 Tasker가 없어 아직 수신을 확인하지 못했다.
2. **Webhook + 중계**: 사용자가 가진 서버나 Home Assistant 같은 곳이 이벤트를 받아 SmartThings API나 차량 API를 호출한다. 앱은 SmartThings 자격 증명을 갖지 않아 구조가 단순하고 안전하다. 서버 도착과 중복 처리는 별도 검증이 필요하다.
3. **앱 내 SmartThings 직접 연동**: 가상 스위치를 만들고 이벤트 때 on/off를 보내 SmartThings 자동화가 이를 조건으로 쓰는 방식을 생각할 수 있다. 하지만 OAuth-In 앱 생성, 사용자 승인 화면, 토큰 저장·갱신, 클라이언트 비밀 처리, 가상 기기 동작 확인이 모두 필요하다. 먼저 사용자의 SmartThings 계정에서 수동으로 같은 흐름을 검증한 뒤에만 구현할 가치가 있다.
4. **삼성 루틴 비공식 provider**: 수동 루틴만 대상이고 문서와 호환성 보장이 없다. 이 프로젝트의 원칙(비공개 경로 의존 금지)에 맞지 않아 구현하지 않는다.

## 권장 순서

1. Tasker 설치 기기 또는 Webhook 수신 URL로 현재 이벤트 전달을 먼저 실측한다. 차량 잠금·해제는 이 경로에 연결하면 SmartThings 없이도 가능하다.
2. SmartThings 루틴이 꼭 필요하면 사용자가 가상 스위치를 만들고 REST 호출 한 번으로 루틴이 도는지 **수동으로** 확인한다(예: `curl`).
3. 위 확인이 성공하면 Webhook 본문 템플릿 또는 OAuth 직접 연동 중 하나를 선택한다.

## 앱이 하지 않는 것

- 앱이 보유한 삼성 계정 토큰·소유자 키를 SmartThings 공개 API 호출에 재사용하지 않는다. 용도와 권한 범위가 다르고 비밀 노출 위험을 키운다.
- SmartThings 앱 수정, 삼성 private Binder 접근, 태그 초기화에 의존하지 않는다.
- 삼성 루틴과 SmartThings 직접 트리거를 구현했다고 주장하지 않는다.
