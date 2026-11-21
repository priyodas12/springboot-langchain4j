package lab.genai.springbootlangchain4j.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Claude (Anthropic) settings, bound from the {@code anthropic.*} keys in application.yaml.
 * The API key must come from the environment (ANTHROPIC_API_KEY), never from a committed file.
 */
@Validated
@ConfigurationProperties(prefix = "anthropic")
public record AnthropicProperties(
        @NotBlank String apiKey,
        @NotBlank String modelName,
        @NotBlank String effort,
        @Positive int maxTokens,
        @Positive int streamingMaxTokens,
        @Positive int memoryMaxMessages,
        Duration timeout,
        boolean logRequests,
        boolean logResponses
) {
}
