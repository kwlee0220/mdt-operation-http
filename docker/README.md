# mdt-operation-http Docker 이미지 만들기

이 문서는 mdt-operation-http Docker 이미지를 빌드하고, 실행하고, 레지스트리에 올리는 절차를 설명한다.
mdt-operation-http는 `operations/` 아래 연산 디렉터리의 명령(주로 Python 프로그램)을 MDT 연산으로 실행하는 서버다.
서버 자체의 동작과 설정은 [프로젝트 README](../README.md)를 본다.

아래 예의 경로는 다음과 같다. 환경에 맞게 바꿔 읽는다.

| 표기 | 의미 | 예 |
| --- | --- | --- |
| `~/development/mdt` | MDT 소스를 받아 둔 위치 | `mdt-operation-http`, `mdt-client`가 이 아래에 있다 |
| `$MDT_HOME` | MDT 설치 디렉터리 | `/home/kwlee/mdt` |
| `1.7.2` | 빌드할 버전 | |

## 1. 준비

- Docker, Gradle(래퍼 없음, 시스템 `gradle` 사용), Java 21
- 형제 프로젝트: `../../common/utils`와 `../mdt-client`를 함께 빌드하므로 둘 다 받아 두어야 한다.
- 환경 변수 `MDT_HOME`: `build_image.sh`는 `$MDT_HOME/mdt-operation-http/`에서 JAR을 가져온다.
- 스크립트에는 실행 권한이 없으므로 `bash`로 실행한다.

## 2. JAR 빌드와 연결

```bash
export MDT_BUILD_VERSION=1.7.2
cd ~/development/mdt/mdt-operation-http && gradle bootJar     # build/libs/mdt-operation-http-1.7.2.jar

ln -sfn ~/development/mdt/mdt-operation-http/build/libs/mdt-operation-http-${MDT_BUILD_VERSION}.jar \
        $MDT_HOME/mdt-operation-http/mdt-operation-http.jar
```

이 링크는 호스트에서 직접 실행하는 서버(systemd 서비스 등)도 쓴다. 다시 빌드하면 실행 중인 서버의 JAR도 바뀌므로, 서버를 다시 시작해야 반영된다.

## 3. 이미지 빌드

```bash
cd ~/development/mdt/mdt-operation-http/docker
bash build_image.sh --tag 1.7.2                 # 이미지 안의 mdt 사용자: UID/GID 1000
bash build_image.sh --host-user --tag 1.7.2     # 현재 호스트 사용자의 UID/GID
```

결과는 `mdt-operation-http:1.7.2` 이미지다. 옵션은 `bash build_image.sh --help`로 볼 수 있다.

| 옵션·환경 변수 | 설명 |
| --- | --- |
| `--tag`, `-t <버전>` | 이미지 태그. 생략하면 `MDT_BUILD_VERSION` |
| `--host-user`, `-H` | 이미지의 `mdt` 사용자를 현재 호스트 사용자의 UID/GID로 만든다. |
| `--uid <UID>`, `--gid <GID>` | UID/GID를 직접 지정한다. `--host-user`와 함께 쓸 수 없다. |
| `MDT_IMAGE_NAME` | 이미지 이름(기본값 `mdt-operation-http`). `push_image.sh`에도 같은 값을 쓴다. |

- **JAR 버전 확인:** 빌드 전에 JAR의 버전(매니페스트의 `Implementation-Version`, 없으면 링크가 가리키는 파일 이름)이 태그와 같은지 확인하고, 다르면 빌드하지 않는다.
- **빌드 위치:** 임시 디렉터리를 빌드 컨텍스트로 쓰므로 `docker/` 폴더의 파일은 바뀌지 않는다.
- **이전 이미지 정리:** 빌드가 성공한 뒤에 라벨 `mdt.image=mdt-operation-http`가 붙은 dangling 이미지만 정리한다. 빌드가 실패하면 기존 이미지는 그대로 남는다.

이미지 구성은 [Dockerfile](Dockerfile)에 있다.

- **기반 이미지:** `eclipse-temurin:21-jre-jammy`를 mdt-manager·mdt-workflow와 같은 digest로 고정했다. 갱신 방법은 첫 주석에 있다.
- **Python:** uv(`ghcr.io/astral-sh/uv:0.12.5`)로 Python 3.11을 설치한다.
  - `python3 xxx.py`로 실행하는 연산은 공통 가상 환경 `/opt/mdt-python`을 쓴다. 이 환경에는 `numpy`, `matplotlib`, `opencv-python-headless`가 들어 있다.
  - `uv run python xxx.py`로 실행하는 연산(`pyproject.toml`이 있는 연산)은 uv가 연산 디렉터리에 `.venv`를 만들어 의존성을 설치한다. 첫 실행은 패키지를 받느라 느리다.
  - 연산이 다른 패키지를 쓰면 그 연산에 `pyproject.toml`을 두고 `uv run`으로 실행하거나, Dockerfile의 `uv pip install` 줄에 추가한다.
  - 연산 프로그램이 `mdtpy`를 쓰면 `pyproject.toml`에서 설치 위치를 지정해야 한다. 공통 환경에는 들어 있지 않다.
- **프로세스:** `tini`가 PID 1이 되어 연산 명령이 남긴 고아 프로세스를 거둔다. 컨테이너를 멈추면(`docker stop`) 서버가 실행 중인 연산을 취소하고 세션 디렉터리를 지운다.
- **헬스체크:** 30초마다 `GET /health`를 확인한다. 서버가 요청을 받는지만 보고, MDT 관리자 연결이나 연산은 확인하지 않는다.

## 4. 실행

서버는 작업 디렉터리(`/mdt/mdt-operation-http`)의 `config/`와 `operations/`를 읽는다. 둘 다 이미지에 없으므로 마운트한다.
마운트하는 디렉터리의 소유자 UID/GID는 이미지를 빌드할 때 쓴 값과 같아야 한다. `uv run` 연산은 연산 디렉터리에 `.venv`를 만들고,
모든 연산이 `output.log`와 세션 디렉터리를 쓰기 때문이다.

```bash
docker run -d --name mdt-operation-http -p 12987:12987 \
    -v $MDT_HOME/mdt-operation-http/config:/mdt/mdt-operation-http/config \
    -v $MDT_HOME/mdt-operation-http/operations:/mdt/mdt-operation-http/operations \
    -v $MDT_HOME/mdt-operation-http/logs:/mdt/mdt-operation-http/logs \
    mdt-operation-http:1.7.2
docker ps                     # STATUS에 (healthy)가 나오면 정상
docker logs -f mdt-operation-http
```

- **설정:** `config/application.yml`에 `server.port`와 `mdt.operation-server.mdtManagerUrl`을 반드시 적는다([프로젝트 README 5.2장](../README.md#52-설치-디렉터리)).
  JAR에는 기본 설정이 없어 `server.port`가 없으면 8080을 쓰고, 그러면 헬스체크가 실패한다.
- **MDT 관리자 주소:** 컨테이너 안에서 `localhost`는 컨테이너 자신이다. `mdtManagerUrl`(또는 `config/env.file`의 `MDT_URL`)에는
  컨테이너에서 닿는 주소를 쓴다. 예를 들어 compose 서비스 이름이나 호스트 IP다.
- **포트:** 포트를 바꾸려면 `server.port`, 포트 매핑, 환경 변수 `MDT_OPERATION_PORT`(헬스체크가 쓴다)를 함께 바꾼다.
- **메모리:** JVM 옵션은 환경 변수 `JAVA_OPTS`(기본값 `-XX:MaxRAMPercentage=75.0`)로 정한다. 메모리 제한(`--memory`, compose의 `mem_limit`)이
  없으면 이 비율은 호스트 메모리를 기준으로 계산된다. 연산 프로그램도 같은 컨테이너 메모리를 쓰므로 그만큼 남겨 둔다.

## 5. 레지스트리에 올리기(선택)

```bash
cd ~/development/mdt/mdt-operation-http/docker
bash push_image.sh --repo <레지스트리/경로> --tag 1.7.2            # <레지스트리/경로>/mdt-operation-http:1.7.2
bash push_image.sh --repo <레지스트리/경로> --tag 1.7.2 --latest   # :latest도 함께
```

레지스트리에 미리 `docker login` 해 둔다. 값이 빠진 옵션, 모르는 옵션, 빈 태그는 거부하고, `docker tag`가 실패하면 푸시하지 않는다.

## 6. 문제 해결

| 증상 | 원인과 조치 |
| --- | --- |
| `오류: $MDT_HOME 환경변수가 설정되어 있어야 합니다.` | `export MDT_HOME=...`을 설정한다. |
| `오류: JAR이 없습니다: ...` | 2장을 다시 확인한다. 링크가 가리키는 파일이 없어도 이 오류가 난다. |
| `오류: JAR 버전(x)이 이미지 태그(y)와 다릅니다` | `--tag`를 JAR 버전과 맞추거나, JAR을 다시 빌드하고 링크를 바꾼다. |
| 빌드 중 `오류: GID ... 가 이미 사용 중입니다` 또는 `UID ...` | `--uid`·`--gid`로 비어 있는 값을 지정한다. |
| 컨테이너가 바로 끝나고 로그에 `mdt.operation-server.mdtManagerUrl is not configured` | `config/`가 마운트되지 않았거나 `application.yml`에 그 값이 없다. |
| `docker ps`에 `(unhealthy)` | `server.port`와 `MDT_OPERATION_PORT`가 다르거나, 서버가 기동하지 못했다. `docker logs`로 확인한다. |
| 연산이 `FAILED`이고 `output.log`에 `ModuleNotFoundError` | 그 연산이 쓰는 패키지가 공통 환경에 없다. 3장의 Python 항목을 본다. |
| 컨테이너 로그나 `output.log`에 권한 오류 | 마운트 디렉터리의 소유자가 이미지의 UID/GID와 다르다. 4장을 확인한다. |
