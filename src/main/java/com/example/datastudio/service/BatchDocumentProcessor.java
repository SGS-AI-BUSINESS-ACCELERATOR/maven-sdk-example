package com.example.datastudio.service;

import ai.accelerator.CountryCode;
import ai.accelerator.DataStudioSDK;
import ai.accelerator.DataStudioSDK.*;
import ai.accelerator.exceptions.DataStudioException;
import ai.accelerator.wait.WaitStrategy;
import com.example.datastudio.model.ProcessedDocument;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Service for batch processing multiple documents.
 *
 * <p>This class demonstrates how to efficiently process multiple documents
 * in parallel using the DataStudio SDK. It manages concurrent uploads
 * and result polling, providing callbacks for progress tracking.
 *
 * <p>Example usage:
 * <pre>{@code
 * BatchDocumentProcessor batchProcessor = new BatchDocumentProcessor(sdk);
 *
 * List<Path> documents = List.of(
 *     Path.of("invoice1.pdf"),
 *     Path.of("invoice2.pdf"),
 *     Path.of("invoice3.pdf")
 * );
 *
 * BatchDocumentProcessor.BatchResult result = batchProcessor.processDocuments(
 *     "user@example.com",
 *     DocType.INVOICE,
 *     documents,
 *     null,  // no metadata
 *     progress -> System.out.println("Progress: " + progress.completedCount() + "/" + progress.totalCount())
 * );
 *
 * System.out.println("Successful: " + result.successful().size());
 * System.out.println("Failed: " + result.failed().size());
 * }</pre>
 */
public class BatchDocumentProcessor {

    private static final Logger logger = LoggerFactory.getLogger(BatchDocumentProcessor.class);

    /** Default wait strategy applied to each document in the batch. */
    private static final WaitStrategy DEFAULT_WAIT_STRATEGY =
            new WaitStrategy(Duration.ofSeconds(2), Duration.ofMinutes(5));

    private final DataStudioSDK sdk;
    private final ExecutorService uploadExecutor;
    private final ScheduledExecutorService pollingExecutor;
    private final int maxConcurrentUploads;
    private final WaitStrategy waitStrategy;

    /**
     * Create a new BatchDocumentProcessor with default settings.
     *
     * @param sdk The initialized DataStudio SDK instance
     */
    public BatchDocumentProcessor(DataStudioSDK sdk) {
        this(sdk, 5); // Default to 5 concurrent uploads
    }

    /**
     * Create a new BatchDocumentProcessor with custom concurrency.
     *
     * @param sdk                  The initialized DataStudio SDK instance
     * @param maxConcurrentUploads Maximum number of concurrent uploads
     */
    public BatchDocumentProcessor(DataStudioSDK sdk, int maxConcurrentUploads) {
        this(sdk, maxConcurrentUploads, DEFAULT_WAIT_STRATEGY);
    }

    /**
     * Create a new BatchDocumentProcessor with a custom {@link WaitStrategy}.
     *
     * @param sdk                  The initialized DataStudio SDK instance.
     * @param maxConcurrentUploads Maximum number of concurrent uploads.
     * @param waitStrategy         Wait strategy passed to {@code sdk.waitForCompletion(...)}
     *                             for every document in the batch.
     */
    public BatchDocumentProcessor(DataStudioSDK sdk, int maxConcurrentUploads, WaitStrategy waitStrategy) {
        this.sdk = sdk;
        this.maxConcurrentUploads = maxConcurrentUploads;
        this.waitStrategy = waitStrategy;
        this.uploadExecutor = Executors.newFixedThreadPool(maxConcurrentUploads);
        this.pollingExecutor = Executors.newScheduledThreadPool(2);
    }

    /**
     * Process multiple documents in parallel.
     *
     * @param userName          Username for tracking
     * @param docType           Type of documents being processed
     * @param documentPaths     List of paths to PDF files
     * @param metadata          Optional metadata to attach to all documents
     * @param progressCallback  Optional callback for progress updates
     * @return BatchResult containing successful and failed results
     */
    public BatchResult processDocuments(
            String userName,
            DocType docType,
            List<Path> documentPaths,
            Map<String, String> metadata,
            ProgressCallback progressCallback) {

        logger.info("Starting batch processing of {} documents", documentPaths.size());

        int totalCount = documentPaths.size();
        Map<Path, CompletableFuture<ProcessedDocument>> futures = new ConcurrentHashMap<>();
        List<ProcessedDocument> successful = Collections.synchronizedList(new ArrayList<>());
        Map<Path, Throwable> failed = new ConcurrentHashMap<>();

        // Upload all documents concurrently
        for (Path path : documentPaths) {
            CompletableFuture<ProcessedDocument> future = CompletableFuture
                    .supplyAsync(() -> uploadAndProcess(userName, docType, path, metadata), uploadExecutor)
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            failed.put(path, error);
                            logger.error("Failed to process {}: {}", path, error.getMessage());
                        } else {
                            successful.add(result);
                            logger.info("Successfully processed {}", path);
                        }

                        // Report progress
                        if (progressCallback != null) {
                            progressCallback.onProgress(new Progress(
                                    totalCount,
                                    successful.size(),
                                    failed.size()
                            ));
                        }
                    });

            futures.put(path, future);
        }

        // Wait for all to complete
        CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]))
                .exceptionally(ex -> null) // Don't fail if some documents fail
                .join();

        logger.info("Batch processing completed. Successful: {}, Failed: {}",
                successful.size(), failed.size());

        return new BatchResult(
                List.copyOf(successful),
                Map.copyOf(failed)
        );
    }

    /**
     * Upload and process a single document, waiting for the result.
     *
     * <p>Uses {@code sdk.waitForCompletion(...)} (added in 0.2.0-alpha) instead of a hand-rolled
     * polling loop. The wait returns when the document reaches a terminal state
     * ({@code completed}, {@code ready_for_review}, {@code failed}, {@code review_expired})
     * or the {@link WaitStrategy} budget is exceeded — in which case the SDK throws a
     * {@link DataStudioException} with {@code errorCode = "wait_timeout"}.
     */
    private ProcessedDocument uploadAndProcess(String userName,
                                               DocType docType,
                                               Path path,
                                               Map<String, String> metadata) {
        try {
            UploadResult uploadResult = sdk.uploadDocument(userName, docType, path, metadata, CountryCode.ES);
            String processId = uploadResult.processId();

            JSONObject statusPayload = sdk.waitForCompletion(processId, waitStrategy);
            String terminalState = statusPayload.optString("status");

            if ("failed".equals(terminalState) || "review_expired".equals(terminalState)) {
                String error = statusPayload.optString("error", "did not succeed");
                throw new DataStudioException(
                        "Document " + path + " reached terminal state '" + terminalState + "': " + error);
            }

            JSONObject result = sdk.getResult(processId);
            return ProcessedDocument.fromJson(result);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Processing interrupted", e);
        }
    }

    /**
     * Shutdown the executor services.
     * Call this when you're done using the processor.
     */
    public void shutdown() {
        uploadExecutor.shutdown();
        pollingExecutor.shutdown();
    }

    /**
     * Result of batch processing.
     *
     * @param successful List of successfully processed documents
     * @param failed     Map of failed paths to their errors
     */
    public record BatchResult(
            List<ProcessedDocument> successful,
            Map<Path, Throwable> failed
    ) {
        /**
         * Get the total number of documents that were processed.
         */
        public int totalCount() {
            return successful.size() + failed.size();
        }

        /**
         * Get the success rate as a percentage.
         */
        public double successRate() {
            if (totalCount() == 0) return 0.0;
            return (double) successful.size() / totalCount() * 100;
        }

        /**
         * Check if all documents were processed successfully.
         */
        public boolean allSuccessful() {
            return failed.isEmpty();
        }

        /**
         * Get documents that meet a confidence threshold.
         */
        public List<ProcessedDocument> getHighConfidenceResults(double threshold) {
            return successful.stream()
                    .filter(doc -> doc.meetsConfidenceThreshold(threshold))
                    .collect(Collectors.toList());
        }
    }

    /**
     * Progress information during batch processing.
     *
     * @param totalCount     Total number of documents being processed
     * @param completedCount Number of successfully completed documents
     * @param failedCount    Number of failed documents
     */
    public record Progress(
            int totalCount,
            int completedCount,
            int failedCount
    ) {
        /**
         * Get the number of documents still being processed.
         */
        public int pendingCount() {
            return totalCount - completedCount - failedCount;
        }

        /**
         * Get the completion percentage.
         */
        public double completionPercentage() {
            if (totalCount == 0) return 100.0;
            return (double) (completedCount + failedCount) / totalCount * 100;
        }

        /**
         * Check if batch processing is complete.
         */
        public boolean isComplete() {
            return pendingCount() == 0;
        }
    }

    /**
     * Callback interface for progress updates.
     */
    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(Progress progress);
    }
}
