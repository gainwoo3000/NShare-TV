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
  nsp/          NShare 프로토콜 수신 서버 (TLS, 페어링, 승인, 재연결 유예)
  security/     보안 설정, 신뢰하는 기기 목록
  ui/           대기 화면, 영상 화면, 설정 화면, PIN·승인·재연결 오버레이, 진단 오버레이
airplay/        AirPlay 호환 수신 라이브러리 (Kotlin)
  server/       RTSP 서버, mDNS 광고, 세션 처리
  crypto/       Ed25519/X25519 페어링, PIN 페어링(SRP-6a), 암호 인증(Digest), FairPlay JNI 래퍼
  stream/       미러링 영상(TCP, AES-CTR) / 오디오(UDP RTP, AES-CBC) 수신
  time/         NTP 시계 동기화
  src/main/cpp/ FairPlay (playfair, GPLv3)
protocol/       NShare 프로토콜 (송수신 공통, NShare-Mobile과 동일하게 유지)
common/         앱 언어 설정, Wi-Fi 저지연 락 (NShare-Mobile과 동일하게 유지)
ads/            Unity LevelPlay 배너 (+ AdMob 미디에이션)
```

### 품질과 싱크 설계
- 영상과 소리 모두 **송신 측 캡처 시각**을 기준으로 같은 시간축에 맞춰 재생합니다.
- 재생 지연은 네트워크 지연, 디코딩 시간, 오디오 출력 지연을 측정해서 자동으로 조절합니다.
- 소리 오차가 작으면 미세 리샘플링(청크 길이의 약 0.6%)으로 흡수하고, 오차가 크면 무음을 넣거나 버립니다.
- 소리가 늦을 때는 무음 청크부터 건너뛰어 따라잡습니다. 소리가 없거나 무음이면 지연을 초당 50ms로 빠르게 줄이고, 소리가 나는 동안에는 초당 5ms로 줄입니다.
- 스트리밍 중에는 Wi-Fi 저지연 락(`WIFI_MODE_FULL_LOW_LATENCY`)을 잡고, AudioTrack 저지연 모드를 요청합니다(기기가 거부하면 기본 경로).
  - Google TV Streamer는 HDMI 출력이 DEEP_BUFFER 경로라서, 오디오 출력 지연이 약 210ms로 고정되고 이 값이 전체 지연을 결정합니다.
- 영상은 하드웨어 디코더와 저지연 모드를 쓰고, 타임스탬프에 맞춰 정확한 vsync에 표시합니다.
- macOS 송신기처럼 SPS에 재정렬 정보가 없는 H.264 스트림은 수신 측에서 SPS를 고쳐 디코더 지연을 없앱니다.

## 빌드

```sh
./gradlew :receiver-tv:assembleDebug        # 테스트용 APK
./gradlew :receiver-tv:bundleRelease        # Google Play 업로드용 AAB
./gradlew :receiver-tv:assembleProfile      # 출시 빌드와 같은 최적화 + 디버그 키 서명 (디버그 빌드 위에 설치, 실제 속도 확인용)
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
- **대기 화면**: 위쪽의 [설정], [진단 정보], [언어] 버튼을 리모컨으로 선택합니다. 설정 화면은 **뒤로**로 닫습니다.
- **승인 창**: 기본 선택은 [거부]입니다. 10초 안에 고르지 않으면 거부됩니다.
- **미러링 중**:
  - **OK**: 진단 정보를 켜고 끕니다.
  - 진단 정보가 켜진 상태에서 **◀ ▶**는 소리 싱크 ±10ms, **▲ ▼**는 재생 모드(저지연 / 균형 / 부드러움)를 바꿉니다.
  - **뒤로 두 번**: 화면 공유를 종료합니다.

## 로그

```sh
adb logcat -s NShare.Rtsp NShare.Mirror NShare.Audio NShare.AudioOut NShare.Video NShare.Stats NShare.Nsp NShare.Hub NShare.Wifi NShare.Ads
```

## 보안 (TV 설정 화면)
- **NShare 연결은 항상 암호화됩니다** (TLS 1.3). 설정으로 끌 수 없습니다.
- **NShare PIN 페어링**: 켜면 처음 연결하는 휴대폰에 TV 화면의 6자리 PIN을 입력하게 합니다. 끄면 처음 연결한 기기를 그대로 믿습니다(TOFU).
- **기기 전환 승인**: 켜면 누군가 공유 중일 때 다른 기기가 연결하려 하면 TV에서 허용/거부를 묻습니다. AirPlay에도 적용됩니다.
- **AirPlay 보안**:
  - 없음
  - 화면 PIN: Apple 기기에 TV 화면의 4자리 코드를 입력하게 합니다. 한 번 페어링한 기기는 다음부터 바로 연결됩니다. 코드는 2분 동안 유효합니다.
    - 페어링된 기기도 연결할 때마다 pair-verify 서명으로 그 키를 가졌음을 증명해야 합니다. 증명 전에는 SETUP이 거부(470)됩니다.
  - 미러링 중에 PIN이 필요하면 화면을 가리지 않도록 오른쪽 위에 작은 카드로 표시합니다.
  - 암호: 정해 둔 암호를 입력해야 연결됩니다. 암호를 아직 저장하지 않았으면 열린 상태로 동작합니다.
- **TV 이름**: 설정 → 기기 → TV 이름에서 바꿉니다. 저장하면 AirPlay와 NShare 수신기가 새 이름으로 다시 시작합니다. 비워 두면 기본 이름(NShare-XXXX)으로 돌아갑니다.
- **신뢰하는 기기**: PIN이나 TOFU로 등록된 휴대폰과 Apple 기기 목록입니다. 하나씩 또는 전체를 삭제할 수 있습니다.
- 설정은 다음 연결부터 적용됩니다.

## NShare 프로토콜 (v2)
- **발견**: mDNS `_nshare._tcp`, TCP 포트 7100. TXT 레코드에 `v=2`(프로토콜 버전)와 `fp`(인증서 지문 앞 8바이트)가 있습니다.
- **암호화**: TLS 1.3, 양방향 인증. TLS 1.3이 없는 Android 8/9 TV에서는 TLS 1.2(ECDHE-ECDSA + AES-GCM/ChaCha20만)로 연결합니다.
  - 각 기기는 AndroidKeyStore에 EC P-256 키와 자체 서명 인증서를 한 번 만들어 둡니다.
  - 상대는 인증서의 SHA-256 지문으로 식별합니다. 송신 앱은 TV를 지문 기준으로 저장합니다(이름은 표시용). TV가 mDNS로 저장된 TV의 지문을 내세우면 TLS 인증서가 정확히 그 TV여야 하고, 재연결은 같은 세션의 TV에만 됩니다.
- **프레임 형식**: `magic(2) type(1) flags(1) length(4) timeNs(8) payload` (TLS 안에서)
- **흐름**: HELLO → [PAIR_REQUIRED → PAIR_PROOF ⇄ PAIR_RESULT] → [WAIT_APPROVAL] → CAPS 또는 ERROR → VIDEO_CONFIG / AUDIO_CONFIG → 미디어 프레임
  - **PIN 페어링**: SRP-6a(RFC 5054 2048비트 그룹, SHA-256). 신원(identity)에 TV와 휴대폰의 인증서 지문을 모두 넣습니다.
    - PIN은 네트워크로 보내지 않고, 오가는 값으로 PIN을 오프라인에서 맞춰 볼 수 없습니다(연결 한 번에 추측 한 번).
    - 중간자가 양쪽과 각각 TLS를 맺고 메시지를 그대로 전달해도, 양쪽이 보는 인증서가 달라 증명이 맞지 않습니다.
    - PIN이 틀리면 TV가 새 SRP 값으로 PAIR_REQUIRED를 다시 보냅니다(PIN은 그대로).
    - 페어링은 TV 전체에서 한 번에 하나만 합니다. 진행 중이면 다른 기기는 ERROR `busy`를 받습니다.
  - **신뢰 저장 시점**: 새 기기(PIN 페어링이든 첫 연결 신뢰든)는 승인까지 통과한 뒤, CAPS 직전에 저장합니다. 거부된 기기는 저장되지 않습니다.
  - **ERROR 코드**: `version`(앱 업데이트 필요), `rejected`, `pairing`, `busy`, `protocol`.
  - v1 송신기는 평문 TCP라 TLS 단계에서 실패합니다. TLS가 아닌 데이터로 실패하면 TV에 "앱을 업데이트하세요" 안내를 띄우되, 포트 스캔 등과 구별할 수 없어 1분에 한 번만 띄웁니다.
- **연결 유지**: CAPS 이후 양쪽이 1초마다 PING을 보내고, 5초 동안 아무 프레임도 없으면 끊긴 것으로 봅니다.
- **재연결**:
  - 송신 앱은 네트워크가 끊기면 1, 2, 4 … 최대 30초 간격으로 2분 동안 다시 연결하고, 그래도 안 되면 공유를 끝냅니다. 기다리는 동안 인코더 입력은 멈추고, 다시 연결되면 코덱 설정을 다시 보내고 키프레임을 요청합니다.
  - TV는 끊긴 세션의 마지막 화면을 10초 동안 "다시 연결하는 중" 표시와 함께 유지합니다. 같은 기기(지문)가 돌아오면 플레이어를 그대로 이어서 씁니다.
  - BYE는 의도적인 종료입니다. TV에서 공유를 끝내거나 다른 기기가 넘겨받으면 BYE를 보내서 휴대폰이 재연결하지 않게 합니다.
- **수신기 → 송신기**: KEYFRAME_REQUEST. 디코더를 다시 만들 때, 화면 출력 대상이 바뀔 때, 키프레임을 기다릴 때 보냅니다(초당 최대 1회).
- **시계 동기화**: 수신기가 송신기의 UDP 타이밍 포트로 NTP 방식 요청을 보냅니다.
- **인증 전 제한**:
  - HELLO 페이로드는 최대 16KB이고, TLS와 HELLO는 10초 안에 끝나야 합니다. HELLO 이후 핸드셰이크 전체(PIN 입력, 승인)는 절대 시간 2분 안에 끝나야 합니다.
  - 신원을 모르는 연결은 동시에 최대 8개까지 받습니다. 이미 신뢰한 기기는 TLS 직후 이 개수에서 빠지므로, 낯선 연결이 슬롯을 차지해도 막히지 않습니다.
  - 길이 필드는 메모리를 할당하기 전에 검사합니다.
  - 승인 대기는 최대 10초입니다. 승인 요청이 이미 떠 있으면 나중 요청은 바로 거부합니다.
- **테스트**: `HandshakeTest`는 JVM에서 실제 TLS 핸드셰이크(1.3, 1.2 대체 경로, SRP, 중간자 중계, busy, 승인 후 신뢰)를 돌립니다. `LiveReceiverTest`는 실제 TV에 붙는 선택 테스트입니다.
  ```sh
  NSHARE_TV_HOST=<TV IP> NSHARE_SCENARIO=connect ./gradlew :protocol:testDebugUnitTest --tests '*LiveReceiverTest*'
  # NSHARE_SCENARIO: connect | pin | v1 | drop
  ```

## AirPlay 세션 감시
- RTSP 요청, 영상 데이터, 소리 데이터가 8초 동안 하나도 없으면 세션을 닫습니다. 마지막 화면에 멈춰 있지 않게 하기 위해서입니다.
- 기기 전환 승인에서 "같은 기기"는 pair-verify로 증명된 Ed25519 키로 판단합니다. 스스로 적어 보내는 deviceID는 쓰지 않습니다.

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
