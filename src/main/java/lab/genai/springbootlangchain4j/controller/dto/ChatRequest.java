package lab.genai.springbootlangchain4j.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * @param sessionId conversation id issued by {@code POST /api/chat/sessions} (UUIDv7); messages with the
 *                  same id share memory
 * @param message   the user's chat message
 */
public record ChatRequest(
        @NotNull UUID sessionId,
        @NotBlank @Size(max = 20_000) String message
) {
}
