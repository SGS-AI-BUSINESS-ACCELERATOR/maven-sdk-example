package com.example.datastudio.webhook;

import ai.accelerator.exceptions.WebhookVerificationException;
import ai.accelerator.webhook.VerificationResult;
import ai.accelerator.webhook.WebhookVerifier;
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
 *
 * <p>When constructed with a non-null {@link WebhookVerifier}, every inbound POST
 * is HMAC-verified using the {@code X-SGS-Timestamp} and {@code X-SGS-Signature}
 * headers (see SDK doc § Webhook Signature Verification). Mismatched, stale, or
 * unsigned deliveries are rejected with HTTP 401 before the handler runs.
 */
public class WebhookServer {

    private static final Logger logger = LoggerFactory.getLogger(WebhookServer.class);

    private static final String SIGNATURE_HEADER = "X-SGS-Signature";
    private static final String TIMESTAMP_HEADER = "X-SGS-Timestamp";

    private final int port;
    private final WebhookHandler handler;
    private final WebhookVerifier verifier;
    private HttpServer server;

    /**
     * Create a new webhook server with no signature verification.
     *
     * @param port    The port to listen on
     * @param handler The webhook handler to process incoming events
     */
    public WebhookServer(int port, WebhookHandler handler) {
        this(port, handler, null);
    }

    /**
     * Create a new webhook server.
     *
     * @param port     The port to listen on
     * @param handler  The webhook handler to process incoming events
     * @param verifier Optional HMAC verifier; when {@code null}, signatures are not checked
     */
    public WebhookServer(int port, WebhookHandler handler, WebhookVerifier verifier) {
        this.port = port;
        this.handler = handler;
        this.verifier = verifier;
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
        server.createContext("/webhooks/failed", exchange -> handleWebhook(exchange, WebhookHandler.EVENT_PROCESSING_FAILED));

        // Health check endpoint
        server.createContext("/health", this::handleHealth);

        server.start();
        logger.info("Webhook server started on port {}", port);
        logger.info("Listening for events:");
        logger.info("  - POST /webhooks/ready     -> {}", WebhookHandler.EVENT_READY_FOR_REVIEW);
        logger.info("  - POST /webhooks/completed -> {}", WebhookHandler.EVENT_COMPLETED);
        logger.info("  - POST /webhooks/failed    -> {}", WebhookHandler.EVENT_PROCESSING_FAILED);
        if (verifier != null) {
            logger.info("HMAC verification: ENABLED (X-SGS-Signature / X-SGS-Timestamp)");
        } else {
            logger.warn("HMAC verification: DISABLED — set DATASTUDIO_WEBHOOK_SECRET to enable");
        }
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
            byte[] rawPayload = readRequestBodyBytes(exchange);
            logger.info("Received {} webhook ({} bytes)", eventType, rawPayload.length);

            if (verifier != null) {
                String signature = exchange.getRequestHeaders().getFirst(SIGNATURE_HEADER);
                String timestamp = exchange.getRequestHeaders().getFirst(TIMESTAMP_HEADER);

                if (signature == null || timestamp == null) {
                    logger.warn("Rejecting unsigned {} delivery (missing {}/{})",
                            eventType, SIGNATURE_HEADER, TIMESTAMP_HEADER);
                    logger.warn("Incoming headers were:");
                    exchange.getRequestHeaders().forEach((name, values) ->
                            logger.warn("  {} = {}", name, values));
                    sendResponse(exchange, 401, "Missing signature headers");
                    return;
                }

                try {
                    VerificationResult vr = verifier.verify(rawPayload, timestamp, signature);
                    logger.info("Signature OK for {} (signedAt={})", eventType, vr.signedAt());
                } catch (WebhookVerificationException e) {
                    logger.warn("Signature verification FAILED for {}: {}", eventType, e.getMessage());
                    sendResponse(exchange, 401, "Invalid signature: " + e.getMessage());
                    return;
                }
            }

            String payload = new String(rawPayload, StandardCharsets.UTF_8);
            logger.debug("Verified payload: {}", payload);
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

    private byte[] readRequestBodyBytes(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return is.readAllBytes();
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