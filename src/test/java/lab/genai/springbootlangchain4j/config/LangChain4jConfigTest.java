package lab.genai.springbootlangchain4j.config;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lab.genai.springbootlangchain4j.service.ChatAssistant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static dev.langchain4j.data.message.UserMessage.userMessage;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the bean wiring and the validation of {@code anthropic.*} properties, without starting a web
 * server and without calling Claude (the models are built but never invoked).
 */
@DisplayName("LangChain4jConfig: bean wiring and anthropic.* validation")
class LangChain4jConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LangChain4jConfig.class)
            .withPropertyValues(
                    "anthropic.api-key=test-key",
                    "anthropic.model-name=test-model",
                    "anthropic.effort=medium",
                    "anthropic.max-tokens=16000",
                    "anthropic.streaming-max-tokens=64000",
                    "anthropic.memory-max-messages=4",
                    "anthropic.timeout=30s");

    @DisplayName("valid properties create all beans and bind every anthropic.* value")
    @Test
    void validProperties_createAllBeans_andBindValues() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed()
                    .hasSingleBean(ChatModel.class)
                    .hasSingleBean(StreamingChatModel.class)
                    .hasSingleBean(ChatMemoryStore.class)
                    .hasSingleBean(ChatMemoryProvider.class)
                    .hasSingleBean(ChatAssistant.class);

            AnthropicProperties props = ctx.getBean(AnthropicProperties.class);
            assertThat(props.modelName()).isEqualTo("test-model");
            assertThat(props.effort()).isEqualTo("medium");
            assertThat(props.maxTokens()).isEqualTo(16_000);
            assertThat(props.streamingMaxTokens()).isEqualTo(64_000);
            assertThat(props.memoryMaxMessages()).isEqualTo(4);
            assertThat(props.timeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(props.logRequests()).isFalse();
            assertThat(props.logResponses()).isFalse();
        });
    }

    @DisplayName("anthropic.timeout is optional")
    @Test
    void timeoutIsOptional() {
        runner.withPropertyValues("anthropic.timeout=")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(ChatModel.class));
    }

    @DisplayName("an invalid anthropic.* property fails application startup")
    @ParameterizedTest(name = "{0}={1} is rejected")
    @CsvSource({
            "anthropic.api-key, ''",
            "anthropic.model-name, ''",
            "anthropic.effort, ''",
            "anthropic.max-tokens, 0",
            "anthropic.streaming-max-tokens, -1",
            "anthropic.memory-max-messages, 0"
    })
    void invalidProperty_failsStartup(String key, String value) {
        runner.withPropertyValues(key + "=" + value)
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @DisplayName("a missing anthropic.api-key fails application startup")
    @Test
    void missingApiKey_failsStartup() {
        new ApplicationContextRunner()
                .withUserConfiguration(LangChain4jConfig.class)
                .withPropertyValues("anthropic.model-name=test-model", "anthropic.effort=medium",
                        "anthropic.max-tokens=1", "anthropic.streaming-max-tokens=1",
                        "anthropic.memory-max-messages=1")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @DisplayName("chat memory is per session and keeps only the last N messages")
    @Test
    void chatMemory_isPerSession_andSlidingWindowKeepsLastMessages() {
        runner.run(ctx -> {
            ChatMemoryProvider provider = ctx.getBean(ChatMemoryProvider.class);

            var memoryA = provider.get("session-a");
            for (int i = 1; i <= 6; i++) {
                memoryA.add(userMessage("message " + i));
            }
            provider.get("session-b").add(userMessage("other session"));

            // memory-max-messages=4: only the last 4 are kept, and sessions don't share memory
            assertThat(provider.get("session-a").messages()).hasSize(4)
                    .extracting(m -> ((dev.langchain4j.data.message.UserMessage) m).singleText())
                    .containsExactly("message 3", "message 4", "message 5", "message 6");
            assertThat(provider.get("session-b").messages()).hasSize(1);
        });
    }

    @DisplayName("chat memory is backed by the shared store, so deleting from the store clears the session")
    @Test
    void chatMemory_isBackedBySharedStore_soClearingTheStoreClearsTheSession() {
        runner.run(ctx -> {
            ChatMemoryProvider provider = ctx.getBean(ChatMemoryProvider.class);
            ChatMemoryStore store = ctx.getBean(ChatMemoryStore.class);
            provider.get("session-a").add(userMessage("hello"));

            store.deleteMessages("session-a");

            assertThat(provider.get("session-a").messages()).isEmpty();
        });
    }
}
