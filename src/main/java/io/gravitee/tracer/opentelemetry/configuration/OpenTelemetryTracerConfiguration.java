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
package io.gravitee.tracer.opentelemetry.configuration;

import io.gravitee.common.util.EnvironmentUtils;
import java.util.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class OpenTelemetryTracerConfiguration {

    @Value("${services.tracing.otel.traces.enabled:true}")
    private boolean tracesEnabled = true;

    @Value("${services.tracing.otel.metrics.enabled:false}")
    private boolean metricsEnabled = false;

    @Value("${services.tracing.otel.ssl.enabled:false}")
    private boolean sslEnabled = false;

    /**
     * OpenTelemetry collector ssl keystore type. (jks, pkcs12,)
     */
    @Value("${services.tracing.otel.ssl.keystore.type:#{null}}")
    private String keystoreType;

    /**
     * OpenTelemetry collector ssl keystore path.
     */
    @Value("${services.tracing.otel.ssl.keystore.path:#{null}}")
    private String keystorePath;

    /**
     * OpenTelemetry collector ssl keystore password.
     */
    @Value("${services.tracing.otel.ssl.keystore.password:#{null}}")
    private String keystorePassword;

    /**
     * OpenTelemetry collector ssl pem certs paths
     */
    private List<String> keystorePemCerts;

    /**
     * OpenTelemetry collector ssl pem keys paths
     */
    private List<String> keystorePemKeys;

    /**
     * OpenTelemetry collector ssl truststore trustall.
     */
    @Value("${services.tracing.otel.ssl.trustall:false}")
    private boolean trustAll = false;

    /**
     * OpenTelemetry collector ssl truststore hostname verifier.
     */
    @Value("${services.tracing.otel.ssl.verifyHostname:true}")
    private boolean hostnameVerifier = true;

    /**
     * OpenTelemetry collector ssl truststore type.
     */
    @Value("${services.tracing.otel.ssl.truststore.type:#{null}}")
    private String truststoreType;

    /**
     * OpenTelemetry collector ssl truststore path.
     */
    @Value("${services.tracing.otel.ssl.truststore.path:#{null}}")
    private String truststorePath;

    /**
     * OpenTelemetry collector ssl truststore password.
     */
    @Value("${services.tracing.otel.ssl.truststore.password:#{null}}")
    private String truststorePassword;

    @Value("${services.tracing.otel.url:grpc://localhost:4317}")
    private String url;

    // Currently, only {@code grpc} and {@code http/protobuf} are allowed.
    @Value("${services.tracing.otel.type:grpc}")
    private String type;

    @Value("${services.tracing.otel.compression:none}")
    private String compressionType;

    /**
     * Sets the maximum time to wait for the collector to process an exported batch of spans. If
     * unset, 10s.
     */
    @Value("${services.tracing.otel.timeout:10000}")
    private int timeout;

    private Map<String, String> customHeaders;

    private ConfigurableEnvironment environment;

    @Autowired
    public OpenTelemetryTracerConfiguration(ConfigurableEnvironment environment) {
        this.environment = environment;
    }

    public List<String> getKeystorePemCerts() {
        if (keystorePemCerts == null) {
            keystorePemCerts = initializeKeystorePemCerts("services.tracing.otel.ssl.keystore.certs[%s]");
        }

        return keystorePemCerts;
    }

    private List<String> initializeKeystorePemCerts(String property) {
        String key = String.format(property, 0);
        List<String> values = new ArrayList<>();

        while (environment.containsProperty(key)) {
            values.add(environment.getProperty(key));
            key = String.format(property, values.size());
        }

        return values;
    }

    public List<String> getKeystorePemKeys() {
        if (keystorePemKeys == null) {
            keystorePemKeys = initializeKeystorePemCerts("services.tracing.otel.ssl.keystore.keys[%s]");
        }

        return keystorePemKeys;
    }

    private final String CUSTOM_HEADERS_KEY = "services.tracing.otel.headers";

    public Map<String, String> getCustomHeaders() {
        if (customHeaders == null) {
            customHeaders = new HashMap<>();

            EnvironmentUtils
                .getPropertiesStartingWith(environment, CUSTOM_HEADERS_KEY)
                .forEach((s, o) -> customHeaders.put(s.substring(CUSTOM_HEADERS_KEY.length() + 1), o.toString()));
        }

        return customHeaders;
    }

    public class Protocol {

        public static final String GRPC = "grpc";
        public static final String HTTP_PROTOBUF = "http/protobuf";
    }
}
