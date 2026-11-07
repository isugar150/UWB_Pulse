# 연결 시간 비교 시험

2026-11-08 KST. USB 연결 SM-F971N / Android 17 API 37, 근처의 기존 SmartTag2 한 개로 시험했다. 사용자 요청에 따른 실제 무선 시험이다. 무선 비교 중에는 설정을 바꾸지 않고 정상 추적만 잠시 중지한 채 디버그 전용 경로에서 검색 → 소유자 인증 → 암호화 OOB → 첫 실제 UWB 거리 → 정상 STOP/해제를 반복했다. 거리 엔진·SmartThings·Tasker·Webhook은 호출하지 않았다. 뒤이은 Gradle 계측 테스트에서는 앱 제거 사고가 있었으며 아래에 별도로 기록한다. 이 시험은 차량 잠금까지의 전체 지연 측정이 아니다.

## 관측과 해석

별도 15초 광고 관측에서 소유 태그 광고 간격은 3.024 / 3.005 / 6.007초였다. 수신 시각과 광고 timestamp 차이는 0~2ms여서 오래된 결과가 한꺼번에 전달된 것은 아니었다. BLE 연결도 여러 회차에서 약 3.1 / 6.2 / 9.2초가 걸렸다. 다음 광고를 기다리거나 놓치는 것이 지연에 영향을 준다는 해석과 일치하지만, 폰 컨트롤러 내부 원인을 확정한 것은 아니다.

인증은 대부분 약 0.45초, 인증 뒤 첫 거리까지 약 0.9초였다. HIGH 요청을 넣어도 인증 시간은 약 0.45초로 같았다. HIGH 시험 한 번의 총 시간은 1.741초였지만 이때 BLE 연결 자체가 0.263초로 짧았다. HIGH 호출은 연결 뒤에 실행되므로 이 빠른 연결의 원인으로 볼 수 없다.

## 화면 ON 비교

표의 시간은 시험 시작부터 첫 실제 UWB 거리까지다. `delayed`와 `warm_scan`은 같은 3초 확인을 포함한다. 실패 회차를 제외한 중앙값이므로 실패 수를 함께 봐야 한다. 실험 순서를 교차했지만 표본이 작고 동일 조건을 완전히 통제한 시험은 아니다.

|방법|회차 / 거리 수신 성공|성공 시간(초)|판단|
|---|---|---|---|
|기존 방식, 3초 확인 제외|6 / 5|5.691, 6.279, 6.684, 8.122, 11.796|중앙값 6.684. 나머지 1회 UWB 응답 없음|
|검색 유지한 채 GATT 연결|2 / 1|9.609|나머지 1회 UWB 응답 없음. 개선 근거 부족|
|자동 연결 `autoConnect=true`|2 / 0|—|검색 유지 여부와 무관하게 status 135. 채택 안 함|
|검색 종료 뒤 250ms 대기|1 / 0|—|UWB 응답 없음. 채택 안 함|
|검색 결과의 기기 객체 그대로 연결|3 / 3|12.282, 16.185, 18.863|속도 개선 증거 없음. 객체 보존은 주소 유형을 잃지 않기 위한 변경|
|기기 객체 연결 + 검색 유지|1 / 1|9.440|속도 개선 증거 부족|
|3초 확인 뒤 검색 시작 (`delayed`)|3 / 2|8.949, 9.576|중앙값 9.263. 나머지 1회 GATT status 147|
|검색을 3초 확인과 겹침 (`warm_scan`)|3 / 3|6.238, 6.626, 15.047|중앙값 6.626. 느린 회차가 남음|
|인증 직전 HIGH 요청|2 / 1|1.741|인증 시간은 그대로. 나머지 1회 UWB 응답 없음. 채택 안 함|

3초 확인을 포함한 두 방식의 성공 중앙값 차이는 약 2.6초였다. 이 값을 일반적인 개선율이나 상한 보장으로 해석하지 않는다. 검색을 일찍 시작해 대기 일부를 겹칠 수 있다는 근거로만 사용한다. 태그가 응답하지 않는 경우나 긴 BLE 연결 대기는 이 변경으로 해결되지 않는다.

## 반영한 변경

- 알려진 차량 연결이 끊겼을 때 광고 검색을 3초 확인과 함께 시작한다. 신선한 차량 저장 이력에서 놓친 끊김을 복원하는 경우도 포함한다.
- 차량 연결 이력이 없는 `WAITING` 시작에서는 검색하지 않는다. 확인 중 재연결·서비스 중지 시 검색을 취소한다.
- 확인 뒤 첫 연결 시도에만 준비한 광고 결과를 넘긴다. 이후 재시도는 새 광고를 찾는다. 준비 중 검색 실패는 확인 뒤 기존 복구 정책으로 처리한다.
- GATT·인증·UWB·이벤트와 60초 판정 기준 시각은 확인 뒤부터다. 3초·8초·60초·30초 전송 만료와 사용자 정책은 유지한다.
- 연결에는 ScanResult의 실제 `BluetoothDevice`를 넘긴다. 주소 문자열로 다시 만들 때 LE 주소 유형이 달라질 수 있다. 이번 기기의 광고 객체는 RANDOM(1)이었다. 이 변경의 속도 이득을 주장하지 않는다.
- `autoConnect=false`와 기존 BLE 저전력 전환을 유지한다. HIGH 요청·검색 중 GATT 연결·250ms 대기는 일반 추적에 적용하지 않는다.

## 재현과 범위

디버그 APK에만 `ConnectionBenchmarkService`가 들어 있다. `DUMP` 권한으로 보호하며 adb shell이 직접 FGS를 시작한다. 계측 테스트가 아니라 사용자가 명시적으로 실행하는 실제 무선 실험이다. 앱의 정상 계정 저장소가 선택 태그와 키를 메모리에서 제공하며, vault·키·주소·원본 ID·nonce는 수집하거나 출력하지 않는다. 합성 vault 계측 테스트 3개와 구분한다.

정상 추적 상태와 설정을 먼저 기록하고 다음처럼 중지한다. 시험 뒤 원래 ON이었다면 시작해 복원한다. OFF였던 세션은 시작하지 않는다. 개인 폰에서 `connectedDebugAndroidTest`를 기본 AGP 설정으로 실행하지 않는다(아래 사고 기록).

```sh
adb shell am broadcast -n dev.uwbpulse.monitor/.debug.DebugCommandReceiver --es monitor stop
python3 scripts/connection-benchmark.py delayed warm_scan --output /tmp/uwb-radio-trials.txt
# 원래 ON이었을 때만 복원. Android가 FGS 시작을 막으면 앱 화면을 연 뒤 실행한다.
adb shell am broadcast -n dev.uwbpulse.monitor/.debug.DebugCommandReceiver --es monitor start
```

`baseline`, `keep_scan`, `auto`, `auto_scan`, `settle`, `native`, `native_scan`, `delayed`, `warm_scan`, `high`, `advertising`, `warm_cancel`을 지원한다. `warm_cancel`은 0.5초 뒤 재연결을 모의해 검색 취소만 검사한다. 정상 모니터링이 저장 ON이면 실험은 거부한다. 각 연결은 최대 40초, 연속 시험 사이에는 8초를 둔다. 실제 장시간 주차 후 연결을 재현하는 간격은 아니다.

검토된 시각·거리·상태 코드 증거는 [connection-latency-validation.txt](../test-results/connection-latency-validation.txt)에 있다. 실제 차량 블루투스의 끊김 시점, 차량 재연결로 인한 준비 검색 취소, 스위치 상태 전파와 실제 차량 잠금 지연, 비충전·깊은 절전·배터리 효과는 미검증이다. 화면 OFF 추가 결과와 최종 복원 상태는 증거 파일 및 DEVICE_TESTS.md 참고.

## 계측 테스트 뒤 앱 제거 사고와 복구

25회의 무선 시험(광고 관측 1회 포함)을 마친 뒤 `:app:connectedDebugAndroidTest`로 합성 vault 테스트 3개를 실행했고 모두 통과했다. 그러나 AGP 9.5.1은 `android.injected.androidTest.leaveApksInstalledAfterRun` 기본값이 false라 테스트 종료 뒤 대상 앱을 제거했다. 다음 `adb install -r`가 새 설치가 되어 사용자 앱의 계정·설정·저널이 사라졌다. 설치 시각이 새로 바뀌고 계정·스위치 vault와 저널이 없어진 것을 확인했다. 비밀 자료의 백업은 만들지 않았으므로 기존 인증을 직접 복원할 수 없었다. 이를 사전에 확인하지 않고 실행한 에이전트의 실수다.

AGP 배포 소스 `BooleanOption.kt`의 기본값 false와 `AndroidTestEngineConfigurer.kt`의 `android-test.uninstall-after-tests = !leaveApksInstalledAfterRun`를 확인했다. `gradle.properties`에 보존 옵션 true를 고정하고 AGENTS.md에 개인 폰은 테스트 APK만 덮어쓰기 설치한 뒤 `am instrument`로 실행하도록 안내했다. 이후 Gradle 기기 테스트는 다시 실행하지 않았다.

사용자에게 즉시 알렸고 사용자가 삼성 계정에 다시 로그인했다. 기존 가상 스위치는 삼성 계정 API에서 정확한 이름으로 하나만 일치하는 기기를 찾아 ID를 앱 내부 암호화 저장소에 다시 연결했다(상태 쓰기는 하지 않음). 차량은 휴대폰에 등록된 CAR_AUDIO 분류의 유일한 기기를 복원했다. ID·주소·토큰·키는 밖으로 출력하지 않았다. 거리 5m/3m/3초, 절전 600ms, 차량 모드·스위치·연결 소실 끄기·펄스 ON과 모니터링 ON/차량 연결 대기로 복원했다. 과거 저널과 마지막 관측 이력은 복구할 수 없다. 최종 확인 값은 증거 파일의 `DEBUG_SETTINGS`와 `CAR_PHASE`에 기록한다.

복구된 인증으로 무선 확인을 1회 추가해 총 시험은 26회다(광고 관측 1회 포함). 08:02:43 `warm_scan` 첫 실제 HIGH 거리 약 0.91m, 시작 후 6.987초, 이후 암호화 STOP ACK와 GATT 해제를 확인했다. 이 추가 회차는 위의 화면 ON 비교 표에 합치지 않았다. 최종 빌드 단위 테스트 117개 통과, lintDebug 9.5.1 보고서 오류 0/경고 0이다. 아이콘은 수정하지 않았으며 과거 빌드의 monochrome 경고 1건과 구분한다. `ensureNotificationChannel`은 기존 채널이 있으면 즉시 반환한다. 채널 숨김은 앱에서 importance를 바꿔도 시스템이 유지하지 않아 사용자가 시스템 화면에서 직접 복원하도록 안내했다. 작동하지 않는 앱 변경 명령은 제거했다.

복구 명령은 기존 `DebugCommandReceiver`의 DUMP 권한 아래에 추가했다. `--ez recover_car true`는 유일한 CAR_AUDIO 기기만 선택한다. `--es recover_switch_name UWB_Pulse`는 읽기 API로 이름이 유일하게 일치할 때만 내부 ID를 저장한다. 여러 기기가 일치하면 변경하지 않는다. `--ef return_distance 3.0`, `--ez status true`는 일반 설정 복구·식별자 없는 상태 확인용이다. 원래 숨긴 추적 채널을 다시 켜는 명령은 추가하지 않았다.

사용자가 시스템 화면에서 추적 알림을 다시 껐고 `channelImportance=0`을 확인했다. 최종 폰은 모니터링 ON·차량 연결 대기, 플랫폼·600ms·5m/3m/3초, SmartThings·연결 소실 끄기·펄스·부팅 재개 ON, 이벤트 알림 4종 ON이다. 실제 차량 연결 없이 시작한 대기 경로에서 태그 검색·연결·UWB·스위치 전송이 없는 상태로 마쳤다.
