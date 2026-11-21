package lab.genai.springbootlangchain4j;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Dummy values so the context starts without ANTHROPIC_API_KEY / ANTHROPIC_MODEL_NAME; no Claude call is made here.
@DisplayName("Application smoke test")
@SpringBootTest(properties = {"anthropic.api-key=test-key", "anthropic.model-name=test-model"})
class SpringbootLangchain4jApplicationTests {

    @DisplayName("Spring application context starts with dummy config values")
    @Test
    void contextLoads() {
    }

}
