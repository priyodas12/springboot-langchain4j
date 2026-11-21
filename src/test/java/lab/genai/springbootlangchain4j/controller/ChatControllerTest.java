package lab.genai.springbootlangchain4j.controller;

import dev.langchain4j.exception.LangChain4jException;
import lab.genai.springbootlangchain4j.logging.ChatContext;
import lab.genai.springbootlangchain4j.service.ChatService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Web-layer slice test: real MVC + validation + JSON, with {@link ChatService} mocked. */
@DisplayName("ChatController: REST and SSE endpoints (web layer)")
@WebMvcTest(ChatController.class)
class ChatControllerTest {

    private static final String FAILURE_MESSAGE = "The assistant could not complete the reply. Please try again.";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ChatService chatService;

    private final UUID sessionId = ChatContext.newSessionId();

    // ---------- POST /api/chat ----------

    @DisplayName("POST /api/chat returns the reply with matching chatId in body and X-Chat-Id header")
    @Test
    void chat_returnsReply_withChatIdInBodyAndHeader() throws Exception {
        when(chatService.chat(any(), eq("Hello"))).thenReturn("Hi there");

        MvcResult result = mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(sessionId, "Hello")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.sessionId").value(sessionId.toString()))
                .andExpect(jsonPath("$.reply").value("Hi there"))
                .andExpect(header().exists(ChatController.CHAT_ID_HEADER))
                .andReturn();

        UUID headerChatId = UUID.fromString(result.getResponse().getHeader(ChatController.CHAT_ID_HEADER));
        assertThat(headerChatId.version()).isEqualTo(7);
        assertThat(result.getResponse().getContentAsString()).contains("\"chatId\":\"" + headerChatId + "\"");

        ArgumentCaptor<ChatContext> ctx = ArgumentCaptor.forClass(ChatContext.class);
        verify(chatService).chat(ctx.capture(), eq("Hello"));
        assertThat(ctx.getValue().sessionId()).isEqualTo(sessionId);
        assertThat(ctx.getValue().chatId()).isEqualTo(headerChatId);
    }

    @DisplayName("POST /api/chat assigns a new chatId to every request")
    @Test
    void chat_eachRequestGetsANewChatId() throws Exception {
        when(chatService.chat(any(), any())).thenReturn("ok");

        String first = postChat().getResponse().getHeader(ChatController.CHAT_ID_HEADER);
        String second = postChat().getResponse().getHeader(ChatController.CHAT_ID_HEADER);

        assertThat(first).isNotEqualTo(second);
    }

    @DisplayName("POST /api/chat returns 502 ProblemDetail with X-Chat-Id when the model fails")
    @Test
    void chat_modelFailure_returns502ProblemDetail_withChatIdHeader() throws Exception {
        when(chatService.chat(any(), any())).thenThrow(new LangChain4jException("upstream down"));

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(sessionId, "Hello")))
                .andExpect(status().isBadGateway())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.detail").value(FAILURE_MESSAGE))
                .andExpect(header().exists(ChatController.CHAT_ID_HEADER));
    }

    @DisplayName("POST /api/chat rejects invalid request bodies with 400 and never calls the service")
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"message\":\"Hello\"}",                                  // missing sessionId
            "{\"sessionId\":\"not-a-uuid\",\"message\":\"Hello\"}",     // malformed sessionId
            "{\"sessionId\":\"%s\"}",                                   // missing message
            "{\"sessionId\":\"%s\",\"message\":\"\"}",                  // empty message
            "{\"sessionId\":\"%s\",\"message\":\"   \"}",               // blank message
            "not json"
    })
    void chat_invalidRequest_returns400_andNeverCallsService(String template) throws Exception {
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(template.formatted(sessionId)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(chatService);
    }

    @DisplayName("POST /api/chat accepts a 20,000-character message and rejects 20,001")
    @Test
    void chat_messageAtMaxLength_isAccepted_andOneOverIsRejected() throws Exception {
        when(chatService.chat(any(), any())).thenReturn("ok");

        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(body(sessionId, "a".repeat(20_000))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(body(sessionId, "a".repeat(20_001))))
                .andExpect(status().isBadRequest());
    }

    @DisplayName("POST /api/chat with a non-JSON content type returns 415")
    @Test
    void chat_wrongContentType_returns415() throws Exception {
        mvc.perform(post("/api/chat").contentType(MediaType.TEXT_PLAIN).content("Hello"))
                .andExpect(status().isUnsupportedMediaType());

        verifyNoInteractions(chatService);
    }

    // ---------- POST /api/chat/stream ----------

    @DisplayName("POST /api/chat/stream emits JSON token events followed by a done event")
    @Test
    @SuppressWarnings("unchecked")
    void streamChat_emitsTokenEventsThenDone_asJson() throws Exception {
        doAnswer(inv -> {
            Consumer<String> onToken = inv.getArgument(2);
            Runnable onComplete = inv.getArgument(3);
            onToken.accept("Hello");
            onToken.accept(" world\nline 2");
            onComplete.run();
            return null;
        }).when(chatService).streamChat(any(), eq("Hi"), any(), any(), any());

        MvcResult started = mvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(sessionId, "Hi")))
                .andExpect(request().asyncStarted())
                .andExpect(header().exists(ChatController.CHAT_ID_HEADER))
                .andReturn();

        String sse = mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(sse)
                .contains("event:token")
                .contains("{\"text\":\"Hello\"}")
                // spaces and newlines inside a token survive because data is JSON-encoded
                .contains("{\"text\":\" world\\nline 2\"}")
                .contains("event:done");
        assertThat(sse.indexOf("event:done")).isGreaterThan(sse.lastIndexOf("event:token"));
    }

    @DisplayName("POST /api/chat/stream emits a generic error event without leaking details")
    @Test
    void streamChat_modelFailure_emitsGenericErrorEvent() throws Exception {
        doAnswer(inv -> {
            Consumer<Throwable> onError = inv.getArgument(4);
            onError.accept(new RuntimeException("internal detail that must not leak"));
            return null;
        }).when(chatService).streamChat(any(), any(), any(), any(), any());

        MvcResult started = mvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(sessionId, "Hi")))
                .andExpect(request().asyncStarted())
                .andReturn();

        String sse = mvc.perform(asyncDispatch(started)).andReturn().getResponse().getContentAsString();

        assertThat(sse)
                .contains("event:error")
                .contains(FAILURE_MESSAGE)
                .doesNotContain("internal detail")
                .doesNotContain("event:done");
    }

    @DisplayName("POST /api/chat/stream rejects an invalid request with 400")
    @Test
    void streamChat_invalidRequest_returns400() throws Exception {
        mvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"" + sessionId + "\",\"message\":\"\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(chatService);
    }

    // ---------- sessions ----------

    @DisplayName("POST /api/chat/sessions returns 201 with the new sessionId")
    @Test
    void createSession_returns201WithSessionId() throws Exception {
        UUID newId = ChatContext.newSessionId();
        when(chatService.createSession()).thenReturn(newId);

        mvc.perform(post("/api/chat/sessions"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sessionId").value(newId.toString()));
    }

    @DisplayName("DELETE /api/chat/{sessionId} returns 204 and clears that session")
    @Test
    void clearSession_returns204_andClearsThatSession() throws Exception {
        mvc.perform(delete("/api/chat/{sessionId}", sessionId))
                .andExpect(status().isNoContent());

        verify(chatService).clearSession(sessionId);
    }

    @DisplayName("DELETE /api/chat/{sessionId} with an invalid UUID returns 400")
    @Test
    void clearSession_invalidUuid_returns400() throws Exception {
        mvc.perform(delete("/api/chat/{sessionId}", "not-a-uuid"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(chatService);
    }

    // ---------- helpers ----------

    private MvcResult postChat() throws Exception {
        return mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(sessionId, "Hello")))
                .andExpect(status().isOk())
                .andReturn();
    }

    private static String body(UUID sessionId, String message) {
        return "{\"sessionId\":\"" + sessionId + "\",\"message\":\"" + message + "\"}";
    }
}
