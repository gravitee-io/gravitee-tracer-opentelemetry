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

import io.gravitee.tracer.opentelemetry.exporter.traces.VertxHttpSpanExporter;
import io.gravitee.tracer.opentelemetry.vertx.BufferOutputStream;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.exporter.internal.ExporterMetrics;
import io.opentelemetry.exporter.internal.http.HttpExporter;
import io.opentelemetry.exporter.internal.http.HttpSender;
import io.opentelemetry.exporter.internal.marshal.Marshaler;
import io.opentelemetry.exporter.internal.marshal.MarshalerWithSize;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.internal.ThrottlingLogger;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.core.net.SocketAddress;
import io.vertx.core.tracing.TracingPolicy;
import io.vertx.grpc.client.GrpcClient;
import io.vertx.grpc.client.GrpcClientRequest;
import io.vertx.grpc.client.GrpcClientResponse;
import io.vertx.grpc.common.GrpcStatus;
import io.vertx.grpc.common.ServiceName;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public abstract class BaseHttpExporter {

    private static final Logger internalLogger = Logger.getLogger(BaseHttpExporter.class.getName());

    private static final ThrottlingLogger logger = new ThrottlingLogger(internalLogger);

    private static final int MAX_ATTEMPTS = 3;

    public final class VertxHttpSender implements HttpSender {

        private final String basePath;
        private final boolean compressionEnabled;
        private final Map<String, String> headers;
        private final String contentType;
        private final HttpClient client;

        private final String path;

        public VertxHttpSender(
            URI httpBaseUri,
            String path,
            boolean compressionEnabled,
            int timeout,
            Consumer<HttpClientOptions> clientOptionsConsumer,
            Map<String, String> headersMap,
            String contentType,
            Vertx vertx
        ) {
            this.basePath = determineBasePath(httpBaseUri);
            this.path = path;
            this.compressionEnabled = compressionEnabled;
            this.headers = headersMap;
            this.contentType = contentType;
            var httpClientOptions = new HttpClientOptions()
                .setReadIdleTimeout(timeout)
                .setDefaultHost(httpBaseUri.getHost())
                .setDefaultPort(OTelExporterUtil.getPort(httpBaseUri))
                .setTracingPolicy(TracingPolicy.IGNORE); // needed to avoid tracing the calls from this http client

            clientOptionsConsumer.accept(httpClientOptions);

            this.client = vertx.createHttpClient(httpClientOptions);
        }

        private final AtomicBoolean isShutdown = new AtomicBoolean();
        private final CompletableResultCode shutdownResult = new CompletableResultCode();

        private static String determineBasePath(URI baseUri) {
            String path = baseUri.getPath();
            if (path.isEmpty() || path.equals("/")) {
                return "";
            }
            if (path.endsWith("/")) { // strip ending slash
                path = path.substring(0, path.length() - 1);
            }
            if (!path.startsWith("/")) { // prepend leading slash
                path = "/" + path;
            }
            return path;
        }

        @Override
        public void send(Marshaler marshaler, int contentLength, Consumer<Response> onHttpResponseRead, Consumer<Throwable> onError) {
            String requestURI = basePath + path;
            var clientRequestSuccessHandler = new ClientRequestSuccessHandler(
                client,
                requestURI,
                headers,
                compressionEnabled,
                contentType,
                contentLength,
                onHttpResponseRead,
                onError,
                marshaler,
                1
            );
            initiateSend(client, requestURI, MAX_ATTEMPTS, clientRequestSuccessHandler, onError);
        }

        private static void initiateSend(
            HttpClient client,
            String requestURI,
            int numberOfAttempts,
            Handler<HttpClientRequest> clientRequestSuccessHandler,
            Consumer<Throwable> onError
        ) {
            // TODO: add support for retry with max_attempts and backoff
            client
                .request(HttpMethod.POST, requestURI)
                .toCompletionStage()
                .whenComplete(
                    new BiConsumer<HttpClientRequest, Throwable>() {
                        @Override
                        public void accept(HttpClientRequest httpClientRequest, Throwable throwable) {
                            if (httpClientRequest != null) {
                                clientRequestSuccessHandler.handle(httpClientRequest);
                            } else {
                                onError.accept(throwable);
                            }
                        }
                    }
                );
        }

        @Override
        public CompletableResultCode shutdown() {
            if (!isShutdown.compareAndSet(false, true)) {
                logger.log(Level.FINE, "Calling shutdown() multiple times.");
                return shutdownResult;
            }

            client
                .close()
                .onSuccess(
                    new Handler<>() {
                        @Override
                        public void handle(Void event) {
                            shutdownResult.succeed();
                        }
                    }
                )
                .onFailure(
                    new Handler<>() {
                        @Override
                        public void handle(Throwable event) {
                            shutdownResult.fail();
                        }
                    }
                );
            return shutdownResult;
        }

        private static class ClientRequestSuccessHandler implements Handler<HttpClientRequest> {

            private final HttpClient client;
            private final String requestURI;
            private final Map<String, String> headers;
            private final boolean compressionEnabled;
            private final String contentType;
            private final int contentLength;
            private final Consumer<Response> onHttpResponseRead;
            private final Consumer<Throwable> onError;
            private final Marshaler marshaler;

            private final int attemptNumber;

            public ClientRequestSuccessHandler(
                HttpClient client,
                String requestURI,
                Map<String, String> headers,
                boolean compressionEnabled,
                String contentType,
                int contentLength,
                Consumer<Response> onHttpResponseRead,
                Consumer<Throwable> onError,
                Marshaler marshaler,
                int attemptNumber
            ) {
                this.client = client;
                this.requestURI = requestURI;
                this.headers = headers;
                this.compressionEnabled = compressionEnabled;
                this.contentType = contentType;
                this.contentLength = contentLength;
                this.onHttpResponseRead = onHttpResponseRead;
                this.onError = onError;
                this.marshaler = marshaler;
                this.attemptNumber = attemptNumber;
            }

            @Override
            public void handle(HttpClientRequest request) {
                HttpClientRequest clientRequest = request
                    .response(
                        new Handler<>() {
                            @Override
                            public void handle(AsyncResult<HttpClientResponse> callResult) {
                                if (callResult.succeeded()) {
                                    HttpClientResponse clientResponse = callResult.result();
                                    clientResponse.body(
                                        new Handler<>() {
                                            @Override
                                            public void handle(AsyncResult<Buffer> bodyResult) {
                                                if (bodyResult.succeeded()) {
                                                    if (clientResponse.statusCode() >= 500) {
                                                        if (attemptNumber <= MAX_ATTEMPTS) {
                                                            // we should retry for 5xx error as they might be recoverable
                                                            initiateSend(
                                                                client,
                                                                requestURI,
                                                                MAX_ATTEMPTS - attemptNumber,
                                                                newAttempt(),
                                                                onError
                                                            );
                                                            return;
                                                        }
                                                    }
                                                    onHttpResponseRead.accept(
                                                        new Response() {
                                                            @Override
                                                            public int statusCode() {
                                                                return clientResponse.statusCode();
                                                            }

                                                            @Override
                                                            public String statusMessage() {
                                                                return clientResponse.statusMessage();
                                                            }

                                                            @Override
                                                            public byte[] responseBody() {
                                                                return bodyResult.result().getBytes();
                                                            }
                                                        }
                                                    );
                                                } else {
                                                    if (attemptNumber <= MAX_ATTEMPTS) {
                                                        // retry
                                                        initiateSend(
                                                            client,
                                                            requestURI,
                                                            MAX_ATTEMPTS - attemptNumber,
                                                            newAttempt(),
                                                            onError
                                                        );
                                                    } else {
                                                        onError.accept(bodyResult.cause());
                                                    }
                                                }
                                            }
                                        }
                                    );
                                } else {
                                    if (attemptNumber <= MAX_ATTEMPTS) {
                                        // retry
                                        initiateSend(client, requestURI, MAX_ATTEMPTS - attemptNumber, newAttempt(), onError);
                                    } else {
                                        onError.accept(callResult.cause());
                                    }
                                }
                            }
                        }
                    )
                    .putHeader("Content-Type", contentType);

                Buffer buffer = Buffer.buffer(contentLength);
                OutputStream os = new BufferOutputStream(buffer);
                if (compressionEnabled) {
                    clientRequest.putHeader("Content-Encoding", "gzip");
                    try (var gzos = new GZIPOutputStream(os)) {
                        marshaler.writeBinaryTo(gzos);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                } else {
                    try {
                        marshaler.writeJsonTo(os);
                    } catch (IOException ioe) {
                        throw new IllegalStateException(ioe);
                    }
                }

                if (!headers.isEmpty()) {
                    for (var entry : headers.entrySet()) {
                        clientRequest.putHeader(entry.getKey(), entry.getValue());
                    }
                }

                clientRequest.send(buffer);
            }

            public ClientRequestSuccessHandler newAttempt() {
                return new ClientRequestSuccessHandler(
                    client,
                    requestURI,
                    headers,
                    compressionEnabled,
                    contentType,
                    contentLength,
                    onHttpResponseRead,
                    onError,
                    marshaler,
                    attemptNumber + 1
                );
            }
        }
    }
}
