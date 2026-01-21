package com.example.datastudio.webhook;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Example webhook handler for DataStudio events.
 *
 * <p>This class demonstrates how to handle webhook notifications from the DataStudio API.
 * In a real application, you would integrate this with your web framework
 * (e.g., Spring Boot, Quarkus, or Jakarta EE).
 *
 * <p>DataStudio supports the following webhook events:
 * <ul>
 *   <li><b>document.ready_for_review</b> - Document has been processed and is ready for human review</li>
 *   <li><b>document.completed</b> - Document processing is fully completed</li>
 * </ul>
 *
 * <h2>Example Spring Boot Integration</h2>
 * <pre>{@code
 * @RestController
 * @RequestMapping("/webhooks")
 * public class WebhookController {
 *
 *     private final WebhookHandler handler;
 *
 *     public WebhookController() {
 *         this.handler = new WebhookHandler();
 *
 *         // Register handlers
 *         handler.onReadyForReview(payload -> {
 *             System.out.println("Document ready for review:");
 *             System.out.println(payload.toString(2));
 *             notificationService.notifyReviewers(payload);
 *         });
 *
 *         handler.onCompleted(payload -> {
 *             System.out.println("Document completed:");
 *             System.out.println(payload.toString(2));
 *             documentService.markCompleted(payload);
 *         });
 *     }
 *
 *     @PostMapping("/ready")
 *     public ResponseEntity<Void> handleReadyForReview(@RequestBody String payload) {
 *         handler.handleWebhook("document.ready_for_review", payload);
 *         return ResponseEntity.ok().build();
 *     }
 *
 *     @PostMapping("/completed")
 *     public ResponseEntity<Void> handleCompleted(@RequestBody String payload) {
 *         handler.handleWebhook("document.completed", payload);
 *         return ResponseEntity.ok().build();
 *     }
 * }
 * }</pre>
 */
public class WebhookHandler {

    private static final Logger logger = LoggerFactory.getLogger(WebhookHandler.class);

    /**
     * Event type constants matching the DataStudio API.
     */
    public static final String EVENT_READY_FOR_REVIEW = "document.ready_for_review";
    public static final String EVENT_COMPLETED = "document.completed";

    private final Map<String, Consumer<JSONObject>> handlers = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<JSONObject>> pendingResults = new ConcurrentHashMap<>();

    /**
     * Register a handler for the "ready for review" event.
     *
     * <p>This event is fired when a document has been processed by the AI
     * and is ready for human review/validation.
     *
     * @param handler Consumer that receives the webhook payload
     * @return this instance for fluent configuration
     */
    public WebhookHandler onReadyForReview(Consumer<JSONObject> handler) {
        handlers.put(EVENT_READY_FOR_REVIEW, handler);
        logger.info("Registered handler for event: {}", EVENT_READY_FOR_REVIEW);
        return this;
    }

    /**
     * Register a handler for the "completed" event.
     *
     * <p>This event is fired when document processing is fully completed,
     * including any human review steps.
     *
     * @param handler Consumer that receives the webhook payload
     * @return this instance for fluent configuration
     */
    public WebhookHandler onCompleted(Consumer<JSONObject> handler) {
        handlers.put(EVENT_COMPLETED, handler);
        logger.info("Registered handler for event: {}", EVENT_COMPLETED);
        return this;
    }

    /**
     * Register a custom handler for any event type.
     *
     * @param eventType The event type string
     * @param handler   Consumer that receives the webhook payload
     * @return this instance for fluent configuration
     */
    public WebhookHandler on(String eventType, Consumer<JSONObject> handler) {
        handlers.put(eventType, handler);
        logger.info("Registered handler for event: {}", eventType);
        return this;
    }

    /**
     * Handle an incoming webhook request.
     *
     * @param eventType   The event type from the webhook
     * @param payloadJson The JSON payload as a string
     * @return true if the event was handled, false if no handler was registered
     */
    public boolean handleWebhook(String eventType, String payloadJson) {
        logger.info("Received webhook event: {}", eventType);
        logger.debug("Webhook payload: {}", payloadJson);

        Consumer<JSONObject> handler = handlers.get(eventType);
        if (handler == null) {
            logger.warn("No handler registered for event: {}", eventType);
            return false;
        }

        try {
            JSONObject payload = new JSONObject(payloadJson);
            handler.accept(payload);
            logger.info("Successfully processed webhook event: {}", eventType);
            return true;
        } catch (Exception e) {
            logger.error("Error processing webhook event {}: {}", eventType, e.getMessage(), e);
            throw new WebhookProcessingException("Failed to process webhook: " + eventType, e);
        }
    }

    /**
     * Handle an incoming webhook request with pre-parsed JSON.
     *
     * @param eventType The event type from the webhook
     * @param payload   The parsed JSON payload
     * @return true if the event was handled, false if no handler was registered
     */
    public boolean handleWebhook(String eventType, JSONObject payload) {
        logger.info("Received webhook event: {}", eventType);

        Consumer<JSONObject> handler = handlers.get(eventType);
        if (handler == null) {
            logger.warn("No handler registered for event: {}", eventType);
            return false;
        }

        try {
            handler.accept(payload);
            logger.info("Successfully processed webhook event: {}", eventType);
            return true;
        } catch (Exception e) {
            logger.error("Error processing webhook event {}: {}", eventType, e.getMessage(), e);
            throw new WebhookProcessingException("Failed to process webhook: " + eventType, e);
        }
    }

    /**
     * Check if a handler is registered for the given event type.
     *
     * @param eventType The event type to check
     * @return true if a handler is registered
     */
    public boolean hasHandler(String eventType) {
        return handlers.containsKey(eventType);
    }

    /**
     * Remove a handler for the given event type.
     *
     * @param eventType The event type
     */
    public void removeHandler(String eventType) {
        handlers.remove(eventType);
        logger.info("Removed handler for event: {}", eventType);
    }

    /**
     * Clear all registered handlers.
     */
    public void clearHandlers() {
        handlers.clear();
        logger.info("Cleared all webhook handlers");
    }

    /**
     * Register a process ID to wait for webhook completion.
     *
     * @param processId The process ID to wait for
     * @return CompletableFuture that will be completed when the webhook arrives
     */
    public CompletableFuture<JSONObject> registerPendingResult(String processId) {
        CompletableFuture<JSONObject> future = new CompletableFuture<>();
        pendingResults.put(processId, future);
        logger.info("Registered pending result for processId: {}", processId);
        return future;
    }

    /**
     * Wait for a webhook result for the given process ID.
     *
     * @param processId The process ID to wait for
     * @param timeout   Maximum time to wait
     * @param unit      Time unit for the timeout
     * @return The webhook payload when received
     * @throws TimeoutException If the timeout expires before the webhook arrives
     * @throws InterruptedException If the thread is interrupted while waiting
     */
    public JSONObject waitForResult(String processId, long timeout, TimeUnit unit)
            throws TimeoutException, InterruptedException {
        CompletableFuture<JSONObject> future = pendingResults.get(processId);
        if (future == null) {
            future = registerPendingResult(processId);
        }

        try {
            return future.get(timeout, unit);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new WebhookProcessingException("Error waiting for webhook", e.getCause());
        } finally {
            pendingResults.remove(processId);
        }
    }

    /**
     * Complete any pending result when webhook arrives.
     * Used when the webhook payload doesn't include a process ID.
     *
     * @param payload The webhook payload
     */
    public void completeAnyPendingResult(JSONObject payload) {
        if (pendingResults.isEmpty()) {
            logger.warn("No pending results to complete");
            return;
        }

        // Complete the first (and typically only) pending result
        var entry = pendingResults.entrySet().iterator().next();
        entry.getValue().complete(payload);
        pendingResults.remove(entry.getKey());
        logger.info("Completed pending result");
    }

    /**
     * Cancel a pending result (e.g., on error or timeout).
     *
     * @param processId The process ID to cancel
     */
    public void cancelPendingResult(String processId) {
        CompletableFuture<JSONObject> future = pendingResults.remove(processId);
        if (future != null) {
            future.cancel(true);
            logger.info("Cancelled pending result for processId: {}", processId);
        }
    }

    /**
     * Exception thrown when webhook processing fails.
     */
    public static class WebhookProcessingException extends RuntimeException {
        public WebhookProcessingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
