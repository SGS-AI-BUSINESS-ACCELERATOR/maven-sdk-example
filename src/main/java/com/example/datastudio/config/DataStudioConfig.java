package com.example.datastudio.config;

import ai.accelerator.DataStudioSDK.Environments;

/**
 * Configuration record for DataStudio SDK.
 *
 * <p>This configuration can be loaded from:
 * <ul>
 *   <li>Environment variables (recommended for production)</li>
 *   <li>Programmatically for testing</li>
 * </ul>
 *
 * <p>Environment variables:
 * <ul>
 *   <li>DATASTUDIO_API_KEY - Required: Your API key</li>
 *   <li>DATASTUDIO_WEBHOOK_URL - Required: Base URL for webhooks (e.g., ngrok URL)</li>
 *   <li>DATASTUDIO_ENVIRONMENT - Optional: PROD or SAND_BOX (default: SAND_BOX)</li>
 *   <li>DATASTUDIO_USER - Optional: Username for document tracking</li>
 *   <li>DATASTUDIO_WEBHOOK_PORT - Optional: Port for webhook server (default: 8080)</li>
 * </ul>
 *
 * @param apiKey      The API key for authentication (required)
 * @param environment The environment to use (PROD or SAND_BOX)
 * @param userName    The username for document tracking
 * @param webhookUrl  The base URL for webhook endpoints (required)
 * @param webhookPort The port for the embedded webhook server (default: 8080)
 */
public record DataStudioConfig(
        String apiKey,
        Environments environment,
        String userName,
        String webhookUrl,
        int webhookPort
) {

    /**
     * Create a new configuration with validation.
     */
    public DataStudioConfig {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("API key is required");
        }
        if (environment == null) {
            throw new IllegalArgumentException("Environment is required");
        }
        if (webhookUrl == null || webhookUrl.isBlank()) {
            throw new IllegalArgumentException("Webhook URL is required");
        }
        if (userName == null || userName.isBlank()) {
            userName = "example-user";
        }
    }

    /**
     * Load configuration from environment variables.
     *
     * @return DataStudioConfig populated from environment
     * @throws IllegalStateException if required environment variables are missing
     */
    public static DataStudioConfig fromEnvironment() {
        String apiKey = getRequiredEnv("DATASTUDIO_API_KEY");
        String webhookUrl = getRequiredEnv("DATASTUDIO_WEBHOOK_URL");
        Environments environment = parseEnvironment(
                getOptionalEnv("DATASTUDIO_ENVIRONMENT", "SAND_BOX")
        );
        String userName = getOptionalEnv("DATASTUDIO_USER", "example-user");
        int webhookPort = parsePort(getOptionalEnv("DATASTUDIO_WEBHOOK_PORT", "8080"));

        return new DataStudioConfig(apiKey, environment, userName, webhookUrl, webhookPort);
    }

    /**
     * Create a configuration for the sandbox environment.
     * Useful for testing and development.
     *
     * @param apiKey     The API key
     * @param webhookUrl The base URL for webhooks
     * @return Configuration for sandbox
     */
    public static DataStudioConfig forSandbox(String apiKey, String webhookUrl) {
        return new DataStudioConfig(apiKey, Environments.SAND_BOX, "test-user", webhookUrl, 8080);
    }

    /**
     * Create a configuration for the production environment.
     *
     * @param apiKey     The API key
     * @param userName   The username
     * @param webhookUrl The base URL for webhooks
     * @return Configuration for production
     */
    public static DataStudioConfig forProduction(String apiKey, String userName, String webhookUrl) {
        return new DataStudioConfig(apiKey, Environments.PROD, userName, webhookUrl, 8080);
    }

    /**
     * Create a new configuration with a different webhook port.
     *
     * @param port The port for the webhook server
     * @return New configuration with the specified port
     */
    public DataStudioConfig withWebhookPort(int port) {
        return new DataStudioConfig(apiKey, environment, userName, webhookUrl, port);
    }

    private static String getRequiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Required environment variable not set: " + name
            );
        }
        return value;
    }

    private static String getOptionalEnv(String name, String defaultValue) {
        String value = System.getenv(name);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }

    private static Environments parseEnvironment(String value) {
        try {
            return Environments.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Invalid environment value: " + value + ". Must be PROD or SAND_BOX"
            );
        }
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65535) {
                throw new IllegalStateException("Port must be between 1 and 65535: " + value);
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid port value: " + value);
        }
    }
}
