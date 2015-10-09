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
 * information: "Portions Copyright [year] [name of copyright owner]".
 *
 * Copyright 2015 ForgeRock AS.
 */
package org.forgerock.openig.filter.oauth2.client;

import static org.forgerock.http.util.Uris.formDecodeParameterNameOrValue;
import static org.forgerock.http.protocol.Response.newResponsePromise;
import static org.forgerock.http.util.Uris.withQuery;
import static org.forgerock.openig.filter.oauth2.client.ClientRegistration.CLIENT_REG_KEY;
import static org.forgerock.openig.filter.oauth2.client.OAuth2Session.stateNew;
import static org.forgerock.openig.filter.oauth2.client.OAuth2Utils.buildUri;
import static org.forgerock.openig.filter.oauth2.client.OAuth2Utils.createAuthorizationNonceHash;
import static org.forgerock.openig.filter.oauth2.client.OAuth2Utils.httpRedirect;
import static org.forgerock.openig.filter.oauth2.client.OAuth2Utils.saveSession;
import static org.forgerock.openig.http.Responses.newInternalServerError;
import static org.forgerock.util.Reject.checkNotNull;
import static org.forgerock.util.Utils.joinAsString;
import static org.forgerock.util.promise.Promises.newResultPromise;

import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.util.List;

import org.forgerock.http.Handler;
import org.forgerock.http.protocol.Form;
import org.forgerock.http.protocol.Request;
import org.forgerock.http.protocol.Response;
import org.forgerock.http.protocol.ResponseException;
import org.forgerock.openig.el.Expression;
import org.forgerock.openig.http.Exchange;
import org.forgerock.services.context.Context;
import org.forgerock.util.Function;
import org.forgerock.util.promise.NeverThrowsException;
import org.forgerock.util.promise.Promise;
import org.forgerock.util.time.TimeService;

/**
 * This terminal Handler has the responsibility to produce a 302 response for
 * the /authorize endpoint of the selected Issuer, configured with the
 * ClientRegistration info:
 *
 * <pre>
 * {@code
 * HTTP/1.1 302 Found
 * Location: https://server.example.com/authorize?           // from Issuer
 *     response_type=code
 *     &scope=openid%20profile%20email                       // from ClientRegistration
 *     &client_id=s6BhdRkqt3                                 // from ClientRegistration
 *     &state=af0ifjsldkj
 *     &redirect_uri=https%3A%2F%2Fclient.example.org%2Fcb   // from Client Registration or if none,
 *                                                              default to loginEndpoint callback URI.
 *     &login_hint=<input if available>                      // login_hint from OPENID RFC.
 * }
 * </pre>
 *
 * @see <a
 *      href="http://openid.net/specs/openid-connect-core-1_0.html#AuthRequest">
 *      OpenID Connect Core 1.0 </a>
 */
class AuthorizationRedirectHandler implements Handler {

    private final ClientRegistration registration;
    private final Expression<String> endpoint;
    private final TimeService timeService;

    AuthorizationRedirectHandler(final TimeService timeService, final Expression<String> endpoint) {
        this(timeService, endpoint, null);
    }

    AuthorizationRedirectHandler(final TimeService timeService,
                                 final Expression<String> endpoint,
                                 final ClientRegistration registration) {
        this.timeService = timeService;
        this.endpoint = checkNotNull(endpoint);
        this.registration = registration;
    }

    @Override
    public Promise<Response, NeverThrowsException> handle(Context context, Request request) {
        final Exchange exchange = context.asContext(Exchange.class);
        final URI clientEndpoint;
        try {
            clientEndpoint = buildUri(exchange, endpoint);
        } catch (ResponseException e) {
            return newResultPromise(e.getResponse());
        }
        String gotoUri = request.getForm().getFirst("goto");
        if (gotoUri == null) {
            gotoUri = exchange.getOriginalUri().toString();
        }
        final ClientRegistration cr = registration != null
                                    ? registration
                                    : (ClientRegistration) exchange.getAttributes().get(CLIENT_REG_KEY);
        final String loginHint = request.getForm().getFirst("discovery");

        if (cr != null && cr.getIssuer() != null) {
            final Issuer issuer = cr.getIssuer();
            final List<String> requestedScopes = cr.getScopes();
            final Form query = new Form();
            query.add("response_type", "code");
            query.add("client_id", cr.getClientId());
            if (cr.getRedirectUris() != null) {
                query.addAll("redirect_uri", cr.getRedirectUris());
            } else {
                query.putSingle("redirect_uri",  buildDefaultCallbackUri(clientEndpoint));
            }
            query.add("scope", joinAsString(" ", requestedScopes));
            if (loginHint != null && !loginHint.isEmpty()) {
                query.add("login_hint", loginHint);
            }

            /*
             * Construct the state parameter whose purpose is to prevent CSRF
             * attacks. The state will be passed back from the authorization
             * server once authorization has completed and the call-back will
             * verify that it received the same state that it sent originally by
             * comparing it with the value stored in the session or cookie
             * (depending on the persistence strategy).
             */
            final String nonce = createAuthorizationNonce();
            final String hash = createAuthorizationNonceHash(nonce);
            query.add("state", createAuthorizationState(hash, gotoUri));

            final String redirect = formDecodeParameterNameOrValue(
                    withQuery(issuer.getAuthorizeEndpoint(), query).toString());
            return newResponsePromise(httpRedirect(redirect)).then(
                    new Function<Response, Response, NeverThrowsException>() {
                        @Override
                        public Response apply(final Response response) {
                            /*
                             * Finally create and save the session. This may involve updating response cookies, so it is
                             * important to do it after creating the response.
                             */
                            try {
                                final String clientUri = clientEndpoint.toString();
                                final OAuth2Session session = stateNew(timeService)
                                                                .stateAuthorizing(cr.getName(),
                                                                                  clientUri,
                                                                                  nonce,
                                                                                  requestedScopes);
                                saveSession(exchange, session, clientEndpoint);
                                return response;
                            } catch (ResponseException e) {
                                return e.getResponse();
                            }
                        }
                    });
        } else {
            return newResultPromise(newInternalServerError("The selected client or its issuer is null. "
                                                           + "Authorization redirect aborted."));
        }
    }

    private String buildDefaultCallbackUri(final URI clientEndpoint) {
        try {
            return new URI(clientEndpoint + "/callback").toString();
        } catch (URISyntaxException ex) {
            // Should never happen
            return null;
        }
    }

    private String createAuthorizationNonce() {
        return new BigInteger(160, new SecureRandom()).toString(Character.MAX_RADIX);
    }

    private String createAuthorizationState(final String hash, final String gotoUri) {
        return gotoUri == null || gotoUri.isEmpty() ? hash : hash + ":" + gotoUri;
    }
}