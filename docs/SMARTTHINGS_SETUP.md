# SmartThings 가상 스위치 연결

이탈하면 SmartThings의 **가상 스위치를 켜고**, 복귀하면 끕니다. 앱이 **서버 없이 SmartThings 서버(`api.smartthings.com`)를 직접 호출**하며, SmartThings 자동화에서 "그 스위치가 켜지면"을 조건으로 차량 문 잠금 같은 동작을 실행할 수 있습니다. 폰 잠금·빅스비·알림·하드웨어와 무관합니다.

## 2026-11-07 기기에서 확인한 것

|항목|결과|
|---|---|
|가상 스위치 생성|CLI `virtualdevices:create`(장치 프로필 직접 정의)로 성공. 표준 프로토타입 방식(`create-standard`)은 CLI 3.0.0에서 서버가 `prototype cannot be null`(422)로 거부|
|SmartThings 앱 기기 목록|`UWB_Pulse` 표시|
|자동화 "만약 → 기기 상태"|`UWB_Pulse`를 고를 수 있음, 조건은 "켜짐 또는 꺼짐 / 켜짐 / 꺼짐"|
|외부 이벤트로 자동화 실행|CLI 이벤트로 스위치를 켜자 약 1~2초 만에 시험 자동화(알림)가 실행됨. 시험 자동화는 삭제함|
|**앱이 직접 통신**|앱에 저장된 삼성 로그인 토큰으로 상태 읽기 200, 이벤트 쓰기 200. 앱의 "켜기/끄기 시험"이 SmartThings 쪽 상태를 실제로 바꿈(몇 ms 차이). OAuth 앱·개인 토큰 불필요|
|**실제 이탈·복귀**|15:35:47 `TAG_DISTANCE_EXCEEDED`(1.87m, 임계 1.5m) → 0.23초 뒤 스위치 ON(HTTP 200). 15:36:04 `TAG_RETURNED`(0.92m) → 0.09초 뒤 OFF(HTTP 200), SmartThings에서 읽은 상태도 꺼짐(06:36:04.133Z)|

## 설정 순서

1. **가상 스위치 만들기(PC, 한 번).** SmartThings CLI(`brew install smartthingscommunity/smartthings/smartthings`)로 로그인한 뒤 아래 내용의 파일을 만들고 `<위치 ID>`, `<방 ID>`는 `smartthings locations`, `smartthings rooms`로 확인해 채웁니다.

```json
{"name":"UWB_Pulse","roomId":"<방 ID>","owner":{"ownerType":"LOCATION","ownerId":"<위치 ID>"},"deviceProfile":{"components":[{"id":"main","capabilities":[{"id":"switch","version":1}],"categories":[{"name":"Switch"}]}]}}
```

```sh
smartthings virtualdevices:create -j -i virtual-switch.json
```

   출력의 `deviceId`가 기기 ID입니다.

2. **앱에 기기 ID 저장.** UWB Pulse 설정 → SmartThings 가상 스위치 → 기기 ID를 넣고 저장합니다. 앱에 삼성 계정이 연결돼 있으면 다른 입력은 필요 없습니다(OAuth 항목은 선택 사항).
3. **시험.** "삼성 로그인 토큰으로 연결 시험"이 `읽기 200 · 쓰기 200`이면 통신 가능입니다. "스위치 켜기/끄기 시험"으로 SmartThings 앱의 스위치가 바뀌는지 봅니다. 진단 로그에는 `ST_PROBE`, `ST_SWITCH_RESULT`가 남습니다.
4. **연동 켜기.** "이탈 시 스위치 켜기 · 복귀 시 끄기"를 켭니다.
5. **SmartThings 자동화 만들기(사용자).** 만약: 기기 상태 → `UWB_Pulse` → 켜짐. 그러면: 원하는 동작(예: 차량 문 잠금 루틴 실행)과 `UWB_Pulse` 끄기. 처음에는 무해한 동작으로 시험하세요.

## 동작과 한계

- 이탈 이벤트(`TAG_DISTANCE_EXCEEDED`)에 스위치 ON, 복귀 이벤트(`TAG_RETURNED`)에 OFF를 보냅니다. 다른 이벤트는 바꾸지 않습니다.
- 인터넷이 필요합니다. 연결이 없으면 WorkManager가 최대 5번 재시도하고, 더 새로운 상태가 생기면 대기 중이던 이전 요청을 교체합니다.
- **삼성 로그인 토큰을 SmartThings 공개 API에 쓰는 것은 비공식 동작입니다.** 이번 시험에서는 통했지만 삼성·SmartThings 정책 변경으로 막힐 수 있고 토큰 유효 기간은 확인하지 못했습니다. 막히면 OAuth 앱 방식(앱 설정의 OAuth 항목, SmartThings CLI `apps:create`)으로 전환할 수 있게 코드를 남겨 두었습니다.
- 이 토큰은 SmartThings 계정의 기기를 제어할 수 있습니다. 앱은 Keystore 암호화 저장소에 보관하고 로그에 남기지 않으며, 로그에는 HTTP 상태만 기록합니다.
- 전송 결과는 진단 로그의 `ST_SWITCH_RESULT`(`httpStatus`)와 `ST_SWITCH_ERROR`에만 남고 화면이나 알림으로는 나오지 않습니다. 401은 토큰을 한 번 갱신해 다시 보내며, 갱신 후에도 401·403·404이면 재시도 없이 멈춥니다. 며칠 사용한 뒤 로그에 200이 계속 남는지 확인하세요.
- 아직 확인하지 못한 것: 스위치 ON으로 차량 문 잠금 자동화가 실행되는 것과 전체 지연(사용자가 구성), 토큰 장기 유효성과 401 갱신 경로.
- 오프라인 확인(2026-11-07): 폰의 Wi-Fi·데이터를 끄고 끄기 시험 신호를 보내면 전송 없이 대기하다가, 연결이 돌아온 약 1.3초 뒤 `ST_SWITCH_RESULT ... httpStatus=200`으로 전송됐습니다. 그 사이 스위치가 이미 꺼져 있었으므로 SmartThings 상태 변화가 아니라 HTTP 200으로 확인했습니다. 오프라인 중 ON이 대기하다 OFF로 교체되는 경로는 시험하지 않았습니다.
