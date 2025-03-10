/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.amazon.dataprepper.plugins.prepper.peerforwarder;

import com.amazon.dataprepper.model.annotations.DataPrepperPlugin;
import com.amazon.dataprepper.model.configuration.PluginSetting;
import com.amazon.dataprepper.model.processor.AbstractProcessor;
import com.amazon.dataprepper.model.processor.Processor;
import com.amazon.dataprepper.model.record.Record;
import com.amazon.dataprepper.model.trace.Span;
import com.amazon.dataprepper.plugins.otel.codec.OTelProtoCodec;
import com.amazon.dataprepper.plugins.prepper.peerforwarder.discovery.StaticPeerListProvider;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.TraceServiceGrpc;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import org.apache.commons.codec.DecoderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@DataPrepperPlugin(name = "peer_forwarder", pluginType = Processor.class)
public class PeerForwarder extends AbstractProcessor<Record<Object>, Record<Object>> {
    public static final String REQUESTS = "requests";
    public static final String LATENCY = "latency";
    public static final String ERRORS = "errors";
    public static final String DESTINATION = "destination";

    private static final TraceServiceGrpc.TraceServiceBlockingStub LOCAL_CLIENT = null;

    public static final int ASYNC_REQUEST_THREAD_COUNT = 200;

    private static final Logger LOG = LoggerFactory.getLogger(PeerForwarder.class);

    private final OTelProtoCodec.OTelProtoEncoder oTelProtoEncoder;
    private final HashRing hashRing;
    private final PeerClientPool peerClientPool;
    private final int maxNumSpansPerRequest;

    private final Map<String, Timer> forwardRequestTimers;
    private final Map<String, Counter> forwardedRequestCounters;
    private final Map<String, Counter> forwardRequestErrorCounters;

    private final ExecutorService executorService;

    public PeerForwarder(final PluginSetting pluginSetting,
                         final OTelProtoCodec.OTelProtoEncoder oTelProtoEncoder,
                         final PeerClientPool peerClientPool,
                         final HashRing hashRing,
                         final int maxNumSpansPerRequest) {
        super(pluginSetting);
        this.oTelProtoEncoder = oTelProtoEncoder;
        this.peerClientPool = peerClientPool;
        this.hashRing = hashRing;
        this.maxNumSpansPerRequest = maxNumSpansPerRequest;
        forwardedRequestCounters = new ConcurrentHashMap<>();
        forwardRequestErrorCounters = new ConcurrentHashMap<>();
        forwardRequestTimers = new ConcurrentHashMap<>();

        executorService = Executors.newFixedThreadPool(ASYNC_REQUEST_THREAD_COUNT);
    }

    public PeerForwarder(final PluginSetting pluginSetting) {
        this(pluginSetting, PeerForwarderConfig.buildConfig(pluginSetting));
    }

    public PeerForwarder(final PluginSetting pluginSetting, final PeerForwarderConfig peerForwarderConfig) {
        this(
                pluginSetting,
                new OTelProtoCodec.OTelProtoEncoder(),
                peerForwarderConfig.getPeerClientPool(),
                peerForwarderConfig.getHashRing(),
                peerForwarderConfig.getMaxNumSpansPerRequest()
        );
    }

    @Override
    public List<Record<Object>> doExecute(final Collection<Record<Object>> records) {
        final List<Span> spans = new ArrayList<>();
        final List<ExportTraceServiceRequest> exportTraceServiceRequests = new ArrayList<>();
        records.forEach(record -> {
            final Object recordData = record.getData();
            // TODO: remove support for ExportTraceServiceRequest in 2.0
            if (recordData instanceof ExportTraceServiceRequest) {
                exportTraceServiceRequests.add((ExportTraceServiceRequest) recordData);
            } else if (recordData instanceof Span) {
                spans.add((Span) recordData);
            } else {
                throw new RuntimeException("Unsupported record data type: " + recordData.getClass());
            }
        });
        final List<ExportTraceServiceRequest> requestsToProcessLocally = executeExportTraceServiceRequests(exportTraceServiceRequests);
        final List<Span> spansToProcessLocally = executeSpans(spans);
        return Stream.concat(requestsToProcessLocally.stream(), spansToProcessLocally.stream()).map(Record::new).collect(Collectors.toList());
    }

    private List<ExportTraceServiceRequest> executeExportTraceServiceRequests(final List<ExportTraceServiceRequest> requests) {
        final Map<String, List<ResourceSpans>> groupedRS = new HashMap<>();

        // Group ResourceSpans by consistent hashing of traceId
        for (final ExportTraceServiceRequest request : requests) {
            for (final ResourceSpans rs : request.getResourceSpansList()) {
                final List<Map.Entry<String, ResourceSpans>> rsBatch = PeerForwarderUtils.splitByTrace(rs);
                for (final Map.Entry<String, ResourceSpans> entry : rsBatch) {
                    final String traceId = entry.getKey();
                    final ResourceSpans newRS = entry.getValue();
                    final String dataPrepperIp = hashRing.getServerIp(traceId).orElse(StaticPeerListProvider.LOCAL_ENDPOINT);
                    groupedRS.computeIfAbsent(dataPrepperIp, x -> new ArrayList<>()).add(newRS);
                }
            }
        }

        final List<ExportTraceServiceRequest> requestsToProcessLocally = new ArrayList<>();
        final List<CompletableFuture<ExportTraceServiceRequest>> forwardedRequestFutures = new ArrayList<>();

        for (final Map.Entry<String, List<ResourceSpans>> entry : groupedRS.entrySet()) {
            final TraceServiceGrpc.TraceServiceBlockingStub client = getClient(entry.getKey());

            // Create ExportTraceRequest for storing single batch of spans
            ExportTraceServiceRequest.Builder currRequestBuilder = ExportTraceServiceRequest.newBuilder();
            int currSpansCount = 0;
            for (final ResourceSpans rs : entry.getValue()) {
                final int rsSize = PeerForwarderUtils.getResourceSpansSize(rs);
                if (currSpansCount >= maxNumSpansPerRequest) {
                    final ExportTraceServiceRequest currRequest = currRequestBuilder.build();
                    if (isLocalClient(client)) {
                        requestsToProcessLocally.add(currRequest);
                    } else {
                        forwardedRequestFutures.add(processRequest(client, currRequest));
                    }
                    currRequestBuilder = ExportTraceServiceRequest.newBuilder();
                    currSpansCount = 0;
                }
                currRequestBuilder.addResourceSpans(rs);
                currSpansCount += rsSize;
            }
            // Dealing with the last batch request
            if (currSpansCount > 0) {
                final ExportTraceServiceRequest currRequest = currRequestBuilder.build();
                if (client == null) {
                    requestsToProcessLocally.add(currRequest);
                } else {
                    forwardedRequestFutures.add(processRequest(client, currRequest));
                }
            }
        }

        for (final CompletableFuture<ExportTraceServiceRequest> future : forwardedRequestFutures) {
            try {
                final ExportTraceServiceRequest request = future.get();
                if (request != null) {
                    requestsToProcessLocally.add(request);
                }
            } catch (InterruptedException | ExecutionException e) {
                LOG.error("Problem with asynchronous peer forwarding", e);
            }
        }

        return requestsToProcessLocally;
    }

    private List<Span> executeSpans(final List<Span> spans) {
        final Map<String, List<Span>> spansByTraceId = PeerForwarderUtils.splitByTrace(spans);
        // Group ResourceSpans by consistent hashing of traceId
        final Map<String, List<Span>> spansByEndPoint = new HashMap<>();
        for (final Map.Entry<String, List<Span>> entry: spansByTraceId.entrySet()) {
            final String traceId = entry.getKey();
            final String dataPrepperIp = hashRing.getServerIp(traceId).orElse(StaticPeerListProvider.LOCAL_ENDPOINT);
            spansByEndPoint.computeIfAbsent(dataPrepperIp, x -> new ArrayList<>()).addAll(entry.getValue());
        }

        final List<Span> spansToProcessLocally = new ArrayList<>();
        final Map<CompletableFuture<ExportTraceServiceRequest>, List<Span>> forwardedRequestFuturesToSpans = new HashMap<>();

        for (final Map.Entry<String, List<Span>> entry : spansByEndPoint.entrySet()) {
            final TraceServiceGrpc.TraceServiceBlockingStub client = getClient(entry.getKey());
            if (isLocalClient(client)) {
                spansToProcessLocally.addAll(entry.getValue());
                continue;
            }

            // Create ExportTraceRequest for storing single batch of spans
            ExportTraceServiceRequest.Builder currRequestBuilder = ExportTraceServiceRequest.newBuilder();
            List<Span> currBatchSpans = new ArrayList<>();
            for (final Span span : entry.getValue()) {
                try {
                    final ResourceSpans rs = oTelProtoEncoder.convertToResourceSpans(span);
                    currRequestBuilder.addResourceSpans(rs);
                    currBatchSpans.add(span);
                } catch (UnsupportedEncodingException | DecoderException e) {
                    LOG.error("failed to encode span with spanId: {} into opentelemetry-protobuf, span will be processed locally.",
                            span.getSpanId(), e);
                    spansToProcessLocally.add(span);
                }
                if (currBatchSpans.size() >= maxNumSpansPerRequest) {
                    final ExportTraceServiceRequest currRequest = currRequestBuilder.build();
                    forwardedRequestFuturesToSpans.put(processRequest(client, currRequest), currBatchSpans);
                    currRequestBuilder = ExportTraceServiceRequest.newBuilder();
                    currBatchSpans = new ArrayList<>();
                }
            }
            // Dealing with the last batch request
            if (currBatchSpans.size() > 0) {
                final ExportTraceServiceRequest currRequest = currRequestBuilder.build();
                forwardedRequestFuturesToSpans.put(processRequest(client, currRequest), currBatchSpans);
            }
        }

        for (final Map.Entry<CompletableFuture<ExportTraceServiceRequest>, List<Span>> entry : forwardedRequestFuturesToSpans.entrySet()) {
            try {
                final CompletableFuture<ExportTraceServiceRequest> future = entry.getKey();
                final ExportTraceServiceRequest request = future.get();
                if (request != null) {
                    final List<Span> spansFailedToForward = entry.getValue();
                    spansToProcessLocally.addAll(spansFailedToForward);
                }
            } catch (InterruptedException | ExecutionException e) {
                LOG.error("Problem with asynchronous peer forwarding, current batch of spans will be processed locally.", e);
                final List<Span> spansFailedToForward = entry.getValue();
                spansToProcessLocally.addAll(spansFailedToForward);
            }
        }

        return spansToProcessLocally;
    }

    /**
     * Asynchronously forwards a request to the peer address. Returns a record with an empty payload if
     * the request succeeds, otherwise the payload will contain the failed ExportTraceServiceRequest to
     * be processed locally.
     */
    private CompletableFuture<ExportTraceServiceRequest> processRequest(final TraceServiceGrpc.TraceServiceBlockingStub client,
                                                     final ExportTraceServiceRequest request) {
        final String peerIp = client.getChannel().authority();
        final Timer forwardRequestTimer = forwardRequestTimers.computeIfAbsent(
                peerIp, ip -> pluginMetrics.timerWithTags(LATENCY, DESTINATION, ip));
        final Counter forwardedRequestCounter = forwardedRequestCounters.computeIfAbsent(
                peerIp, ip -> pluginMetrics.counterWithTags(REQUESTS, DESTINATION, ip));
        final Counter forwardRequestErrorCounter = forwardRequestErrorCounters.computeIfAbsent(
                peerIp, ip -> pluginMetrics.counterWithTags(ERRORS, DESTINATION, ip));

        final CompletableFuture<ExportTraceServiceRequest> callFuture = CompletableFuture.supplyAsync(() ->
        {
            forwardedRequestCounter.increment();
            try {
                forwardRequestTimer.record(() -> client.export(request));
                return null;
            } catch (Exception e) {
                LOG.error("Failed to forward request to address: {}", peerIp, e);
                forwardRequestErrorCounter.increment();
                return request;
            }
        }, executorService);

        return callFuture;
    }

    private TraceServiceGrpc.TraceServiceBlockingStub getClient(final String address) {
        return isAddressDefinedLocally(address) ? LOCAL_CLIENT : peerClientPool.getClient(address);
    }

    private boolean isLocalClient(final TraceServiceGrpc.TraceServiceBlockingStub client) {
        return client == LOCAL_CLIENT;
    }

    private boolean isAddressDefinedLocally(final String address) {
        final InetAddress inetAddress;
        try {
            inetAddress = InetAddress.getByName(address);
        } catch (UnknownHostException e) {
            return false;
        }
        if (inetAddress.isAnyLocalAddress() || inetAddress.isLoopbackAddress()) {
            return true;
        } else {
            try {
                return NetworkInterface.getByInetAddress(inetAddress) != null;
            } catch (SocketException e) {
                return false;
            }
        }
    }


    @Override
    public void prepareForShutdown() {

    }

    @Override
    public boolean isReadyForShutdown() {
        return true;
    }

    @Override
    public void shutdown() {
        //TODO: cleanup resources
    }
}
