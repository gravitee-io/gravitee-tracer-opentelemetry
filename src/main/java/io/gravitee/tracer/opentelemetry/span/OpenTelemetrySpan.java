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
package io.gravitee.tracer.opentelemetry.span;

import io.gravitee.tracing.api.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class OpenTelemetrySpan implements Span {

    private final io.opentelemetry.api.trace.Span span;
    private final Scope scope;

    public OpenTelemetrySpan(final io.opentelemetry.api.trace.Span span, Scope scope) {
        this.span = span;
        this.scope = scope;
    }

    @Override
    public Span withAttribute(String name, String value) {
        span.setAttribute(name, value);
        return this;
    }

    @Override
    public Span withAttribute(String name, boolean value) {
        span.setAttribute(name, value);
        return this;
    }

    @Override
    public Span withAttribute(String name, long value) {
        span.setAttribute(name, value);
        return this;
    }

    @Override
    public Span reportError(Throwable throwable) {
        span.recordException(throwable);
        span.setStatus(StatusCode.ERROR, throwable.getMessage());
        return this;
    }

    @Override
    public Span reportError(String message) {
        span.setStatus(StatusCode.ERROR, message);
        return this;
    }

    @Override
    public void end() {
        span.end();
        scope.close();
    }
}
