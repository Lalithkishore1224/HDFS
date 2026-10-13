package com.cloudstorage.insights;

import com.cloudstorage.config.InsightsProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Calls Google's Gemini API (model {@code gemini-3.8-flash}) over its REST interface.
 * <p>
 * One call: {@code POST /v1beta/models/{model}:generateContent}. The key travels in the
 * {@code x-goog-api-key} header (never in the URL, so it cannot end up in logs) and is read from server
 * configuration; the browser never sees it. Any failure is thrown and handled by the caller, which falls
 * back to the built-in summary.
 */
@Component
class GeminiAiClient implements AiClient {

    private final InsightsProperties properties;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http;

    GeminiAiClient(InsightsProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(properties.timeoutSeconds())).build();
    }

    @Override
    public boolean available() {
        return properties.hasApiKey();
    }

    @Override
    public String modelName() {
        return available() ? properties.model() : null;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        properties.baseUrl() + "/v1beta/models/" + properties.model() + ":generateContent"))
                .timeout(Duration.ofSeconds(properties.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", properties.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(systemPrompt, userPrompt)))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("Could not reach the Gemini API: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the Gemini API", e);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("The Gemini API answered " + response.statusCode() + ": " + errorMessage(response.body()));
        }
        return answerText(response.body());
    }

    /**
     * The request. Notes from the model's documentation: {@code temperature}, {@code top_p} and {@code top_k}
     * must not be sent for Gemini 3.x (they are left at the model default), and the reasoning level is left
     * at its default. {@code responseMimeType} asks for JSON so the answer is easy to parse.
     */
    private String requestBody(String systemPrompt, String userPrompt) {
        ObjectNode root = json.createObjectNode();

        root.putObject("systemInstruction").putArray("parts").addObject().put("text", systemPrompt);

        ObjectNode user = root.putArray("contents").addObject();
        user.put("role", "user");
        user.putArray("parts").addObject().put("text", userPrompt);

        ObjectNode generation = root.putObject("generationConfig");
        generation.put("maxOutputTokens", properties.maxTokens());
        generation.put("responseMimeType", "application/json");
        return root.toString();
    }

    private String answerText(String body) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (IOException e) {
            throw new IllegalStateException("The Gemini API returned something that is not JSON", e);
        }
        String blocked = root.path("promptFeedback").path("blockReason").asText("");
        if (!blocked.isEmpty()) {
            throw new IllegalStateException("The Gemini API blocked the request: " + blocked);
        }
        JsonNode candidate = root.path("candidates").path(0);
        String finish = candidate.path("finishReason").asText("");
        if (!finish.isEmpty() && !"STOP".equals(finish)) {
            throw new IllegalStateException("The Gemini API did not finish normally: " + finish);
        }
        StringBuilder text = new StringBuilder();
        JsonNode parts = candidate.path("content").path("parts");
        if (parts instanceof ArrayNode array) {
            for (JsonNode part : array) {
                if (!part.path("thought").asBoolean(false)) {
                    text.append(part.path("text").asText(""));
                }
            }
        }
        String answer = text.toString().strip();
        if (answer.isEmpty()) {
            throw new IllegalStateException("The Gemini API returned no text");
        }
        return answer;
    }

    private String errorMessage(String body) {
        try {
            String message = json.readTree(body).path("error").path("message").asText("");
            return message.isEmpty() ? "no details" : message;
        } catch (IOException e) {
            return "no details";
        }
    }
}
