/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.amazon.dataprepper.model.trace;

import com.amazon.dataprepper.model.event.EventType;
import com.amazon.dataprepper.model.event.JacksonEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * A Jackson implementation for {@link Span}. This class extends the {@link JacksonEvent}.
 *
 * @since 1.2
 */
public class JacksonSpan extends JacksonEvent implements Span {

    private static final String TRACE_ID_KEY = "traceId";
    private static final String SPAN_ID_KEY = "spanId";
    private static final String TRACE_STATE_KEY = "traceState";
    private static final String PARENT_SPAN_ID_KEY = "parentSpanId";
    private static final String NAME_KEY = "name";
    private static final String KIND_KEY = "kind";
    private static final String START_TIME_KEY = "startTime";
    private static final String END_TIME_KEY = "endTime";
    private static final String ATTRIBUTES_KEY = "attributes";
    private static final String DROPPED_ATTRIBUTES_COUNT_KEY = "droppedAttributesCount";
    private static final String EVENTS_KEY = "events";
    private static final String DROPPED_EVENTS_COUNT_KEY = "droppedEventsCount";
    private static final String LINKS_KEY = "links";
    private static final String DROPPED_LINKS_COUNT_KEY = "droppedLinksCount";
    private static final String SERVICE_NAME_KEY = "serviceName";
    private static final String TRACE_GROUP_KEY = "traceGroup";
    private static final String DURATION_IN_NANOS_KEY = "durationInNanos";
    private static final String TRACE_GROUP_FIELDS_KEY = "traceGroupFields";

    private static final List<String> REQUIRED_KEYS = Arrays.asList(TRACE_GROUP_KEY);
    private static final List<String>
            REQUIRED_NON_EMPTY_KEYS = Arrays.asList(TRACE_ID_KEY, SPAN_ID_KEY, NAME_KEY, KIND_KEY, START_TIME_KEY, END_TIME_KEY);
    private static final List<String> REQUIRED_NON_NULL_KEYS = Arrays.asList(DURATION_IN_NANOS_KEY, TRACE_GROUP_FIELDS_KEY);

    protected JacksonSpan(final Builder builder) {
        super(builder);

        checkArgument(this.getMetadata().getEventType().equals("TRACE"), "eventType must be of type Trace");
    }

    @Override
    public String getTraceId() {
        return this.get(TRACE_ID_KEY, String.class);
    }

    @Override
    public String getSpanId() {
        return this.get(SPAN_ID_KEY, String.class);
    }

    @Override
    public String getTraceState() {
        return this.get(TRACE_STATE_KEY, String.class);
    }

    @Override
    public String getParentSpanId() {
        return this.get(PARENT_SPAN_ID_KEY, String.class);
    }

    @Override
    public String getName() {
        return this.get(NAME_KEY, String.class);
    }

    @Override
    public String getKind() {
        return this.get(KIND_KEY, String.class);
    }

    @Override
    public String getStartTime() {
        return this.get(START_TIME_KEY, String.class);
    }

    @Override
    public String getEndTime() {
        return this.get(END_TIME_KEY, String.class);
    }

    @Override
    public Map<String, Object> getAttributes() {
        return this.get(ATTRIBUTES_KEY, Map.class);
    }

    @Override
    public Integer getDroppedAttributesCount() {
        return this.get(DROPPED_ATTRIBUTES_COUNT_KEY, Integer.class);
    }

    @Override
    public List<? extends SpanEvent> getEvents() {
        return this.getList(EVENTS_KEY, DefaultSpanEvent.class);
    }

    @Override
    public Integer getDroppedEventsCount() {
        return this.get(DROPPED_EVENTS_COUNT_KEY, Integer.class);
    }

    @Override
    public List<? extends Link> getLinks() {
        return this.getList(LINKS_KEY, DefaultLink.class);
    }

    @Override
    public Integer getDroppedLinksCount() {
        return this.get(DROPPED_LINKS_COUNT_KEY, Integer.class);
    }

    @Override
    public String getTraceGroup() {
        return this.get(TRACE_GROUP_KEY, String.class);
    }

    @Override
    public Long getDurationInNanos() {
        return this.get(DURATION_IN_NANOS_KEY, Long.class);
    }

    @Override
    public TraceGroupFields getTraceGroupFields() {
        return this.get(TRACE_GROUP_FIELDS_KEY, DefaultTraceGroupFields.class);
    }

    @Override
    public String getServiceName() {
        return this.get(SERVICE_NAME_KEY, String.class);
    }

    @Override
    public void setTraceGroup(final String traceGroup) {
        this.put(TRACE_GROUP_KEY, traceGroup);
    }

    @Override
    public void setTraceGroupFields(final TraceGroupFields traceGroupFields) {
        this.put(TRACE_GROUP_FIELDS_KEY, traceGroupFields);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toJsonString() {
        final ObjectNode attributesNode = (ObjectNode) getJsonNode().get("attributes");
        final ObjectNode flattenedJsonNode = getJsonNode().deepCopy();
        if (attributesNode != null) {
            flattenedJsonNode.remove("attributes");
            for (Iterator<Map.Entry<String, JsonNode>> it = attributesNode.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                String field = entry.getKey();
                if (!flattenedJsonNode.has(field)) {
                    flattenedJsonNode.set(field, entry.getValue());
                }
            }
        }
        return flattenedJsonNode.toString();
    }

    /**
     * Builder for creating {@link JacksonSpan}
     * @since 1.2
     */
    public static class Builder extends JacksonEvent.Builder<Builder> {

        private final Map<String, Object> data;

        public Builder() {
            data = new HashMap();
        }

        @Override
        public Builder getThis() {
            return this;
        }

        /**
         * Sets the span id.
         * @param spanId
         * @since 1.2
         */
        public Builder withSpanId(final String spanId) {
            data.put(SPAN_ID_KEY, spanId);
            return this;
        }

        /**
         * Sets the trace id for the span.
         * @param traceId
         * @since 1.2
         */
        public Builder withTraceId(final String traceId) {
            data.put(TRACE_ID_KEY, traceId);
            return this;
        }

        /**
         * Sets the trace state
         * @param traceState
         * @since 1.2
         */
        public Builder withTraceState(final String traceState) {
            data.put(TRACE_STATE_KEY, traceState);
            return this;
        }

        /**
         * Sets the parent span id.
         * @param parentSpanId
         * @since 1.2
         */
        public Builder withParentSpanId(final String parentSpanId) {
            data.put(PARENT_SPAN_ID_KEY, parentSpanId);
            return this;
        }

        /**
         * Sets the span name
         * @param name
         * @since 1.2
         */
        public Builder withName(final String name) {
            data.put(NAME_KEY, name);
            return this;
        }

        /**
         * Sets the type of span
         * @param kind
         * @since 1.2
         */
        public Builder withKind(final String kind) {
            data.put(KIND_KEY, kind);
            return this;
        }

        /**
         * Sets the start time of the span
         * @param startTime
         * @since 1.2
         */
        public Builder withStartTime(final String startTime) {
            data.put(START_TIME_KEY, startTime);
            return this;
        }

        /**
         * Sets the end time of the span
         * @param endTime
         * @since 1.2
         */
        public Builder withEndTime(final String endTime) {
            data.put(END_TIME_KEY, endTime);
            return this;
        }

        /**
         * Optional - sets the attributes for {@link JacksonSpan}. Default is an empty map.
         * @param attributes the attributes to associate with this event.
         * @since 1.2
         */
        public Builder withAttributes(final Map<String, Object> attributes) {
            data.put(ATTRIBUTES_KEY, attributes);
            return this;
        }

        /**
         * Optional - sets the dropped attribute count for {@link JacksonSpan}. Default is 0.
         * @param droppedAttributesCount the total number of dropped attributes
         * @since 1.2
         */
        public Builder withDroppedAttributesCount(final Integer droppedAttributesCount) {
            data.put(DROPPED_ATTRIBUTES_COUNT_KEY, droppedAttributesCount);
            return this;
        }

        /**
         * Optional - sets the events for {@link JacksonSpan}. Default is an empty list.
         * @param events the events to associate.
         * @since 1.2
         */
        public Builder withEvents(final List<? extends SpanEvent> events) {
            data.put(EVENTS_KEY, events);
            return this;
        }

        /**
         * Optional - sets the dropped events count for {@link JacksonSpan}. Default is 0.
         * @param droppedEventsCount the total number of dropped events
         * @since 1.2
         */
        public Builder withDroppedEventsCount(final Integer droppedEventsCount) {
            data.put(DROPPED_EVENTS_COUNT_KEY, droppedEventsCount);
            return this;
        }

        /**
         * Optional - sets the links for {@link JacksonSpan}. Default is an empty list.
         * @param links the links to associate.
         * @since 1.2
         */
        public Builder withLinks(final List<? extends Link> links) {
            data.put(LINKS_KEY, links);
            return this;
        }

        /**
         * Optional - sets the dropped links count for {@link JacksonSpan}. Default is 0.
         * @param droppedLinksCount the total number of dropped links
         * @since 1.2
         */
        public Builder withDroppedLinksCount(final Integer droppedLinksCount) {
            data.put(DROPPED_LINKS_COUNT_KEY, droppedLinksCount);
            return this;
        }

        /**
         * Sets the trace group name
         * @param traceGroup
         * @since 1.2
         */
        public Builder withTraceGroup(final String traceGroup) {
            data.put(TRACE_GROUP_KEY, traceGroup);
            return this;
        }

        /**
         * Sets the duration of the span
         * @param durationInNanos
         * @since 1.2
         */
        public Builder withDurationInNanos(final Long durationInNanos) {
            data.put(DURATION_IN_NANOS_KEY, durationInNanos);
            return this;
        }

        /**
         * Sets the trace group fields
         * @param traceGroupFields
         * @since 1.2
         */
        public Builder withTraceGroupFields(final TraceGroupFields traceGroupFields) {
            data.put(TRACE_GROUP_FIELDS_KEY, traceGroupFields);
            return this;
        }

        /**
         * Sets the service name of the span
         * @param serviceName
         * @since 1.3
         */
        public Builder withServiceName(final String serviceName) {
            data.put(SERVICE_NAME_KEY, serviceName);
            return this;
        }

        /**
         * Returns a newly created {@link JacksonSpan}
         * @return a JacksonSpan
         * @since 1.2
         */
        public JacksonSpan build() {
            validateParameters();
            checkAndSetDefaultValues();
            this.withData(data);
            this.withEventType(EventType.TRACE.toString());
            return new JacksonSpan(this);
        }

        private void validateParameters() {
            REQUIRED_KEYS.forEach(key -> {
                checkState(data.containsKey(key), key + " need to be assigned");
            });

            REQUIRED_NON_EMPTY_KEYS.forEach(key -> {
                final String value = (String) data.get(key);
                checkNotNull(value, key + " cannot be null");
                checkArgument(!value.isEmpty(),  key + " cannot be an empty string");
            });

            REQUIRED_NON_NULL_KEYS.forEach(key -> {
                final Object value = data.get(key);
                checkNotNull(value, key + " cannot be null");
            });
        }

        private void checkAndSetDefaultValues() {
            data.computeIfAbsent(ATTRIBUTES_KEY, k -> new HashMap<>());
            data.putIfAbsent(DROPPED_ATTRIBUTES_COUNT_KEY, 0);
            data.computeIfAbsent(LINKS_KEY, k -> new LinkedList<>());
            data.putIfAbsent(DROPPED_LINKS_COUNT_KEY, 0);
            data.computeIfAbsent(EVENTS_KEY, k -> new LinkedList<>());
            data.putIfAbsent(DROPPED_EVENTS_COUNT_KEY, 0);
        }

    }
}
