# UWB Pulse 작업 안내

## 먼저 읽을 문서

1. `docs/HANDOFF.md`: 현재 구현 상태, 검증된 경로, 다음 작업 후보
2. `docs/ARCHITECTURE.md`: 파일별 역할과 상태·이벤트 흐름
3. `docs/DEVICE_TESTS.md`: 실제 기기에서 관측한 결과와 미검증 범위
4. `README.md`: 사용자 기능과 빌드·설치 안내

원래 요구사항은 `docs/REQUIREMENTS.ko.md`에 있다. 문서 끝의 후속 요청을 우선한다. 최대 임계거리는 **10m**이며 재부팅 후 이전 설정으로 추적을 재개해야 한다.

## 수정 시 유지할 동작

- 실제 UWB 콜백만 거리로 사용한다. RSSI 변환·임의 값·연결 중단으로 이탈을 만들지 않는다.
- 현재 성공 경로는 삼성 계정 → 소유자 키 → 광고 서명 매핑 → 상호 인증 → 암호화 OOB → 플랫폼 UWB다. 초기 진단용 실패 경계로 되돌리지 않는다.
- 태그는 기존 계정 등록을 유지한다. 태그 초기화, 앱 데이터 삭제, 수정 SmartThings 설치는 일반 개발 절차에 포함하지 않는다.
- 모니터링 ON/OFF·선택 태그·backend·거리 정책을 보존한다. 사용자가 중지한 세션을 자동 재개하지 않는다.
- 부팅 복구는 첫 잠금 해제 이후 추적 서비스만 시작한다. Activity를 강제로 열지 않는다.
- FGS `connectedDevice` 유형과 유효 알림을 유지한다. 추적 알림 표시 여부는 시스템 채널 `uwb_probe`를 사용하며 사용자가 끈 채널을 다시 켜지 않는다.
- 토큰·태그 키·nonce·전체 BLE 주소·원본 클라우드 태그 ID를 로그·문서·Git에 넣지 않는다. `account.vault`, `webhook.vault`, Keystore와 서명 키를 수집하지 않는다.
- 사용자 아이콘은 원본을 유지한다. GPL-3.0 및 오픈소스 고지를 보존하고 루트 고지와 APK asset 고지를 함께 갱신한다.

## 빌드와 검증

JDK 25, Android SDK Platform 37, Build Tools 36.0.0을 사용한다. daemon JVM 25 설정은 `gradle/gradle-daemon-jvm.properties`에 있다. SDK 경로는 커밋하지 않는 `local.properties` 또는 개발 환경에서 지정한다.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

macOS에서 필요한 경우 `JAVA_HOME=$(/usr/libexec/java_home -v 25)`를 사용한다. 현재 기준 단위 테스트는 31개, Lint 오류 0 / monochrome 아이콘 경고 1이다. 새 테스트를 추가하면 문서의 개수도 갱신한다.

실제 무선 검증은 별도다. `docs/HANDOFF.md`의 재현 절차를 따른다. 재부팅 검증 중에는 부팅 후 자동 재개 증거를 얻기 전에 앱 Activity를 열지 않는다. 실기기 시험을 하지 않았다면 그 사실을 명확히 기록한다.

## 저장소와 배포

- `main` / origin: `https://github.com/isugar150/UWB_Pulse.git`
- 빌드 캐시·SDK 경로·APK·소스 ZIP·개인 서명 키는 Git에서 제외한다. Gradle wrapper JAR는 빌드 재현을 위해 포함한다.
- `test-results/`의 검토된 증거는 추적한다. `scripts/device-evidence.sh`가 새로 수집한 원본 `device-*.txt`는 기본 제외하며, 비밀과 식별자를 점검한 뒤 필요한 부분만 반영한다.
- 배포 파일 재생성: 검증 빌드 후 `python3 scripts/package-release.py`. APK 서명 검증도 수행한다.
- 기능 수정 후 관련 문서와 검증 범위를 갱신한다. 커밋·푸시는 해당 사용자 요청 범위에 맞춘다.
