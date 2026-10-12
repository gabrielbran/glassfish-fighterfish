/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation. All rights reserved.
 * Copyright (c) 2009, 2019 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */
package org.glassfish.osgihttp;

import org.apache.catalina.HttpRequest;
import org.apache.catalina.Request;
import org.apache.catalina.Response;
import org.apache.catalina.valves.ValveBase;
import org.osgi.service.servlet.context.ServletContextHelper;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Serializable;
import java.security.Principal;

/**
 * This valve is used to call
 * {@link ServletContextHelper#handleSecurity} before a servlet is invoked
 * and {@link ServletContextHelper#finishSecurity} after.
 */
public final class OSGiSecurityValve extends ValveBase {

    /**
     * The servlet context helper.
     */
    private final ServletContextHelper httpContext;

    /**
     * Create a new instance.
     * @param ctx the servlet context helper
     */
    public OSGiSecurityValve(final ServletContextHelper ctx) {
        this.httpContext = ctx;
    }

    @Override
    public int invoke(final Request request, final Response response)
            throws IOException, ServletException {

        if (httpContext.handleSecurity(HttpServletRequest.class.cast(request),
                HttpServletResponse.class.cast(response))) {
            // Issue #13283: If user has set username and auth type, we need to
            // map it to appropriate catalina apis
            // so that HttpServletRequest.getRemoteUser() and
            // getAuthenticationType() will return appropriate values.
            mapUser((HttpRequest) request);
            mapAuthType((HttpRequest) request);
            return INVOKE_NEXT;
        } else {
            return END_PIPELINE;
        }
    }

    @Override
    public void postInvoke(final Request request, final Response response)
            throws IOException, ServletException {

        httpContext.finishSecurity(HttpServletRequest.class.cast(request),
                HttpServletResponse.class.cast(response));
    }

    /**
     * Map the authentication type.
     * @param httpRequest the request
     */
    private void mapAuthType(final HttpRequest httpRequest) {
        String authType = (String) httpRequest.getRequest()
                .getAttribute(ServletContextHelper.AUTHENTICATION_TYPE);
        if (authType != null) {
            httpRequest.setAuthType(authType);
        }
    }

    /**
     * Map the remote user.
     * @param httpRequest the request
     */
    private void mapUser(final HttpRequest httpRequest) {
        String userName = (String) httpRequest.getRequest()
                .getAttribute(ServletContextHelper.REMOTE_USER);
        if (userName != null) {
            httpRequest.setUserPrincipal(new UserPrincipal(userName));
        }
    }

    /**
     * Principal for the remote user set by
     * {@link ServletContextHelper#handleSecurity}.
     */
    private static final class UserPrincipal
            implements Principal, Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * The user name.
         */
        private final String name;

        /**
         * Create a new instance.
         * @param userName the user name
         */
        UserPrincipal(final String userName) {
            this.name = userName;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean equals(final Object obj) {
            return obj instanceof UserPrincipal
                    && name.equals(((UserPrincipal) obj).name);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
