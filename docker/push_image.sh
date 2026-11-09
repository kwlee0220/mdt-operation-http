#! /bin/bash

# build_image.sh로 만든 mdt-operation-http 이미지를 레지스트리에 올린다. 절차는 docker/README.md를 본다.

set -euo pipefail

usage() {
    echo "사용법: $0 --repo <REPO> [--tag <VERSION>] [--latest]"
    echo ""
    echo "옵션:"
    echo "  --repo <REPO>        (필수) 푸시할 레지스트리/레포지토리 경로 (예: myrepo, registry.example.com/mdt)"
    echo "  --tag, -t <VERSION>  태그 지정 (예: 1.3.0). 기본값은 \$MDT_BUILD_VERSION"
    echo "  --latest             latest 태그도 함께 푸시"
    echo "  --help, -h           도움말 출력"
    echo ""
    echo "환경 변수:"
    echo "  MDT_IMAGE_NAME       이미지 이름 (기본값 mdt-operation-http, build_image.sh와 같아야 함)"
    echo ""
    echo "예제:"
    echo "  $0 --repo myrepo                     # 기본 태그로 푸시"
    echo "  $0 --repo myrepo --tag 1.3.0         # 1.3.0 태그로 푸시"
    echo "  $0 --repo myrepo --latest            # latest 태그도 함께 푸시"
}

# 2026-11-08 이전에는 set -e와 인자 검증이 없었다. 값 없는 --repo·--tag는 무한 루프에 빠졌고, 모르는 옵션은
# 조용히 무시했으며, 태그가 빈 채로 진행하고 docker tag 실패도 검사하지 않았다(검토 I6).
IMAGE_NAME="${MDT_IMAGE_NAME:-mdt-operation-http}"
REPO=""
TAG=""
PUSH_LATEST=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --help|-h)
            usage
            exit 0
            ;;
        --repo)
            [[ $# -ge 2 && -n "$2" && "$2" != -* ]] || { echo "오류: --repo 뒤에 값이 필요합니다." >&2; exit 1; }
            REPO="$2"
            shift 2
            ;;
        --tag|-t)
            [[ $# -ge 2 && -n "$2" && "$2" != -* ]] || { echo "오류: $1 뒤에 값이 필요합니다." >&2; exit 1; }
            TAG="$2"
            shift 2
            ;;
        --latest)
            PUSH_LATEST=true
            shift
            ;;
        *)
            echo "오류: 알 수 없는 옵션: $1" >&2
            usage >&2
            exit 1
            ;;
    esac
done

if [[ -z "$REPO" ]]; then
    echo "오류: --repo 옵션을 반드시 지정해야 합니다." >&2
    exit 1
fi

# TAG가 지정되지 않았으면 기본값 사용
TAG="${TAG:-${MDT_BUILD_VERSION:-}}"
if [[ -z "$TAG" ]]; then
    echo "오류: --tag를 지정하거나 MDT_BUILD_VERSION 환경변수를 설정해야 합니다." >&2
    exit 1
fi

FULL_TAG="$REPO/$IMAGE_NAME:$TAG"
LATEST_TAG="$REPO/$IMAGE_NAME:latest"

echo "docker tag $IMAGE_NAME:$TAG $FULL_TAG"
docker tag "$IMAGE_NAME:$TAG" "$FULL_TAG"

echo "Docker 이미지 PUSH 시작: $FULL_TAG"
docker push "$FULL_TAG"
echo "==> 이미지 푸시 완료: $FULL_TAG"

if [[ "$PUSH_LATEST" == true ]]; then
    echo "==> latest 태그로도 푸시합니다: $LATEST_TAG"
    docker tag "$IMAGE_NAME:$TAG" "$LATEST_TAG"
    docker push "$LATEST_TAG"
    echo "==> latest 이미지 푸시 완료: $LATEST_TAG"
fi
