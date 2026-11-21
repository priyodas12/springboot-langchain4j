package lab.genai.springbootlangchain4j.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.exception.LangChain4jException;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lab.genai.springbootlangchain4j.logging.ChatContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("ChatService: model calls, streaming and sessions")
@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private ChatAssistant assistant;

    @Mock
    private ChatMemoryStore chatMemoryStore;

    @InjectMocks
    private ChatService chatService;

    private ChatContext ctx;

    @BeforeEach
    void setUp() {
        ctx = ChatContext.newChat(ChatContext.newSessionId());
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    // ---------- chat (blocking) ----------

    @DisplayName("chat returns the assistant's reply and uses the sessionId as memory id")
    @Test
    void chat_returnsAssistantReply_usingSessionIdAsMemoryId() {
        when(assistant.chat(ctx.memoryId(), "Hello")).thenReturn(result("Hi there"));

        String reply = chatService.chat(ctx, "Hello");

        assertThat(reply).isEqualTo("Hi there");
        verify(assistant).chat(ctx.sessionId().toString(), "Hello");
    }

    @DisplayName("chat sets chatId/sessionId in the MDC during the model call and clears them afterwards")
    @Test
    void chat_setsMdcDuringModelCall_andClearsItAfterwards() {
        AtomicReference<String> chatIdDuringCall = new AtomicReference<>();
        AtomicReference<String> sessionIdDuringCall = new AtomicReference<>();
        when(assistant.chat(any(), any())).thenAnswer(inv -> {
            chatIdDuringCall.set(MDC.get(ChatContext.CHAT_ID));
            sessionIdDuringCall.set(MDC.get(ChatContext.SESSION_ID));
            return result("ok");
        });

        chatService.chat(ctx, "Hello");

        assertThat(chatIdDuringCall.get()).isEqualTo(ctx.chatId().toString());
        assertThat(sessionIdDuringCall.get()).isEqualTo(ctx.sessionId().toString());
        assertThat(MDC.get(ChatContext.CHAT_ID)).isNull();
        assertThat(MDC.get(ChatContext.SESSION_ID)).isNull();
    }

    @DisplayName("chat tolerates a null reply and missing token usage")
    @Test
    void chat_handlesNullReplyAndMissingTokenUsage() {
        when(assistant.chat(any(), any())).thenReturn(Result.<String>builder().content(null).build());

        assertThat(chatService.chat(ctx, "Hello")).isNull();
    }

    @DisplayName("chat rethrows a model failure and still clears the MDC")
    @Test
    void chat_rethrowsModelFailure_andClearsMdc() {
        LangChain4jException failure = new LangChain4jException("boom");
        when(assistant.chat(any(), any())).thenThrow(failure);

        assertThatThrownBy(() -> chatService.chat(ctx, "Hello")).isSameAs(failure);
        assertThat(MDC.get(ChatContext.CHAT_ID)).isNull();
    }

    // ---------- streamChat ----------

    @DisplayName("streamChat forwards tokens and completion to the caller and starts the stream")
    @Test
    void streamChat_forwardsTokensAndCompletion_andStartsStream() {
        StreamCallbacks cb = mockTokenStream();
        List<String> tokens = new ArrayList<>();
        boolean[] completed = {false};

        chatService.streamChat(ctx, "Hello", tokens::add, () -> completed[0] = true, e -> { });
        cb.partial().getValue().accept("Hel");
        cb.partial().getValue().accept("lo");
        cb.complete().getValue().accept(chatResponse("Hello"));

        assertThat(tokens).containsExactly("Hel", "lo");
        assertThat(completed[0]).isTrue();
        verify(cb.stream()).start();
        verify(assistant).streamChat(ctx.memoryId(), "Hello");
    }

    @DisplayName("streamChat forwards a stream error to the caller")
    @Test
    void streamChat_forwardsErrorToCaller() {
        StreamCallbacks cb = mockTokenStream();
        AtomicReference<Throwable> received = new AtomicReference<>();
        RuntimeException failure = new RuntimeException("stream failed");

        chatService.streamChat(ctx, "Hello", t -> { }, () -> { }, received::set);
        cb.error().getValue().accept(failure);

        assertThat(received.get()).isSameAs(failure);
    }

    @DisplayName("streamChat restores the MDC log context inside completion and error callbacks")
    @Test
    void streamChat_reopensMdcInCompletionAndErrorCallbacks() {
        StreamCallbacks cb = mockTokenStream();
        AtomicReference<String> chatIdOnComplete = new AtomicReference<>();
        AtomicReference<String> chatIdOnError = new AtomicReference<>();

        chatService.streamChat(ctx, "Hello", t -> { },
                () -> chatIdOnComplete.set(MDC.get(ChatContext.CHAT_ID)),
                e -> chatIdOnError.set(MDC.get(ChatContext.CHAT_ID)));

        // The request thread's scope is closed by now, as it would be when LangChain4j calls back later.
        assertThat(MDC.get(ChatContext.CHAT_ID)).isNull();
        cb.complete().getValue().accept(chatResponse("done"));
        cb.error().getValue().accept(new RuntimeException("x"));

        assertThat(chatIdOnComplete.get()).isEqualTo(ctx.chatId().toString());
        assertThat(chatIdOnError.get()).isEqualTo(ctx.chatId().toString());
        assertThat(MDC.get(ChatContext.CHAT_ID)).isNull();
    }

    // ---------- sessions ----------

    @DisplayName("createSession returns a new UUIDv7 without touching chat memory")
    @Test
    void createSession_returnsNewUuid7_withoutTouchingMemory() {
        UUID first = chatService.createSession();
        UUID second = chatService.createSession();

        assertThat(first.version()).isEqualTo(7);
        assertThat(first).isNotEqualTo(second);
        verifyNoInteractions(chatMemoryStore, assistant);
    }

    @DisplayName("clearSession deletes chat memory for that session only")
    @Test
    void clearSession_deletesMemoryForThatSession() {
        UUID sessionId = ChatContext.newSessionId();

        chatService.clearSession(sessionId);

        verify(chatMemoryStore).deleteMessages(sessionId.toString());
        verify(chatMemoryStore, never()).deleteMessages(ctx.memoryId());
    }

    // ---------- helpers ----------

    private record StreamCallbacks(TokenStream stream,
                                   ArgumentCaptor<Consumer<String>> partial,
                                   ArgumentCaptor<Consumer<ChatResponse>> complete,
                                   ArgumentCaptor<Consumer<Throwable>> error) {
    }

    @SuppressWarnings("unchecked")
    private StreamCallbacks mockTokenStream() {
        TokenStream stream = mock(TokenStream.class);
        ArgumentCaptor<Consumer<String>> partial = ArgumentCaptor.forClass(Consumer.class);
        ArgumentCaptor<Consumer<ChatResponse>> complete = ArgumentCaptor.forClass(Consumer.class);
        ArgumentCaptor<Consumer<Throwable>> error = ArgumentCaptor.forClass(Consumer.class);
        when(assistant.streamChat(any(), any())).thenReturn(stream);
        when(stream.onPartialResponse(partial.capture())).thenReturn(stream);
        when(stream.onCompleteResponse(complete.capture())).thenReturn(stream);
        when(stream.onError(error.capture())).thenReturn(stream);
        return new StreamCallbacks(stream, partial, complete, error);
    }

    private static Result<String> result(String content) {
        return Result.<String>builder()
                .content(content)
                .tokenUsage(new TokenUsage(10, 5))
                .finishReason(FinishReason.STOP)
                .build();
    }

    private static ChatResponse chatResponse(String text) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(text))
                .tokenUsage(new TokenUsage(10, 5))
                .finishReason(FinishReason.STOP)
                .build();
    }
}
