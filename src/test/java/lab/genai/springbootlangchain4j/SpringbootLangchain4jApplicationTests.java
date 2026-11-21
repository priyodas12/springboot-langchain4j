package lab.genai.springbootlangchain4j;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Dummy key so the context starts without ANTHROPIC_API_KEY; no Claude call is made here.
@SpringBootTest(properties = "anthropic.api-key=test-key")
class SpringbootLangchain4jApplicationTests {

    @Test
    void contextLoads() {
    }

}
