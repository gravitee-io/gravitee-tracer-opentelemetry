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
package io.gravitee.tracer.opentelemetry.exporter;

import io.gravitee.tracer.opentelemetry.configuration.OpenTelemetryTracerConfiguration;
import io.gravitee.tracer.opentelemetry.exporter.metrics.VertxGrpcMetricExporter;
import io.gravitee.tracer.opentelemetry.exporter.metrics.VertxHttpMetricExporter;
import io.gravitee.tracer.opentelemetry.exporter.traces.VertxGrpcSpanExporter;
import io.gravitee.tracer.opentelemetry.exporter.traces.VertxHttpSpanExporter;
import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.net.JksOptions;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.PemTrustOptions;
import io.vertx.core.net.PfxOptions;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class ExporterFactory {

    private static final String EXPORTER_NAME = "otlp";

    private final OpenTelemetryTracerConfiguration configuration;

    private final Vertx vertx;

    public ExporterFactory(final OpenTelemetryTracerConfiguration configuration, final Vertx vertx) {
        this.configuration = configuration;
        this.vertx = vertx;
    }

    public SpanExporter createSpanExporter() {
        String protocol = configuration.getType();

        if (protocol == null || protocol.isEmpty() || OpenTelemetryTracerConfiguration.Protocol.HTTP_PROTOBUF.equalsIgnoreCase(protocol)) {
            boolean exportAsJson = true; //TODO: this will be enhanced in the future

            return new VertxHttpSpanExporter(
                EXPORTER_NAME, // use the same as OTel does
                URI.create(configuration.getUrl()),
                determineCompression(configuration),
                configuration.getTimeout(),
                new HttpClientOptionsConsumer(configuration),
                populateTracingExportHttpHeaders(configuration),
                exportAsJson ? "application/json" : "application/x-protobuf",
                vertx,
                MeterProvider::noop,
                exportAsJson
            );
        } else {
            return new VertxGrpcSpanExporter(
                EXPORTER_NAME, // use the same as OTel does
                MeterProvider::noop,
                URI.create(configuration.getUrl()),
                determineCompression(configuration),
                configuration.getTimeout(),
                new HttpClientOptionsConsumer(configuration),
                populateTracingExportHttpHeaders(configuration),
                vertx
            );
        }
    }

    public MetricExporter createMetricExporter() {
        String protocol = configuration.getType();

        if (protocol == null || protocol.isEmpty() || OpenTelemetryTracerConfiguration.Protocol.HTTP_PROTOBUF.equalsIgnoreCase(protocol)) {
            boolean exportAsJson = true; //TODO: this will be enhanced in the future

            return new VertxHttpMetricExporter(
                EXPORTER_NAME, // use the same as OTel does
                URI.create(configuration.getUrl()),
                determineCompression(configuration),
                configuration.getTimeout(),
                new HttpClientOptionsConsumer(configuration),
                populateTracingExportHttpHeaders(configuration),
                exportAsJson ? "application/json" : "application/x-protobuf",
                vertx,
                MeterProvider::noop,
                exportAsJson
            );
        } else {
            return new VertxGrpcMetricExporter(
                EXPORTER_NAME, // use the same as OTel does
                MeterProvider::noop,
                URI.create(configuration.getUrl()),
                determineCompression(configuration),
                configuration.getTimeout(),
                new HttpClientOptionsConsumer(configuration),
                populateTracingExportHttpHeaders(configuration),
                vertx
            );
        }
    }

    private static boolean determineCompression(OpenTelemetryTracerConfiguration tracerConfig) {
        return CompressionType.from(tracerConfig.getCompressionType()) == CompressionType.GZIP;
    }

    private static Map<String, String> populateTracingExportHttpHeaders(OpenTelemetryTracerConfiguration tracerConfig) {
        Map<String, String> headersMap = new HashMap<>();

        if (tracerConfig.getCustomHeaders() != null) {
            headersMap.putAll(tracerConfig.getCustomHeaders());
        }

        return headersMap;
    }

    private static final class HttpClientOptionsConsumer implements Consumer<HttpClientOptions> {

        private static final String KEYSTORE_FORMAT_JKS = "JKS";
        private static final String KEYSTORE_FORMAT_PEM = "PEM";
        private static final String KEYSTORE_FORMAT_PKCS12 = "PKCS12";

        private final OpenTelemetryTracerConfiguration configuration;

        private HttpClientOptionsConsumer(OpenTelemetryTracerConfiguration configuration) {
            this.configuration = configuration;
        }

        @Override
        public void accept(HttpClientOptions options) {
            if (configuration.isSslEnabled()) {
                configureSsl(options);
            }
        }

        private void configureSsl(HttpClientOptions options) {
            options.setSsl(true).setUseAlpn(true);

            if (configuration.isTrustAll()) {
                options.setTrustAll(true).setVerifyHost(false);
            } else {
                options.setTrustAll(false).setVerifyHost(configuration.isHostnameVerifier());
            }

            if (configuration.getKeystoreType() != null) {
                if (configuration.getKeystoreType().equalsIgnoreCase(KEYSTORE_FORMAT_JKS)) {
                    options.setKeyCertOptions(
                        new JksOptions().setPath(configuration.getKeystorePath()).setPassword(configuration.getKeystorePassword())
                    );
                } else if (configuration.getKeystoreType().equalsIgnoreCase(KEYSTORE_FORMAT_PKCS12)) {
                    options.setKeyCertOptions(
                        new PfxOptions().setPath(configuration.getKeystorePath()).setPassword(configuration.getKeystorePassword())
                    );
                } else if (configuration.getKeystoreType().equalsIgnoreCase(KEYSTORE_FORMAT_PEM)) {
                    options.setKeyCertOptions(
                        new PemKeyCertOptions()
                            .setCertPaths(configuration.getKeystorePemCerts())
                            .setKeyPaths(configuration.getKeystorePemKeys())
                    );
                }
            }

            if (configuration.getTruststoreType() != null) {
                if (configuration.getTruststoreType().equalsIgnoreCase(KEYSTORE_FORMAT_JKS)) {
                    options.setTrustOptions(
                        new JksOptions().setPath(configuration.getTruststorePath()).setPassword(configuration.getTruststorePassword())
                    );
                } else if (configuration.getTruststoreType().equalsIgnoreCase(KEYSTORE_FORMAT_PKCS12)) {
                    options.setTrustOptions(
                        new PfxOptions().setPath(configuration.getTruststorePath()).setPassword(configuration.getTruststorePassword())
                    );
                } else if (configuration.getTruststoreType().equalsIgnoreCase(KEYSTORE_FORMAT_PEM)) {
                    options.setTrustOptions(new PemTrustOptions().addCertPath(configuration.getTruststorePath()));
                }
            }
        }
    }
}
