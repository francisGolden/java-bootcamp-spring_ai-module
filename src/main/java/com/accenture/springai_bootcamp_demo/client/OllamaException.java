package com.accenture.springai_bootcamp_demo.client;

/**
 * Raised when the Ollama API cannot be reached or returns an unusable
 * response. Carries an HTTP-friendly status hint for the web layer.
 */
public class OllamaException extends RuntimeException {

    public OllamaException(String message) {
        super(message);
    }

    public OllamaException(String message, Throwable cause) {
        super(message, cause);
    }
}