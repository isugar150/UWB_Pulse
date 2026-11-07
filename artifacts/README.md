# 배포 파일

APK와 소스 ZIP은 Git에서 제외한 생성물이다. 이 폴더에 파일이 없으면 프로젝트 루트에서 다음을 실행한다.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
python3 scripts/package-release.py
```

- `UWB-Pulse.apk`: 현재 debug 빌드
- `UWB-Pulse-diagnostic.apk`: 이전 파일 링크를 위한 동일 APK 복사본
- `UWB-Pulse-source.zip`: 소스·문서·라이선스·검토된 시험 증거. 개인 SDK 경로·캐시·계정 데이터·서명 키는 제외
- `SHA256SUMS.txt`: 해당 로컬 배포 파일의 체크섬

서명 확인은 Android SDK Build Tools의 `apksigner verify --verbose artifacts/UWB-Pulse.apk`로 한다. debug 키와 빌드 환경에 따라 APK 바이트·해시는 달라진다. 추적된 체크섬은 마지막 패키징 기록이며 새 환경에서 재생성하면 변경될 수 있다.

검토된 기기 증거는 특정 시험일의 기록으로, 패키징을 다시 했다는 이유로 새로운 실기기 시험을 수행한 것으로 간주하지 않는다. 설치 시 기존 앱의 서명과 데이터를 보존하는 업데이트인지 확인한다.
