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

import io.gravitee.common.service.AbstractService;
import io.gravitee.common.util.Version;
import io.gravitee.node.api.Node;
import io.gravitee.node.api.tracing.Tracer;
import io.gravitee.node.tracing.vertx.VertxTracer;
import io.gravitee.tracer.opentelemetry.configuration.OpenTelemetryTracerConfiguration;
import io.gravitee.tracer.opentelemetry.exporter.ExporterFactory;
import io.gravitee.tracer.opentelemetry.instrumenter.HttpInstrumenterVertxTracer;
import io.gravitee.tracer.opentelemetry.instrumenter.InstrumenterVertxTracer;
import io.gravitee.tracer.opentelemetry.span.OpenTelemetrySpan;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.instrumentation.runtimemetrics.java17.RuntimeMetrics;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.OpenTelemetrySdkBuilder;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.semconv.ResourceAttributes;
import io.vertx.core.Context;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.spi.tracing.SpanKind;
import io.vertx.core.spi.tracing.TagExtractor;
import io.vertx.core.tracing.TracingPolicy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class OpenTelemetryTracer
    extends AbstractService<Tracer>
    implements VertxTracer<OpenTelemetryTracer.SpanOperation, OpenTelemetryTracer.SpanOperation> {

    private final OpenTelemetryTracerConfiguration configuration;

    private final Node node;

    private io.opentelemetry.api.trace.Tracer tracer;

    private List<InstrumenterVertxTracer<?, ?>> instrumenterVertxTracers;

    private RuntimeMetrics runtimeMetrics;

    private OpenTelemetrySdk openTelemetrySdk;

    private static final String EXPORTER_NAME = "otlp";
    private static final String DEFAULT_HOST_NAME = "unknown";
    private static final String IP_DEFAULT = "0.0.0.0";

    private static final AttributeKey<String> HOSTNAME_KEY = AttributeKey.stringKey("hostname");

    static final AttributeKey<String> IP_KEY = AttributeKey.stringKey("ip");

    private final ExporterFactory exporterFactory;

    @Autowired
    public OpenTelemetryTracer(final OpenTelemetryTracerConfiguration configuration, Node node, Vertx vertx) {
        this.configuration = configuration;
        this.node = node;
        this.exporterFactory = new ExporterFactory(configuration, vertx);
    }

    @Override
    protected void doStart() {
        final Resource resource = createResource();

        final OpenTelemetrySdkBuilder builder = OpenTelemetrySdk
            .builder()
            .setPropagators(
                ContextPropagators.create(
                    TextMapPropagator.composite(W3CTraceContextPropagator.getInstance(), W3CBaggagePropagator.getInstance())
                )
            );

        configureTracerProvider(builder, resource);
        configureMetrics(builder, resource);

        this.openTelemetrySdk = builder.buildAndRegisterGlobal();

        if (configuration.isMetricsEnabled()) {
            // Initialize Metrics telemetry
            this.runtimeMetrics = RuntimeMetrics.builder(openTelemetrySdk).enableAllFeatures().enableExperimentalJmxTelemetry().build();
        }

        if (configuration.isTracesEnabled()) {
            this.instrumenterVertxTracers = List.of(new HttpInstrumenterVertxTracer(openTelemetrySdk));
        } else {
            this.instrumenterVertxTracers = Collections.emptyList();
        }

        this.tracer = openTelemetrySdk.getTracer(node.getClass().getName());
    }

    private Resource createResource() {
        String hostname;
        String ipv4;

        try {
            hostname = InetAddress.getLocalHost().getHostName();
            ipv4 = InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            hostname = DEFAULT_HOST_NAME;
            ipv4 = IP_DEFAULT;
        }

        return Resource
            .getDefault()
            .toBuilder()
            .put(ResourceAttributes.SERVICE_NAME, node.application())
            .put(ResourceAttributes.SERVICE_VERSION, Version.RUNTIME_VERSION.MAJOR_VERSION)
            .put(ResourceAttributes.SERVICE_INSTANCE_ID, node.id())
            .put(IP_KEY, ipv4)
            .put(HOSTNAME_KEY, hostname)
            .build();
    }

    private void configureTracerProvider(OpenTelemetrySdkBuilder builder, Resource resource) {
        if (configuration.isTracesEnabled()) {
            SdkTracerProvider tracerProvider = SdkTracerProvider
                .builder()
                .addSpanProcessor(BatchSpanProcessor.builder(exporterFactory.createSpanExporter()).build())
                .setResource(resource)
                .build();

            builder.setTracerProvider(tracerProvider);
        }
    }

    private void configureMetrics(OpenTelemetrySdkBuilder builder, Resource resource) {
        if (configuration.isMetricsEnabled()) {
            SdkMeterProvider meterProvider = SdkMeterProvider
                .builder()
                .registerMetricReader(
                    PeriodicMetricReader
                        .builder(exporterFactory.createMetricExporter())
                        // Default is 60000ms (60 seconds).
                        .setInterval(Duration.ofSeconds(60))
                        .build()
                )
                .setResource(resource)
                .build();

            builder.setMeterProvider(meterProvider);
        }
    }

    @Override
    public <R> SpanOperation receiveRequest(
        final Context context,
        final SpanKind kind,
        final TracingPolicy policy,
        final R request,
        final String operation,
        final Iterable<Map.Entry<String, String>> headers,
        final TagExtractor<R> tagExtractor
    ) {
        return getTracer(request, tagExtractor).receiveRequest(context, kind, policy, request, operation, headers, tagExtractor);
    }

    @Override
    public <R> void sendResponse(
        final Context context,
        final R response,
        final SpanOperation spanOperation,
        final Throwable failure,
        final TagExtractor<R> tagExtractor
    ) {
        getTracer(spanOperation, tagExtractor).sendResponse(context, response, spanOperation, failure, tagExtractor);
    }

    @Override
    public <R> SpanOperation sendRequest(
        final Context context,
        final SpanKind kind,
        final TracingPolicy policy,
        final R request,
        final String operation,
        final BiConsumer<String, String> headers,
        final TagExtractor<R> tagExtractor
    ) {
        return getTracer(request, tagExtractor).sendRequest(context, kind, policy, request, operation, headers, tagExtractor);
    }

    @Override
    public <R> void receiveResponse(
        final Context context,
        final R response,
        final SpanOperation spanOperation,
        final Throwable failure,
        final TagExtractor<R> tagExtractor
    ) {
        getTracer(spanOperation, tagExtractor).receiveResponse(context, response, spanOperation, failure, tagExtractor);
    }

    @SuppressWarnings("unchecked")
    private <R> io.vertx.core.spi.tracing.VertxTracer<SpanOperation, SpanOperation> getTracer(
        final R request,
        final TagExtractor<R> tagExtractor
    ) {
        for (InstrumenterVertxTracer<?, ?> instrumenterVertxTracer : instrumenterVertxTracers) {
            if (instrumenterVertxTracer.canHandle(request, tagExtractor)) {
                return instrumenterVertxTracer;
            }
        }

        return NOOP;
    }

    @SuppressWarnings("unchecked")
    private <R> io.vertx.core.spi.tracing.VertxTracer<SpanOperation, SpanOperation> getTracer(
        final SpanOperation spanOperation,
        final TagExtractor<R> tagExtractor
    ) {
        return spanOperation != null ? getTracer((R) spanOperation.getRequest(), tagExtractor) : NOOP;
    }

    @Override
    protected void doStop() {
        // Close JfrTelemetry to stop listening for JFR events
        this.runtimeMetrics.close();

        this.openTelemetrySdk.close();

        this.close();
    }

    @Override
    public io.gravitee.tracing.api.Span trace(String spanName) {
        io.opentelemetry.context.Context tracingContext = VertxContextStorage.INSTANCE.current();
        if (tracingContext == null) {
            tracingContext = io.opentelemetry.context.Context.root();
        }
        io.opentelemetry.api.trace.Span span = tracer.spanBuilder(spanName).setParent(tracingContext).startSpan();
        Scope scope = VertxContextStorage.INSTANCE.attach(tracingContext.with(span));
        return new OpenTelemetrySpan(span, scope);
    }

    public static class SpanOperation {

        private final Context context;
        private final Object request;
        private final MultiMap headers;
        private final io.opentelemetry.context.Context spanContext;
        private final Scope scope;

        public SpanOperation(
            final Context context,
            final Object request,
            final MultiMap headers,
            final io.opentelemetry.context.Context spanContext,
            final Scope scope
        ) {
            this.context = context;
            this.request = request;
            this.headers = headers;
            this.spanContext = spanContext;
            this.scope = scope;
        }

        public Context getContext() {
            return context;
        }

        public Object getRequest() {
            return request;
        }

        public MultiMap getHeaders() {
            return headers;
        }

        public io.opentelemetry.context.Context getSpanContext() {
            return spanContext;
        }

        public Scope getScope() {
            return scope;
        }

        public static SpanOperation span(
            final Context context,
            final Object request,
            final MultiMap headers,
            final io.opentelemetry.context.Context spanContext,
            final Scope scope
        ) {
            return new SpanOperation(context, request, headers, spanContext, scope);
        }
    }
}
