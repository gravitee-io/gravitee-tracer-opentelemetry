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
package io.gravitee.tracer.opentelemetry.instrumenter;

import io.gravitee.tracer.opentelemetry.OpenTelemetryTracer.SpanOperation;
import io.gravitee.tracer.opentelemetry.VertxContextStorage;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import io.vertx.core.Context;
import io.vertx.core.MultiMap;
import io.vertx.core.http.impl.headers.HeadersAdaptor;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import io.vertx.core.spi.tracing.SpanKind;
import io.vertx.core.spi.tracing.TagExtractor;
import io.vertx.core.spi.tracing.VertxTracer;
import io.vertx.core.tracing.TracingPolicy;
import java.util.Map;
import java.util.function.BiConsumer;

@SuppressWarnings("unchecked")
public interface InstrumenterVertxTracer<REQ, RESP> extends VertxTracer<SpanOperation, SpanOperation> {
    String INSTRUMENTATION_NAME = "io.gravitee.opentelemetry";

    @Override
    default <R> SpanOperation receiveRequest(
        // The Vert.x context passed to use is already duplicated.
        final Context context,
        final SpanKind kind,
        final TracingPolicy policy,
        final R request,
        final String operation,
        final Iterable<Map.Entry<String, String>> headers,
        final TagExtractor<R> tagExtractor
    ) {
        if (TracingPolicy.IGNORE == policy) {
            return null;
        }

        Instrumenter<REQ, RESP> instrumenter = getReceiveRequestInstrumenter();
        io.opentelemetry.context.Context parentContext = VertxContextStorage.getContext(context);
        if (parentContext == null) {
            parentContext = io.opentelemetry.context.Context.current();
        }

        if (instrumenter.shouldStart(parentContext, (REQ) request)) {
            io.opentelemetry.context.Context spanContext = instrumenter.start(parentContext, (REQ) request);
            Scope scope = VertxContextStorage.INSTANCE.attach(context, spanContext);
            return spanOperation(context, (REQ) request, toMultiMap(headers), spanContext, scope);
        }

        return null;
    }

    @Override
    default <R> void sendResponse(
        // The Vert.x context passed to use is already duplicated.
        final Context context,
        final R response,
        final SpanOperation spanOperation,
        final Throwable failure,
        final TagExtractor<R> tagExtractor
    ) {
        if (spanOperation == null) {
            return;
        }

        Scope scope = spanOperation.getScope();
        if (scope == null) {
            return;
        }

        Object request = spanOperation.getRequest();
        Instrumenter<REQ, RESP> instrumenter = getSendResponseInstrumenter();
        try (scope) {
            instrumenter.end(spanOperation.getSpanContext(), (REQ) request, (RESP) response, failure);
        }
    }

    @Override
    default <R> SpanOperation sendRequest(
        // This context is not duplicated, so we need to do it.
        final Context context,
        final SpanKind kind,
        final TracingPolicy policy,
        final R request,
        final String operation,
        final BiConsumer<String, String> headers,
        final TagExtractor<R> tagExtractor
    ) {
        if (TracingPolicy.IGNORE == policy) {
            return null;
        }

        Instrumenter<REQ, RESP> instrumenter = getSendRequestInstrumenter();
        io.opentelemetry.context.Context parentContext = VertxContextStorage.getContext(context);
        if (parentContext == null) {
            parentContext = io.opentelemetry.context.Context.current();
        }

        if (instrumenter.shouldStart(parentContext, (REQ) request)) {
            io.opentelemetry.context.Context spanContext = instrumenter.start(parentContext, writableHeaders((REQ) request, headers));
            // Create a new scope with an empty termination callback.
            Scope scope = new Scope() {
                @Override
                public void close() {}
            };
            return spanOperation(context, (REQ) request, toMultiMap(headers), spanContext, scope);
        }

        return null;
    }

    @Override
    default <R> void receiveResponse(
        // This context is not duplicated, so we need to do it, but we can't duplicate it again because it was already done in
        // io.quarkus.opentelemetry.runtime.tracing.vertx.OpenTelemetryVertxTracer.sendRequest, but we don't use it so it should be ok.
        final Context context,
        final R response,
        final SpanOperation spanOperation,
        final Throwable failure,
        final TagExtractor<R> tagExtractor
    ) {
        if (spanOperation == null) {
            return;
        }

        Scope scope = spanOperation.getScope();
        if (scope == null) {
            return;
        }

        Object request = spanOperation.getRequest();
        Instrumenter<REQ, RESP> instrumenter = getReceiveResponseInstrumenter();
        try (scope) {
            instrumenter.end(spanOperation.getSpanContext(), (REQ) request, (RESP) response, failure);
        }
    }

    <R> boolean canHandle(R request, TagExtractor<R> tagExtractor);

    Instrumenter<REQ, RESP> getReceiveRequestInstrumenter();

    Instrumenter<REQ, RESP> getSendResponseInstrumenter();

    Instrumenter<REQ, RESP> getSendRequestInstrumenter();

    Instrumenter<REQ, RESP> getReceiveResponseInstrumenter();

    default SpanOperation spanOperation(
        Context context,
        REQ request,
        MultiMap headers,
        io.opentelemetry.context.Context spanContext,
        Scope scope
    ) {
        return SpanOperation.span(context, request, headers, spanContext, scope);
    }

    default REQ writableHeaders(REQ request, BiConsumer<String, String> headers) {
        return request;
    }

    private static MultiMap toMultiMap(Iterable<Map.Entry<String, String>> headers) {
        MultiMap headersMultiMap;
        if (headers instanceof MultiMap) {
            headersMultiMap = (MultiMap) headers;
        } else {
            headersMultiMap = new HeadersMultiMap();
            for (final Map.Entry<String, String> header : headers) {
                headersMultiMap.add(header.getKey(), header.getValue());
            }
        }
        return headersMultiMap;
    }

    private static MultiMap toMultiMap(BiConsumer<String, String> headers) {
        return new HeadersAdaptor(new HeadersMultiMap()) {
            @Override
            public MultiMap set(final String name, final String value) {
                MultiMap result = super.set(name, value);
                headers.accept(name, value);
                return result;
            }
        };
    }
}
