package lab.genai.springbootlangchain4j.controller;

import dev.langchain4j.exception.LangChain4jException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lab.genai.springbootlangchain4j.controller.dto.ChatRequest;
import lab.genai.springbootlangchain4j.controller.dto.ChatResponse;
import lab.genai.springbootlangchain4j.controller.dto.SessionResponse;
import lab.genai.springbootlangchain4j.logging.ChatContext;
import lab.genai.springbootlangchain4j.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Entry point for the chat UI. Every request gets a new UUIDv7 {@code chatId}, which is put in the
 * log context (MDC) here and in {@link ChatService}, and returned to the client in the
 * {@value #CHAT_ID_HEADER} header so a user-reported problem can be found in the logs.
 *
 * <ul>
 *   <li>{@code POST /api/chat/sessions} - start a conversation; returns its UUIDv7 {@code sessionId}</li>
 *   <li>{@code POST /api/chat} - full reply as JSON</li>
 *   <li>{@code POST /api/chat/stream} - reply streamed token-by-token as Server-Sent Events</li>
 *   <li>{@code DELETE /api/chat/{sessionId}} - clear a conversation's memory</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    public static final String CHAT_ID_HEADER = "X-Chat-Id";

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final long SSE_TIMEOUT_MS = Duration.ofMinutes(10).toMillis();
    private static final String FAILURE_MESSAGE = "The assistant could not complete the reply. Please try again.";

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ChatResponse chat(@Valid @RequestBody ChatRequest request, HttpServletResponse response) {
        ChatContext ctx = ChatContext.newChat(request.sessionId());
        response.setHeader(CHAT_ID_HEADER, ctx.chatId().toString());

        try (ChatContext.Scope _ = ctx.open()) {
            log.info("Chat request received: mode=blocking");
            String reply = chatService.chat(ctx, request.message());
            log.info("Chat response sent");
            return new ChatResponse(ctx.chatId(), ctx.sessionId(), reply);
        }
    }

    /**
     * Streams events: {@code token} (a text chunk), then {@code done}, or {@code error} on failure.
     * Each event's data is JSON {@code {"text": "..."}}.
     */
    @PostMapping(path = "/stream", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> streamChat(@Valid @RequestBody ChatRequest request) {
        ChatContext ctx = ChatContext.newChat(request.sessionId());
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        try (ChatContext.Scope _ = ctx.open()) {
            log.info("Chat request received: mode=streaming");

            emitter.onTimeout(() -> logInContext(ctx, "Chat stream timed out after {} ms", SSE_TIMEOUT_MS));

            chatService.streamChat(ctx, request.message(),
                    token -> send(emitter, "token", token),
                    () -> {
                        send(emitter, "done", "");
                        emitter.complete();
                        log.info("Chat stream completed");
                    },
                    error -> {
                        send(emitter, "error", FAILURE_MESSAGE);
                        emitter.complete();
                    });
        }

        return ResponseEntity.ok()
                .header(CHAT_ID_HEADER, ctx.chatId().toString())
                .body(emitter);
    }

    /** Starts a new conversation; the returned UUIDv7 {@code sessionId} must be sent with each message. */
    @PostMapping(path = "/sessions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SessionResponse> createSession() {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new SessionResponse(chatService.createSession()));
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> clearSession(@PathVariable UUID sessionId) {
        chatService.clearSession(sessionId);
        return ResponseEntity.noContent().build();
    }

    /** The failure is already logged, with stack trace and chatId, by {@link ChatService}. */
    @ExceptionHandler(LangChain4jException.class)
    public ProblemDetail handleChatFailure(LangChain4jException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, FAILURE_MESSAGE);
    }

    /** Each event's data is JSON {@code {"text": "..."}} so spaces and newlines inside tokens survive SSE framing. */
    private static void send(SseEmitter emitter, String event, String text) {
        try {
            emitter.send(SseEmitter.event().name(event).data(Map.of("text", text), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            // Client disconnected or emitter already completed; nothing more to send.
            log.debug("Could not send SSE event '{}': {}", event, e.getMessage());
        }
    }

    private static void logInContext(ChatContext ctx, String message, Object arg) {
        try (ChatContext.Scope _ = ctx.open()) {
            log.warn(message, arg);
        }
    }
}
