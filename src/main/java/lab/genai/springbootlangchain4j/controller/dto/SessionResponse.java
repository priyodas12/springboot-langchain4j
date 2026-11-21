package lab.genai.springbootlangchain4j.controller.dto;

import java.util.UUID;

/**
 * @param sessionId UUIDv7 identifying a new conversation; send it with every message of that conversation
 */
public record SessionResponse(UUID sessionId) {
}
