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
package io.gravitee.tracer.opentelemetry.exporter.traces;

import io.gravitee.tracer.opentelemetry.exporter.BaseHttpExporter;
import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.exporter.internal.http.HttpExporter;
import io.opentelemetry.exporter.internal.otlp.traces.TraceRequestMarshaler;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientOptions;
import java.net.URI;
import java.util.Collection;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class VertxHttpSpanExporter extends BaseHttpExporter implements SpanExporter {

    private static final String TRACES_PATH = "/v1/traces";

    private final HttpExporter<TraceRequestMarshaler> delegate;

    public VertxHttpSpanExporter(
        String exporterName,
        URI httpBaseUri,
        boolean compressionEnabled,
        int timeout,
        Consumer<HttpClientOptions> clientOptionsConsumer,
        Map<String, String> headersMap,
        String contentType,
        Vertx vertx,
        Supplier<MeterProvider> meterProviderSupplier,
        boolean exportAsJson
    ) {
        this.delegate =
            new HttpExporter<>(
                exporterName,
                "span",
                new BaseHttpExporter.VertxHttpSender(
                    httpBaseUri,
                    TRACES_PATH,
                    compressionEnabled,
                    timeout,
                    clientOptionsConsumer,
                    headersMap,
                    contentType,
                    vertx
                ),
                meterProviderSupplier,
                exportAsJson
            );
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        TraceRequestMarshaler exportRequest = TraceRequestMarshaler.create(spans);
        return delegate.export(exportRequest, spans.size());
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        return delegate.shutdown();
    }
}
