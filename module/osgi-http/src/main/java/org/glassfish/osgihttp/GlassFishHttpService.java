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
package org.glassfish.osgihttp;

import com.sun.enterprise.web.WebModule;
import org.apache.catalina.Container;
import org.glassfish.web.valve.GlassFishValve;
import org.osgi.service.servlet.context.ServletContextHelper;

import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Registers servlets in the GlassFish web container.
 *
 * All servlets are added to a single root web module (by default mounted at
 * {@code /osgi}). Servlets registered with the same
 * {@link ServletContextHelper} share the same {@link ServletContext}.
 *
 * This class is used by both the OSGi Servlet Whiteboard runtime and the
 * legacy OSGi HttpService compatibility layer.
 */
public final class GlassFishHttpService {

    /**
     * The root web module.
     */
    private final WebModule context;

    /**
     * The servlet contexts, one per servlet context helper.
     */
    private final Map<ServletContextHelper, OSGiServletContext>
            servletContextMap = new IdentityHashMap<>();

    /**
     * The number of servlets registered per servlet context helper.
     */
    private final Map<ServletContextHelper, Integer> servletContextUsage =
            new IdentityHashMap<>();

    /**
     * Create a new instance.
     * @param ctx the root web module
     */
    public GlassFishHttpService(final WebModule ctx) {
        this.context = ctx;
    }

    /**
     * Get the root web module.
     * @return WebModule
     */
    public WebModule getWebModule() {
        return context;
    }

    /**
     * Get the context path of the root web module.
     * @return context path
     */
    public String getContextPath() {
        return context.getPath();
    }

    /**
     * Get (and create if needed) the servlet context for the given helper.
     * @param helper the servlet context helper
     * @return the servlet context
     */
    public synchronized OSGiServletContext getServletContext(
            final ServletContextHelper helper) {

        OSGiServletContext servletContext = servletContextMap.get(helper);
        if (servletContext == null) {
            servletContext = new OSGiServletContext(context, helper);
            servletContextMap.put(helper, servletContext);
        }
        return servletContext;
    }

    /**
     * Release the servlet context for the given helper.
     * @param helper the servlet context helper
     */
    public synchronized void releaseServletContext(
            final ServletContextHelper helper) {

        if (!servletContextUsage.containsKey(helper)) {
            servletContextMap.remove(helper);
        }
    }

    /**
     * Register a servlet.
     * @param name the unique name of the servlet registration, also used as
     * servlet name
     * @param patterns the URL patterns, relative to the root web module
     * @param servlet the servlet
     * @param initParams the init parameters
     * @param helper the servlet context helper
     * @throws ServletException if the servlet fails to initialize
     * @throws IllegalStateException if the name or one of the patterns is
     * already in use
     */
    public void registerServlet(final String name, final String[] patterns,
            final Servlet servlet, final Map<String, String> initParams,
            final ServletContextHelper helper) throws ServletException {

        registerServlet(name, name, patterns, servlet, initParams, helper);
    }

    /**
     * Register a servlet.
     * @param name the unique name of the servlet registration
     * @param servletName the servlet name exposed by the servlet config
     * @param patterns the URL patterns, relative to the root web module
     * @param servlet the servlet
     * @param initParams the init parameters
     * @param helper the servlet context helper
     * @throws ServletException if the servlet fails to initialize
     * @throws IllegalStateException if the name or one of the patterns is
     * already in use
     */
    public synchronized void registerServlet(final String name,
            final String servletName, final String[] patterns,
            final Servlet servlet,
            final Map<String, String> initParams,
            final ServletContextHelper helper) throws ServletException {

        // the name is part of the JMX object name of the servlet, which the
        // web container needs to map requests to the servlet
        if (name.indexOf(':') >= 0 || name.indexOf(',') >= 0
                || name.indexOf('=') >= 0) {
            throw new IllegalArgumentException("invalid name: " + name);
        }
        if (getWrapper(name) != null) {
            throw new IllegalStateException("name already registered: "
                    + name);
        }
        for (String pattern : patterns) {
            if (isPatternInUse(pattern)) {
                throw new IllegalStateException("pattern already in use: "
                        + pattern);
            }
        }
        OSGiServletContext servletContext = getServletContext(helper);
        OSGiServletConfig servletConfig = new OSGiServletConfig(servletName,
                servletContext.getServletContext(),
                initParams == null
                        ? Collections.<String, String>emptyMap()
                        : initParams);
        OSGiServletWrapper wrapper = new OSGiServletWrapper(name, servlet,
                servletConfig, patterns, servletContext);
        wrapper.addValve((GlassFishValve) new OSGiSecurityValve(helper));
        context.addChild(wrapper);
        try {
            wrapper.initializeServlet();
        } catch (ServletException | RuntimeException e) {
            context.removeChild(wrapper);
            releaseServletContext(helper);
            throw e;
        }
        servletContextUsage.merge(helper, 1, Integer::sum);
    }

    /**
     * Unregister a servlet.
     * @param name the name of the servlet registration
     * @param callDestroy {@code true} if {@code Servlet.destroy} must be
     * called
     * @return {@code true} if a registration was removed
     */
    public synchronized boolean unregister(final String name,
            final boolean callDestroy) {

        OSGiServletWrapper wrapper = getWrapper(name);
        if (wrapper == null) {
            return false;
        }
        context.removeChild(wrapper);
        if (callDestroy) {
            wrapper.destroyServlet();
        }
        ServletContextHelper helper = wrapper.getServletContextHelper();
        Integer count = servletContextUsage.get(helper);
        if (count != null && count > 1) {
            servletContextUsage.put(helper, count - 1);
        } else {
            servletContextUsage.remove(helper);
            releaseServletContext(helper);
        }
        return true;
    }

    /**
     * Test if a servlet registration exists.
     * @param name the name of the servlet registration
     * @return {@code true} if a registration exists
     */
    public synchronized boolean isRegistered(final String name) {
        return getWrapper(name) != null;
    }

    /**
     * Test if a servlet instance is already registered.
     * @param servlet the servlet instance
     * @return {@code true} if the servlet is registered
     */
    public synchronized boolean isRegistered(final Servlet servlet) {
        for (Container c : context.findChildren()) {
            if (c instanceof OSGiServletWrapper
                    && servlet == OSGiServletWrapper.class.cast(c)
                            .getServlet()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Test if a URL pattern is already mapped.
     * @param pattern the URL pattern
     * @return {@code true} if the pattern is mapped
     */
    public synchronized boolean isPatternInUse(final String pattern) {
        return context.findServletMapping(pattern) != null;
    }

    /**
     * Get the wrapper for a given registration name.
     * @param name the registration name
     * @return OSGiServletWrapper or {@code null} if not found
     */
    private OSGiServletWrapper getWrapper(final String name) {
        Container c = context.findChild(name);
        if (c instanceof OSGiServletWrapper) {
            return (OSGiServletWrapper) c;
        }
        return null;
    }
}
