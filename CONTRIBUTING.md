# 어딧(Eodit) 기여 안내

이 저장소의 기여 대상은 어딧의 **Android 앱과 Node.js/SQLite 서버**, 테스트·빌드·배포 설정·문서입니다. 직접 작성한 앱 리소스도 공개하지만 APK·운영 데이터와 별도 권리 검토가 필요한 `android/store/` 소재는 포함하지 않습니다. 먼저 [README.md](README.md)의 빌드 안내·운영 한계와 [SECURITY.md](SECURITY.md)의 신뢰 경계를 확인하세요. 보안 취약점은 공개 이슈 대신 SECURITY의 비공개 제보 절차를 따릅니다.

## 로컬 작업

1. 저장소를 포크하고 작업 브랜치를 만듭니다. 변경 목적과 사용자에게 보이는 동작을 작게 나눠 설명하세요.
2. 서버 작업에는 Node.js 24 LTS를 준비합니다. Docker와 Compose는 컨테이너 배포를 확인할 때 선택적으로 사용합니다. 서버 개발·테스트에는 JDK, Android SDK, 지도 키, 앱 서명 키가 필요하지 않습니다. Android 작업에는 JDK 17, SDK Platform 35·Build-Tools 34.0.0과 포함된 Gradle 8.9 wrapper를 사용합니다. [README의 앱 빌드 안내](README.md#android-앱-빌드설치)에 따라 SDK·지도 키를 설정하세요.
3. 운영 데이터와 분리된 로컬 저장소를 사용합니다. 서버가 `.env`를 자동으로 읽지 않는 점에 유의하세요. 아래는 저장소 루트에서 시작하는, 프록시 없는 로컬 **HTTP 개발 전용** 예입니다. 이 설정에는 공개 TLS가 없으므로 실사용 위치·프로필을 넣지 마세요.

```sh
cd server
HOST=127.0.0.1 PORT=8080 DATA_DIR=./data-local \
  TRUST_PROXY=false ALLOW_INSECURE_INVITES=true \
  PUBLIC_BASE_URL=http://127.0.0.1:8080 \
  node src/index.mjs
```

`127.0.0.1`은 명령을 실행한 컴퓨터 자신을 가리키므로 이 예의 초대 주소는 다른 휴대전화에서 바로 접근할 수 없습니다. 실제 기기 확인에는 직접 빌드한 앱과 본인이 관리하는 서버가 필요합니다. debug만 개발용 HTTP를 추가로 허용하고 release는 HTTPS만 허용합니다. 실사용 가족 환경을 테스트에 쓰지 말고 운영 `.env`도 읽지 마세요. 기존에 export한 환경 변수, 포트와 `DATA_DIR`가 운영 환경과 분리되어 있는지 확인합니다. 서버는 내장 모듈을 사용하여 npm 의존성 설치가 필요하지 않습니다.

## 검증

아래 명령은 저장소 루트에서 시작합니다. 서버 테스트 통과만으로 실제 기기의 위치·알림 동작이나 운영 배포가 확인되지는 않습니다.

```sh
cd server
npm test
```

Android 빌드 확인은 저장소 루트에서:

```sh
cd android
./gradlew --no-daemon :app:assembleDebug :app:assembleRelease
```

지도 키 없이 두 변형을 빌드할 수 있습니다. 이때 지도 영역에는 설정 필요 안내가 표시됩니다. 실제 지도를 검증하려면 본인 NAVER Cloud Maps에 `org.ansim.link`와 Dynamic Map을 등록하세요. 키 우선순위는 `-PnaverMapKeyId` → `NAVER_MAP_KEY_ID` → 무시되는 `android/local.properties`의 `naverMapKeyId`입니다. 가짜 키·공유 키로 CI를 우회하지 마세요. release 출력은 unsigned이며 설치·운영 배포에는 별도 서명이 필요합니다.

CI는 고정 커밋의 Actions로 wrapper 무결성 검사, 키 없는 Android debug/unsigned release 빌드, 서버 테스트와 격리된 컨테이너 검증을 수행합니다. APK 업로드·서명·Play 제출은 하지 않습니다. 현재 Android 소스는 버전 1.4.0(code 6), target/compile SDK 35입니다. 향후 Play 제출에는 API 36 이상 전환 등 당시 정책 검증이 별도로 필요합니다.

- 서버 동작을 바꾸면 영향받는 테스트를 갱신하고, 실제 오류를 막는 재현을 추가하세요. 테스트는 임시·합성 데이터를 사용하고 실서버에 접근하지 않아야 합니다.
- 초대 참여, 공유 동의/중지, 연결 해제·프로필 삭제 같은 상태 전환을 바꿨다면 접근 권한과 저장 데이터의 결과도 확인합니다. 기기 확인이 필요한 변경은 실제로 확인한 범위를 밝히고, 실행하지 못한 항목은 PR에 명시하세요. 지도 인증·위치 포그라운드 서비스·백그라운드 알림은 빌드 성공만으로 검증되지 않습니다.
- 데이터 보존·초대·인증·프록시 신뢰·기존 SOS/첨부 API를 바꾸면 개인정보 및 기존 사용자에 대한 영향과 마이그레이션을 함께 설명하세요. 위치 공유를 가족 연결과 묶어 자동 동의로 처리하지 않습니다.
- 기존 설치·데이터와의 호환성을 지킵니다. 제품명은 어딧(Eodit)이지만 `org.ansim.link`, `ansimlink://`, DB·저장소 이름, `/downloads/ansim-link.apk`와 저장소 URL은 호환 식별자입니다. 개발 APK는 기존 앱과 서명이 다를 수 있으므로 별도 기기/에뮬레이터에 설치하고 기존 앱 삭제로 업데이트 오류를 우회하지 않습니다.

## 제출 전

PR에는 변경 이유, 사용자에게 보이는 결과, 실제로 실행한 검증 명령과 결과, 남은 제약을 적습니다. 실행하지 않은 테스트·기기 확인·배포를 완료했다고 쓰지 마세요. 동작·설정 변경에 해당하는 기존 문서도 갱신하고, 관계없는 포맷 변경이나 생성 파일은 제외합니다.

다음은 커밋·PR 첨부 금지입니다: `android/store/` 소재·지도 캡처, `.env`, 실제 서버 주소와 사설 배포 메모, `local.properties`·개인 지도 설정, DB/WAL/SHM·첨부·백업·Git bundle, 로그의 실제 위치·가족 이름·기기 식별자, 초대 코드·링크, 세션 토큰, 터널 토큰, 서명 키·암호, 개인 로컬 경로, APK/AAB와 생성 산출물. 공개 대상은 Android 빌드 설정·wrapper와 `app/src/`의 소스·직접 작성한 리소스입니다. `.gitignore`에만 의존하지 말고 제출할 파일을 직접 확인하세요. 예시는 `family.example.com`과 합성 데이터를 사용합니다.

[LICENSE](LICENSE)의 Android·서버 코드에 대한 MIT 저작권·허가 고지와 기존 Ansim Link 기여자 귀속을 유지하세요. [NOTICE](NOTICE)의 제3자 구성요소 고지도 보존합니다. SDK·라이브러리·폰트·지도 콘텐츠의 권리를 프로젝트 MIT 허가로 대체하지 않으며 각 제공자의 조건을 따릅니다. 기여하는 코드·문서·이미지에 배포할 권리가 있는지 확인하세요.
