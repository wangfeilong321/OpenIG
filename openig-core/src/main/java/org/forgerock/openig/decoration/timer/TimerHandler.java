/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2014-2015 ForgeRock AS.
 */

package org.forgerock.openig.decoration.timer;

import org.forgerock.http.Handler;
import org.forgerock.http.protocol.Request;
import org.forgerock.http.protocol.Response;
import org.forgerock.openig.log.LogTimer;
import org.forgerock.openig.log.Logger;
import org.forgerock.services.context.Context;
import org.forgerock.util.promise.NeverThrowsException;
import org.forgerock.util.promise.Promise;

/**
 * Log a {@literal started} message when a {@link Request} is flowing into this Handler and an {@literal elapsed}
 * message when the {@link Response} is flowing out, delegating to a given encapsulated {@link Handler} instance.
 */
class TimerHandler implements Handler {
    private final Handler delegate;
    private final Logger logger;

    public TimerHandler(final Handler delegate, final Logger logger) {
        this.delegate = delegate;
        this.logger = logger;
    }

    @Override
    public Promise<Response, NeverThrowsException> handle(final Context context, final Request request) {
        final LogTimer timer = logger.getTimer().start();
        return delegate.handle(context, request)
                .thenAlways(new Runnable() {
                    @Override
                    public void run() {
                        timer.stop();
                    }
                });
    }
}
