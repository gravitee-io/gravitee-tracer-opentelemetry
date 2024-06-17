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

import io.gravitee.tracer.opentelemetry.exporter.BaseGrpcExporter;
import io.opentelemetry.api.metrics.MeterProvider;
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

public final class VertxGrpcSpanExporter extends BaseGrpcExporter implements SpanExporter {

    private static final String GRPC_SERVICE_NAME = "opentelemetry.proto.collector.trace.v1.TraceService";
    private static final String GRPC_METHOD_NAME = "Export";

    public VertxGrpcSpanExporter(
        String exporterName,
        Supplier<MeterProvider> meterProviderSupplier,
        URI grpcBaseUri,
        boolean compressionEnabled,
        int timeout,
        Consumer<HttpClientOptions> clientOptionsConsumer,
        Map<String, String> headersMap,
        Vertx vertx
    ) {
        super(
            exporterName,
            "span",
            meterProviderSupplier,
            grpcBaseUri,
            compressionEnabled,
            timeout,
            clientOptionsConsumer,
            headersMap,
            vertx,
            GRPC_SERVICE_NAME,
            GRPC_METHOD_NAME
        );
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        TraceRequestMarshaler request = TraceRequestMarshaler.create(spans);

        return export(request, spans.size());
    }
}
