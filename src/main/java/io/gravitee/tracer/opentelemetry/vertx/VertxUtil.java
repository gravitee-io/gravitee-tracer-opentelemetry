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
package io.gravitee.tracer.opentelemetry.vertx;

import io.vertx.core.http.HttpServerRequest;
import java.util.regex.Pattern;

public final class VertxUtil {

    private static final String X_FORWARDED_FOR = "X-Forwarded-For";
    private static final Pattern FORWARDED_FOR_PATTERN = Pattern.compile("for=\"?([^;,\"]+)\"?");
    private static final String FORWARDED = "Forwarded";
    private static final String COMMA_SPLITTER = ",";
    private static final String COLON_SPLITTER = ":";
    private static final int SPLIT_LIMIT = -1;

    private VertxUtil() {}

    private static String getForwardedHeaderValue(HttpServerRequest httpServerRequest) {
        var forwardedHeader = httpServerRequest.getHeader(FORWARDED);
        if (forwardedHeader == null) {
            return null;
        }
        var forwardedHeaderMatcher = FORWARDED_FOR_PATTERN.matcher(forwardedHeader);
        if (forwardedHeaderMatcher.find()) {
            return forwardedHeaderMatcher.group(1).trim();
        }
        return null;
    }

    private static String getXForwardedHeaderValue(HttpServerRequest httpServerRequest) {
        var xForwardedForHeader = httpServerRequest.getHeader(X_FORWARDED_FOR);
        if (xForwardedForHeader == null) {
            return null;
        }
        return xForwardedForHeader.split(COMMA_SPLITTER, SPLIT_LIMIT)[0];
    }

    private static String getHostHeader(HttpServerRequest httpRequest) {
        String header = httpRequest.getHeader("host");
        if (header == null) {
            return null;
        }
        return header.split(COLON_SPLITTER, SPLIT_LIMIT)[0];
    }

    private static String getHostPortHeader(HttpServerRequest httpRequest) {
        String header = httpRequest.getHeader("host");
        if (header == null) {
            return null;
        }
        String[] headerValues = header.split(COLON_SPLITTER, SPLIT_LIMIT);
        return headerValues.length > 1 ? headerValues[1] : null;
    }

    public static String extractClientIP(HttpServerRequest httpServerRequest) {
        // Tries to fetch Forwarded first since X-Forwarded can be lost by a proxy
        // If Forwarded is not there tries to fetch the X-Forwarded-For header
        // If none is found resorts to the remote address from the http request

        var forwardedHeaderValue = getForwardedHeaderValue(httpServerRequest);
        if (forwardedHeaderValue != null) {
            return forwardedHeaderValue;
        }
        var xForwardedHeaderValue = getXForwardedHeaderValue(httpServerRequest);
        if (xForwardedHeaderValue != null) {
            return xForwardedHeaderValue;
        }
        return httpServerRequest.remoteAddress() != null ? httpServerRequest.remoteAddress().host() : null;
    }

    public static String extractRemoteHostname(HttpServerRequest httpRequest) {
        String hostname = getHostHeader(httpRequest);
        if (hostname != null) {
            return hostname;
        }
        return httpRequest.remoteAddress() != null ? httpRequest.remoteAddress().hostName() : null;
    }

    public static Long extractRemoteHostPort(HttpServerRequest httpRequest) {
        String portString = getHostPortHeader(httpRequest);
        if (portString != null) {
            try {
                return Long.parseLong(portString);
            } catch (NumberFormatException e) {
                //ignore
            }
        }
        if (httpRequest.remoteAddress() != null) {
            return Integer.toUnsignedLong(httpRequest.remoteAddress().port());
        }
        return null;
    }
}
