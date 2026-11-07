# NShare TV

Screen-mirroring receiver for Google TV / Android TV (Chromecast with Google TV, Google TV Streamer, …).

- **iPhone · iPad · Mac** mirror with the built-in *Screen Mirroring* (AirPlay-compatible receiver, no app needed).
- **Android** phones mirror with the NShare sender app over the NShare protocol (HEVC up to 80 Mbps, adaptive bitrate).
- Low latency, hardware decoding up to 4K60 HEVC, audio/video kept in sync on a shared timeline.

Licensed under the **GNU GPL v3** (see [LICENSE.md](LICENSE.md)).

> NShare is not affiliated with, endorsed by, or sponsored by Apple Inc. AirPlay, iPhone, iPad and Mac are
> trademarks of Apple Inc. Interoperability with Apple devices is based on publicly available reverse-engineering work.

---

## 한국어

Google TV / Android TV에 설치하는 화면 공유 수신 앱입니다.

- **iPhone · iPad · Mac**: 기기에 있는 "화면 미러링"으로 바로 연결합니다(AirPlay 호환 수신기). 앱 설치가 필요 없습니다.
- **Android**: NShare 송신 앱으로 보냅니다. HEVC 최대 80Mbps이고, 네트워크 상태에 맞춰 비트레이트를 자동으로 조절합니다.

## 구조

```
receiver-tv/    Android TV 앱 (Jetpack Compose for TV)
  player/       Timeline(공통 재생 시간축), VideoRenderer(MediaCodec), AudioRenderer(AudioTrack)
  nsp/          NShare 프로토콜 수신 서버
  ui/           대기 화면, 영상 화면, 진단 오버레이
airplay/        AirPlay 호환 수신 라이브러리 (Kotlin)
  server/       RTSP 서버, mDNS 광고, 세션 처리
  crypto/       Ed25519/X25519 페어링, FairPlay JNI 래퍼
  stream/       미러링 영상(TCP, AES-CTR) / 오디오(UDP RTP, AES-CBC) 수신
  time/         NTP 시계 동기화
  src/main/cpp/ FairPlay (playfair, GPLv3)
protocol/       NShare 프로토콜 (송수신 공통)
common/         앱 언어 설정 (한국어 / English / 시스템)
ads/            Unity LevelPlay 배너 (+ AdMob 미디에이션)
```

### 품질과 싱크 설계
- 영상과 소리 모두 **송신 측 캡처 시각**을 기준으로 같은 시간축에 맞춰 재생합니다.
- 재생 지연은 네트워크 지연, 디코딩 시간, 오디오 출력 지연을 측정해서 자동으로 조절합니다.
- 소리 오차가 작으면 미세 리샘플링으로 흡수하고, 오차가 크면 무음을 넣거나 버립니다.
- 영상은 하드웨어 디코더와 저지연 모드를 쓰고, 타임스탬프에 맞춰 정확한 vsync에 표시합니다.
- macOS 송신기처럼 SPS에 재정렬 정보가 없는 H.264 스트림은 수신 측에서 SPS를 고쳐 디코더 지연을 없앱니다.

## 빌드

```sh
./gradlew :receiver-tv:assembleDebug        # 테스트용 APK
./gradlew :receiver-tv:bundleRelease        # Google Play 업로드용 AAB
./gradlew :airplay:testDebugUnitTest :protocol:testDebugUnitTest
```

출시용 서명을 하려면 `keystore.properties.example`을 `keystore.properties`로 복사해서 값을 채우세요. 이 파일은 Git에 올라가지 않습니다. 파일이 없으면 release 빌드는 디버그 키로 서명됩니다.

## TV에 설치 (개발용)

1. TV에서 설정 → 시스템 → 정보 → **Android TV OS 빌드**를 7번 눌러 개발자 모드를 켭니다.
2. 개발자 옵션에서 **USB 디버깅**(네트워크 디버깅)을 켭니다.
3. 아래 명령을 실행합니다.
   ```sh
   adb connect <TV IP>:5555
   adb install -r receiver-tv/build/outputs/apk/debug/receiver-tv-debug.apk
   ```

## 리모컨 조작
- **대기 화면**: 위쪽의 [진단 정보] 버튼과 [언어] 버튼을 리모컨으로 선택합니다.
- **미러링 중**:
  - **OK**: 진단 정보를 켜고 끕니다.
  - 진단 정보가 켜진 상태에서 **◀ ▶**는 소리 싱크 ±10ms, **▲ ▼**는 재생 모드(저지연 / 균형 / 부드러움)를 바꿉니다.
  - **뒤로 두 번**: 화면 공유를 종료합니다.

## 로그

```sh
adb logcat -s NShare.Rtsp NShare.Mirror NShare.Audio NShare.AudioOut NShare.Video NShare.Stats NShare.Nsp NShare.Ads
```

## NShare 프로토콜 (v1)
- **발견**: mDNS `_nshare._tcp`, TCP 포트 7100
- **프레임 형식**: `magic(2) type(1) flags(1) length(4) timeNs(8) payload`
- **흐름**: HELLO → CAPS(수신기 해상도·코덱) → VIDEO_CONFIG / AUDIO_CONFIG → 미디어 프레임 (수신기 → 송신기: KEYFRAME_REQUEST)
- **시계 동기화**: 수신기가 송신기의 UDP 타이밍 포트로 NTP 방식 요청을 보냅니다.

## 광고
- `ads/` 모듈이 Unity LevelPlay를 초기화하고 하단 배너를 띄웁니다. TV에서는 대기 화면에만 표시됩니다.
- LevelPlay 키는 `receiver-tv/.../ui/MainActivity.kt`의 `AdKeys`에 있습니다.
- AdMob 앱 ID는 `AndroidManifest.xml`에 있습니다.
- AdMob 광고 단위 ID는 코드가 아니라 LevelPlay 대시보드에 입력합니다.

## 제3자 구성요소
| 구성요소 | 라이선스 |
|---|---|
| playfair (FairPlay 복호화), `airplay/src/main/cpp/playfair` | GPLv3 |
| FairPlay 응답 테이블 (shairplay / RPiPlay / UxPlay 계열) | LGPL 2.1+ / GPLv3 |
| Bouncy Castle | MIT |
| AndroidX, Jetpack Compose | Apache 2.0 |
| Unity LevelPlay SDK, Google Mobile Ads SDK | 각 제공사 약관 |

AirPlay 프로토콜 동작은 [UxPlay](https://github.com/FDH2/UxPlay)를 참고해서 Kotlin으로 새로 작성했습니다.
