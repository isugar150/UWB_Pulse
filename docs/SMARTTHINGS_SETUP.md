# SmartThings 가상 스위치 연결

이탈하면 SmartThings의 **가상 스위치를 켜고**, 복귀하면 끕니다. SmartThings 자동화에서 "그 스위치가 켜지면"을 조건으로 차량 문 잠금 같은 동작을 실행합니다. 폰 잠금 상태, 빅스비, 스마트 플러그 같은 하드웨어와 무관합니다.

**상태: 앱 코드는 구현했고 단위 테스트로 요청 형식만 검증했습니다. SmartThings 쪽 동작은 모두 미검증입니다.** 아래 1~5단계가 가장 중요한 가정(가상 스위치가 자동화 ‘만약’에 나오고 외부 이벤트로 켜지는지)을 앱 없이 먼저 확인합니다. 명령 이름과 화면은 CLI·앱 버전에 따라 다를 수 있으니 `--help`로 확인하세요.

## A. 앱 없이 먼저 확인 (1~5단계)

1. SmartThings CLI를 설치합니다(macOS).

```sh
brew install smartthingscommunity/smartthings/smartthings
```

2. 로그인하고 위치 목록을 봅니다. 브라우저에서 삼성 계정으로 로그인합니다.

```sh
smartthings locations
```

3. **클라우드** 가상 스위치를 만듭니다(`--local`은 허브용이니 쓰지 않습니다). 이름은 `UWB Pulse 이탈`, 위치를 고릅니다. 출력된 기기 ID(UUID)를 적어 둡니다.

```sh
smartthings virtualdevices:create-standard
```

   **CLI 3.0.0에서 `prototype cannot be null`(422)로 실패하는 경우**(2026-11-07 관찰, 플래그를 직접 지정해도 같은 오류): 스크립트로 API를 직접 호출해 만듭니다. 소스상 표준 가상 기기의 생성 주소는 `POST https://api.smartthings.com/virtualdevices/prototypes`입니다(커뮤니티·소스 기반, 공식 문서로 확인하지 못함).

   ① https://account.smartthings.com/tokens/new 에서 토큰 이름을 정하고 범위에서 **장치 → 모든 디바이스 보기·관리·제어**, **위치 → 모든 장소 보기**를 체크해 토큰을 만듭니다(24시간 유효, 값은 한 번만 보임).

   ② 아래 스크립트를 실행하고 토큰을 **숨김 입력창**에 붙여 넣습니다. 토큰은 출력·저장·명령줄에 남지 않습니다. 위치와 방을 고르면 기기 ID가 출력됩니다.

```sh
python3 scripts/smartthings-create-virtual-switch.py
```

   ③ 끝나면 토큰 페이지에서 그 토큰을 삭제합니다.

4. SmartThings 앱의 기기 목록에 새 스위치가 나타나는지 봅니다.
5. SmartThings 앱 → 자동화 → 루틴 만들기 → **만약: 기기 → 이 가상 스위치 → 켜짐**, **그러면: 처음에는 알림이나 조명 같은 무해한 동작**으로 저장합니다. 그리고 CLI로 스위치를 켭니다(화면 안내에 따라 capability `switch`, attribute `switch`, value `on`).

```sh
smartthings virtualdevices:events <기기 ID>
```

   자동화가 실행되면 핵심 가정이 맞습니다. 만약 가상 스위치가 ‘만약’ 목록에 안 나오거나 실행되지 않으면 여기서 멈추고 알려 주세요.

## B. 앱 연결 (6~9단계)

6. OAuth 앱을 만듭니다. 종류는 **OAuth-In App**, 범위(scope)는 `r:devices:*`, `w:devices:*`, `x:devices:*`, 리디렉션 주소는 `https://uwbpulse.invalid/callback`입니다. 끝나면 **클라이언트 ID와 비밀**이 한 번만 표시되니 바로 앱에 넣을 때까지만 보관하세요.

```sh
smartthings apps:create
```

7. UWB Pulse 설정 → **SmartThings 가상 스위치**에 클라이언트 ID, 클라이언트 비밀, 3단계의 기기 ID를 넣고 **저장 및 연결**을 누릅니다.
8. **승인 페이지 열기**를 누르고 삼성 계정으로 로그인해 위치를 고르고 허용합니다. 브라우저에 `uwbpulse.invalid` 오류 페이지가 뜨는 것이 정상입니다. **주소창의 `code=` 뒤 값**을 복사해 앱의 **승인 코드**에 붙여 넣고 **저장 및 연결**을 다시 누릅니다(코드는 몇 분 안에 쓰세요).
9. **스위치 켜기 시험**과 **끄기 시험**을 눌러 SmartThings 자동화가 반응하는지 봅니다. 진단 로그의 `ST_SWITCH_RESULT ... httpStatus=200`을 확인합니다. 정상이면 **이탈 시 스위치 켜기 · 복귀 시 끄기**를 켭니다.

## 동작과 한계

- 이탈 이벤트(`TAG_DISTANCE_EXCEEDED`)에 스위치 ON, 복귀 이벤트(`TAG_RETURNED`)에 OFF를 보냅니다. 다른 이벤트는 스위치를 바꾸지 않습니다.
- 인터넷이 필요합니다. 연결이 없으면 WorkManager가 연결될 때까지 최대 5번 재시도하고, 더 새로운 상태가 생기면 대기 중이던 이전 요청은 교체합니다.
- SmartThings는 갱신 토큰을 돌려 씁니다. 앱은 하루 한 번 토큰을 갱신해 만료(미사용 약 29일)를 막습니다. 갱신이 거부되면 다시 승인해야 합니다.
- 클라이언트 비밀·토큰·기기 ID는 Keystore로 암호화한 `smartthings.vault`에 저장하며 로그와 문서에 남기지 않습니다. 이 토큰은 위 범위의 SmartThings 기기를 제어할 수 있으므로 폰 분실 시 SmartThings 계정에서 앱 연결을 해제하세요.
- `.invalid` 주소는 어디에도 연결되지 않아 승인 코드가 제3자 서버로 가지 않습니다. OAuth 앱 생성이 이 주소를 거부하면 다른 https 주소를 쓰고 앱의 ‘리디렉션 주소’도 같게 바꾸세요(그 주소를 가진 서버는 코드를 볼 수 있습니다).
- SmartThings 가상 기기·OAuth 호출은 공식 문서가 아닌 커뮤니티 자료를 근거로 구현했습니다. 요청이 거부되면 `ST_SWITCH_RESULT`의 HTTP 상태로 원인을 확인하세요.
