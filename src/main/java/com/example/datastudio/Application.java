package com.example.datastudio;

import ai.accelerator.CountryCode;
import ai.accelerator.DataStudioSDK;
import ai.accelerator.DataStudioSDK.*;
import ai.accelerator.config.SdkConfig;
import ai.accelerator.exceptions.*;
import ai.accelerator.retry.ExponentialBackoffPolicy;
import ai.accelerator.retry.NoRetryPolicy;
import com.example.datastudio.config.DataStudioConfig;
import com.example.datastudio.service.DocumentProcessorService;
import com.example.datastudio.webhook.WebhookHandler;
import com.example.datastudio.webhook.WebhookServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;

/**
 * Main application demonstrating SGS AI DataStudio SDK integration.
 *
 * <p>This example shows:
 * <ul>
 *   <li>SDK initialization with Builder pattern</li>
 *   <li>Document upload with metadata</li>
 *   <li>Webhook-based event handling</li>
 *   <li>Comprehensive error handling</li>
 * </ul>
 *
 * <p>Prerequisites:
 * <ul>
 *   <li>Set DATASTUDIO_API_KEY environment variable</li>
 *   <li>Set DATASTUDIO_WEBHOOK_URL environment variable (e.g., ngrok URL)</li>
 *   <li>Prepare a PDF document for upload</li>
 * </ul>
 */
public class Application {

    private static final Logger logger = LoggerFactory.getLogger(Application.class);

    private static WebhookHandler webhookHandler;

    public static void main(String[] args) {
        logger.info("Starting SGS DataStudio SDK Example Application");

        try {
            // Load configuration from environment
            DataStudioConfig config = DataStudioConfig.fromEnvironment();

            // Initialize the SDK
            DataStudioSDK sdk = initializeSDK(config);

            // Start webhook server
            WebhookServer webhookServer = startWebhookServer(config);
            logger.info("Webhook server started on port {}", config.webhookPort());

            // Register shutdown hook to stop server cleanly
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Shutting down...");
                webhookServer.stop();
            }));

            // Create document processor service
            DocumentProcessorService processor = new DocumentProcessorService(sdk);

            // Validate command line arguments
            if (args.length == 0) {
                logger.error("No file path provided");
                printUsage();
                System.exit(1);
            }

            // Upload the document
            uploadDocument(processor, args[0], config);

            // Keep the server running to receive webhook events
            logger.info("Waiting for webhook events. Press Ctrl+C to stop.");
            Thread.currentThread().join();

        } catch (IllegalStateException | IllegalArgumentException e) {
            logger.error("Configuration error: {}", e.getMessage());
            printUsage();
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.info("Application interrupted");
        } catch (Exception e) {
            logger.error("Unexpected error: {}", e.getMessage(), e);
            System.exit(1);
        }

        logger.info("Application completed");
    }

    /**
     * Initialize the DataStudio SDK with the provided configuration.
     *
     * <p>Since SDK 0.2.0-alpha the SDK accepts an explicit {@link SdkConfig} via
     * {@code .withSdkConfig(...)} that controls per-stage timeouts and the retry policy.
     * See {@link #buildSdkConfig()} for the full rationale of the values used here.
     *
     * <p>This example also registers a dedicated {@code PROCESSING_FAILED} webhook
     * (added in 0.2.0-alpha). If you do <em>not</em> register one, failure events are
     * delivered to the {@code READY_FOR_REVIEW} URL — backward-compatible, but harder
     * to route in your handler.
     */
    private static DataStudioSDK initializeSDK(DataStudioConfig config) {
        logger.info("Initializing DataStudio SDK for environment: {}", config.environment());
        logger.info("Configuring webhooks for URL: {}", config.webhookUrl());

        return DataStudioSDK.Builder.aDataStudioSDK()
                .withApiKey(config.apiKey())
                .withEnvironment(config.environment())
                .withDefaultHeaders(config.defaultHeaders())
                .withSdkConfig(buildSdkConfig())
                .withWebhooks(
                    new WebHook(Events.READY_FOR_REVIEW, config.webhookUrl() + "/ready"),
                    new WebHook(Events.COMPLETED, config.webhookUrl() + "/completed"),
                    new WebHook(Events.PROCESSING_FAILED, config.webhookUrl() + "/failed")
                )
                .build();
    }

    /**
     * Build the {@link SdkConfig} used by the SDK (timeouts + retry policy).
     *
     * <p><strong>Why this method exists</strong>: in 0.2.0-alpha the SDK introduced
     * {@code SdkConfig} so callers can tune two orthogonal concerns:
     * <ol>
     *   <li><strong>Timeouts</strong> — how long a single HTTP attempt may take before
     *       failing, separated per stage so a slow upload does not force every status
     *       check to wait 60 s.</li>
     *   <li><strong>Retry policy</strong> — what to do when a single attempt fails with a
     *       <em>transient</em> error (network/timeout or 5xx). 4xx responses are surfaced
     *       immediately and never retried.</li>
     * </ol>
     *
     * <h3>Timeouts (per HTTP attempt, not per call)</h3>
     * <ul>
     *   <li>{@code connectTimeout = 10 s} — TCP connect + TLS handshake. The SDK default is
     *       also 10 s; we set it explicitly to make the contract visible at the call site.</li>
     *   <li>{@code uploadTimeout  = 120 s} — wall-clock budget for the streaming PDF upload
     *       (or the GCS PUT in the presigned flow). Default is 60 s; we raise it to 120 s
     *       so files near the 10 MB cap on slow uplinks do not hit the timeout. If you
     *       only ever upload small documents you can lower this back to {@code Duration.ofSeconds(60)}.</li>
     *   <li>{@code queryTimeout   = 30 s} — budget for non-upload calls
     *       ({@code getStatus}, {@code getResult}, webhook registration). 30 s is the SDK
     *       default and is plenty for these endpoints.</li>
     * </ul>
     *
     * <p>Note: when retries fire, <em>each attempt</em> gets the full timeout — i.e. the
     * total worst-case wall time is roughly
     * {@code maxAttempts * stageTimeout + sum(retryDelays)}. Plan your caller-side
     * deadlines accordingly.
     *
     * <h3>Retry policy: {@link ExponentialBackoffPolicy}</h3>
     * <ul>
     *   <li>{@code maxAttempts(3)} — total attempts including the initial one. So at most
     *       2 retries after the first failure. Higher numbers are rarely worth it: if a
     *       call has not succeeded within 3 attempts it is usually a real outage, not a
     *       transient blip.</li>
     *   <li>{@code initialDelay(500 ms)} — wait before the first retry. Short enough that
     *       a flapping connection recovers without the user noticing.</li>
     *   <li>{@code maxDelay(5 s)} — cap for the exponential growth so we do not stretch a
     *       single API call into tens of seconds.</li>
     *   <li>{@code jitter(0.20)} — ±20% random jitter on each delay. Prevents the
     *       "thundering herd" effect where every client retries on the exact same
     *       millisecond after a transient outage and re-overloads the server.</li>
     * </ul>
     *
     * <p><strong>Idempotency safety</strong>: retries are safe by default because every
     * upload made by the SDK carries a {@code Idempotency-Key} (UUID v4) that is preserved
     * across retries — the server deduplicates so you cannot accidentally upload the same
     * document twice. If a retry collides with an in-flight original the SDK throws
     * {@link IdempotencyConflictException} (HTTP 409) instead of duplicating work — see
     * {@link #handleDataStudioError(DataStudioException)} for how this example reacts.
     *
     * <h3>Caveat: {@code SdkConfig.builder()} vs {@code SdkConfig.defaults()}</h3>
     * <p>{@code SdkConfig.defaults()} returns a config with the
     * {@link ExponentialBackoffPolicy} above already applied. {@code SdkConfig.builder()},
     * however, defaults the retry policy to {@link NoRetryPolicy} when {@code .retryPolicy(...)}
     * is not called — so a builder with no retry call gives you <em>no retries</em>, not
     * the documented exponential default. We therefore set the policy explicitly.
     *
     * <h3>Opting out of retries</h3>
     * <p>If you need the pre-0.2.0 behaviour (no automatic retries), replace the policy with:
     * <pre>{@code
     * .retryPolicy(new NoRetryPolicy())
     * }</pre>
     * Useful for write paths where you have stronger external idempotency guarantees and
     * want errors to surface immediately for caller-side handling.
     */
    private static SdkConfig buildSdkConfig() {
        ExponentialBackoffPolicy retryPolicy = new ExponentialBackoffPolicy.Builder()
                .maxAttempts(3)
                .initialDelay(Duration.ofMillis(500))
                .maxDelay(Duration.ofSeconds(5))
                .jitter(0.20)
                .build();

        return SdkConfig.builder()
                .connectTimeout(Duration.ofSeconds(10))
                .uploadTimeout(Duration.ofSeconds(120))
                .queryTimeout(Duration.ofSeconds(30))
                .retryPolicy(retryPolicy)
                .build();
    }

    /**
     * Start the embedded webhook server with handlers that complete pending results.
     */
    private static WebhookServer startWebhookServer(DataStudioConfig config) throws IOException {
        webhookHandler = new WebhookHandler();

        // Register handlers that complete pending results
        webhookHandler.onReadyForReview(payload -> {
            logger.info("Webhook received: document.ready_for_review");
            System.out.println("\n========== WEBHOOK: ready_for_review ==========");
            System.out.println(payload.toString(2));
            System.out.println("================================================\n");
            webhookHandler.completeAnyPendingResult(payload);
        });

        webhookHandler.onCompleted(payload -> {
            logger.info("Webhook received: document.completed");
            System.out.println("\n========== WEBHOOK: completed ==========");
            System.out.println(payload.toString(2));
            System.out.println("=========================================\n");
            webhookHandler.completeAnyPendingResult(payload);
        });

        webhookHandler.onProcessingFailed(payload -> {
            String error = payload.optString("error", "(no error message)");
            logger.warn("Webhook received: document.processing_failed - {}", error);
            System.out.println("\n========== WEBHOOK: processing_failed ==========");
            System.out.println(payload.toString(2));
            System.out.println("=================================================\n");
            webhookHandler.completeAnyPendingResult(payload);
        });

        WebhookServer server = new WebhookServer(config.webhookPort(), webhookHandler);
        server.start();

        return server;
    }

    /**
     * Upload a document for processing.
     */
    private static void uploadDocument(DocumentProcessorService processor,
                                       String filePath,
                                       DataStudioConfig config) {
        logger.info("Uploading file: {}", filePath);

        Path path = Paths.get(filePath);
        Map<String, String> metadata = Map.of(
            "source", "maven-example",
            "processed_by", config.userName()
        );

        try {
            UploadResult uploadResult = processor.uploadDocument(
                config.userName(),
                DocType.EXPORT_DECLARATION,
                path,
                metadata,
                CountryCode.ES
            );
            logger.info("Document uploaded successfully");
            System.out.println("\nDocument uploaded. Webhook events will be printed as they arrive.\n");

        } catch (DataStudioException e) {
            handleDataStudioError(e);
            System.exit(1);
        }
    }

    /**
     * Handle DataStudio SDK specific errors with appropriate messages.
     *
     * <p>Since 0.2.0-alpha every {@link DataStudioException} carries a
     * {@code requestId} (server-side correlation ID) and an {@code errorCode}
     * (machine-readable, e.g. {@code "validation_failed"}, {@code "wait_timeout"},
     * {@code "idempotency_in_progress"}). Both are surfaced here to make support
     * tickets actionable.
     */
    private static void handleDataStudioError(DataStudioException e) {
        if (e instanceof FileValidationException) {
            logger.error("File validation failed: {}", e.getMessage());
            System.err.println("\nFile Error: " + e.getMessage());
            System.err.println("Please ensure the file:");
            System.err.println("  - Exists at the specified path");
            System.err.println("  - Is a valid PDF file");
            System.err.println("  - Is less than 10 MB in size");

        } else if (e instanceof AuthenticationException) {
            logger.error("Authentication failed: {}", e.getMessage());
            System.err.println("\nAuthentication Error: " + e.getMessage());
            System.err.println("Please verify your API key is correct.");
            System.err.println("Set it via: export DATASTUDIO_API_KEY=your_api_key");

        } else if (e instanceof DocumentNotFoundException) {
            logger.error("Document not found: {}", e.getMessage());
            System.err.println("\nDocument Not Found: " + e.getMessage());

        } else if (e instanceof IdempotencyConflictException) {
            // Added in 0.2.0-alpha: another request with the same Idempotency-Key
            // is still being processed. The conflict is rare; usually it means a
            // retry collided with the in-flight original. Back off and let it settle.
            logger.warn("Idempotency conflict (requestId={}, errorCode={}): {}",
                    e.getRequestId(), e.getErrorCode(), e.getMessage());
            System.err.println("\nIdempotency Conflict: another upload with the same key is still in progress.");
            System.err.println("Wait for it to complete, then retry with a fresh key.");

        } else if (e instanceof NetworkException) {
            logger.error("Network error: {}", e.getMessage());
            System.err.println("\nNetwork Error: " + e.getMessage());
            System.err.println("Please check your internet connection and try again.");

        } else if (e instanceof ServerException) {
            logger.error("Server error ({}): {}", e.getStatusCode(), e.getMessage());
            System.err.println("\nServer Error: " + e.getMessage());
            System.err.println("The server encountered an error. Please try again later.");

        } else {
            logger.error("SDK error ({}): {}", e.getStatusCode(), e.getMessage());
            System.err.println("\nError: " + e.getMessage());
            if (e.getStatusCode() > 0) {
                System.err.println("Status Code: " + e.getStatusCode());
            }
        }

        // Diagnostic fields added in 0.2.0-alpha — quote requestId on support tickets.
        if (e.getRequestId() != null) {
            System.err.println("Request ID: " + e.getRequestId());
        }
        if (e.getErrorCode() != null) {
            System.err.println("Error Code: " + e.getErrorCode());
        }
    }

    /**
     * Print usage instructions.
     */
    private static void printUsage() {
        System.err.println("\nUsage:");
        System.err.println("  1. Set required environment variables:");
        System.err.println("     export DATASTUDIO_API_KEY=your_api_key");
        System.err.println("     export DATASTUDIO_WEBHOOK_URL=https://your-ngrok-url.ngrok-free.app/webhooks");
        System.err.println("");
        System.err.println("  2. Optional environment variables:");
        System.err.println("     export DATASTUDIO_ENVIRONMENT=SAND_BOX  # or PROD");
        System.err.println("     export DATASTUDIO_USER=your_username    # default: example-user");
        System.err.println("     export DATASTUDIO_WEBHOOK_PORT=8080     # default: 8080");
        System.err.println("");
        System.err.println("  3. Run the application:");
        System.err.println("     mvn exec:java -Dexec.args=\"/path/to/document.pdf\"");
    }
}
