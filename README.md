# 바톤 (Baton)

렌터카·카셰어링·공용차를 넘기는 순간, 차가 스스로 자기 상태를 기록하고 전자서명해 다음 운전자와 업체에 넘겨주는 차량 인수인계 앱입니다. (Pleos Connect)

- **APK:** [`apk/cjhhalla_Baton_release.apk`](apk/cjhhalla_Baton_release.apk)
- **반납 증명서 검증 페이지:** https://cjhhalla.github.io/baton/verify/ (QR 없이 열면 서명된 예시 증명서가 표시됩니다)

## 주요 기능

| 기능 | 설명 |
| --- | --- |
| 위치로 판단하는 하차 체크 | P단에 놓을 때마다 차량 내비 위치로 지정 반납 장소(반경 300m)인지 잠깐 정차인지 구분하고, 열린 창문·문, 켜진 조명, 배터리 부족, 좌석에 남은 물건을 알려줍니다. |
| 운행 중 반납 도우미 | 인수할 때 고른 반납 시각과 내비 도착 예정 시각을 비교해 초록·주황("17:45까지 출발하세요")·빨강으로 보여주고, 버튼 하나로 반납 장소 경로 안내를 시작합니다. |
| 분실물 확인 | 운전석을 제외한 좌석의 착석 감지 신호로 남은 물건을 알려줍니다. 신호 의미가 차종마다 다를 수 있어 주행 중 운전석 값으로 자동 보정합니다. |
| 반납 리포트 + 증명서 QR | 반납 기록을 Android Keystore(EC P-256) 키로 서명해 QR로 보여줍니다. 폰으로 찍으면 검증 페이지가 서명을 확인하고, 기록이 바뀌면 "서명이 맞지 않아요"로 걸러냅니다. |
| 다음 운전자 브리핑 | 반납 후 주행거리 변화로 무단 이동을 감지하고, 직전 운전자가 남긴 항목을 보여줍니다. |
| 주행 중 화면 최소화 | CarUxRestrictions에 따라 주행 중에는 반납 시간 한 줄만 표시합니다. |

## 실행 방법

### APK로 바로 실행

1. Pleos Connect 에뮬레이터(v2.1.0, API 34 `pleos_car_emulator`)를 실행합니다.
2. `adb install apk/cjhhalla_Baton_release.apk`
3. 앱 목록에서 **바톤**을 실행하고, 위치 권한을 허용합니다.

### 소스에서 빌드

1. Android Studio에서 이 폴더를 엽니다 (File → Open).
2. Gradle 동기화가 끝나면 Pleos Connect 에뮬레이터를 실행하고 ▶ Run으로 설치합니다.
3. release APK: `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk` (시연용으로 debug 키로 서명됩니다)

Pleos SDK는 `https://nexus-playground.pleos.ai/repository/maven-releases/` 저장소에서 받습니다 (`settings.gradle.kts`에 설정됨).

## 테스트 환경과 필요한 설정

- Pleos Connect Emulator v2.1.0 (API 34), Android Studio, JDK 17
- Kotlin 2.0, Jetpack Compose, AGP 8.8, Gradle 8.10
- Virtual Console: 터미널에서 `adb forward tcp:8088 tcp:8088` 후 브라우저로 `http://127.0.0.1:8088/` → Emulation Controls
- 시연 전 준비
  - 바톤 홈 오른쪽 위 조절 아이콘 → 신호 진단 → **시연 기록 초기화**
  - Virtual Console: 기어 P, Seat Occupancy의 Driver를 Occupied, 나머지 좌석 Empty, 창문 닫힘, 조명 꺼짐
  - 반납 장소에서 떨어진 상황은 신호 진단의 **반납 장소 3km 옮기기**로 만듭니다 (에뮬레이터 위치는 차량 내비 기준이라 고정돼 있습니다)

## 시연 순서 (약 3분)

1. **차 인수하기** → 반납 "30분 뒤" → 운전자 A
2. 신호 진단 → **반납 장소 3km 옮기기**
3. 기어 D: 주행 화면과 반납 시간 한 줄
4. 뒷좌석 오른쪽 Occupied → 기어 P: "잠깐 정차 중이에요" + "좌석에 뭔가 있어요 · 뒷좌석 오른쪽"
5. **반납 장소로 안내**: 차량 내비가 "바톤 반납 장소"로 경로 안내, 바톤 카드는 "안내 중"
6. 실내등 켜고 **반납하기 → 반납 완료**: 리포트와 반납 증명서 QR
7. Odometer +5 km → 운전자 B 인수: "반납 후 5.0 km 이동했어요"와 A가 남긴 항목

## 사용 SDK·API

| 용도 | API |
| --- | --- |
| 배터리 / 연료 잔량 | Vehicle `EvBattery.getEvBatteryStateOfCharge`, `Fuel.getFuelPercent` |
| 누적 주행거리 | Vehicle `Odometer.getCurrentDistanceDriven` |
| 문·창문 위치, 잠금 | Vehicle `Door.getCurrentDoorPosition`, `Door.getDoorLockState`, `Window.getCurrentWindowPosition` |
| 실내등·독서등·전조등 | Vehicle `Light.getCurrentCabinLightState` 등 |
| 좌석 착석 감지 | Vehicle `Seat.getCurrentSeatOccupancy` |
| 차량 경고 | Vehicle `Safety.*` |
| 현재 위치 | NaviHelper `getCurrentLocationInfo` |
| 반납 장소 경로 안내 | NaviHelper `requestRoute` |
| 남은 시간·거리 | NaviHelper `getDestinationInfo` |
| P단 전환 | AAOS `CarPropertyManager` (`GEAR_SELECTION`) |
| 주행 중 화면 제한 | AAOS `CarUxRestrictionsManager` |
| 반납 기록 서명 / QR | Android Keystore (EC P-256, SHA256withECDSA), ZXing |

Vehicle SDK 문서에 메서드 파라미터 시그니처가 공개돼 있지 않아, 공식 샘플 앱과 같은 리플렉션 방식으로 호출합니다 (`vehicle/VehicleReader.kt`). 신호 진단 화면에서 차종별 지원 여부와 원시 값을 확인할 수 있습니다.

## 폴더 구조

```
app/src/main/java/com/baton/app/
├── BatonViewModel.kt      화면 상태, 반납·인수 흐름, 반납 시간 계산
├── data/HandoverStore.kt  운전자·반납 장소·반납 기록 저장 (기기 안)
├── data/ReturnProof.kt    반납 기록 전자서명 + 증명서 URL
├── vehicle/               차량 신호(Vehicle SDK), 기어·UX 제한(AAOS), 내비(NaviHelper)
└── ui/BatonApp.kt         Compose 화면 (홈, 하차 체크, 리포트, 인수 브리핑, 신호 진단)
docs/verify/index.html     반납 증명서 검증 페이지 (GitHub Pages)
apk/                       release APK
```
