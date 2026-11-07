# 실제 기기 시험 결과

시험일: **2026-11-07 (Asia/Seoul)**. 최종 설치 버전: **0.4.0 / versionCode 5**.
삼성 계정 로그인·소유자 인증을 완료한 실제 SmartTag2에서 측정했다. 계정 비밀·전체 BLE 주소·휴대폰 시리얼은 증거에 포함하지 않는다.

## 환경

|항목|값|
|---|---|
|휴대폰|Samsung SM-F971N / 사용자 기기 Galaxy Z Fold8|
|OS|Android 17, API 37, One UI 속성 90000|
|빌드|CP2A.260605.016.F971NKSU3AZJ8|
|태그|삼성 계정의 기존 SmartTag2 한 개, 소유자 광고 서명·상호 인증 확인|
|UWB 경로|플랫폼 Ranging RAW / technology=0(UWB), confidence=2(HIGH)|
|전원|USB 충전 연결|
|배터리 최적화|최초 화면 OFF 시험 시작 시 예외 false. 후속 시험에서 true 관측. 비충전·기본 절전 조건의 장시간 보장은 별도 검증 필요|

## 실측 결과

|시각(KST)|시험|결과|
|---|---|---|
|08:47:44~|소유자 인증·암호화 OOB|상호 proof 검증, AES 명령 ACK, 실제 거리 콜백 수신|
|08:47~08:48|태그 이동|사용자가 멀리 이동 후 복귀. 실제 거리 변화 및 HIGH 신뢰도 확인|
|08:48~|다른 화면|activityVisible=false 상태에서 실제 거리 콜백 지속|
|08:48:49~09:04|화면 OFF|약 15분 동안 screenOff 실제 콜백 3,549개. 잠금 해제 뒤 일괄 수신한 값으로 집계하지 않음|
|09:04:09|정상 중지|암호화 태그 UWB stop ACK, 로컬 세션·서비스 종료|
|09:09:35 / 09:09:50|이탈·복귀 1차|시험 임계값 1.5m에서 2.32m 이탈, 0.34m 복귀 이벤트|
|09:21:37 / 09:21:53|최종 버전 이탈·복귀|사용자 이동 후 2.46m 이탈, 0.46m 복귀. 각 상태 전환 한 번 발생|
|09:24~09:28|임계거리 UI|최대 10.0m 선택 확인 후 기본 5.0m / 복귀 4.0m / 지속 3초 복원|
|09:28:15~|추적 알림 숨기기|앱 설정에서 시스템 채널을 열어 OFF. channelImportance=0, FGS 유지, 실제 거리 수신 지속|
|09:28:44~|실제 휴대폰 재부팅|모니터링 ON·자동 재개 ON·추적 알림 OFF 상태에서 adb reboot|
|09:29:57.470|첫 잠금 해제 후 자동 시작|사용자가 첫 잠금만 해제하고 앱을 열지 않음. AUTO_RESUME_REQUEST reason=BOOT 관측|
|09:29:57.508|저장 설정 복원|MONITOR_RESTORE manual=false, backend=PLATFORM, threshold=5.0, dwell=3000|
|09:30:02~09:32:~|재부팅 후 실제 수신|자동 소유자 인증·암호화 OOB 성공. 앱 비표시 상태에서 실제 UWB 수신 지속, 앱을 다시 열기 전 background 콜백 655회|
|09:32:~|화면 재확인|자동 재개 증거 확보 후 앱을 열어 실제 약 0.22m, 임계값 5m / 복귀 4m 확인. 알림 채널 OFF도 재부팅 후 유지|

현재 휴대폰은 모니터링 ON, 자동 재개 ON, 임계거리 5m, 복귀 4m, 지속 3초이며 추적 알림 채널은 숨김 상태다. 이탈·복귀 알림 채널은 별도 설정이다.

## 자동 재개와 알림의 범위

재부팅 시 Activity 화면을 강제로 띄우지 않고 추적 서비스를 시작한다. 계정 키가 자격 증명 보호 저장소에 있으므로 첫 잠금 해제가 필요하다. 사용자가 모니터링을 중지하면 저장 ON 상태도 지워져 다음 부팅에서 시작하지 않는다. BOOT/업데이트/프로세스 재생성의 정책 분기는 단위 테스트로 검증했다. 실제 강제 프로세스 종료·앱 업데이트 후 복원은 이번 최종 실기기 시험과 구분한다.

Android의 Foreground Service에는 유효한 알림이 필요하다. 앱의 ‘추적 중 알림 표시’는 해당 시스템 알림 채널 설정을 열고 실제 표시 상태를 읽는다. 채널을 숨겨도 FGS 및 거리 수신이 유지되는 것을 검증했다. OS의 ‘실행 중인 앱’ 표시는 별도로 남을 수 있다. 사용자의 강제 중지·권한 회수는 우회하지 않는다.

## 빌드 / 단위 검증

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

- BUILD SUCCESSFUL, 단위 테스트 **24개 통과**, 실패·오류 0.
- 암호화 고정 벡터, 유효 거리/신선도, 디바운스·히스테리시스·중복 방지, 10m 상한, Webhook 정책, 자동 재개 정책을 검사했다.
- Lint 오류 0, 경고 1: 사용자 원본 아이콘에 별도 monochrome layer가 없다. 컬러 원본은 그대로 유지했다.
- APK debug 서명 검증 결과와 SHA-256은 `test-results/apk-signature.txt`, `artifacts/SHA256SUMS.txt`에 보관한다.
- 아이콘 원본과 패키징 PNG SHA-256: `137ab93fe092e0846f024a48354a9998af6024a225163b2d3806158502a0934c`.

## 증거 파일

- `test-results/owner-auth-phase1.txt`: 최초 실제 인증·거리
- `test-results/owner-auth-background.txt`, `owner-auth-screen-off*.txt`: 백그라운드·화면 OFF 수신
- `test-results/event-validation.json`: 실제 이탈·복귀 4건의 비식별 이벤트
- `test-results/notification-hidden-monitoring.txt`: 채널 OFF 후 서비스·거리
- `test-results/reboot-auto-resume.txt`: 재부팅 전후 저널, BOOT 자동 재개·저장 임계값·앱 비표시 실제 수신
- `test-results/notification-channel-after-reboot.txt`: 재부팅 후 채널 importance=0 유지
- `test-results/device-ui.png`: 재부팅 자동 재개 검증 뒤 연 화면
- `test-results/unit-tests.xml`, `build-validation.txt`, `lint.txt`: 빌드 검증

초기 0.1 진단 빌드의 직접 쓰기 실패/암호화 미구현 기록은 개발 이력이다. 최종 0.4.0은 소유자 키 조회·상호 인증·암호화 명령을 구현했고 실제 콜백으로 검증했다. 초기 실패를 현재 기능 상태로 해석하지 않는다.

## 아직 실기기에서 검증하지 않은 범위

|항목|상태|
|---|---|
|Tasker 수신·프로필 실행|공식 SDK 플러그인 구현, 시험 휴대폰에 Tasker 미설치로 외부 수신 미검증|
|HTTPS Webhook 서버 도착|암호화 자격 증명·영속 큐·재시도 구현, 수신 URL 미제공으로 외부 전달 미검증|
|삼성 Modes and Routines 직접 거리 조건|공식 등록 SDK 미확보. 앱 자체의 직접 루틴 제어를 주장하지 않음|
|AndroidX UWB backend|비교 구현 존재, 이번 실측은 플랫폼 backend|
|비충전·깊은 절전·30분 이상·배터리 소모|추가 측정 필요|
|권한 회수·Bluetooth/UWB OFF·장거리 단절 후 재접속|회복 경로 구현, 실제 최종 기기 반복 검증 미실시|
|다른 휴대폰·태그 펌웨어|미검증|
