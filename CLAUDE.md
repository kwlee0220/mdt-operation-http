# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

`mdt-operation-http` is a small Spring Boot 3.5 service (Java 21, entry point `mdt.operation.server.MDTOperationServerApplication`) that exposes external programs as MDT operations over the RESTful async RPC protocol of `utils.rpc.restful`. Each directory under `operations/` holds an `operation.json` (a `utils.rpc.restful.process.CommandExecutionDescriptor`). For each request the server builds a `CommandExecution`, runs it in a per-request session directory, and returns the output variables. User-facing documentation, in Korean: [README.md](README.md). It covers the API, the operation directory layout, value formats, deployment and troubleshooting. Update it when behavior changes. The Docker image procedure is in [docker/README.md](docker/README.md).

Callers:
- `mdt-instance` `mdt.assetconnection.operation.HttpOperationProvider`: `endpoint` is this server's base URL.
- `mdt-client` `mdt.task.RESTfulAsyncRpcTask`.
- `mdt-cli` `mdt.cli.run.RESTfulAsyncRpcCommand`.
- `mdt-client`'s `HttpOperationClient` is deprecated and does **not** match this server's URLs or messages.

The project is not a git repository. Source comments and Javadoc are in Korean. Match that when editing near them. Comments such as `(검토 D1)` refer to the 2026-11-08 code review. Its tracking document was deleted once every item was done, so the comment and the dated note beside it are the only record left.

## Build and test

Composite build: `settings.gradle` includes `../../common/utils` (`:utils`) and `../mdt-client` (`:mdt-client`). Library versions come from `../mdt-client/gradle/libs.versions.toml`. There is no Gradle wrapper: use the system `gradle`. The jar version is `$MDT_BUILD_VERSION`, or `unknown` when it is unset.

```bash
gradle :test -x :utils:test -x :mdt-client:test   # this project's tests only (plain `gradle test` also runs all utils tests)
gradle bootJar                                     # build/libs/mdt-operation-http-<ver>.jar (no -all suffix)
```

- **`bootJar` overwrites the deployed JAR.** `$MDT_HOME/mdt-operation-http/mdt-operation-http.jar` is a symlink to `build/libs/mdt-operation-http-1.7.2.jar`. To try a build without touching it, use another version (`MDT_BUILD_VERSION=0.0.0-test gradle bootJar`) and run that jar from a scratch directory with its own `config/application.yml` (any `mdt.operation-server.mdtManagerUrl` works: `HttpMDTManager.connect` does not contact the server at startup).
- **Test framework:** tests are **JUnit 4** (`libs.junit4`, `org.junit.Test`, `@Rule TemporaryFolder`) plus `mockito-core`. This differs from `utils`, which uses Jupiter. There is no `spring-boot-starter-test`, and the `test` task is not configured: Gradle's default JUnit 4 runner runs them.
- **How controller tests work:** they call the methods directly and inject `m_config` / `m_serde` by reflection (`OperationControllerTest.setField`).
- **What the tests cover:**
  - `OperationControllerTest`: routing, ids, exception → status mapping, concurrent first load.
  - `OperationControllerLifecycleTest`: real `/bin/sh` / `/bin/sleep` operations (Linux only): start → COMPLETED → read-once 404, cancel, FAILED start, list/get, reload, `destroy`.
  - `MDTCommandVariableSerDeTest`: plain values, argument checks, file outputs with a mocked reference.
  - `OperationServerPropertiesTest`.

## Structure (`mdt.operation.server`)

**Renamed on 2026-11-08 with no aliases:**
- Package `mdt.operation.servers` → `mdt.operation.server`.
- `RpcServerDispatcher` → `OperationController`; its endpoint methods `run`/`status`/`delete` → `startOperation`/`getSessionStatus`/`cancelSession`, and its path variables `{id}`/`{sid}` → `{opId}`/`{sessionId}`. The URLs did not change.
- `RpcServerDispatcherApplication` → `MDTOperationServerApplication` (`bootJar.mainClass` follows).
- `RpcServersConfiguration` → `OperationServerProperties`, together with its config keys (see below).
- `RpcServerDispatcherEnvironment` → `EnvFilePostProcessor`.
- Controller internals: `RpcServerInfo` → `RegisteredOperation`, `m_serverInfos` → `m_operations`, `findRpcServer` → `findOperationBySession`.

Logger names changed accordingly (`mdt.operation.server.*`).

- **`OperationController`** (`@RestController`, prefix `/api/v1`, `InitializingBean` + `DisposableBean`). Endpoints:

  | Endpoint | Method | Result |
  |---|---|---|
  | `GET /operations` | `listOperations` | loads not-yet-registered operation dirs first; sorted `OperationInfo` list |
  | `GET /operations/{opId}` | `getOperation` | `OperationInfo` or 404 |
  | `POST /operations/{opId}` | `startOperation` | 202 + `RpcResponseMessage`, 404 unknown op, 400 bad id |
  | `POST /operations/{opId}/reload` | `reload` | re-reads `operation.json`; 404 (and unregisters) if it is gone |
  | `GET /sessions/{sessionId}` | `getSessionStatus` | 200 or 404 |
  | `DELETE /sessions/{sessionId}` | `cancelSession` | 200 `CANCELLED`, 409 (cannot cancel now), 404 |

  - **`OperationInfo`** (record) = `id`, `outputVariables`, ISO-8601 `timeout` / `startTimeout` / `sessionRetainTimeout` (null when unset). It deliberately omits `commandLine` and `workingDirectory`: they expose server paths.
  - **`startOperation` takes `@RequestBody RpcRequestMessage`** (Spring's `ObjectMapper`). A non-JSON or malformed body is `HttpMessageNotReadableException` → 400. A wrong `Content-Type` is 415 (Spring `ErrorResponse`). The Java and Python clients send `application/json`.
  - **A start that fails** (bad input, command cannot run) is still **202 with `status: FAILED`** and no session. This is deliberate: `RESTfulAsyncRpcClient` and mdtpy's client only turn a FAILED body into an operation failure on a 2xx; a 4xx/5xx becomes a plain HTTP error there (`HttpRESTfulClient` would unwrap the `error` object into a `RESTfulRemoteException`; mdtpy calls `raise_for_status()`). Do not change it without changing both clients.
  - **A start that succeeds is always 202 `RUNNING`**, even when the command already finished during the start wait. Outputs come only from `getSessionStatus`. This is enforced in `utils` `RESTfulAsyncRpcServer.start()`: both clients reject a `COMPLETED` start response, and before 2026-11-08 a very fast command lost its result that way. `OperationControllerLifecycleTest.testRunToCompletion` asserts it.
  - **All "not found" cases throw `ResourceNotFoundException`.** This covers an unknown op, an unknown or already-collected session, and a vanished descriptor on reload. Do not return 404 entities directly.
  - **Startup (`afterPropertiesSet`):**
    - Connects `HttpMDTManager` to `mdt.operation-server.mdtManagerUrl` (already validated by `OperationServerProperties`).
    - Registers every `operations/<id>/operation.json` through `loadAllOperations`. A bad descriptor is WARN-logged and skipped; a missing `operations` dir is WARN-logged and the server starts empty.
    - Deletes stale `session-<UUID>-<seq>` directories in every registered operation's working directory (`removeStaleSessionDirectories`). At startup this process owns no session, so they are leftovers of a crash. Two server processes sharing one working directory is unsupported.
  - **Shutdown (`destroy`):** `cancelAllSessions(10s)` on every current and retired server. This kills the command process trees and lets `releaseSession` delete the session dirs. Before 2026-11-08 there was no shutdown hook: running commands were orphaned and their dirs left behind.
  - **Registry:** `m_operations` (operation id → `RegisteredOperation(opId, server)`) is a `ConcurrentHashMap` (request threads write to it).
    - `findOrLoadOperation` validates the id (blank, `.`, `..`, `/`, `\` → `IllegalArgumentException`) and checks `operations/<id>/operation.json` directly (no listing; a missing `operations` dir just means 404). It registers via `computeIfAbsent`, so concurrent first calls build one server.
    - `reload` builds a new server, `put`s it, and moves the old one to `m_retiredOperations` (`RetiredOperation`) so sessions started before the reload stay reachable. Retired servers are pruned on the next reload once they have no sessions **and** were retired more than 10 minutes ago. The grace period covers a `startOperation` that fetched the old server just before the swap.
  - **Session lookup:** `findOperationBySession` scans the current servers, then the retired ones (`getSession`). There is no session cache: a 30 s Guava cache was removed on 2026-11-08.
  - **Session endpoints** are minted by `RESTfulCommandExecutionServer` as `/api/v1/sessions/<server UUID>-<global seq>`. The UUID is new per server object, so endpoints from before a restart (or a reload) never collide with new ones. `toSessionEndpoint` rebuilds that string from the path id, so the two prefixes must stay identical.
  - **`@ExceptionHandler`:**
    - **Mapping:** `HttpMessageNotReadableException` → 400; any Spring `ErrorResponse` → its own status; otherwise, after `Throwables.unwrapThrowable`, `IllegalArgumentException` → 400, `ResourceNotFoundException` → 404, `TimeoutException` → 408, anything else → 500.
    - **Logging:** 4xx are logged as one WARN line, 5xx as ERROR with stack.
- **`OperationServerHealthController`**: `GET /health` → `"ok"` (text/plain), same as mdt-manager / mdt-workflow. It is used by the Docker `HEALTHCHECK` and checks nothing else.
- **`MDTCommandVariableSerDe`** (`CommandVariableSerDe`) turns request JSON into command variables:
  - **How the input is classified:**
    - `@type` starting with `mdt:ref:`: the reference is activated with the instance manager and its current value is read. A `FileValue` is downloaded into the variable file via `readAttachment`.
    - `@type` starting with `mdt:value:`: an `ElementValue`. `FileValue` is rejected.
    - `modelType` present: an AAS `SubmodelElement`. `File` is rejected.
    - Otherwise: plain JSON.
  - **Variable value:** the variable value is the **JSON string**, so a string input keeps its quotes in `${x:value}`.
  - **Output variables** go through `deserializeOutput` (see below): the request's `outputs` entry, or `TextNode("")` when absent.
  - **Errors:** a bad input (unknown `@type`, a non-MDT reference, a file value given as a value) is an `IllegalArgumentException`; failures reading a reference or writing the variable file are an `IOException` (`failed to prepare command variable: ...`). Either way it happens inside `createExecution`, so the client sees it as the FAILED start response above.
- **`MDTCommandVariable`**:
  - **Output parsing:** `toJsonNode()` parses the output file using the prototype `ElementValue` when there is one, or as plain JSON otherwise.
  - **Outputs are not written to the MDT model.** Callers persist results themselves (mdt-client's README says the same).
  - **One exception, file outputs:**
    - `deserializeOutput` (the `CommandVariableSerDe` hook `utils` calls for output variables) turns an `mdt:ref:` pointing at a `File` element into `MDTCommandVariable(name, file, fileOutputRef)`. It does not download the current attachment.
    - `toJsonNode()` uploads the file the command wrote (`updateAttachment(File)`) and returns the new `FileValue`. The session dir is deleted right after, so without the upload the caller could never get the file.
    - mdt-cli's `RESTfulAsyncRpcCommand` relies on this: it sends the reference for `FileValue` outputs.
  - **File inputs:** inputs still go through `deserialize`, which downloads a `File` reference.
  - **A `File` given as a value** (`SubmodelElement`, `mdt:value:`) is rejected in both directions. So mdt-instance's `HttpOperationProvider`, which sends outputs as `SubmodelElement`s, cannot use file outputs.
- **`OperationServerProperties`**: `@ConfigurationProperties("mdt.operation-server")`, registered by `@ConfigurationPropertiesScan` on the application class (there is no `ApplicationConfiguration` any more).
  - **Properties:** `operationsDir` (default `<cwd>/operations`) and `mdtManagerUrl` (the MDT manager URL passed to `HttpMDTManager.connect`; the deployed config fills it from `${MDT_URL}`). Setters reject null.
  - **Renamed on 2026-11-08 with no aliases:** class `RpcServersConfiguration`, keys `rpc-servers.instanceManagerUrl` / `rpc-servers.operationsDir`. The old keys are silently ignored, so an old config fails startup with the "not configured" error, and a pre-rename JAR cannot read the new keys. The deployed `$MDT_HOME/mdt-operation-http/config/application.yml` (CRLF line endings) was switched to the new keys on the same day.
  - **Validation:** `afterPropertiesSet` (`InitializingBean`) fails startup with `IllegalStateException` when `mdtManagerUrl` is missing or blank. No bean-validation dependency is used.
- **`EnvFilePostProcessor`** (named `RpcServerDispatcherEnvironment` before 2026-11-08): an `EnvironmentPostProcessor` registered in `META-INF/spring.factories`.
  - **What it loads:** `-Denv.file`, else `$ENV_FILE`, else `config/env.file`. The variables become the highest-precedence property source (`mdtEnv`), which is how `${MDT_URL}` in the deployed `application.yml` resolves.
  - It logs only the variable **names** at INFO, never the values (values were logged before 2026-11-08). Keep it that way: the file feeds `${...}` in the Spring config and may hold credentials.

Per-operation runtime behavior lives in `utils` (`RESTfulCommandExecutionServer`, `CommandExecutionDescriptor`, `CommandVariable`). It covers the session dir `session-<UUID>-<seq>` under the working directory, `env.file` in the working directory, stdout appended to `output.log`, stderr to the server's stdout, the `${name:value|path|name}` modifiers and the descriptor keys. The same goes for the server hooks this project uses: `getDescriptor`, `getWorkingDirectory`, `hasSessions`, `cancelAllSessions` and `deserializeOutput`. See `../../common/utils/src/main/java/utils/rpc/restful/CLAUDE.md`. Change them there, not here.

springdoc (`springdoc-openapi-starter-webmvc-ui`) is kept: endpoints carry `@Tag` / `@Operation` summaries, and `/swagger-ui` serves them.

## Deployment

- **Install directory:** `$MDT_HOME/mdt-operation-http/`. It is outside this repo and holds:
  - `mdt-operation-http.jar`, a symlink to `build/libs`.
  - `config/application.yml` (port 12987), `config/env.file` and `config/logback.xml`.
  - `operations/` (sample operations such as `AddAndSleep`, `ThicknessInspection` and `CollectTimeSeriesSamples`).
  - `sbin/` (`run_mdt_operation_server.sh`, plus systemd `install_system_service.sh` / `uninstall_system_service.sh` / `mdt-operation-http.template`).
- **Paths are cwd-relative:** the server resolves `config/` and `operations/` against its working directory, so it must be started from the install directory.
- **Secrets:** `config/env.file` may contain secrets. Show keys only, never values.
- **Deployed state (2026-11-08):** the deployed config already uses the new `mdt.operation-server.*` keys. The JAR the symlink points to was built before the renames, so it cannot start with that config. Rebuild (`gradle bootJar` with `MDT_BUILD_VERSION=1.7.2`) before starting the deployed server. At that time the server was not running and was not registered as a systemd service.
- **Docker** (`docker/`, procedure in [docker/README.md](docker/README.md)): rewritten on 2026-11-08 in the mdt-workflow format.
  - **Scripts:** `build_image.sh` builds in a `mktemp -d` context and checks the JAR version against the tag (manifest `Implementation-Version`, else the `mdt-operation-http-<ver>.jar` file name). `push_image.sh` validates its options. Both honour `MDT_IMAGE_NAME`.
  - **Dockerfile:**
    - Base image: the digest-pinned temurin image.
    - `tini` as PID 1, because commands spawn children.
    - Runs as user `mdt` (UID 1000).
    - Python 3.11 is installed by uv (`ghcr.io/astral-sh/uv:0.12.5`), with a shared venv `/opt/mdt-python` (numpy, matplotlib, opencv-python-headless) first on `PATH`. `uv run` operations reuse the uv-managed interpreter.
    - `EXPOSE 12987`, `MDT_OPERATION_PORT`, `HEALTHCHECK` on `/health`, and `JAVA_OPTS`.
  - **Mounts:** `config/` and `operations/` are not in the image; they are mounted.
  - **File modes:** the scripts are not executable (same as mdt-workflow), so run them with `bash`.
