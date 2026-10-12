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
package org.glassfish.osgihttp.whiteboard;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;

/**
 * Servlet registered in the web container for a whiteboard servlet or
 * resource service.
 *
 * It invokes the whiteboard filters matching the request and notifies the
 * whiteboard {@link ServletRequestListener} services of the servlet context
 * before delegating to the actual servlet.
 */
final class WhiteboardServlet implements Servlet {

    /**
     * The runtime.
     */
    private final WhiteboardRuntime runtime;

    /**
     * The servlet context id.
     */
    private final long contextId;

    /**
     * The servlet name, used to match filters by servlet name.
     */
    private final String servletName;

    /**
     * The actual servlet.
     */
    private final Servlet delegate;

    /**
     * The servlet config.
     */
    private volatile ServletConfig config;

    /**
     * Create a new instance.
     * @param rt the runtime
     * @param ctxId the servlet context id
     * @param name the servlet name
     * @param servlet the actual servlet
     */
    WhiteboardServlet(final WhiteboardRuntime rt, final long ctxId,
            final String name, final Servlet servlet) {

        this.runtime = rt;
        this.contextId = ctxId;
        this.servletName = name;
        this.delegate = servlet;
    }

    /**
     * Get the actual servlet.
     * @return Servlet
     */
    Servlet getDelegate() {
        return delegate;
    }

    @Override
    public void init(final ServletConfig servletConfig)
            throws ServletException {

        this.config = servletConfig;
        delegate.init(servletConfig);
    }

    @Override
    public ServletConfig getServletConfig() {
        return config;
    }

    @Override
    public void service(final ServletRequest req, final ServletResponse res)
            throws ServletException, IOException {

        String path = requestPath(req);
        List<Filter> filters = runtime.getFilters(contextId, servletName,
                path);
        List<ServletRequestListener> listeners =
                runtime.getRequestListeners(contextId);
        ServletContext sc = config == null
                ? null
                : config.getServletContext();
        ServletRequestEvent event = new ServletRequestEvent(sc, req);
        for (ServletRequestListener l : listeners) {
            l.requestInitialized(event);
        }
        try {
            new Chain(filters.iterator()).doFilter(req, res);
        } finally {
            for (int i = listeners.size() - 1; i >= 0; i--) {
                listeners.get(i).requestDestroyed(event);
            }
        }
    }

    @Override
    public String getServletInfo() {
        return delegate.getServletInfo();
    }

    @Override
    public void destroy() {
        delegate.destroy();
    }

    /**
     * Compute the path of a request, relative to the root web module.
     * @param req the request
     * @return path
     */
    private static String requestPath(final ServletRequest req) {
        if (!(req instanceof HttpServletRequest)) {
            return "/";
        }
        HttpServletRequest hreq = (HttpServletRequest) req;
        String servletPath = hreq.getServletPath();
        String pathInfo = hreq.getPathInfo();
        String path = (servletPath == null ? "" : servletPath)
                + (pathInfo == null ? "" : pathInfo);
        return path.isEmpty() ? "/" : path;
    }

    /**
     * Filter chain ending with the actual servlet.
     */
    private final class Chain implements FilterChain {

        /**
         * The remaining filters.
         */
        private final Iterator<Filter> filters;

        /**
         * Create a new instance.
         * @param it the filters
         */
        Chain(final Iterator<Filter> it) {
            this.filters = it;
        }

        @Override
        public void doFilter(final ServletRequest req,
                final ServletResponse res)
                throws IOException, ServletException {

            if (filters.hasNext()) {
                filters.next().doFilter(req, res, this);
            } else {
                delegate.service(req, res);
            }
        }
    }
}
