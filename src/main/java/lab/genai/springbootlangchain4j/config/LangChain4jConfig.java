package lab.genai.springbootlangchain4j.config;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import lab.genai.springbootlangchain4j.service.ChatAssistant;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;

@Configuration
@EnableConfigurationProperties(AnthropicProperties.class)
public class LangChain4jConfig {

    /** Server-side refusal fallback: if Claude declines, the API retries on a fallback model it picks. */
    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    @Bean
    ChatModel chatModel(AnthropicProperties props) {
        return AnthropicChatModel.builder()
                .apiKey(props.apiKey())
                .modelName(props.modelName())
                .maxTokens(props.maxTokens())
                // Thinking is always on for Claude Opus 5.5 (adaptive); depth is controlled by effort.
                // Do not set temperature/topP/topK: current Claude models reject them.
                .thinkingType("adaptive")
                .returnThinking(true)
                .sendThinking(true)
                .beta(FALLBACK_BETA)
                .customParameters(requestExtras(props))
                .timeout(timeout(props))
                .logRequests(props.logRequests())
                .logResponses(props.logResponses())
                .build();
    }

    @Bean
    StreamingChatModel streamingChatModel(AnthropicProperties props) {
        return AnthropicStreamingChatModel.builder()
                .apiKey(props.apiKey())
                .modelName(props.modelName())
                .maxTokens(props.streamingMaxTokens())
                .thinkingType("adaptive")
                .returnThinking(true)
                .sendThinking(true)
                .beta(FALLBACK_BETA)
                .customParameters(requestExtras(props))
                .timeout(timeout(props))
                .logRequests(props.logRequests())
                .logResponses(props.logResponses())
                .build();
    }

    /**
     * In-memory store: conversations are lost on restart and not shared across instances.
     * Swap in a persistent {@link ChatMemoryStore} (Redis, JDBC, ...) for multi-instance deployments.
     */
    @Bean
    ChatMemoryStore chatMemoryStore() {
        return new InMemoryChatMemoryStore();
    }

    @Bean
    ChatMemoryProvider chatMemoryProvider(ChatMemoryStore store, AnthropicProperties props) {
        return sessionId -> MessageWindowChatMemory.builder()
                .id(sessionId)
                .maxMessages(props.memoryMaxMessages())
                .chatMemoryStore(store)
                .build();
    }

    @Bean
    ChatAssistant chatAssistant(ChatModel chatModel,
                                StreamingChatModel streamingChatModel,
                                ChatMemoryProvider chatMemoryProvider) {
        return AiServices.builder(ChatAssistant.class)
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .build();
    }

    private static Map<String, Object> requestExtras(AnthropicProperties props) {
        return Map.of(
                "fallbacks", "default",
                "output_config", Map.of("effort", props.effort()));
    }

    private static Duration timeout(AnthropicProperties props) {
        return props.timeout() != null ? props.timeout() : DEFAULT_TIMEOUT;
    }
}
