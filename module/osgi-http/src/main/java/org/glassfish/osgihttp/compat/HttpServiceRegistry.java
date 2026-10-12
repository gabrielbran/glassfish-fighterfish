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

import org.glassfish.osgihttp.GlassFishHttpService;
import org.glassfish.osgihttp.OSGiResourceServlet;
import org.osgi.service.http.HttpContext;
import org.osgi.service.http.NamespaceException;

import jakarta.servlet.Servlet;
import jakarta.servlet.ServletException;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * State shared by all the {@link HttpServiceWrapper} instances of a virtual
 * server.
 *
 * Aliases are global to the HttpService, a servlet instance can only be
 * registered once, and all the registrations made with the same
 * {@link HttpContext} share the same servlet context.
 */
final class HttpServiceRegistry {

    /**
     * Prefix of the registration names, distinguishing them from the
     * registrations made by the whiteboard runtime.
     */
    private static final String NAME_PREFIX = "httpservice";

    /**
     * The underlying service.
     */
    private final GlassFishHttpService delegate;

    /**
     * The adapted HTTP contexts.
     */
    private final Map<HttpContext, HttpContextHelper> helpers =
            new IdentityHashMap<HttpContext, HttpContextHelper>();

    /**
     * The HTTP context of each alias.
     */
    private final Map<String, HttpContext> aliases =
            new HashMap<String, HttpContext>();

    /**
     * The registered servlet instances.
     */
    private final Set<Object> servlets = Collections.newSetFromMap(
            new IdentityHashMap<Object, Boolean>());

    /**
     * The servlet instance of each alias, if any.
     */
    private final Map<String, Object> aliasServlets =
            new HashMap<String, Object>();

    /**
     * Create a new instance.
     * @param gfHttpService the underlying service
     */
    HttpServiceRegistry(final GlassFishHttpService gfHttpService) {
        this.delegate = gfHttpService;
    }

    /**
     * Get the underlying service.
     * @return GlassFishHttpService
     */
    GlassFishHttpService getGlassFishHttpService() {
        return delegate;
    }

    /**
     * Register a servlet.
     * @param alias the alias
     * @param servlet the adapted servlet
     * @param original the servlet given by the client
     * @param initParams the init parameters
     * @param ctx the HTTP context
     * @throws ServletException if the servlet fails to initialize or is
     * already registered
     * @throws NamespaceException if the alias is already registered
     */
    synchronized void registerServlet(final String alias,
            final Servlet servlet, final Object original,
            final Map<String, String> initParams, final HttpContext ctx)
            throws ServletException, NamespaceException {

        validateAlias(alias);
        if (servlets.contains(original)) {
            throw new ServletException("servlet is already registered");
        }
        // Unlike web.xml, here there is no notion of Servlet name,
        // so we use the alias as the servlet name. It is unique, so no issues.
        register(alias, alias, servlet, initParams, ctx);
        servlets.add(original);
        aliasServlets.put(alias, original);
    }

    /**
     * Register resources.
     * @param alias the alias
     * @param name the internal name
     * @param ctx the HTTP context
     * @throws NamespaceException if the alias is already registered
     */
    synchronized void registerResources(final String alias,
            final String name, final HttpContext ctx)
            throws NamespaceException {

        validateAlias(alias);
        if (name == null || (name.endsWith("/") && !"/".equals(name))) {
            throw new IllegalArgumentException("malformed name");
        }
        HttpContextHelper helper = getHelper(ctx);
        try {
            register(alias, alias, new OSGiResourceServlet(alias, name,
                    helper), null, ctx);
        } catch (ServletException e) {
            throw new IllegalStateException(e); // should never happen
        }
    }

    /**
     * Unregister an alias.
     * @param alias the alias
     * @param callDestroy {@code true} if {@code Servlet.destroy} must be
     * called
     */
    synchronized void unregister(final String alias,
            final boolean callDestroy) {

        delegate.unregister(NAME_PREFIX + alias, callDestroy);
        servlets.remove(aliasServlets.remove(alias));
        HttpContext ctx = aliases.remove(alias);
        if (ctx != null && !aliases.containsValue(ctx)) {
            helpers.remove(ctx);
        }
    }

    /**
     * Register a servlet with the underlying service.
     * @param alias the alias
     * @param servletName the servlet name
     * @param servlet the servlet
     * @param initParams the init parameters
     * @param ctx the HTTP context
     * @throws ServletException if the servlet fails to initialize
     * @throws NamespaceException if the alias is already in use
     */
    private void register(final String alias, final String servletName,
            final Servlet servlet, final Map<String, String> initParams,
            final HttpContext ctx)
            throws ServletException, NamespaceException {

        HttpContextHelper helper = getHelper(ctx);
        aliases.put(alias, ctx);
        try {
            delegate.registerServlet(NAME_PREFIX + alias, servletName,
                    new String[]{convert(alias)}, servlet, initParams,
                    helper);
        } catch (IllegalStateException e) {
            aliases.remove(alias);
            throw new NamespaceException(e.getMessage());
        } catch (ServletException | RuntimeException e) {
            aliases.remove(alias);
            if (!aliases.containsValue(ctx)) {
                helpers.remove(ctx);
            }
            throw e;
        }
    }

    /**
     * Get the adapter for an HTTP context.
     * @param ctx the HTTP context
     * @return HttpContextHelper
     */
    private HttpContextHelper getHelper(final HttpContext ctx) {
        HttpContextHelper helper = helpers.get(ctx);
        if (helper == null) {
            helper = new HttpContextHelper(ctx);
            helpers.put(ctx, helper);
        }
        return helper;
    }

    /**
     * Validate an alias.
     * @param alias the alias
     * @throws NamespaceException if the alias is already registered
     */
    private void validateAlias(final String alias)
            throws NamespaceException {

        if (alias == null || (!alias.equals("/")
                && (!alias.startsWith("/") || alias.endsWith("/")))) {
            throw new IllegalArgumentException("malformed alias");
        }
        if (aliases.containsKey(alias)) {
            throw new NamespaceException("alias already registered");
        }
    }

    /**
     * Convert an alias to a URL pattern.
     * @param alias the alias
     * @return URL pattern
     */
    private static String convert(final String alias) {
        if (alias.equals("/")) {
            return "/*";
        } else {
            return alias + "/*";
        }
    }
}
