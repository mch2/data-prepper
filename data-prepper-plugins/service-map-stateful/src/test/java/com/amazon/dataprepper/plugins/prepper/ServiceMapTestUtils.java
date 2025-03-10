/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.amazon.dataprepper.plugins.prepper;

import com.amazon.dataprepper.model.record.Record;
import com.amazon.dataprepper.model.trace.DefaultTraceGroupFields;
import com.amazon.dataprepper.model.trace.JacksonSpan;
import com.amazon.dataprepper.model.trace.Span;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.InstrumentationLibrarySpans;
import io.opentelemetry.proto.trace.v1.ResourceSpans;

import java.io.UnsupportedEncodingException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

public class ServiceMapTestUtils {
    private static final long TEST_DURATION_IN_NANOS = 1000;
    private static final int TEST_STATUS_CODE = 1;
    private static final Random RANDOM = new Random();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();


    public static byte[] getRandomBytes(int len) {
        byte[] bytes = new byte[len];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    public static byte[] getSpanId(ResourceSpans resourceSpans) {
        return resourceSpans.getInstrumentationLibrarySpans(0).getSpans(0).getSpanId().toByteArray();
    }

    public static Future<Set<ServiceMapRelationship>> startExecuteAsync(ExecutorService threadpool, ServiceMapStatefulPrepper prepper,
                                                                 Collection<Record<Object>> records) {
        return threadpool.submit(() -> {
            return prepper.execute(records)
                    .stream()
                    .map(record -> {
                        try {
                            return OBJECT_MAPPER.readValue(record.getData().toJsonString(), ServiceMapRelationship.class);
                        } catch (JsonProcessingException e) {
                            throw new RuntimeException(e);
                        }
                    }).collect(Collectors.toSet());
        });
    }

    /**
     * Creates a ResourceSpans object with the given parameters, with a single span
     * @param serviceName Resource name for the ResourceSpans object
     * @param spanName Span name for the single span in the ResourceSpans object
     * @param spanId Span id for the single span in the ResourceSpans object
     * @param parentId Parent id for the single span in the ResourceSpans object
     * @param spanKind Span kind for the single span in the ResourceSpans object
     * @return ResourceSpans object with a single span constructed according to the parameters
     * @throws UnsupportedEncodingException
     */
    public static ResourceSpans getResourceSpans(final String serviceName, final String spanName, final byte[]
            spanId, final byte[] parentId, final byte[] traceId, final io.opentelemetry.proto.trace.v1.Span.SpanKind spanKind) throws UnsupportedEncodingException {
        final ByteString parentSpanId = parentId != null ? ByteString.copyFrom(parentId) : ByteString.EMPTY;
        return ResourceSpans.newBuilder()
                .setResource(
                        Resource.newBuilder()
                                .addAttributes(KeyValue.newBuilder()
                                        .setKey(OTelHelper.SERVICE_NAME_KEY)
                                        .setValue(AnyValue.newBuilder().setStringValue(serviceName).build()).build())
                                .build()
                )
                .addInstrumentationLibrarySpans(
                        0,
                        InstrumentationLibrarySpans.newBuilder()
                                .addSpans(
                                        io.opentelemetry.proto.trace.v1.Span.newBuilder()
                                                .setName(spanName)
                                                .setKind(spanKind)
                                                .setSpanId(ByteString.copyFrom(spanId))
                                                .setParentSpanId(parentSpanId)
                                                .setTraceId(ByteString.copyFrom(traceId))
                                                .build()
                                )
                                .build()
                )
                .build();
    }

    public static ExportTraceServiceRequest getExportTraceServiceRequest(ResourceSpans...spans){
        return ExportTraceServiceRequest.newBuilder()
                .addAllResourceSpans(Arrays.asList(spans))
                .build();
    }

    /**
     * Creates a {@link com.amazon.dataprepper.model.trace.Span} object with the given parameters
     * @param serviceName Resource name for the ResourceSpans object
     * @param spanName Span name for the single span in the ResourceSpans object
     * @param spanId Span id for the single span in the ResourceSpans object
     * @param parentId Parent id for the single span in the ResourceSpans object
     * @param spanKind Span kind for the single span in the ResourceSpans object
     * @return {@link com.amazon.dataprepper.model.trace.Span} object with a single span constructed according to the parameters
     */
    public static Span getSpan(final String serviceName, final String spanName, final String
            spanId, final String parentId, final String traceId, final io.opentelemetry.proto.trace.v1.Span.SpanKind spanKind) {
        final Instant endInstant = Instant.now();
        final String endTime = endInstant.toString();
        final JacksonSpan.Builder builder = JacksonSpan.builder()
                .withSpanId(spanId)
                .withTraceId(traceId)
                .withTraceState("")
                .withParentSpanId(parentId)
                .withName(spanName)
                .withServiceName(serviceName)
                .withKind(spanKind.name())
                .withStartTime(endInstant.minusNanos(TEST_DURATION_IN_NANOS).toString())
                .withEndTime(endTime)
                .withTraceGroup(parentId.isEmpty()? null : spanName)
                .withDurationInNanos(TEST_DURATION_IN_NANOS);
        if (parentId.isEmpty()) {
            builder.withTraceGroupFields(
                    DefaultTraceGroupFields.builder()
                            .withStatusCode(TEST_STATUS_CODE)
                            .withDurationInNanos(TEST_DURATION_IN_NANOS)
                            .withEndTime(endTime)
                            .build()
            );
        } else {
            builder.withTraceGroupFields(
                    DefaultTraceGroupFields.builder().build());
        }
        return builder.build();
    }

}
