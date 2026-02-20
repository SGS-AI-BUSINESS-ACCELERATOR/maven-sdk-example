package com.example.datastudio.service;

import ai.accelerator.CountryCode;
import ai.accelerator.DataStudioSDK;
import ai.accelerator.DataStudioSDK.*;
import ai.accelerator.exceptions.*;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Service class for processing documents using the DataStudio SDK.
 *
 * <p>This service provides:
 * <ul>
 *   <li>Synchronous document upload and processing</li>
 *   <li>Asynchronous document processing with callbacks</li>
 *   <li>Configurable polling with exponential backoff</li>
 *   <li>Comprehensive status tracking</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * DataStudioSDK sdk = DataStudioSDK.Builder.aDataStudioSDK()
 *     .withApiKey("your-api-key")
 *     .withEnvironment(Environments.PROD)
 *     .build();
 *
 * DocumentProcessorService processor = new DocumentProcessorService(sdk);
 *
 * // Synchronous processing
 * JSONObject result = processor.uploadAndWaitForResult(
 *     "user@example.com",
 *     DocType.INVOICE,
 *     Path.of("/path/to/invoice.pdf"),
 *     Map.of("orderId", "ORD-123")
 * );
 *
 * // Asynchronous processing
 * processor.uploadAndProcessAsync(
 *     "user@example.com",
 *     DocType.EXPORT_DECLARATION,
 *     Path.of("/path/to/declaration.pdf"),
 *     null,
 *     result -> System.out.println("Processed: " + result),
 *     error -> System.err.println("Failed: " + error.getMessage())
 * );
 * }</pre>
 */
public class DocumentProcessorService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentProcessorService.class);

    private static final int DEFAULT_MAX_ATTEMPTS = 30;
    private static final long DEFAULT_INITIAL_DELAY_MS = 2000;
    private static final long DEFAULT_MAX_DELAY_MS = 30000;
    private static final double BACKOFF_MULTIPLIER = 2.0;

    private final DataStudioSDK sdk;
    private final ExecutorService executorService;
    private final int maxAttempts;
    private final long initialDelayMs;
    private final long maxDelayMs;

    /**
     * Create a new DocumentProcessorService with default settings.
     *
     * @param sdk The initialized DataStudio SDK instance
     */
    public DocumentProcessorService(DataStudioSDK sdk) {
        this(sdk, DEFAULT_MAX_ATTEMPTS, DEFAULT_INITIAL_DELAY_MS, DEFAULT_MAX_DELAY_MS);
    }

    /**
     * Create a new DocumentProcessorService with custom polling settings.
     *
     * @param sdk            The initialized DataStudio SDK instance
     * @param maxAttempts    Maximum polling attempts before timeout
     * @param initialDelayMs Initial delay between polls in milliseconds
     * @param maxDelayMs     Maximum delay between polls in milliseconds
     */
    public DocumentProcessorService(DataStudioSDK sdk,
                                    int maxAttempts,
                                    long initialDelayMs,
                                    long maxDelayMs) {
        this.sdk = sdk;
        this.maxAttempts = maxAttempts;
        this.initialDelayMs = initialDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.executorService = Executors.newCachedThreadPool();
    }

    /**
     * Upload a document and wait for the result synchronously.
     *
     * <p>This method will block until the document is processed or an error occurs.
     * Uses exponential backoff for polling the status.
     *
     * @param userName Username for tracking
     * @param docType  Type of document (INVOICE or EXPORT_DECLARATION)
     * @param filePath Path to the PDF file
     * @param metadata Optional metadata key-value pairs
     * @return The processing result as a JSONObject
     * @throws FileValidationException   If the file doesn't exist or exceeds size limits
     * @throws AuthenticationException   If authentication fails
     * @throws NetworkException          If network errors occur
     * @throws ServerException           If server errors occur
     * @throws DataStudioException       For other SDK errors
     * @throws InterruptedException      If the thread is interrupted while waiting
     */
    public JSONObject uploadAndWaitForResult(String userName,
                                             DocType docType,
                                             Path filePath,
                                             Map<String, String> metadata)
            throws DataStudioException, InterruptedException {

        logger.info("Uploading document: {} as {}", filePath.getFileName(), docType);

        // Upload the document
        UploadResult uploadResult = sdk.uploadDocument(userName, docType, filePath, metadata, CountryCode.ES);
        String processId = uploadResult.processId();

        logger.info("Document uploaded with processId: {}, initial status: {}",
                processId, uploadResult.status());

        // If upload failed immediately, throw an exception
        if (uploadResult.status() == UploadResultStatus.FAILED) {
            throw new DataStudioException("Document upload failed immediately");
        }

        // Poll for completion
        return pollForResult(processId);
    }

    /**
     * Upload a document and process it asynchronously.
     *
     * <p>This method returns immediately and executes the processing in a background thread.
     * Results are delivered via callbacks.
     *
     * @param userName      Username for tracking
     * @param docType       Type of document
     * @param filePath      Path to the PDF file
     * @param metadata      Optional metadata
     * @param onSuccess     Callback for successful processing
     * @param onError       Callback for errors
     * @return CompletableFuture that completes when processing is done
     */
    public CompletableFuture<JSONObject> uploadAndProcessAsync(
            String userName,
            DocType docType,
            Path filePath,
            Map<String, String> metadata,
            Consumer<JSONObject> onSuccess,
            Consumer<Throwable> onError) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                JSONObject result = uploadAndWaitForResult(userName, docType, filePath, metadata);
                if (onSuccess != null) {
                    onSuccess.accept(result);
                }
                return result;
            } catch (Exception e) {
                if (onError != null) {
                    onError.accept(e);
                }
                throw new RuntimeException(e);
            }
        }, executorService);
    }

    /**
     * Upload a document without waiting for results.
     *
     * <p>Use this when you have webhooks configured or want to poll manually.
     *
     * @param userName Username for tracking
     * @param docType  Type of document
     * @param filePath Path to the PDF file
     * @param metadata Optional metadata
     * @return UploadResult containing the processId and initial status
     * @throws DataStudioException If upload fails
     */
    public UploadResult uploadDocument(String userName,
                                       DocType docType,
                                       Path filePath,
                                       Map<String, String> metadata)
            throws DataStudioException {
        logger.info("Uploading document: {}", filePath.getFileName());
        return sdk.uploadDocument(userName, docType, filePath, metadata);
    }

    /**
     * Upload a document without waiting for results, specifying a country code.
     *
     * @param userName    Username for tracking
     * @param docType     Type of document
     * @param filePath    Path to the PDF file
     * @param metadata    Optional metadata
     * @param countryCode Country code for the document
     * @return UploadResult containing the processId and initial status
     * @throws DataStudioException If upload fails
     */
    public UploadResult uploadDocument(String userName,
                                       DocType docType,
                                       Path filePath,
                                       Map<String, String> metadata,
                                       CountryCode countryCode)
            throws DataStudioException {
        logger.info("Uploading document: {} with country: {}", filePath.getFileName(), countryCode);
        return sdk.uploadDocument(userName, docType, filePath, metadata, countryCode);
    }

    /**
     * Check the current status of a document.
     *
     * @param processId The process ID from uploadDocument
     * @return Current status of the document
     * @throws DocumentNotFoundException If the process ID doesn't exist
     * @throws DataStudioException       For other errors
     */
    public UploadResult checkStatus(String processId) throws DataStudioException {
        logger.debug("Checking status for processId: {}", processId);
        return sdk.getStatus(processId);
    }

    /**
     * Get the result of a processed document.
     *
     * @param processId The process ID
     * @return The processing result
     * @throws DocumentNotFoundException If the document doesn't exist
     * @throws DataStudioException       If the document is still processing or other errors
     */
    public JSONObject getResult(String processId) throws DataStudioException {
        logger.debug("Getting result for processId: {}", processId);
        return sdk.getResult(processId);
    }

    /**
     * Poll for document result with exponential backoff.
     */
    private JSONObject pollForResult(String processId)
            throws DataStudioException, InterruptedException {

        long currentDelay = initialDelayMs;
        int attempt = 0;

        while (attempt < maxAttempts) {
            attempt++;

            // Wait before checking status
            Thread.sleep(currentDelay);

            // Check status
            UploadResult status = sdk.getStatus(processId);
            logger.debug("Poll attempt {}/{}: status = {}", attempt, maxAttempts, status.status());

            switch (status.status()) {
                case UPLOADED:
                    // Document is ready, get the result
                    logger.info("Document processing completed, retrieving result");
                    return sdk.getResult(processId);

                case IN_PROGRESS:
                    // Still processing, continue polling
                    logger.info("Document still processing (attempt {}/{})", attempt, maxAttempts);
                    break;

                case FAILED:
                    throw new DataStudioException("Document processing failed");
            }

            // Exponential backoff
            currentDelay = Math.min((long) (currentDelay * BACKOFF_MULTIPLIER), maxDelayMs);
        }

        throw new DataStudioException("Timeout waiting for document processing after " +
                maxAttempts + " attempts");
    }

    /**
     * Shutdown the executor service.
     * Call this when you're done using the service.
     */
    public void shutdown() {
        executorService.shutdown();
    }
}
