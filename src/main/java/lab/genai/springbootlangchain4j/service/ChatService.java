package lab.genai.springbootlangchain4j.service;

import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lab.genai.springbootlangchain4j.logging.ChatContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Calls the LangChain4j {@link ChatAssistant} and logs each chat exchange: start, duration,
 * reply size, token usage and failures. Message and reply text are never logged, only their lengths.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatAssistant assistant;
    private final ChatMemoryStore chatMemoryStore;

    public ChatService(ChatAssistant assistant, ChatMemoryStore chatMemoryStore) {
        this.assistant = assistant;
        this.chatMemoryStore = chatMemoryStore;
    }

    public String chat(ChatContext ctx, String message) {
        try (ChatContext.Scope _ = ctx.open()) {
            long start = System.nanoTime();
            log.info("Model call started: mode=blocking messageLength={}", message.length());
            try {
                Result<String> result = assistant.chat(ctx.memoryId(), message);
                String reply = result.content();
                log.info("Model call completed: durationMs={} replyLength={} finishReason={} {}",
                        elapsedMs(start), reply == null ? 0 : reply.length(), result.finishReason(),
                        tokens(result.tokenUsage()));
                return reply;
            } catch (RuntimeException e) {
                log.error("Model call failed: durationMs={}", elapsedMs(start), e);
                throw e;
            }
        }
    }

    /**
     * Streams the reply. Callbacks run on LangChain4j's HTTP threads, so the log context is
     * re-opened inside each one that logs.
     */
    public void streamChat(ChatContext ctx, String message,
                           Consumer<String> onToken, Runnable onComplete, Consumer<Throwable> onError) {
        try (ChatContext.Scope _ = ctx.open()) {
            long start = System.nanoTime();
            AtomicInteger replyLength = new AtomicInteger();
            log.info("Model call started: mode=streaming messageLength={}", message.length());

            assistant.streamChat(ctx.memoryId(), message)
                    .onPartialResponse(token -> {
                        replyLength.addAndGet(token.length());
                        onToken.accept(token);
                    })
                    .onCompleteResponse((ChatResponse response) -> {
                        try (ChatContext.Scope _ = ctx.open()) {
                            log.info("Model call completed: durationMs={} replyLength={} finishReason={} {}",
                                    elapsedMs(start), replyLength.get(), response.finishReason(),
                                    tokens(response.tokenUsage()));
                            onComplete.run();
                        }
                    })
                    .onError(error -> {
                        try (ChatContext.Scope _ = ctx.open()) {
                            log.error("Model call failed: durationMs={} partialReplyLength={}",
                                    elapsedMs(start), replyLength.get(), error);
                            onError.accept(error);
                        }
                    })
                    .start();
        }
    }

    /** Starts a new conversation and returns its UUIDv7 id. */
    public UUID createSession() {
        UUID sessionId = ChatContext.newSessionId();
        try (ChatContext.Scope _ = ChatContext.forSession(sessionId).open()) {
            log.info("Chat session created");
        }
        return sessionId;
    }

    public void clearSession(UUID sessionId) {
        ChatContext ctx = ChatContext.forSession(sessionId);
        try (ChatContext.Scope _ = ctx.open()) {
            chatMemoryStore.deleteMessages(ctx.memoryId());
            log.info("Chat memory cleared");
        }
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static String tokens(TokenUsage usage) {
        return usage == null
                ? "inputTokens=? outputTokens=?"
                : "inputTokens=" + usage.inputTokenCount() + " outputTokens=" + usage.outputTokenCount();
    }
}
