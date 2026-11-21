package lab.genai.springbootlangchain4j.controller.dto;

import java.util.UUID;

/**
 * @param chatId    UUIDv7 of this exchange (one message and its reply); matches {@code chatId} in server logs
 * @param sessionId UUIDv7 of the conversation; matches {@code sessionId} in server logs
 */
public record ChatResponse(UUID chatId, UUID sessionId, String reply) {
}
