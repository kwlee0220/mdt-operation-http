# 기반 이미지를 digest로 고정해, 같은 소스로 빌드하면 같은 JRE·OS 패키지가 들어가게 한다(검토 I6).
# mdt-manager·mdt-workflow 이미지와 같은 digest를 쓴다. 대신 보안 갱신을 자동으로 받지 않으므로 주기적으로 갱신한다:
#   docker buildx imagetools inspect eclipse-temurin:21-jre-jammy   # 'Digest:' 값으로 아래를 바꾼다
FROM eclipse-temurin:21-jre-jammy@sha256:1eac8b8fee11ca7d6725a48bc5d41992ed1561504ebae25490d2d062847e8269

# build_image.sh 의 'docker image prune --filter' 가 이 라벨로 자기 dangling 이미지만 정리한다.
LABEL mdt.image=mdt-operation-http

# UID/GID 설정 (빌드 시 --build-arg UID=1000 형태로 오버라이드 가능)
ARG UID=1000
ARG GID=1000

ENV MDT_HOME=/mdt

# curl: 아래 HEALTHCHECK에서 쓴다.
# tini: PID 1로 java를 감싸, 연산 명령이 남긴 고아 프로세스를 거둔다(아래 ENTRYPOINT).
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl tini \
 && apt-get clean \
 && rm -rf /var/lib/apt/lists/*

# 연산 프로그램용 Python 3.11.
# - uv로 Python을 설치한다. 2026-11-08 이전에는 deadsnakes PPA로 설치했는데, PPA는 버전을 고정할 수 없고,
#   pip3(시스템 Python 3.10용)로 넣은 패키지가 python3(3.11)에서 보이지 않았다.
# - 공통 패키지는 /opt/mdt-python 가상 환경에 넣고 PATH 앞에 둔다. 그래서 'python3 xxx.py'로 실행하는 연산은
#   이 환경을 쓴다. 공통 패키지는 배포된 연산 프로그램들이 import하는 것(numpy, cv2, matplotlib)이다.
#   GUI 라이브러리(PySide6)는 쓰는 연산이 없어 뺐고, OpenCV는 GUI가 없는 headless 판을 쓴다.
# - 'uv run python xxx.py'로 실행하는 연산(pyproject.toml이 있는 연산)은 uv가 연산 디렉터리에 자기 가상 환경을
#   만든다. 이때 같은 Python(UV_PYTHON_INSTALL_DIR)을 쓴다.
# - uv 버전을 바꾸려면 아래 이미지 태그를 바꾼다.
COPY --from=ghcr.io/astral-sh/uv:0.12.5 /uv /uvx /usr/local/bin/
ENV UV_PYTHON_INSTALL_DIR=/opt/uv/python \
    UV_PYTHON_PREFERENCE=only-managed
RUN UV_NO_CACHE=1 uv python install 3.11 \
 && UV_NO_CACHE=1 uv venv --python 3.11 /opt/mdt-python \
 && UV_NO_CACHE=1 uv pip install --python /opt/mdt-python numpy matplotlib opencv-python-headless
ENV PATH=/opt/mdt-python/bin:$PATH

# mdt 사용자 생성 + 작업 디렉토리 준비 + 권한 부여 (단일 layer)
# GID/UID가 베이스 이미지에 이미 점유돼 있으면(예: --host-user로 GID 100=users 전달)
# 조용히 재사용하지 않고 충돌 원인을 명확히 알리며 빌드를 중단한다.
RUN if getent group "${GID}" >/dev/null; then \
        echo "오류: GID ${GID}가 이미 사용 중입니다 ($(getent group "${GID}" | cut -d: -f1)). --gid 로 비어 있는 GID를 지정하세요." >&2; \
        exit 1; \
    fi \
 && if getent passwd "${UID}" >/dev/null; then \
        echo "오류: UID ${UID}가 이미 사용 중입니다 ($(getent passwd "${UID}" | cut -d: -f1)). --uid 로 비어 있는 UID를 지정하세요." >&2; \
        exit 1; \
    fi \
 && groupadd -g ${GID} mdt \
 && useradd -u ${UID} -g mdt -m -s /bin/bash mdt \
 && mkdir -p /mdt/mdt-operation-http/config \
             /mdt/mdt-operation-http/operations \
             /mdt/mdt-operation-http/logs \
 && chown -R mdt:mdt /mdt

# 아래 JAR은 빌드 컨텍스트에 있어야 한다. docker/build_image.sh는 임시 디렉터리를 컨텍스트로 만들어
# 이 Dockerfile과 $MDT_HOME/mdt-operation-http/의 JAR(버전 확인 후)을 복사해 빌드하고, 끝나면 그 디렉터리만 지운다.
# docker build를 직접 쓰려면 이 Dockerfile과 JAR을 한 디렉터리에 두고 그 디렉터리를 컨텍스트로 지정한다.
COPY --chown=mdt:mdt ./mdt-operation-http.jar /mdt/mdt-operation-http/

# 서버는 현재 디렉터리의 config/, operations/를 읽는다. 둘 다 실행할 때 마운트한다.
WORKDIR /mdt/mdt-operation-http
USER mdt

# REST 포트. config/application.yml의 server.port를 바꾸면 MDT_OPERATION_PORT도 같이 바꾼다.
# JAR에는 기본 설정이 없으므로 config/application.yml에 server.port를 꼭 적는다(없으면 8080).
ENV MDT_OPERATION_PORT=12987
EXPOSE 12987

# GET /health가 응답하는지 확인한다. MDT 관리자 연결이나 연산 디렉터리는 확인하지 않는다.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS "http://localhost:${MDT_OPERATION_PORT}/health" > /dev/null || exit 1

# 연산 명령은 java의 자식 프로세스로 실행되고, 그 명령이 다시 자식을 띄울 수 있다(uv run → python 등).
# tini가 PID 1이 되어 고아가 된 프로세스를 거두고, SIGTERM 등은 java에 전달한다. java는 종료할 때
# 실행 중인 연산을 취소한다.
ENTRYPOINT ["/usr/bin/tini", "--"]

# JVM 옵션. 'docker run -e JAVA_OPTS=...'이나 compose의 environment로 바꾼다.
# sh가 exec로 java를 실행하므로 tini 아래에서 java가 바로 신호를 받는다.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
CMD ["sh", "-c", "exec java $JAVA_OPTS -jar /mdt/mdt-operation-http/mdt-operation-http.jar"]
