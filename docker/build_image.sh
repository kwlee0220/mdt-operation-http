#! /bin/bash

# --tag(-t) 옵션 처리 및 MDT_VERSION 변수 설정
MDT_VERSION=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --tag|-t)
            MDT_VERSION="$2"
            shift 2
            ;;
        *)
            break
            ;;
    esac
done

# MDT_VERSION이 지정되지 않았으면 기본값 사용
if [ -z "$MDT_VERSION" ]; then
    MDT_VERSION="$MDT_BUILD_VERSION"
fi
REPOSITORY="mdt-operation-http:$MDT_VERSION"

# 사용법 출력 (--help 옵션)
if [[ "$1" == "--help" || "$1" == "-h" ]]; then
    echo "사용법: $0 [--tag <VERSION>]"
    echo ""
    echo "옵션:"
    echo "  --tag, -t <VERSION> 버전 지정 (예: 1.3.0). 기본값은 \$MDT_BUILD_VERSION"
    echo "  --help, -h              도움말 출력"
    echo ""
    echo "예제:"
    echo "  $0                        # 기본 버전으로 빌드"
    echo "  $0 --tag 1.3.0        # 1.3.0 버전으로 빌드"
    exit 0
fi

# 기존 이미지 삭제
docker image rmi -f $REPOSITORY

echo "==> Docker 이미지 빌드 시작: $REPOSITORY"
MDT_WORKFLOW_HOME=$MDT_HOME/mdt-operation-http
cp $MDT_WORKFLOW_HOME/mdt-operation-http.jar mdt-operation-http.jar

# Docker 이미지 빌드
docker build -t $REPOSITORY .

# 성공 메시지
if [ $? -eq 0 ]; then
    echo "==> 빌드 완료: $REPOSITORY"
else
    echo "==> 빌드 실패!"
    exit 1
fi

# 클론한 디렉토리 정리
rm mdt-operation-http.jar