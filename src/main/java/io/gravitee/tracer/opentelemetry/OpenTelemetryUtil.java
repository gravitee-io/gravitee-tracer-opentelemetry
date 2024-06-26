/*
 * Copyright © 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.tracer.opentelemetry;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadableSpan;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class OpenTelemetryUtil {

    public static final String TRACE_ID = "traceId";
    public static final String SPAN_ID = "spanId";
    public static final String SAMPLED = "sampled";
    public static final String PARENT_ID = "parentId";
    private static final Set<String> SPAN_DATA_KEYS = Set.of(TRACE_ID, SPAN_ID, SAMPLED, PARENT_ID);

    private OpenTelemetryUtil() {}

    /**
     * Gets current span data from the MDC context.
     *
     * @param context opentelemetry context
     */
    public static Map<String, String> getSpanData(Context context) {
        if (context == null) {
            return Collections.emptyMap();
        }
        Span span = Span.fromContextOrNull(context);
        Map<String, String> spanData = new HashMap<>();
        if (span != null) {
            SpanContext spanContext = span.getSpanContext();
            spanData.put(SPAN_ID, spanContext.getSpanId());
            spanData.put(TRACE_ID, spanContext.getTraceId());
            spanData.put(SAMPLED, Boolean.toString(spanContext.isSampled()));
            if (span instanceof ReadableSpan) {
                SpanContext parentSpanContext = ((ReadableSpan) span).getParentSpanContext();
                if (parentSpanContext != null && parentSpanContext.isValid()) {
                    spanData.put(PARENT_ID, parentSpanContext.getSpanId());
                }
            }
        }
        return spanData;
    }
}
