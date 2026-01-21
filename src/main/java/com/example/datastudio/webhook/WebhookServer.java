package com.example.datastudio.webhook;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * Embedded HTTP server for receiving webhook callbacks from DataStudio.
 *
 * <p>This server starts automatically when webhooks are configured and listens
 * for incoming POST requests on the configured endpoints.
 */
public class WebhookServer {

    private static final Logger logger = LoggerFactory.getLogger(WebhookServer.class);

    private final int port;
    private final WebhookHandler handler;
    private HttpServer server;

    /**
     * Create a new webhook server.
     *
     * @param port    The port to listen on
     * @param handler The webhook handler to process incoming events
     */
    public WebhookServer(int port, WebhookHandler handler) {
        this.port = port;
        this.handler = handler;
    }

    /**
     * Start the webhook server.
     *
     * @throws IOException if the server cannot be started
     */
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));

        // Also support /webhooks/* paths for flexibility
        server.createContext("/webhooks/ready", exchange -> handleWebhook(exchange, WebhookHandler.EVENT_READY_FOR_REVIEW));
        server.createContext("/webhooks/completed", exchange -> handleWebhook(exchange, WebhookHandler.EVENT_COMPLETED));

        // Health check endpoint
        server.createContext("/health", this::handleHealth);

        server.start();
        logger.info("Webhook server started on port {}", port);
        logger.info("Listening for events:");
        logger.info("  - POST /ready    -> {}", WebhookHandler.EVENT_READY_FOR_REVIEW);
        logger.info("  - POST /completed -> {}", WebhookHandler.EVENT_COMPLETED);
    }

    /**
     * Stop the webhook server.
     */
    public void stop() {
        if (server != null) {
            server.stop(0);
            logger.info("Webhook server stopped");
        }
    }

    /**
     * Get the port the server is listening on.
     */
    public int getPort() {
        return port;
    }

    private void handleWebhook(HttpExchange exchange, String eventType) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method Not Allowed");
            return;
        }

        try {
            String payload = readRequestBody(exchange);
            logger.info("Received {} webhook, raw payload: {}", eventType, payload);

            boolean handled = handler.handleWebhook(eventType, payload);

            if (handled) {
                sendResponse(exchange, 200, "OK");
            } else {
                sendResponse(exchange, 404, "No handler registered for event: " + eventType);
            }

        } catch (WebhookHandler.WebhookProcessingException e) {
            logger.error("Error processing webhook: {}", e.getMessage());
            sendResponse(exchange, 500, "Error processing webhook: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error handling webhook: {}", e.getMessage(), e);
            sendResponse(exchange, 500, "Internal Server Error");
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method Not Allowed");
            return;
        }
        sendResponse(exchange, 200, "{\"status\":\"healthy\"}");
    }

    private String readRequestBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}