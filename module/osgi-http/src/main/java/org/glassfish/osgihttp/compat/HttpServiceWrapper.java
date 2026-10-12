/*
 * Copyright (c) 2009, 2019 Oracle and/or its affiliates. All rights reserved.
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

import org.apache.felix.http.jakartawrappers.ServletWrapper;
import org.apache.felix.http.javaxwrappers.ServletExceptionUtil;
import org.osgi.framework.Bundle;
import org.osgi.service.http.HttpContext;
import org.osgi.service.http.HttpService;
import org.osgi.service.http.NamespaceException;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Legacy OSGi {@link HttpService} (javax.servlet based) for one client
 * bundle, implemented on top of the jakarta.servlet based
 * {@link org.glassfish.osgihttp.GlassFishHttpService}.
 *
 * javax.servlet objects are adapted with the Apache Felix javax/jakarta
 * servlet wrappers.
 */
public final class HttpServiceWrapper implements HttpService {

    /**
     * The shared registry of the HttpService compatibility layer.
     */
    private final HttpServiceRegistry registry;

    /**
     * The bundle using this service.
     */
    private final Bundle registeringBundle;

    /**
     * The aliases registered by the client bundle.
     */
    private final Set<String> aliases = new HashSet<String>();

    /**
     * Create a new instance.
     * @param reg the shared registry
     * @param bnd the client bundle
     */
    HttpServiceWrapper(final HttpServiceRegistry reg, final Bundle bnd) {
        this.registry = reg;
        this.registeringBundle = bnd;
    }

    @Override
    public HttpContext createDefaultHttpContext() {
        return new DefaultHttpContext(registeringBundle);
    }

    @Override
    @SuppressWarnings("rawtypes")
    public synchronized void registerServlet(final String alias,
            final Servlet servlet, final Dictionary initParams,
            final HttpContext httpContext)
            throws ServletException, NamespaceException {

        if (servlet == null) {
            throw new IllegalArgumentException("servlet is null");
        }
        HttpContext ctx;
        if (httpContext != null) {
            ctx = httpContext;
        } else {
            ctx = createDefaultHttpContext();
        }
        try {
            registry.registerServlet(alias, new ServletWrapper(servlet),
                    servlet, toMap(initParams), ctx);
        } catch (jakarta.servlet.ServletException e) {
            throw ServletExceptionUtil.getServletException(e);
        }
        aliases.add(alias);
    }

    @Override
    public synchronized void registerResources(final String alias,
            final String name, final HttpContext httpContext)
            throws NamespaceException {

        HttpContext ctx;
        if (httpContext != null) {
            ctx = httpContext;
        } else {
            ctx = createDefaultHttpContext();
        }
        registry.registerResources(alias, name, ctx);
        aliases.add(alias);
    }

    @Override
    public synchronized void unregister(final String alias) {
        if (!aliases.contains(alias)) {
            throw new IllegalArgumentException(
                    "No registration exists for " + alias);
        }
        registry.unregister(alias, true);
        aliases.remove(alias);
    }

    /**
     * Unregister all the aliases registered by the client bundle, without
     * calling {@code Servlet.destroy}.
     */
    synchronized void unregisterAll() {
        for (String alias : aliases.toArray(new String[0])) {
            registry.unregister(alias, false);
        }
        aliases.clear();
    }

    /**
     * Convert the servlet init parameters.
     * @param initParams the init parameters
     * @return map
     */
    @SuppressWarnings("rawtypes")
    private static Map<String, String> toMap(final Dictionary initParams) {
        if (initParams == null) {
            return Collections.emptyMap();
        }
        Map<String, String> map = new HashMap<String, String>();
        Enumeration e = initParams.keys();
        while (e.hasMoreElements()) {
            Object key = e.nextElement();
            Object value = initParams.get(key);
            map.put(String.valueOf(key),
                    value == null ? null : String.valueOf(value));
        }
        return map;
    }
}
