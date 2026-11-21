package lab.genai.springbootlangchain4j.logging;

import com.github.f4b6a3.uuid.UuidCreator;
import org.slf4j.MDC;

import java.util.UUID;

/**
 * Log-correlation ids, both UUIDv7 (time-ordered):
 * <ul>
 *   <li>{@code sessionId} - one conversation, issued by the server when a chat starts</li>
 *   <li>{@code chatId} - one exchange (a user message and its reply) within that conversation</li>
 * </ul>
 *
 * <p>{@link #open()} puts both into the SLF4J MDC, so every log line written inside the scope carries
 * them. Use it with try-with-resources. Open it again on other threads (e.g. streaming callbacks),
 * because the MDC is thread-local.
 */
public record ChatContext(UUID chatId, UUID sessionId) {

    public static final String CHAT_ID = "chatId";
    public static final String SESSION_ID = "sessionId";

    /** A new conversation id (UUIDv7). */
    public static UUID newSessionId() {
        return UuidCreator.getTimeOrderedEpoch();
    }

    /** A new exchange within an existing conversation, with a fresh UUIDv7 {@code chatId}. */
    public static ChatContext newChat(UUID sessionId) {
        return new ChatContext(UuidCreator.getTimeOrderedEpoch(), sessionId);
    }

    /** Memory id used by LangChain4j chat memory for this conversation. */
    public String memoryId() {
        return sessionId.toString();
    }

    public Scope open() {
        if (chatId != null) {
            MDC.put(CHAT_ID, chatId.toString());
        }
        MDC.put(SESSION_ID, sessionId.toString());
        return () -> {
            MDC.remove(CHAT_ID);
            MDC.remove(SESSION_ID);
        };
    }

    /** Context for session-level operations (create/clear) that have no exchange yet. */
    public static ChatContext forSession(UUID sessionId) {
        return new ChatContext(null, sessionId);
    }

    /** An {@link AutoCloseable} whose {@code close()} throws no checked exception. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
