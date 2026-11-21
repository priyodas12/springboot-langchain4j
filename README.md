# springboot-langchain4j

A chat-bot web application built with **Spring Boot 4.2** and **LangChain4j 1.24**, backed by Anthropic's **Claude** models. It exposes a small REST + Server-Sent Events (SSE) API with per-conversation memory, and ships a single-page chat UI. Every conversation and every message exchange is tagged with a time-ordered **UUIDv7** that appears on every log line, so any reply can be traced end to end. Runs on **Java 25**.

## Features

- **Chat with Claude** through LangChain4j `AiServices` (`ChatAssistant`), using adaptive thinking and a configurable reasoning `effort`.
- **Two reply modes:** blocking JSON (`POST /api/chat`) or token-by-token streaming over SSE (`POST /api/chat/stream`).
- **Per-conversation memory:** the server issues a UUIDv7 `sessionId` per conversation, and keeps a sliding window of the last *N* messages (default 40) for each one.
- **Clear a conversation:** `DELETE /api/chat/{sessionId}` wipes its memory.
- **Server-side refusal fallback:** if Claude declines a request, the Anthropic API retries it on a fallback model it selects (`fallbacks: "default"`, beta `server-side-fallback-2026-07-01`).
- **Log correlation:** a UUIDv7 `chatId` for each message exchange, plus the `sessionId`, are put in the SLF4J MDC on every log line, including the streaming callback threads. The `chatId` is returned in the `X-Chat-Id` response header.
- **Privacy-aware logging:** model call duration, reply length, finish reason and input/output token counts are logged. Message and reply text are never logged.
- **Input validation:** `sessionId` must be a UUID, and `message` must be non-blank and at most 20,000 characters (HTTP 400 otherwise).
- **Error mapping:** LLM/API failures return HTTP 502 as an RFC 7807 `ProblemDetail` (blocking), or an `error` SSE event (streaming).
- **Built-in chat UI** at `/`: no build step or dependencies, light/dark theme, Enter to send, a "New chat" button, and model output rendered as plain text (never as HTML).

## Tech Stack and Dependencies

**Prerequisites**

| Requirement | Version / notes |
|---|---|
| JDK | 25 (`java.version` in `pom.xml`) |
| Maven | 3.x installed locally. The Maven wrapper (`mvnw`) is intentionally not part of this repo |
| Anthropic API key | Required. Set it as the `ANTHROPIC_API_KEY` environment variable |
| Claude model name | Required. Set it as the `ANTHROPIC_MODEL_NAME` environment variable (e.g. `claude-opus-5-5`) |

**Direct dependencies**

| Dependency | Version | Scope | Purpose |
|---|---|---|---|
| `org.springframework.boot:spring-boot-starter-parent` | 4.2.0 | parent | Spring Boot build and dependency management |
| `org.springframework.boot:spring-boot-starter-webmvc` | managed by Spring Boot (4.2.0) | compile | REST controllers, SSE (`SseEmitter`), embedded Tomcat, static UI |
| `org.springframework.boot:spring-boot-starter-validation` | managed by Spring Boot (4.2.0) | compile | Jakarta Bean Validation for requests and config properties |
| `dev.langchain4j:langchain4j-bom` | 1.24.0 | import | LangChain4j version alignment |
| `dev.langchain4j:langchain4j` | managed by `langchain4j-bom` (1.24.0) | compile | `AiServices`, chat memory, `TokenStream` |
| `dev.langchain4j:langchain4j-anthropic` | managed by `langchain4j-bom` (1.24.0) | compile | Claude chat and streaming chat models |
| `com.github.f4b6a3:uuid-creator` | 6.1.1 | compile | UUIDv7 generation for `sessionId` and `chatId` |
| `org.springframework.boot:spring-boot-starter-webmvc-test` | managed by Spring Boot (4.2.0) | test | JUnit 5 / Spring test support |

## Getting Started

1. **Clone the repository** and `cd` into it.
2. **Set the required environment variables:**
   ```bash
   export ANTHROPIC_API_KEY=...            # your Anthropic API key
   export ANTHROPIC_MODEL_NAME=claude-opus-5-5
   ```
3. **Build:**
   ```bash
   mvn clean package
   ```
4. **Run:**
   ```bash
   mvn spring-boot:run
   # or: java -jar target/springboot-langchain4j-0.0.1-SNAPSHOT.jar
   ```
5. **Open the chat UI** at <http://localhost:8080/> (Spring Boot's default port; `server.port` is not overridden), or call the API (see [API / Usage](#api--usage)).

## Configuration

All app settings live under `anthropic.*` in `src/main/resources/application.yaml`. They are bound to `AnthropicProperties` and validated at startup.

| Key | Env var | Default | Required | Description |
|---|---|---|---|---|
| `anthropic.api-key` | `ANTHROPIC_API_KEY` | none | **yes** | Anthropic API key. Never commit it |
| `anthropic.model-name` | `ANTHROPIC_MODEL_NAME` | none | **yes** | Claude model id, e.g. `claude-opus-5-5` |
| `anthropic.effort` | `ANTHROPIC_EFFORT` | `medium` | no | Reasoning effort: `low`, `medium`, `high`, `xhigh`, `max` |
| `anthropic.max-tokens` | `ANTHROPIC_MAX_TOKENS` | `16000` | no | Output limit for blocking `/api/chat` |
| `anthropic.streaming-max-tokens` | `ANTHROPIC_STREAMING_MAX_TOKENS` | `64000` | no | Output limit for `/api/chat/stream` |
| `anthropic.memory-max-messages` | `ANTHROPIC_MEMORY_MAX_MESSAGES` | `40` | no | Messages kept per conversation (sliding window) |
| `anthropic.timeout` | `ANTHROPIC_TIMEOUT` | `5m` | no | HTTP timeout for Claude calls |
| `anthropic.log-requests` | `ANTHROPIC_LOG_REQUESTS` | `false` | no | Log raw requests sent to Claude (includes prompts) |
| `anthropic.log-responses` | `ANTHROPIC_LOG_RESPONSES` | `false` | no | Log raw responses from Claude (includes replies) |
| `logging.pattern.correlation` | none | `[chatId=… sessionId=…] ` | no | Adds the correlation ids to every log line |

The env vars for optional keys work through Spring Boot's relaxed binding (`anthropic.max-tokens` maps to `ANTHROPIC_MAX_TOKENS`). Any key can also be passed on the command line, e.g. `--anthropic.effort=low`.

**Local overrides:** you can put local values (including the API key) in `src/main/resources/application-local.yaml` and run with `--spring.profiles.active=local`. That file is git-ignored by `.gitignore` (`application-local.*`), so it is never committed.

## API / Usage

Base path: `/api/chat`. All request and response bodies are JSON unless noted otherwise.

| Method | Path | Purpose | Request body | Response |
|---|---|---|---|---|
| `POST` | `/api/chat/sessions` | Start a conversation | none | `201` `{"sessionId": "<uuidv7>"}` |
| `POST` | `/api/chat` | Send a message, get the full reply | `{"sessionId": "<uuid>", "message": "..."}` | `200` `{"chatId", "sessionId", "reply"}` and `X-Chat-Id` header. `400` on validation errors; `502` `ProblemDetail` if the model call fails |
| `POST` | `/api/chat/stream` | Send a message, stream the reply | same as above | `200` `text/event-stream` and `X-Chat-Id` header |
| `DELETE` | `/api/chat/{sessionId}` | Clear a conversation's memory | none | `204` |
| `GET` | `/` | Chat UI (`static/index.html`) | none | HTML page |

**Streaming format** (`/api/chat/stream`). Each event's `data` is JSON `{"text": "..."}`:

| Event | Meaning |
|---|---|
| `token` | Next chunk of the reply text |
| `done` | Reply finished (`text` is empty) |
| `error` | The model call failed. `text` holds a generic message; details are in the server logs under the same `chatId` |

The stream times out after 10 minutes.

**Examples**

```bash
# 1. Start a conversation
SID=$(curl -s -X POST localhost:8080/api/chat/sessions | sed -E 's/.*"sessionId":"([^"]+)".*/\1/')

# 2. Blocking reply
curl -s -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d "{\"sessionId\":\"$SID\",\"message\":\"Hello! What can you do?\"}"

# 3. Streaming reply (Server-Sent Events)
curl -N -X POST localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' \
  -d "{\"sessionId\":\"$SID\",\"message\":\"Tell me a short joke\"}"

# 4. Clear the conversation
curl -X DELETE localhost:8080/api/chat/$SID
```

**Chat UI:** open `/`. The page requests a `sessionId` before the first message, streams replies, and "New chat" clears the old conversation and starts a new one.

**Tracing a reply in the logs:** take the `X-Chat-Id` value (or `chatId` from the JSON) and search the logs for it, for example:
```
[chatId=01a1fca4-f12f-7200-… sessionId=01a1fca4-f0e4-7be7-…] ChatService : Model call completed: durationMs=… replyLength=… finishReason=… inputTokens=… outputTokens=…
```

## Folder Structure

```
.
├── pom.xml                                   # Maven build: Spring Boot 4.2 parent, LangChain4j BOM, dependencies
├── .gitignore                                # Ignores secrets, IDE files, build output, Maven wrapper
├── .gitattributes                            # Line-ending rules
└── src
    ├── main
    │   ├── java/lab/genai/springbootlangchain4j
    │   │   ├── SpringbootLangchain4jApplication.java   # Spring Boot entry point
    │   │   ├── config
    │   │   │   ├── AnthropicProperties.java            # Validated anthropic.* settings
    │   │   │   └── LangChain4jConfig.java              # Claude models, chat memory, AiServices wiring
    │   │   ├── controller
    │   │   │   ├── ChatController.java                 # REST + SSE endpoints, X-Chat-Id, error mapping
    │   │   │   └── dto
    │   │   │       ├── ChatRequest.java                # {sessionId, message} with validation
    │   │   │       ├── ChatResponse.java               # {chatId, sessionId, reply}
    │   │   │       └── SessionResponse.java            # {sessionId}
    │   │   ├── logging
    │   │   │   └── ChatContext.java                    # UUIDv7 sessionId/chatId and MDC scope
    │   │   └── service
    │   │       ├── ChatAssistant.java                  # LangChain4j AI service interface + system prompt
    │   │       └── ChatService.java                    # Calls the assistant; logs duration, tokens, errors
    │   └── resources
    │       ├── application.yaml                        # App, logging and anthropic.* configuration
    │       └── static/index.html                       # Single-page chat UI
    └── test/java/lab/genai/springbootlangchain4j
        └── SpringbootLangchain4jApplicationTests.java  # Spring context smoke test
```

## Tests

| | |
|---|---|
| Framework | JUnit 5 + Spring Boot Test (`spring-boot-starter-webmvc-test`) |
| Location | `src/test/java/lab/genai/springbootlangchain4j/` |
| Run | `mvn test` |
| Coverage | **Smoke test only:** `SpringbootLangchain4jApplicationTests.contextLoads()` checks that the Spring context starts. There are no unit or integration tests for the controller, service, SSE streaming, validation or memory behavior, and no Claude calls are made in tests |

> **Known issue:** the test sets only `anthropic.api-key=test-key`. Since `anthropic.model-name` is now `${ANTHROPIC_MODEL_NAME}` with no default, `mvn test` is expected to fail when `ANTHROPIC_MODEL_NAME` isn't set: the placeholder can't be resolved. This is inferred from the configuration and was not verified by running the tests. Fix it by also setting `anthropic.model-name` in the test's `properties`, or by giving it a default (`${ANTHROPIC_MODEL_NAME:claude-opus-5-5}`).

## Vulnerabilities

**Dependencies**

| Tool | Scope | Result |
|---|---|---|
| `mvn dependency:list` + OSV.dev lookup (`osv_audit.py`) | 89 resolved Maven dependencies, including indirect ones (55 compile, 1 runtime, 33 test) | **No known vulnerabilities found** |

Key resolved versions: Spring Framework 7.1.0, Tomcat 11.0.27, Jackson 3.1.8 (`tools.jackson`) and 2.21.8 (`com.fasterxml`, via LangChain4j), Hibernate Validator 9.1.6, LangChain4j 1.24.0, uuid-creator 6.1.1. Note that LangChain4j pulls in `langchain4j-reactive-streaming:1.24.0-beta34`, an indirect **beta** artifact.

**Code-level findings**

| Severity | Location | Description | Suggested fix |
|---|---|---|---|
| Medium | `controller/ChatController.java` (all `/api/chat/**` endpoints) | No authentication or rate limiting. Anyone who can reach the server can send unlimited Claude requests billed to the configured API key | Add Spring Security (e.g. OAuth2/OIDC or API-key auth) and per-client rate limiting before exposing the app beyond localhost |
| Medium | `config/LangChain4jConfig.java` (`InMemoryChatMemoryStore`), `ChatController.createSession` | Conversation memory is unbounded and in-process. Any well-formed UUID is accepted as a `sessionId`, and memory is never expired. A client creating many sessions can exhaust the heap | Track issued session ids and reject unknown ones, add a TTL/eviction or a max-session cap, or use a persistent store with expiry (e.g. Redis) |
| Low | `application.yaml` (`anthropic.log-requests` / `log-responses`) | When enabled, full prompts and replies are written to logs, and request headers are logged too. Whether the API key header is masked was not verified in this scan | Keep these `false` outside local debugging; check the logged headers before enabling them in shared environments |
| Info | `src/main/resources/static/index.html:146` | Uses `innerHTML`, but only with a constant string. Model output is inserted with `textContent` (line 66) | None needed |

No hardcoded secrets, committed `.env` files, `eval`/`exec`, SQL, shell execution, disabled TLS checks or permissive CORS settings were found. All dependency versions are pinned, either directly or by the Spring Boot parent and the LangChain4j BOM.

**Commands used**
```bash
TMP=$(mktemp -d)
mvn -q dependency:list -DoutputFile=$TMP/deps.txt -DappendOutput=true
python3 -I ~/.claude/scripts/chk-vulns/osv_audit.py --maven $TMP/deps.txt
git grep -n -E 'innerHTML|eval\(|exec\(|Runtime\.getRuntime|@CrossOrigin|allowedOrigins|TrustAll|setHostnameVerifier|Statement|createQuery'
```

> This scan matches code against known patterns and a public vulnerability database. It is not a substitute for a full security audit or penetration test.

## Future Scope

- **Fix the test configuration** so `mvn test` passes without `ANTHROPIC_MODEL_NAME` (see [Tests](#tests)).
- **Add real tests:** controller tests with `MockMvc` (validation errors → 400, `X-Chat-Id` header, 502 mapping), a service test with a mocked `ChatAssistant`, an SSE stream test, and memory-window behavior.
- **Authentication and rate limiting** for `/api/chat/**` (see Vulnerabilities).
- **Persistent, expiring chat memory:** replace `InMemoryChatMemoryStore` (lost on restart, not shared across instances, unbounded) with Redis or JDBC, with a TTL.
- **Reject unknown sessions:** only accept `sessionId`s that the server issued.
- **Check long conversations:** dropping the oldest messages once a conversation passes `memory-max-messages` changes the earlier history sent to the API. Current Claude models are strict about edits to earlier history (preserved thinking), so verify that long conversations don't produce API errors.
- **Markdown rendering in the UI** (with sanitization). Replies are currently shown as plain text.
- **Optional defaults** for `ANTHROPIC_MODEL_NAME`, or a startup error that names the missing variable.
- **Observability:** expose model latency and token usage as metrics (Micrometer/Actuator), since they are already computed in `ChatService`.
- **A Dockerfile and CI pipeline** (build, test, `osv_audit`/`osv-scanner`) are not present yet.
