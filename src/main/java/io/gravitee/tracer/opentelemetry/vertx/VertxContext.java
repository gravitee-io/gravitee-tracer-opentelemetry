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

import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.impl.ContextInternal;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class VertxContext {

    /**
     * Checks if the given context is a duplicated context.
     *
     * @param context the context, must not be {@code null}
     * @return {@code true} if the given context is a duplicated context, {@code false} otherwise.
     */
    public static boolean isDuplicatedContext(Context context) {
        return ((ContextInternal) context).isDuplicate();
    }

    /**
     * Checks if the current context is a duplicated context.
     * If the method is called from a Vert.x thread, it retrieves the current context and checks if it's a duplicated
     * context. Otherwise, it returns false.
     *
     * @return {@code true} if the method is called from a duplicated context, {@code false} otherwise.
     */
    public static boolean isOnDuplicatedContext() {
        Context context = Vertx.currentContext();
        return context != null && isDuplicatedContext(context);
    }

    /**
     * Creates a new duplicated context, even if the passed one is already a duplicated context.
     * If the passed context is {@code null}, it returns {@code null}
     *
     * @return a new duplicated context created from the given context, {@code null} is the passed context is {@code null}
     */
    public static Context createNewDuplicatedContext(Context context) {
        if (context == null) {
            return null;
        }
        // This creates a duplicated context from the root context of the current duplicated context (if that's one)
        return ((ContextInternal) context).duplicate();
    }
}
