#! /bin/bash

# 사용법 출력 (--help 옵션)
if [[ "$1" == "--help" || "$1" == "-h" ]]; then
    echo "사용법: $0 --repo <REPO> [--tag <VERSION>]"
    echo ""
    echo "옵션:"
    echo "  --repo <REPO>           (필수) 푸시할 도커 레포지토리 경로 (예: myrepo)"
    echo "  --tag, -t <VERSION> 태그 지정 (예: 1.3.0). 기본값은 \$MDT_BUILD_VERSION"
    echo "  --help, -h              도움말 출력"
    echo ""
    echo "예제:"
    echo "  $0 --repo myrepo                     # 기본 태그로 푸시"
    echo "  $0 --repo myrepo --tag 1.3.0         # 1.3.0 태그로 푸시"
    exit 0
fi

# --repo 옵션 처리 및 FULL_TAG 변수 설정
REPO=""
# --tag(-t) 옵션 처리 및 TAG 변수 설정
TAG=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --repo)
            REPO="$2"
            shift 2
            ;;
        --tag|-t)
            TAG="$2"
            shift 2
            ;;
        *)
            break
            ;;
    esac
done

if [ -z "$REPO" ]; then
    echo "오류: --repo 옵션을 반드시 지정해야 합니다." >&2
    exit 1
fi

# TAG가 지정되지 않았으면 기본값 사용
if [ -z "$TAG" ]; then
    TAG="$MDT_BUILD_VERSION"
fi

FULL_TAG="$REPO/mdt-operation-http:$TAG"

# 이미지 태그 변경
echo "docker tag mdt-operation-http:$TAG $FULL_TAG"
docker tag mdt-operation-http:$TAG $FULL_TAG

echo "Docker 이미지 PUSH 시작: $FULL_TAG"
docker push $FULL_TAG

# 성공 메시지
if [ $? -eq 0 ]; then
    echo "==> DockerHub에 이미지 푸시 완료: $FULL_TAG"
else
    echo "==> DockerHub에 이미지 푸시 실패!"
    exit 1
fi