#! /bin/bash

# mdt-operation-http Docker 이미지를 빌드한다. 절차는 docker/README.md를 본다.
# 2026-11-08에 mdt-workflow의 build_image.sh 형식으로 다시 썼다(검토 I6). 이전 판은 빌드 전에 기존 이미지를
# 'rmi -f'로 지웠고(빌드가 실패하면 쓸 이미지가 없어짐), JAR을 docker/에 복사했다가 성공했을 때만 지웠으며,
# 자기 위치로 cd하지 않았고, set -e·옵션 값 검사·JAR 버전 확인이 없었다.

set -euo pipefail
cd "$(dirname "$0")"

# --help, -h 옵션 (옵션 파싱보다 먼저 처리)
for arg in "$@"; do
    case "$arg" in
        --help|-h)
            echo "사용법: $0 [--tag <VERSION>] [--uid <UID>] [--gid <GID>] [--host-user]"
            echo ""
            echo "옵션:"
            echo "  --tag, -t <VERSION>  버전 지정 (예: 1.3.0). 기본값은 \$MDT_BUILD_VERSION"
            echo "  --uid <UID>          mdt 사용자 UID 지정 (기본값은 Dockerfile의 ARG UID)"
            echo "  --gid <GID>          mdt 그룹 GID 지정 (기본값은 Dockerfile의 ARG GID)"
            echo "  --host-user, -H      호스트 사용자 UID/GID로 빌드 (볼륨 마운트 시 권한 맞추기)"
            echo "  --help, -h           도움말 출력"
            echo ""
            echo "환경 변수:"
            echo "  MDT_HOME             (필수) JAR을 가져올 설치 디렉터리(\$MDT_HOME/mdt-operation-http/)"
            echo "  MDT_IMAGE_NAME       이미지 이름 (기본값 mdt-operation-http, push_image.sh와 같아야 함)"
            echo ""
            echo "예제:"
            echo "  $0 --tag 1.3.0        # 1.3.0 버전으로 빌드"
            echo "  $0 -H --tag 1.3.0     # 호스트 UID/GID + 1.3.0 버전으로 빌드"
            exit 0
            ;;
    esac
done

# 옵션 값이 있는지 확인한다. 값 자리에 다른 옵션(예: '--tag --uid')이 오면 거부한다.
require_value() {
    local opt="$1" value="$2" example="$3"
    if [ -z "$value" ] || [[ "$value" == -* ]]; then
        echo "오류: '$opt' 옵션에는 값이 필요합니다 (예: $opt $example)." >&2
        echo "사용법은 '$0 --help' 참고" >&2
        exit 1
    fi
}

# UID/GID는 숫자여야 한다. 확인하지 않으면 docker build의 groupadd/useradd 단계에서야 실패한다.
require_number() {
    local opt="$1" value="$2"
    if ! [[ "$value" =~ ^[0-9]+$ ]]; then
        echo "오류: '$opt' 옵션의 값은 숫자여야 합니다: '$value'" >&2
        exit 1
    fi
}

# --tag(-t), --uid, --gid, --host-user 옵션 처리
MDT_VERSION=""
BUILD_UID=""
BUILD_GID=""
USE_HOST_USER=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --tag|-t)
            require_value "$1" "${2-}" 1.3.0
            MDT_VERSION="$2"
            shift 2
            ;;
        --uid)
            require_value "$1" "${2-}" 1000
            require_number "$1" "$2"
            BUILD_UID="$2"
            shift 2
            ;;
        --gid)
            require_value "$1" "${2-}" 1000
            require_number "$1" "$2"
            BUILD_GID="$2"
            shift 2
            ;;
        --host-user|-H)
            USE_HOST_USER=true
            shift 1
            ;;
        *)
            echo "오류: 알 수 없는 옵션 '$1'" >&2
            echo "사용법은 '$0 --help' 참고" >&2
            exit 1
            ;;
    esac
done

# --host-user 옵션 시 호스트 사용자 UID/GID 사용
if [ "$USE_HOST_USER" = true ]; then
    # --uid/--gid와 함께 쓰면 어느 값을 의도했는지 모호하므로 명확히 실패시킨다.
    if [ -n "$BUILD_UID" ] || [ -n "$BUILD_GID" ]; then
        echo "오류: --host-user 는 --uid/--gid 와 함께 사용할 수 없습니다 (둘 중 하나만 지정하세요)." >&2
        echo "사용법은 '$0 --help' 참고" >&2
        exit 1
    fi
    BUILD_UID=$(id -u)
    BUILD_GID=$(id -g)
fi

# MDT_VERSION이 지정되지 않았으면 환경변수 fallback 후 검증
if [ -z "$MDT_VERSION" ]; then
    MDT_VERSION="${MDT_BUILD_VERSION:-}"
fi
if [ -z "$MDT_VERSION" ]; then
    echo "오류: --tag 또는 \$MDT_BUILD_VERSION 둘 중 하나는 지정해야 합니다." >&2
    exit 1
fi
# 이미지 이름은 push_image.sh와 같은 환경 변수로 정한다.
IMAGE_NAME="${MDT_IMAGE_NAME:-mdt-operation-http}"
IMAGE="$IMAGE_NAME:$MDT_VERSION"

# MDT_HOME 환경변수 가드
if [ -z "${MDT_HOME:-}" ]; then
    echo "오류: \$MDT_HOME 환경변수가 설정되어 있어야 합니다." >&2
    exit 1
fi
# 2026-11-08 이전 변수 이름은 MDT_WORKFLOW_HOME(다른 스크립트에서 옮겨 온 이름)이었다.
OPERATION_JAR="$MDT_HOME/mdt-operation-http/mdt-operation-http.jar"

# JAR의 버전: 매니페스트의 Implementation-Version, 없으면 실제 파일 이름의 '-<버전>.jar'.
# 둘 다 없으면 빈 문자열을 출력한다.
jar_version() {
    local jar="$1" version real
    version=$(unzip -p "$jar" META-INF/MANIFEST.MF 2>/dev/null \
                | sed -n 's/^Implementation-Version: *//p' | tr -d '\r' | head -n 1)
    if [ -z "$version" ]; then
        real=$(readlink -f "$jar")
        if [[ "$(basename "$real")" =~ ^mdt-operation-http-([0-9][^/]*)\.jar$ ]]; then
            version="${BASH_REMATCH[1]}"
        fi
    fi
    echo "$version"
}

# 이미지에 넣을 JAR이 있고 태그와 같은 버전인지 확인한다.
if [ ! -f "$OPERATION_JAR" ]; then
    echo "오류: JAR이 없습니다: $OPERATION_JAR" >&2
    echo "      gradle bootJar로 빌드한 build/libs/mdt-operation-http-<버전>.jar를 이 경로에 복사하거나 심볼릭 링크로 연결하세요." >&2
    exit 1
fi
JAR_VERSION=$(jar_version "$OPERATION_JAR")
if [ -z "$JAR_VERSION" ]; then
    echo "경고: JAR 버전을 확인할 수 없습니다(매니페스트·파일 이름 모두 없음): $OPERATION_JAR" >&2
elif [ "$JAR_VERSION" != "$MDT_VERSION" ]; then
    echo "오류: JAR 버전($JAR_VERSION)이 이미지 태그($MDT_VERSION)와 다릅니다: $OPERATION_JAR" >&2
    exit 1
fi

# 빌드 컨텍스트는 임시 디렉터리로 만든다. docker/ 폴더에 사용자가 둔 파일을 덮어쓰거나 지우지 않는다.
BUILD_CONTEXT=$(mktemp -d)
trap 'rm -rf "$BUILD_CONTEXT"' EXIT
cp Dockerfile "$BUILD_CONTEXT/"
cp -L "$OPERATION_JAR" "$BUILD_CONTEXT/mdt-operation-http.jar"

echo "==> Docker 이미지 빌드 시작: $IMAGE"

# Docker 이미지 빌드 (UID/GID가 지정된 경우 --build-arg 전달)
BUILD_ARGS=(-t "$IMAGE")
if [ -n "$BUILD_UID" ]; then
    BUILD_ARGS+=(--build-arg "UID=$BUILD_UID")
fi
if [ -n "$BUILD_GID" ]; then
    BUILD_ARGS+=(--build-arg "GID=$BUILD_GID")
fi
docker build "${BUILD_ARGS[@]}" "$BUILD_CONTEXT"

# 빌드 성공으로 태그가 재지정되며 떨어져 나온 dangling 이미지 정리 (이 이미지 라벨에 한정).
# 이전 이미지는 빌드가 성공한 뒤에야 태그를 잃으므로, 빌드가 실패해도 기존 이미지는 남는다.
docker image prune -f --filter "label=mdt.image=mdt-operation-http"

# 성공 메시지 (실패 시 set -e가 트리거되어 여기까지 도달하지 않음)
echo "==> 빌드 완료: $IMAGE"
