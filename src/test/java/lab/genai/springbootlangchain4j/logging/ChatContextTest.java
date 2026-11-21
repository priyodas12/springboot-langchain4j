package lab.genai.springbootlangchain4j.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ChatContext: UUIDv7 ids and MDC log context")
class ChatContextTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @DisplayName("newSessionId returns an RFC 9562 UUIDv7 (version 7, IETF variant)")
    @Test
    void newSessionId_returnsRfc9562Uuid7() {
        UUID id = ChatContext.newSessionId();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2); // IETF variant
    }

    @DisplayName("newSessionId generates unique ids that sort by creation time")
    @Test
    void newSessionId_isUniqueAndTimeOrdered() {
        Set<UUID> ids = new HashSet<>();
        UUID previous = ChatContext.newSessionId();
        ids.add(previous);

        for (int i = 0; i < 1_000; i++) {
            UUID next = ChatContext.newSessionId();
            assertThat(next.compareTo(previous)).as("UUIDv7 ids must sort by creation order").isPositive();
            ids.add(next);
            previous = next;
        }

        assertThat(ids).hasSize(1_001);
    }

    @DisplayName("newChat keeps the sessionId and creates a fresh UUIDv7 chatId each time")
    @Test
    void newChat_keepsSessionIdAndCreatesNewUuid7ChatId() {
        UUID sessionId = ChatContext.newSessionId();

        ChatContext first = ChatContext.newChat(sessionId);
        ChatContext second = ChatContext.newChat(sessionId);

        assertThat(first.sessionId()).isEqualTo(sessionId);
        assertThat(second.sessionId()).isEqualTo(sessionId);
        assertThat(first.chatId().version()).isEqualTo(7);
        assertThat(first.chatId()).isNotEqualTo(second.chatId()).isNotEqualTo(sessionId);
    }

    @DisplayName("forSession creates a context with a sessionId and no chatId")
    @Test
    void forSession_hasNoChatId() {
        UUID sessionId = ChatContext.newSessionId();

        ChatContext ctx = ChatContext.forSession(sessionId);

        assertThat(ctx.chatId()).isNull();
        assertThat(ctx.sessionId()).isEqualTo(sessionId);
    }

    @DisplayName("memoryId is the sessionId as a string")
    @Test
    void memoryId_isSessionIdAsString() {
        UUID sessionId = ChatContext.newSessionId();

        assertThat(ChatContext.newChat(sessionId).memoryId()).isEqualTo(sessionId.toString());
    }

    @DisplayName("open() puts chatId and sessionId in the MDC and close() removes them")
    @Test
    void open_putsBothIdsInMdc_andCloseRemovesThem() {
        ChatContext ctx = ChatContext.newChat(ChatContext.newSessionId());

        try (ChatContext.Scope _ = ctx.open()) {
            assertThat(MDC.get(ChatContext.CHAT_ID)).isEqualTo(ctx.chatId().toString());
            assertThat(MDC.get(ChatContext.SESSION_ID)).isEqualTo(ctx.sessionId().toString());
        }

        assertThat(MDC.get(ChatContext.CHAT_ID)).isNull();
        assertThat(MDC.get(ChatContext.SESSION_ID)).isNull();
    }

    @DisplayName("open() on a session-only context puts only sessionId in the MDC")
    @Test
    void open_forSession_putsOnlySessionIdInMdc() {
        ChatContext ctx = ChatContext.forSession(ChatContext.newSessionId());

        try (ChatContext.Scope _ = ctx.open()) {
            assertThat(MDC.get(ChatContext.CHAT_ID)).isNull();
            assertThat(MDC.get(ChatContext.SESSION_ID)).isEqualTo(ctx.sessionId().toString());
        }

        assertThat(MDC.get(ChatContext.SESSION_ID)).isNull();
    }

    @DisplayName("MDC values set by open() do not leak into other threads")
    @Test
    void open_doesNotLeakIntoOtherThreads() throws InterruptedException {
        ChatContext ctx = ChatContext.newChat(ChatContext.newSessionId());
        String[] seenOnOtherThread = new String[1];

        try (ChatContext.Scope _ = ctx.open()) {
            Thread other = Thread.ofPlatform().start(() -> seenOnOtherThread[0] = MDC.get(ChatContext.CHAT_ID));
            other.join();
        }

        // MDC is thread-local, which is why ChatService re-opens the context in streaming callbacks.
        assertThat(seenOnOtherThread[0]).isNull();
    }
}
