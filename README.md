# mdt-operation-http

mdt-operation-http는 외부 프로그램(주로 Python 스크립트)을 **MDT 연산(operation)** 으로 노출하는 HTTP 서버다.
연산 디렉터리마다 `operation.json`에 실행할 명령줄을 적어 두면, 서버가 요청을 받을 때마다 그 명령을 실행하고
결과를 RESTful 비동기 RPC로 돌려준다.

- **호출하는 쪽:** MDT 인스턴스의 HTTP 연산(`mdt-instance`의 `HttpOperationProvider`), 워크플로 태스크(`mdt-client`의
  `RESTfulAsyncRpcTask`), CLI(`mdt-cli`의 `RESTfulAsyncRpcCommand`).
- **RPC 계약:** `utils.rpc.restful`의 비동기 RPC를 그대로 쓴다. 명령 실행은 `utils.rpc.restful.process.RESTfulCommandExecutionServer`가
  맡고, 이 프로젝트는 여러 연산을 한 서버로 묶는 REST 컨트롤러(`OperationController`)와 MDT 모델 값 변환을 더한다.

## 1. 동작 개요

```
클라이언트                       mdt-operation-http                         외부 프로그램
   │ POST /api/v1/operations/{opId}  │                                          │
   │────────────────────────────────▶│ operations/{opId}/operation.json 로 명령 구성│
   │                                 │ 입력 → 명령 변수(값·파일)                  │
   │  202 { status: RUNNING,         │──────── 명령 실행 ───────────────────────▶│
   │        session_endpoint }       │                                          │
   │◀────────────────────────────────│                                          │
   │ GET /api/v1/sessions/{sessionId}│ (반복)                                    │
   │────────────────────────────────▶│  RUNNING                                 │
   │                                 │◀──────── 종료, 출력 파일 기록 ────────────│
   │  200 { status: COMPLETED,       │ 출력 파일 → 출력 값                       │
   │        outputs: {...} }         │                                          │
   │◀────────────────────────────────│ 결과를 돌려준 뒤 세션 정리                 │
```

1. 클라이언트가 연산을 시작하면 서버는 세션 디렉터리를 만들고, 입력 값을 명령 변수로 바꾸어 명령을 실행한다.
2. 응답에는 세션 엔드포인트(`/api/v1/sessions/<세션 id>`)가 들어 있고, 클라이언트는 이 주소로 상태를 조회한다.
   세션 id는 `<UUID>-<번호>` 형식이다. UUID는 서버를 시작하거나 연산을 다시 읽을 때마다 달라지므로, 재시작 전의 세션 주소가
   새 세션을 가리키는 일은 없다.
   - 연산이 시작되면 시작 응답은 늘 `RUNNING`이다. 명령이 그 사이에 이미 끝났더라도 결과는 상태 조회로만 돌려준다.
3. 명령이 끝나면 출력 변수 파일을 읽어 `outputs`로 돌려준다. 결과를 한 번 돌려주었거나 보존 시간이 지나면
   세션과 세션 디렉터리를 지운다.

## 2. REST API

모든 경로의 접두사는 `/api/v1`이다.

| 메서드·경로 | 하는 일 | 응답 |
| --- | --- | --- |
| `GET /operations` | 연산 목록을 돌려준다. 서버가 떠 있는 동안 추가된 연산 디렉터리도 이때 읽는다. | `200` 목록(연산 id 순) |
| `GET /operations/{opId}` | 연산 정보(출력 변수, 제한 시간)를 돌려준다. | `200`, `404` 연산 없음 |
| `POST /operations/{opId}` | 연산 `{opId}`를 시작한다. 본문은 `RpcRequestMessage`(`Content-Type: application/json`). | `202` 시작함, `404` 연산 없음 |
| `POST /operations/{opId}/reload` | `operation.json`을 다시 읽는다(5.3장). | `200` 새 연산 정보, `404` 디스크립터 없음 |
| `GET /sessions/{sessionId}` | 세션의 상태를 조회한다. | `200` 상태, `404` 세션 없음(이미 회수됨 포함) |
| `DELETE /sessions/{sessionId}` | 세션의 연산을 취소한다. | `200` 취소됨(`CANCELLED`), `409` 지금 취소할 수 없음, `404` 세션 없음 |

- **연산 정보:** `{"id", "outputVariables", "timeout", "startTimeout", "sessionRetainTimeout"}`. 시간 값은 `PT1M` 같은
  ISO-8601 기간 문자열이고, 지정하지 않았으면 `null`이다. 서버 내부 경로가 드러나는 `commandLine`·`workingDirectory`는 넣지 않는다.
- **요청 본문** `RpcRequestMessage`: `{"inputs": {이름: 값, ...}, "outputs": {이름: 값, ...}}`. `outputs`는 생략할 수 있다(4.2장).
- **응답 본문** `RpcResponseMessage`: `session_endpoint`, `status`(`RUNNING`/`COMPLETED`/`FAILED`/`CANCELLED`),
  `outputs`, `error`. 예전 이름 `state`·`sessionEndpoint`는 쓰지 않으므로 옛 클라이언트와는 통신할 수 없다.
- **시작 응답:** 연산이 시작되면 `202`와 `status: RUNNING`, `session_endpoint`를 돌려준다. 명령이 아주 빨리 끝나도 시작 응답은
  `RUNNING`이고, 결과(`COMPLETED`와 `outputs`)는 `GET /sessions/{sessionId}`로 받는다. 두 클라이언트(Java, mdtpy)가 시작 응답으로
  `RUNNING`·`FAILED`만 받기 때문이다.
- **시작하지 못한 경우:** 입력 값이 잘못되었거나(4.1장) 명령을 실행하지 못하면, `202`와 함께 `status: FAILED`, `error`를
  담은 응답을 돌려준다. 세션은 만들어지지 않는다. Java(`RESTfulAsyncRpcClient`)와 Python(mdtpy) 클라이언트는 2xx 응답의
  `FAILED`만 연산 실패로 해석하므로 이 형식을 유지한다.
- **`409`가 나는 경우:** 연산이 이미 끝났거나(`COMPLETED`/`FAILED`), 취소 요청을 받고도 계속 실행 중인 경우다.
  계속 실행 중인 세션은 남아 있어 이후에도 조회할 수 있다.
- **오류 응답:** 본문은 `RESTfulErrorEntity`다. 요청 처리 중 예외는 다음 상태 코드로 바뀐다.

| 예외 | 상태 코드 |
| --- | --- |
| 본문이 JSON이 아니거나 `RpcRequestMessage` 형식이 아님 | `400` |
| `IllegalArgumentException` (연산 id에 `/`·`\`가 있거나 `.`·`..`인 경우 등) | `400` |
| `ResourceNotFoundException` | `404` |
| `TimeoutException` | `408` |
| Spring이 정한 오류(`Content-Type`이 JSON이 아님 `415`, 허용하지 않는 메서드 `405` 등) | 그 상태 코드 |
| 그 밖의 예외 | `500` |

- **로그:** `4xx`는 WARN 한 줄로, `5xx`는 ERROR와 스택으로 남는다.
- **그 밖의 경로:** `GET /health`는 서버가 요청을 받으면 `ok`를 돌려준다(Docker 헬스체크용). `/swagger-ui`에서 API 설명을 볼 수 있다.

## 3. 연산 만들기

연산 하나는 `operations/` 아래 디렉터리 하나다. 디렉터리 이름이 연산 id가 된다.

```
operations/
└── AddAndSleep/
    ├── operation.json      # 필수: 연산 디스크립터
    ├── op_increment.py     # 실행할 프로그램
    ├── env.file            # 선택: 명령에 넘길 환경 변수
    └── output.log          # 실행 중 자동 생성: 명령의 stdout (append)
```

### 3.1 `operation.json`

예: `AddAndSleep`

```json
{
  "commandLine": [
    "python3", "op_increment.py",
    "${Data:value}",
    "--inc", "${IncAmount:value}",
    "--sleep", "${SleepTime:value}",
    "--output", "${Output:path}"
  ],
  "outputVariables": ["Output"],
  "timeout": "1m",
  "startTimeout": "2s",
  "sessionRetainTimeout": "30s"
}
```

| 키 | 필수 | 설명 |
| --- | --- | --- |
| `commandLine` | ✓ | 실행할 명령줄. 토큰 단위 배열이다. `${이름:수식어}`는 실행 직전에 바뀐다(3.2장). |
| `workingDirectory` | | 명령의 작업 디렉터리. 생략하면 `operation.json`이 있는 디렉터리. 상대 경로는 서버를 실행한 디렉터리 기준이다. 세션 디렉터리, `env.file`, `output.log`도 이 디렉터리를 기준으로 한다. |
| `outputVariables` | | 명령이 결과를 기록할 출력 변수 이름 목록. |
| `timeout` | | 명령 실행 제한 시간. 넘으면 명령을 종료하고 실패로 끝난다. 생략하면 제한이 없다. |
| `startTimeout` | | 명령이 시작되기를 기다리는 제한 시간. 기본값 5초. 넘으면 시작 응답이 `FAILED`다. |
| `sessionRetainTimeout` | | 끝난 세션의 결과를 회수할 때까지 보관하는 시간. 기본값 30초. |

- 시간 값은 `"30s"`, `"5m"`, `"1h"`처럼 쓴다.
- 이 표에 없는 키(예: 예전 형식의 `command`, `portParameters`, `runAsync`)는 읽지 않는다.

### 3.2 명령 변수와 수식어

입력 변수(요청의 `inputs`)와 출력 변수(`outputVariables`)는 명령줄에서 `${이름:수식어}`로 쓴다.

| 수식어 | 바뀌는 값 | 쓰임 |
| --- | --- | --- |
| `value` | 변수 값 문자열 | 입력 값을 인자로 바로 넘길 때 |
| `path` | 값이 기록된 파일의 절대 경로 | 큰 입력이나 파일 입력을 넘길 때, 출력 파일 위치를 알려 줄 때 |
| `name` | 변수 이름 | |

- **세션 디렉터리:** 변수 파일은 요청마다 만드는 `session-<UUID>-<번호>` 디렉터리 안에 생기고, 세션이 정리될 때 함께 지워진다.
- **출력:** 프로그램은 `${이름:path}`로 받은 경로에 결과를 써야 한다. 내용은 JSON으로 읽으므로 숫자는 `11`, 문자열은
  `"abc"`처럼 JSON 형식으로 쓴다.
- **`env.file`:** 작업 디렉터리에 두면 그 내용이 명령의 환경 변수로 들어간다.
- **로그:** 명령의 stdout은 작업 디렉터리의 `output.log`에 이어 쓰고, stderr는 서버의 표준 출력으로 나간다.
  `output.log`는 세션이 지워져도 남으므로 직접 정리한다.

## 4. 입력·출력 값 형식

### 4.1 입력 값

입력 값 하나는 JSON 하나다. 서버는 그 모양을 보고 다음 중 하나로 해석한다.

| 입력 JSON | 해석 | 명령 변수에 들어가는 값 |
| --- | --- | --- |
| `@type`이 `mdt:ref:`로 시작 | MDT 요소 참조 | 참조가 가리키는 요소의 현재 값(값 객체 JSON). 파일(`File`) 요소면 첨부 파일 내용을 변수 파일로 내려받는다. |
| `@type`이 `mdt:value:`로 시작 | MDT `ElementValue` | 값 객체 JSON. 파일 값은 받지 않는다. |
| `modelType`이 있음 | AAS `SubmodelElement` | 그 요소의 값 객체 JSON. `File` 요소는 받지 않는다. |
| 그 밖의 JSON | 일반 값 | JSON 그대로 |

- **문자열 주의:** 값은 JSON 문자열로 바꿔 넣으므로, 문자열 입력 `"abc"`의 `${이름:value}`는 따옴표까지 포함한 `"abc"`가 된다.
- **참조 입력:** 서버가 설정의 `mdtManagerUrl`(5장)로 MDT 관리자에 접속해 값을 읽는다.

### 4.2 출력 값

- 요청에 `outputs`를 주지 않으면 출력 파일 내용을 일반 JSON으로 읽어 돌려준다.
- `outputs`에 출력 변수의 값(예: 출력을 받을 요소의 참조)을 주면, 그 요소의 현재 값 형태에 맞춰 출력 파일을 해석해 돌려준다.
- **원격 모델에는 쓰지 않는다:** 참조를 주어도 서버는 결과를 그 요소에 기록하지 않는다. 모델에 저장하려면 호출하는 쪽이 저장한다.
- **파일 출력은 예외다:** 출력 위치로 `File` 요소의 MDT 참조(`@type`이 `mdt:ref:`)를 주면, 서버는 그 요소의 현재 첨부 파일을
  내려받지 않는다. 명령이 끝나면 `${이름:path}` 파일을 그 요소의 첨부 파일로 올리고, 갱신된 `FileValue`를 돌려준다.
  세션 디렉터리는 곧 지워지므로 서버가 올리지 않으면 결과 파일을 받을 방법이 없기 때문이다. mdt-cli의 `RESTfulAsyncRpcCommand`가
  이 방식으로 파일 출력을 받는다.
  - 첨부 파일 이름은 출력 변수 이름이 되고(확장자 없음), content type은 파일 내용으로 판별한다.
  - 명령이 출력 파일을 기록하지 않으면 결과 조회가 실패한다.
  - `File` 요소를 참조가 아닌 값(AAS `SubmodelElement`, `mdt:value:`)으로 주면 받지 않는다(4.1장). mdt-instance의 HTTP 연산은
    출력을 `SubmodelElement`로 보내므로, `File` 출력 변수를 가진 연산은 시작하지 못하고 `FAILED`가 된다.

## 5. 빌드와 설정

### 5.1 빌드

Java 21과 시스템 `gradle`이 필요하다(래퍼 없음). 형제 프로젝트 `../../common/utils`와 `../mdt-client`를 함께 빌드하므로 둘 다 받아 두어야 한다.

```bash
export MDT_BUILD_VERSION=1.7.2
gradle bootJar                    # build/libs/mdt-operation-http-1.7.2.jar
ln -sfn $PWD/build/libs/mdt-operation-http-${MDT_BUILD_VERSION}.jar \
        $MDT_HOME/mdt-operation-http/mdt-operation-http.jar
```

### 5.2 설치 디렉터리

서버는 **현재 디렉터리**를 기준으로 설정과 연산을 찾는다. 배포 위치는 `$MDT_HOME/mdt-operation-http`다.

```
$MDT_HOME/mdt-operation-http/
├── mdt-operation-http.jar     # 5.1의 링크
├── config/
│   ├── application.yml        # Spring 설정
│   ├── env.file               # 설정에서 ${...}로 참조할 변수 (예: MDT_URL)
│   └── logback.xml
├── operations/                # 3장의 연산 디렉터리들
└── sbin/                      # 실행·systemd 등록 스크립트
```

`config/application.yml`의 예:

```yaml
server:
   port: 12987
   error:
      include-message: always

logging:
   config: file:config/logback.xml

mdt:
   operation-server:
      mdtManagerUrl: ${MDT_URL}      # MDT 관리자 URL (필수)
      operationsDir: operations      # 연산 디렉터리 (기본값: 현재 디렉터리의 operations)
```

- **`mdtManagerUrl`은 필수다.** 없으면 서버가 시작하지 않는다.
- **옛 설정 키:** 2026-11-08 이전 키 `rpc-servers.instanceManagerUrl`·`rpc-servers.operationsDir`는 읽지 않는다(별칭 없음).
  옛 설정 파일은 위 형식으로 고친다. 거꾸로 그 이전에 빌드한 JAR은 새 키를 읽지 못하므로, 설정과 JAR을 함께 바꾼다.
- **포트:** JAR에 기본 설정이 없으므로 `config/application.yml`이 없으면 Spring 기본값인 8080을 쓴다. MDT 배포에서는 12987을 쓴다.
- **`config/env.file`:** 시작할 때 읽어 가장 높은 우선순위의 설정 값으로 넣는다. 다른 파일을 쓰려면
  시스템 프로퍼티 `-Denv.file=<경로>`나 환경 변수 `ENV_FILE`로 지정한다. 파일이 없으면 건너뛴다.
  로그에는 변수 이름만 남기고 값은 남기지 않는다.

### 5.3 실행

```bash
cd $MDT_HOME/mdt-operation-http
java -jar mdt-operation-http.jar
```

- `sbin/run_mdt_operation_server.sh`가 같은 일을 한다.
- systemd 서비스로 등록하려면 `sbin/install_system_service.sh`를 쓰고, 해제할 때는 `uninstall_system_service.sh`를 쓴다.
- **연산 등록:** 시작할 때 `operations/` 아래 연산을 모두 등록한다. 서버가 떠 있는 동안 새로 추가한 연산 디렉터리는
  처음 호출될 때나 `GET /api/v1/operations`를 부를 때 읽는다.
- **`operation.json`을 고쳤을 때:** `POST /api/v1/operations/{opId}/reload`로 다시 읽게 한다. 이후 요청부터 새 설정을 쓰고,
  이미 실행 중인 세션은 이전 설정으로 끝까지 수행되며 계속 조회·취소할 수 있다. 디스크립터를 읽지 못하면 이전 설정을 그대로 쓴다.
  ```bash
  curl -s -X POST http://localhost:12987/api/v1/operations/AddAndSleep/reload
  ```
- **종료:** 서버를 멈추면(`systemctl stop`, `Ctrl-C`, `docker stop`) 실행 중인 연산을 취소한다. 외부 명령은 그 하위 프로세스까지
  종료되고 세션 디렉터리는 지워진다. 10초 안에 끝나지 않는 연산은 기다리지 않는다.
- **비정상 종료 뒤:** 서버가 강제로 죽어 정리하지 못한 세션 디렉터리(`session-<UUID>-<번호>`)는 다음 기동 때 지운다.
  같은 연산 디렉터리를 두 서버 프로세스가 함께 쓰는 구성은 지원하지 않는다.

### 5.4 호출 예

```bash
curl -s -X POST http://localhost:12987/api/v1/operations/AddAndSleep \
     -H 'Content-Type: application/json' \
     -d '{"inputs": {"Data": 10, "IncAmount": 1, "SleepTime": 2}}'
# → 202 {"session_endpoint": "/api/v1/sessions/<UUID>-1", "status": "RUNNING", ...}

curl -s http://localhost:12987/api/v1/sessions/<UUID>-1        # 위 응답의 session_endpoint
# → 실행 중이면 "status": "RUNNING", 끝나면 "status": "COMPLETED", "outputs": {"Output": 11}
```

MDT 인스턴스에서 HTTP 연산으로 연결할 때는 연산 서버의 기본 URL(예: `http://localhost:12987`)을 `endpoint`로 준다.
인스턴스는 `POST {endpoint}/api/v1/operations/{연산 id}`로 시작하고 세션 상태를 polling한다.

## 6. Docker 이미지

`docker/`에 이미지 빌드(`build_image.sh`)와 푸시(`push_image.sh`) 스크립트가 있다. 빌드·실행·푸시 절차, 이미지의 Python 환경,
마운트할 디렉터리는 [docker/README.md](docker/README.md)를 본다.

```bash
cd docker
bash build_image.sh --tag 1.7.2      # $MDT_HOME/mdt-operation-http/mdt-operation-http.jar 로 mdt-operation-http:1.7.2 를 만든다
bash push_image.sh --repo <레지스트리/경로> --tag 1.7.2 [--latest]
```

## 7. 문제 해결

| 증상 | 원인과 조치 |
| --- | --- |
| 시작할 때 `mdt.operation-server.mdtManagerUrl is not configured` | `config/application.yml`의 `mdt.operation-server.mdtManagerUrl`이 없다. 옛 키(`rpc-servers.*`)를 쓰고 있다면 5.2장대로 바꾼다. `${MDT_URL}`을 쓴다면 `config/env.file`에 `MDT_URL`이 있는지 본다. |
| 로그에 `operations directory not found` | 서버를 `$MDT_HOME/mdt-operation-http`가 아닌 곳에서 실행했거나 `operationsDir`이 틀렸다. 연산 없이 시작한다. |
| `POST /operations/{opId}`가 `404` | `operations/{opId}/operation.json`이 없다. 디렉터리 이름과 연산 id의 대소문자까지 같은지 본다. |
| 로그에 `failed to load operation: id=...` | `operation.json`이 JSON 형식에 맞지 않는다. 그 연산만 빠진다. 고친 뒤 `reload`를 부른다. |
| `operation.json`을 고쳤는데 반영되지 않음 | 이미 등록된 연산은 다시 읽지 않는다. `POST /api/v1/operations/{opId}/reload`를 부른다(5.3장). |
| `POST /operations/{opId}`가 `415` | `Content-Type: application/json` 헤더가 없다. |
| `GET /sessions/{sessionId}`이 `404` | 결과를 이미 한 번 받았거나 `sessionRetainTimeout`이 지나 세션이 정리되었다. 서버를 다시 시작해도 세션은 모두 사라진다. |
| 시작 응답이 `202`인데 `status`가 `FAILED` | 연산을 시작하지 못했다. `error.message`에 원인이 있다. 모르는 `@type`, 파일 값을 값으로 준 경우 등은 입력 오류(`IllegalArgumentException`)다(4.1장). |
| 명령은 끝났는데 결과 조회가 오류로 끝남 | 출력 파일 내용이 JSON이 아닐 수 있다(예: 따옴표 없는 문자열). 3.2장을 본다. |
| 프로그램이 어떻게 실행됐는지 보고 싶다 | 연산 디렉터리의 `output.log`(stdout)와 서버 로그(stderr)를 본다. |
