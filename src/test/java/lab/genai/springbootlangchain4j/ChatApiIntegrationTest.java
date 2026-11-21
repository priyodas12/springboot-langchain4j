package lab.genai.springbootlangchain4j;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.LangChain4jException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import lab.genai.springbootlangchain4j.controller.ChatController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end through every layer: HTTP → ChatController → ChatService → LangChain4j AiServices
 * (system prompt + per-session chat memory) → chat model. Only the Claude models are replaced by
 * in-process fakes, so no network call is made and no real API key is needed.
 */
@DisplayName("Chat API integration: HTTP to LangChain4j with fake Claude models")
@SpringBootTest(properties = {
        "anthropic.api-key=test-key",
        "anthropic.model-name=test-model",
        "anthropic.memory-max-messages=40"
})
@AutoConfigureMockMvc
class ChatApiIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private FakeChatModel chatModel;

    @Autowired
    private FakeStreamingChatModel streamingChatModel;

    @BeforeEach
    void resetFakes() {
        chatModel.reset();
        streamingChatModel.reset();
    }

    @DisplayName("a blocking conversation sends the system prompt and full history to the model")
    @Test
    void fullConversation_blocking_keepsMemoryPerSession() throws Exception {
        UUID session = createSession();

        String first = chat(session, "My name is Ada");
        String second = chat(session, "What is my name?");

        assertThat(first).isEqualTo("echo: My name is Ada");
        assertThat(second).isEqualTo("echo: What is my name?");

        // The second model call carries the system prompt plus the full conversation so far.
        List<ChatMessage> secondCall = chatModel.requests.get(1).messages();
        assertThat(secondCall).hasSize(4);
        assertThat(secondCall.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(((SystemMessage) secondCall.get(0)).text()).contains("helpful, friendly assistant");
        assertThat(((UserMessage) secondCall.get(1)).singleText()).isEqualTo("My name is Ada");
        assertThat(((AiMessage) secondCall.get(2)).text()).isEqualTo("echo: My name is Ada");
        assertThat(((UserMessage) secondCall.get(3)).singleText()).isEqualTo("What is my name?");
    }

    @DisplayName("different sessions do not share conversation memory")
    @Test
    void differentSessions_doNotShareMemory() throws Exception {
        UUID sessionA = createSession();
        UUID sessionB = createSession();

        chat(sessionA, "secret of A");
        chat(sessionB, "hello from B");

        List<ChatMessage> sessionBCall = chatModel.requests.get(1).messages();
        assertThat(sessionBCall).hasSize(2); // system + B's message only
        assertThat(sessionBCall.toString()).doesNotContain("secret of A");
    }

    @DisplayName("clearing a session makes the next message start a fresh conversation")
    @Test
    void clearSession_forgetsTheConversation() throws Exception {
        UUID session = createSession();
        chat(session, "remember this");

        mvc.perform(delete("/api/chat/{id}", session)).andExpect(status().isNoContent());
        chat(session, "new start");

        List<ChatMessage> afterClear = chatModel.requests.get(1).messages();
        assertThat(afterClear).hasSize(2); // system + "new start"
        assertThat(afterClear.toString()).doesNotContain("remember this");
    }

    @DisplayName("a model failure on POST /api/chat returns 502")
    @Test
    void blockingChat_modelFailure_returns502() throws Exception {
        chatModel.fail.set(true);
        UUID session = createSession();

        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(body(session, "Hi")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("The assistant could not complete the reply. Please try again."))
                .andExpect(header().exists(ChatController.CHAT_ID_HEADER));
    }

    @DisplayName("streaming sends tokens then done, and saves the reply in memory")
    @Test
    void streamingChat_streamsTokensThenDone_andStoresReplyInMemory() throws Exception {
        UUID session = createSession();

        String sse = stream(session, "Hi");

        assertThat(sse)
                .contains("event:token")
                .contains("{\"text\":\"Hello\"}")
                .contains("{\"text\":\", \"}")
                .contains("{\"text\":\"world\"}")
                .contains("event:done");

        // The streamed reply is saved, so a follow-up blocking call sees it in the history.
        chat(session, "and again?");
        List<ChatMessage> followUp = chatModel.requests.getFirst().messages();
        assertThat(followUp).hasSize(4);
        assertThat(((AiMessage) followUp.get(2)).text()).isEqualTo("Hello, world");
    }

    @DisplayName("a model failure while streaming emits an error event")
    @Test
    void streamingChat_modelFailure_emitsErrorEvent() throws Exception {
        streamingChatModel.fail.set(true);
        UUID session = createSession();

        String sse = stream(session, "Hi");

        assertThat(sse).contains("event:error").doesNotContain("event:done");
    }

    @DisplayName("an invalid request returns 400 and never reaches the model")
    @Test
    void invalidRequest_returns400_andNeverReachesTheModel() throws Exception {
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"not-a-uuid\",\"message\":\"Hi\"}"))
                .andExpect(status().isBadRequest());

        assertThat(chatModel.requests).isEmpty();
    }

    @DisplayName("the chat UI is served as a static HTML page")
    @Test
    void chatUi_isServedAsStaticPage() throws Exception {
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/api/chat/stream")));
    }

    // ---------- helpers ----------

    private UUID createSession() throws Exception {
        String json = mvc.perform(post("/api/chat/sessions"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.replaceAll(".*\"sessionId\":\"([^\"]+)\".*", "$1"));
        assertThat(id.version()).isEqualTo(7);
        return id;
    }

    private String chat(UUID session, String message) throws Exception {
        String json = mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(body(session, message)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(session.toString()))
                .andReturn().getResponse().getContentAsString();
        return json.replaceAll(".*\"reply\":\"([^\"]*)\".*", "$1");
    }

    private String stream(UUID session, String message) throws Exception {
        MvcResult started = mvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON).content(body(session, message)))
                .andExpect(request().asyncStarted())
                .andExpect(header().exists(ChatController.CHAT_ID_HEADER))
                .andReturn();
        return mvc.perform(asyncDispatch(started)).andReturn().getResponse().getContentAsString();
    }

    private static String body(UUID sessionId, String message) {
        return "{\"sessionId\":\"" + sessionId + "\",\"message\":\"" + message + "\"}";
    }

    // ---------- fake models (replace the real Claude models via @Primary) ----------

    @TestConfiguration
    static class FakeModels {
        @Bean
        @Primary
        FakeChatModel fakeChatModel() {
            return new FakeChatModel();
        }

        @Bean
        @Primary
        FakeStreamingChatModel fakeStreamingChatModel() {
            return new FakeStreamingChatModel();
        }
    }

    /** Replies "echo: <last user message>" and records every request it receives. */
    static class FakeChatModel implements ChatModel {
        final List<ChatRequest> requests = new CopyOnWriteArrayList<>();
        final AtomicBoolean fail = new AtomicBoolean();

        void reset() {
            requests.clear();
            fail.set(false);
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            requests.add(request);
            if (fail.get()) {
                throw new LangChain4jException("simulated upstream failure");
            }
            UserMessage last = (UserMessage) request.messages().getLast();
            return response("echo: " + last.singleText());
        }
    }

    /** Streams "Hello", ", ", "world" and completes, or fails when {@link #fail} is set. */
    static class FakeStreamingChatModel implements StreamingChatModel {
        final AtomicBoolean fail = new AtomicBoolean();

        void reset() {
            fail.set(false);
        }

        @Override
        public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            if (fail.get()) {
                handler.onError(new LangChain4jException("simulated stream failure"));
                return;
            }
            handler.onPartialResponse("Hello");
            handler.onPartialResponse(", ");
            handler.onPartialResponse("world");
            handler.onCompleteResponse(response("Hello, world"));
        }
    }

    private static ChatResponse response(String text) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(text))
                .tokenUsage(new TokenUsage(12, 3))
                .finishReason(FinishReason.STOP)
                .build();
    }
}
