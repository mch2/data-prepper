/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.amazon.dataprepper.plugins.sink.opensearch;

import com.amazon.dataprepper.model.annotations.DataPrepperPlugin;
import com.amazon.dataprepper.model.configuration.PluginSetting;
import com.amazon.dataprepper.model.event.Event;
import com.amazon.dataprepper.model.record.Record;
import com.amazon.dataprepper.model.sink.AbstractSink;
import com.amazon.dataprepper.model.sink.Sink;
import com.amazon.dataprepper.plugins.sink.opensearch.index.IndexManager;
import com.amazon.dataprepper.plugins.sink.opensearch.index.IndexManagerFactory;
import com.amazon.dataprepper.plugins.sink.opensearch.index.IndexType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Timer;
import org.opensearch.action.DocWriteRequest;
import org.opensearch.action.bulk.BulkRequest;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.RestHighLevelClient;
import org.opensearch.common.unit.ByteSizeUnit;
import org.opensearch.common.xcontent.LoggingDeprecationHandler;
import org.opensearch.common.xcontent.NamedXContentRegistry;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.common.xcontent.XContentParser;
import org.opensearch.common.xcontent.XContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

@DataPrepperPlugin(name = "opensearch", pluginType = Sink.class)
public class OpenSearchSink extends AbstractSink<Record<Object>> {
  public static final String BULKREQUEST_LATENCY = "bulkRequestLatency";
  public static final String BULKREQUEST_ERRORS = "bulkRequestErrors";
  public static final String BULKREQUEST_SIZE_BYTES = "bulkRequestSizeBytes";

  private static final Logger LOG = LoggerFactory.getLogger(OpenSearchSink.class);
  // Pulled from BulkRequest to make estimation of bytes consistent
  private static final int REQUEST_OVERHEAD = 50;

  private BufferedWriter dlqWriter;
  private final OpenSearchSinkConfiguration openSearchSinkConfig;
  private final IndexManagerFactory indexManagerFactory;
  private RestHighLevelClient restHighLevelClient;
  private IndexManager indexManager;
  private Supplier<BulkRequest> bulkRequestSupplier;
  private BulkRetryStrategy bulkRetryStrategy;
  private final long bulkSize;
  private final IndexType indexType;
  private final String documentIdField;

  private final Timer bulkRequestTimer;
  private final Counter bulkRequestErrorsCounter;
  private final DistributionSummary bulkRequestSizeBytesSummary;

  public OpenSearchSink(final PluginSetting pluginSetting) {
    super(pluginSetting);
    bulkRequestTimer = pluginMetrics.timer(BULKREQUEST_LATENCY);
    bulkRequestErrorsCounter = pluginMetrics.counter(BULKREQUEST_ERRORS);
    bulkRequestSizeBytesSummary = pluginMetrics.summary(BULKREQUEST_SIZE_BYTES);

    this.openSearchSinkConfig = OpenSearchSinkConfiguration.readESConfig(pluginSetting);
    this.bulkSize = ByteSizeUnit.MB.toBytes(openSearchSinkConfig.getIndexConfiguration().getBulkSize());
    this.indexType = openSearchSinkConfig.getIndexConfiguration().getIndexType();
    this.documentIdField = openSearchSinkConfig.getIndexConfiguration().getDocumentIdField();
    this.indexManagerFactory = new IndexManagerFactory();

    try {
      initialize();
    } catch (final IOException e) {
      this.shutdown();
      throw new RuntimeException(e.getMessage(), e);
    }
  }

  public void initialize() throws IOException {
    LOG.info("Initializing OpenSearch sink");
    restHighLevelClient = openSearchSinkConfig.getConnectionConfiguration().createClient();
    indexManager = indexManagerFactory.getIndexManager(indexType, restHighLevelClient, openSearchSinkConfig);
    final boolean isISMEnabled = indexManager.checkISMEnabled();
    final Optional<String> policyIdOptional = isISMEnabled ? indexManager.checkAndCreatePolicy() :
            Optional.empty();
    if (!openSearchSinkConfig.getIndexConfiguration().getIndexTemplate().isEmpty()) {
      indexManager.checkAndCreateIndexTemplate(isISMEnabled, policyIdOptional.orElse(null));
    }
    final String dlqFile = openSearchSinkConfig.getRetryConfiguration().getDlqFile();
    if (dlqFile != null) {
      dlqWriter = Files.newBufferedWriter(Paths.get(dlqFile), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    indexManager.checkAndCreateIndex();
    bulkRequestSupplier = () -> new BulkRequest(indexManager.getIndexAlias());
    bulkRetryStrategy = new BulkRetryStrategy(
            bulkRequest -> restHighLevelClient.bulk(bulkRequest, RequestOptions.DEFAULT),
            this::logFailure,
            pluginMetrics,
            bulkRequestSupplier);
    LOG.info("Initialized OpenSearch sink");
  }

  @Override
  public void doOutput(final Collection<Record<Object>> records) {
    if (records.isEmpty()) {
      return;
    }
    BulkRequest bulkRequest = bulkRequestSupplier.get();
    for (final Record<Object> record : records) {
      final String document = getDocument(record.getData());
      final IndexRequest indexRequest = new IndexRequest().source(document, XContentType.JSON);
      try {
        final Map<String, Object> source = getMapFromJson(document);
        final String docId = (String) source.get(documentIdField);
        if (docId != null) {
          indexRequest.id(docId);
        }
        final long estimatedBytesBeforeAdd = bulkRequest.estimatedSizeInBytes() + calcEstimatedSizeInBytes(indexRequest);
        if (bulkSize >= 0 && estimatedBytesBeforeAdd >= bulkSize && bulkRequest.numberOfActions() > 0) {
          flushBatch(bulkRequest);
          bulkRequest = bulkRequestSupplier.get();
        }
        bulkRequest.add(indexRequest);
      } catch (final IOException e) {
        throw new RuntimeException(e.getMessage(), e);
      }
    }

    // Flush the remaining requests
    if (bulkRequest.numberOfActions() > 0) {
      flushBatch(bulkRequest);
    }
  }


  // Temporary function to support both trace and log ingestion pipelines.
  // TODO: This function should be removed with the completion of: https://github.com/opensearch-project/data-prepper/issues/546
  private String getDocument(final Object object) {
    if (object instanceof String) {
      return (String) object;
    } else if (object instanceof Event) {
      return ((Event) object).toJsonString();
    } else {
      throw new RuntimeException("Invalid record type. OpenSearch sink only supports String and Events");
    }
  }

  private long calcEstimatedSizeInBytes(final IndexRequest indexRequest) {
    // From BulkRequest#internalAdd(IndexRequest request)
    return (indexRequest.source() != null ? indexRequest.source().length() : 0) + REQUEST_OVERHEAD;
  }

  private void flushBatch(final BulkRequest bulkRequest) {
    bulkRequestTimer.record(() -> {
      try {
        bulkRetryStrategy.execute(bulkRequest);
        bulkRequestSizeBytesSummary.record(bulkRequest.estimatedSizeInBytes());
      } catch (final InterruptedException e) {
        LOG.error("Unexpected Interrupt:", e);
        bulkRequestErrorsCounter.increment();
        Thread.currentThread().interrupt();
      }
    });
  }

  private Map<String, Object> getMapFromJson(final String documentJson) throws IOException {
    final XContentParser parser = XContentFactory.xContent(XContentType.JSON)
            .createParser(NamedXContentRegistry.EMPTY, LoggingDeprecationHandler.INSTANCE, documentJson);
    return parser.map();
  }

  private void logFailure(final DocWriteRequest<?> docWriteRequest, final Throwable failure) {
    if (dlqWriter != null) {
      try {
        dlqWriter.write(String.format("{\"Document\": [%s], \"failure\": %s}\n",
                docWriteRequest.toString(), failure.getMessage()));
      } catch (final IOException e) {
        LOG.error("DLQ failed for Document [{}]", docWriteRequest.toString());
      }
    } else {
      LOG.warn("Document [{}] has failure: {}", docWriteRequest.toString(), failure);
    }
  }

  @Override
  public void shutdown() {
    // Close the client
    if (restHighLevelClient != null) {
      try {
        restHighLevelClient.close();
      } catch (final IOException e) {
        throw new RuntimeException(e.getMessage(), e);
      }
    }
    if (dlqWriter != null) {
      try {
        dlqWriter.close();
      } catch (final IOException e) {
        LOG.error(e.getMessage(), e);
      }
    }
  }
}
