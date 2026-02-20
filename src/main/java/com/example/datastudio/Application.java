package com.example.datastudio;

import ai.accelerator.CountryCode;
import ai.accelerator.DataStudioSDK;
import ai.accelerator.DataStudioSDK.*;
import ai.accelerator.exceptions.*;
import com.example.datastudio.config.DataStudioConfig;
import com.example.datastudio.service.DocumentProcessorService;
import com.example.datastudio.webhook.WebhookHandler;
import com.example.datastudio.webhook.WebhookServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
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
     */
    private static DataStudioSDK initializeSDK(DataStudioConfig config) {
        logger.info("Initializing DataStudio SDK for environment: {}", config.environment());
        logger.info("Configuring webhooks for URL: {}", config.webhookUrl());

        return DataStudioSDK.Builder.aDataStudioSDK()
                .withApiKey(config.apiKey())
                .withEnvironment(config.environment())
                .withWebhooks(
                    new WebHook(Events.READY_FOR_REVIEW, config.webhookUrl() + "/ready"),
                    new WebHook(Events.COMPLETED, config.webhookUrl() + "/completed")
                )
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
