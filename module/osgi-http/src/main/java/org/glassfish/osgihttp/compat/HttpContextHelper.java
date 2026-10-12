/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation. All rights reserved.
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
package org.glassfish.osgihttp.compat;

import org.apache.felix.http.javaxwrappers.HttpServletRequestWrapper;
import org.apache.felix.http.javaxwrappers.HttpServletResponseWrapper;
import org.osgi.service.http.HttpContext;
import org.osgi.service.servlet.context.ServletContextHelper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URL;

/**
 * Adapts a legacy (javax.servlet based) {@link HttpContext} to a
 * {@link ServletContextHelper}.
 */
final class HttpContextHelper extends ServletContextHelper {

    /**
     * The adapted HTTP context.
     */
    private final HttpContext httpContext;

    /**
     * Create a new instance.
     * @param ctx the HTTP context to adapt
     */
    HttpContextHelper(final HttpContext ctx) {
        this.httpContext = ctx;
    }

    @Override
    public boolean handleSecurity(final HttpServletRequest request,
            final HttpServletResponse response) throws IOException {

        return httpContext.handleSecurity(
                new HttpServletRequestWrapper(request),
                new HttpServletResponseWrapper(response));
    }

    @Override
    public URL getResource(final String name) {
        return httpContext.getResource(name);
    }

    @Override
    public String getMimeType(final String name) {
        return httpContext.getMimeType(name);
    }
}
