package com.cloudstorage.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cloudstorage.config.InsightsProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Runs the real client against a local fake of the Gemini REST API, so no key or network is needed. */
class GeminiAiClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> query = new AtomicReference<>();
    private final AtomicReference<String> keyHeader = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private volatile String responseJson;
    private volatile int responseStatus = 200;

    @BeforeEach
    void startFakeApi() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            query.set(exchange.getRequestURI().getRawQuery());
            keyHeader.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopFakeApi() {
        server.stop(0);
    }

    private GeminiAiClient client(String apiKey) {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        return new GeminiAiClient(new InsightsProperties(apiKey, "gemini-3.8-flash", 512, 5, baseUrl));
    }

    private static String answer(String text, String finishReason) {
        return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":"
                + JSON.valueToTree(text) + "}]},\"finishReason\":\"" + finishReason + "\"}],"
                + "\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":5,\"totalTokenCount\":15}}";
    }

    @Test
    void callsTheGenerateContentEndpointOfTheConfiguredModelWithTheKeyInAHeader() throws Exception {
        responseJson = answer("{\"summary\":\"ok\",\"suggestions\":[]}", "STOP");

        String text = client("test-key-123").complete("SYSTEM RULES", "files: 12");

        assertThat(text).contains("\"summary\":\"ok\"");
        assertThat(path.get()).isEqualTo("/v1beta/models/gemini-3.8-flash:generateContent");
        assertThat(keyHeader.get()).isEqualTo("test-key-123");
        assertThat(query.get()).as("the key must never be in the URL").isNull();

        JsonNode body = JSON.readTree(requestBody.get());
        assertThat(body.at("/systemInstruction/parts/0/text").asText()).isEqualTo("SYSTEM RULES");
        assertThat(body.at("/contents/0/role").asText()).isEqualTo("user");
        assertThat(body.at("/contents/0/parts/0/text").asText()).isEqualTo("files: 12");
        assertThat(body.at("/generationConfig/maxOutputTokens").asInt()).isEqualTo(512);
        assertThat(body.at("/generationConfig/responseMimeType").asText()).isEqualTo("application/json");
    }

    @Test
    void doesNotSendSamplingSettingsThatGemini3ModelsRejectOrIgnore() throws Exception {
        responseJson = answer("{}", "STOP");

        client("k").complete("s", "u");

        JsonNode generation = JSON.readTree(requestBody.get()).path("generationConfig");
        assertThat(generation.has("temperature")).isFalse();
        assertThat(generation.has("topP")).isFalse();
        assertThat(generation.has("topK")).isFalse();
    }

    @Test
    void reportsUnavailableWhenThereIsNoKey() {
        GeminiAiClient withoutKey = client("");

        assertThat(withoutKey.available()).isFalse();
        assertThat(withoutKey.modelName()).isNull();
    }

    @Test
    void anErrorStatusSurfacesWithGeminisOwnMessage() {
        responseStatus = 429;
        responseJson = "{\"error\":{\"code\":429,\"message\":\"Quota exceeded\",\"status\":\"RESOURCE_EXHAUSTED\"}}";

        assertThatThrownBy(() -> client("k").complete("s", "u"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("429")
                .hasMessageContaining("Quota exceeded");
    }

    @Test
    void aBlockedPromptIsAFailureNotAnAnswer() {
        responseJson = "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}";

        assertThatThrownBy(() -> client("k").complete("s", "u"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blocked");
    }

    @Test
    void anAnswerCutOffByTheTokenLimitIsAFailure() {
        responseJson = answer("{\"summary\":\"half an ans", "MAX_TOKENS");

        assertThatThrownBy(() -> client("k").complete("s", "u"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAX_TOKENS");
    }

    @Test
    void reasoningPartsAreNotMixedIntoTheAnswer() {
        responseJson = "{\"candidates\":[{\"content\":{\"parts\":["
                + "{\"thought\":true,\"text\":\"let me think\"},{\"text\":\"{\\\"summary\\\":\\\"S\\\"}\"}]},\"finishReason\":\"STOP\"}]}";

        String text = client("k").complete("s", "u");

        assertThat(text).isEqualTo("{\"summary\":\"S\"}").doesNotContain("let me think");
    }
}
