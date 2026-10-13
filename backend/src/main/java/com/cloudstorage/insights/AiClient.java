package com.cloudstorage.insights;

/** One question to a language model. Kept tiny so it is easy to fake in tests or swap for another provider. */
public interface AiClient {

    /** False when no API key is configured. */
    boolean available();

    /** Name of the model that will answer; null if unavailable. */
    String modelName();

    /**
     * @return the model's text answer
     * @throws RuntimeException on any failure (network, timeout, refusal), which the caller treats as "no AI"
     */
    String complete(String systemPrompt, String userPrompt);
}
