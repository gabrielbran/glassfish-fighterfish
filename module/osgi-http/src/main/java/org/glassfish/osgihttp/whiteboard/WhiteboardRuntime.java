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

import org.glassfish.osgihttp.GlassFishHttpService;
import org.glassfish.osgihttp.OSGiResourceServlet;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.Filter;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.ServiceObjects;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.dto.ServiceReferenceDTO;
import org.osgi.service.servlet.context.ServletContextHelper;
import org.osgi.service.servlet.runtime.HttpServiceRuntime;
import org.osgi.service.servlet.runtime.HttpServiceRuntimeConstants;
import org.osgi.service.servlet.runtime.dto.DTOConstants;
import org.osgi.service.servlet.runtime.dto.ErrorPageDTO;
import org.osgi.service.servlet.runtime.dto.FailedErrorPageDTO;
import org.osgi.service.servlet.runtime.dto.FailedFilterDTO;
import org.osgi.service.servlet.runtime.dto.FailedListenerDTO;
import org.osgi.service.servlet.runtime.dto.FailedPreprocessorDTO;
import org.osgi.service.servlet.runtime.dto.FailedResourceDTO;
import org.osgi.service.servlet.runtime.dto.FailedServletContextDTO;
import org.osgi.service.servlet.runtime.dto.FailedServletDTO;
import org.osgi.service.servlet.runtime.dto.FilterDTO;
import org.osgi.service.servlet.runtime.dto.ListenerDTO;
import org.osgi.service.servlet.runtime.dto.PreprocessorDTO;
import org.osgi.service.servlet.runtime.dto.RequestInfoDTO;
import org.osgi.service.servlet.runtime.dto.ResourceDTO;
import org.osgi.service.servlet.runtime.dto.RuntimeDTO;
import org.osgi.service.servlet.runtime.dto.ServletContextDTO;
import org.osgi.service.servlet.runtime.dto.ServletDTO;
import org.osgi.service.servlet.whiteboard.HttpWhiteboardConstants;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;

import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequestListener;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Implementation of the OSGi Whiteboard Specification for Jakarta Servlet
 * (OSGi Compendium R8.1, chapter 140) on top of the GlassFish web container.
 *
 * Supported: servlet context helpers (with context path and init
 * parameters), servlets, resources, filters (REQUEST dispatcher, URL
 * patterns, servlet names and regular expressions),
 * {@link ServletContextListener} and {@link ServletRequestListener}
 * listeners, the {@code osgi.http.whiteboard.target} property and the
 * {@link HttpServiceRuntime} service with its DTOs.
 *
 * Not supported (reported as failure DTOs or ignored): error pages,
 * preprocessors, the other listener types, asynchronous and multipart
 * servlets. A whiteboard service is bound to the highest ranked servlet
 * context helper matching its {@code osgi.http.whiteboard.context.select}
 * filter only.
 *
 * All the servlets are mounted below the context path of the root web
 * module of {@link GlassFishHttpService} (by default {@code /osgi}); the
 * path of a servlet context helper is prepended to the patterns of the
 * services bound to it.
 */
public final class WhiteboardRuntime {

    /**
     * The logger.
     */
    private static final Logger LOGGER = Logger.getLogger(
            WhiteboardRuntime.class.getPackage().getName());

    /**
     * Default value of the context select property.
     */
    private static final String DEFAULT_CONTEXT_SELECT = "("
            + HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "="
            + HttpWhiteboardConstants.HTTP_WHITEBOARD_DEFAULT_CONTEXT_NAME
            + ")";

    /**
     * Pattern for valid servlet context names (symbolic names).
     */
    private static final Pattern CONTEXT_NAME_PATTERN =
            Pattern.compile("[0-9a-zA-Z_\\-]+(\\.[0-9a-zA-Z_\\-]+)*");

    /**
     * Pattern for valid servlet context paths.
     */
    private static final Pattern CONTEXT_PATH_PATTERN =
            Pattern.compile("/|(/[0-9a-zA-Z_\\-.~%!$&'()*+,;=:@]+)+");

    /**
     * Supported listener types.
     */
    private static final Class<?>[] LISTENER_TYPES = {
        ServletContextListener.class,
        ServletRequestListener.class,
    };

    /**
     * Kinds of whiteboard services.
     */
    private enum Kind {
        /**
         * Listener.
         */
        LISTENER,
        /**
         * Filter.
         */
        FILTER,
        /**
         * Servlet.
         */
        SERVLET,
        /**
         * Resource.
         */
        RESOURCE
    }

    /**
     * The bundle context of the runtime.
     */
    private final BundleContext bctx;

    /**
     * Used to register servlets in the web container.
     */
    private final GlassFishHttpService http;

    /**
     * The trackers.
     */
    private final List<ServiceTracker<?, ?>> trackers =
            new ArrayList<ServiceTracker<?, ?>>();

    /**
     * The tracked servlet context helpers.
     */
    private final Map<ServiceReference<?>, ContextInfo> contexts =
            new HashMap<ServiceReference<?>, ContextInfo>();

    /**
     * The tracked whiteboard services, keyed by kind and service id.
     */
    private final Map<String, Element> elements =
            new LinkedHashMap<String, Element>();

    /**
     * The registration of the default servlet context helper.
     */
    private ServiceRegistration<?> defaultContextReg;

    /**
     * The registration of the {@link HttpServiceRuntime} service.
     */
    private ServiceRegistration<?> runtimeReg;

    /**
     * The service properties of the {@link HttpServiceRuntime} service.
     */
    private Hashtable<String, Object> runtimeProps;

    /**
     * The change count.
     */
    private final AtomicLong changeCount = new AtomicLong();

    /**
     * Snapshot of the bound filters, used at request time.
     */
    private volatile List<Element> boundFilters =
            Collections.<Element>emptyList();

    /**
     * Snapshot of the bound request listeners, used at request time.
     */
    private volatile List<Element> boundRequestListeners =
            Collections.<Element>emptyList();

    /**
     * Create a new instance.
     * @param context the bundle context of the runtime
     * @param httpService the service used to register servlets
     */
    public WhiteboardRuntime(final BundleContext context,
            final GlassFishHttpService httpService) {

        this.bctx = context;
        this.http = httpService;
    }

    /**
     * Start the runtime.
     */
    public void start() {
        Hashtable<String, Object> ctxProps = new Hashtable<String, Object>();
        ctxProps.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME,
                HttpWhiteboardConstants.HTTP_WHITEBOARD_DEFAULT_CONTEXT_NAME);
        ctxProps.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_PATH,
                "/");
        ctxProps.put(Constants.SERVICE_RANKING, Integer.MIN_VALUE);
        defaultContextReg = bctx.registerService(
                ServletContextHelper.class.getName(),
                new DefaultServletContextHelperFactory(), ctxProps);

        runtimeProps = new Hashtable<String, Object>();
        runtimeProps.put(HttpServiceRuntimeConstants.HTTP_SERVICE_ENDPOINT,
                new String[]{http.getContextPath() + "/"});
        runtimeProps.put(Constants.SERVICE_CHANGECOUNT, changeCount.get());
        runtimeReg = bctx.registerService(HttpServiceRuntime.class.getName(),
                new HttpServiceRuntimeImpl(this), runtimeProps);

        track("(" + Constants.OBJECTCLASS + "="
                + ServletContextHelper.class.getName() + ")", null);
        track("(&(" + Constants.OBJECTCLASS + "="
                + ServletContextListener.class.getName() + ")"
                + listenerFilter() + ")", Kind.LISTENER);
        track("(&(" + Constants.OBJECTCLASS + "="
                + ServletRequestListener.class.getName() + ")"
                + listenerFilter() + ")", Kind.LISTENER);
        track("(&(" + Constants.OBJECTCLASS + "="
                + jakarta.servlet.Filter.class.getName() + ")(|("
                + HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_PATTERN
                + "=*)(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_SERVLET
                + "=*)(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_REGEX
                + "=*)))", Kind.FILTER);
        track("(&(" + Constants.OBJECTCLASS + "=" + Servlet.class.getName()
                + ")(|("
                + HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN
                + "=*)(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_NAME
                + "=*)("
                + HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_ERROR_PAGE
                + "=*)))", Kind.SERVLET);
        track("(&("
                + HttpWhiteboardConstants.HTTP_WHITEBOARD_RESOURCE_PATTERN
                + "=*)("
                + HttpWhiteboardConstants.HTTP_WHITEBOARD_RESOURCE_PREFIX
                + "=*))", Kind.RESOURCE);
        LOGGER.log(Level.INFO, "OSGi Servlet Whiteboard runtime started on {0}",
                http.getContextPath());
    }

    /**
     * Stop the runtime.
     */
    public void stop() {
        List<ServiceTracker<?, ?>> copy =
                new ArrayList<ServiceTracker<?, ?>>(trackers);
        Collections.reverse(copy);
        for (ServiceTracker<?, ?> tracker : copy) {
            tracker.close();
        }
        trackers.clear();
        synchronized (this) {
            for (Element e : sortedForUnbind(elements.values())) {
                unbind(e);
            }
            elements.clear();
            contexts.clear();
            updateSnapshots();
        }
        if (runtimeReg != null) {
            runtimeReg.unregister();
            runtimeReg = null;
        }
        if (defaultContextReg != null) {
            defaultContextReg.unregister();
            defaultContextReg = null;
        }
    }

    /**
     * Filter fragment for listener services.
     * @return LDAP filter
     */
    private static String listenerFilter() {
        return "(" + HttpWhiteboardConstants.HTTP_WHITEBOARD_LISTENER
                + "=true)";
    }

    /**
     * Open a tracker.
     * @param filter the LDAP filter
     * @param kind the kind of whiteboard services, {@code null} for servlet
     * context helpers
     */
    private void track(final String filter, final Kind kind) {
        Filter f;
        try {
            f = bctx.createFilter(filter);
        } catch (InvalidSyntaxException e) {
            throw new IllegalStateException(e);
        }
        ServiceTracker<Object, ServiceReference<Object>> tracker =
                new ServiceTracker<Object, ServiceReference<Object>>(bctx, f,
                        new Customizer(kind));
        trackers.add(tracker);
        tracker.open();
    }

    /**
     * Tracker customizer.
     */
    private final class Customizer implements
            ServiceTrackerCustomizer<Object, ServiceReference<Object>> {

        /**
         * The kind of tracked services, {@code null} for servlet context
         * helpers.
         */
        private final Kind kind;

        /**
         * Create a new instance.
         * @param k the kind
         */
        Customizer(final Kind k) {
            this.kind = k;
        }

        @Override
        public ServiceReference<Object> addingService(
                final ServiceReference<Object> ref) {

            added(kind, ref);
            return ref;
        }

        @Override
        public void modifiedService(final ServiceReference<Object> ref,
                final ServiceReference<Object> service) {

            removed(kind, ref);
            added(kind, ref);
        }

        @Override
        public void removedService(final ServiceReference<Object> ref,
                final ServiceReference<Object> service) {

            removed(kind, ref);
        }
    }

    /**
     * Handle a new or modified service.
     * @param kind the kind, {@code null} for a servlet context helper
     * @param ref the service reference
     */
    private synchronized void added(final Kind kind,
            final ServiceReference<?> ref) {

        if (!matchesTarget(ref)) {
            return;
        }
        if (kind == null) {
            contexts.put(ref, new ContextInfo(ref));
        } else {
            elements.put(key(kind, ref), new Element(kind, ref));
        }
        reconcile();
    }

    /**
     * Handle a removed or modified service.
     * @param kind the kind, {@code null} for a servlet context helper
     * @param ref the service reference
     */
    private synchronized void removed(final Kind kind,
            final ServiceReference<?> ref) {

        if (kind == null) {
            if (contexts.remove(ref) == null) {
                return;
            }
        } else {
            Element e = elements.remove(key(kind, ref));
            if (e == null) {
                return;
            }
            unbind(e);
        }
        reconcile();
    }

    /**
     * Compute the key of a whiteboard service.
     * @param kind the kind
     * @param ref the service reference
     * @return key
     */
    private static String key(final Kind kind, final ServiceReference<?> ref) {
        return kind + ":" + ref.getProperty(Constants.SERVICE_ID);
    }

    /**
     * Test if a service targets this runtime.
     * @param ref the service reference
     * @return {@code true} if the service has no target filter or if the
     * filter matches this runtime
     */
    private boolean matchesTarget(final ServiceReference<?> ref) {
        Object target = ref.getProperty(
                HttpWhiteboardConstants.HTTP_WHITEBOARD_TARGET);
        if (target == null) {
            return true;
        }
        try {
            return bctx.createFilter(String.valueOf(target))
                    .match(runtimeReg.getReference());
        } catch (InvalidSyntaxException e) {
            LOGGER.log(Level.WARNING, "Invalid target filter for {0}: {1}",
                    new Object[]{ref, target});
            return false;
        }
    }

    /**
     * Bring the registrations in line with the tracked services.
     */
    private void reconcile() {
        // 1. compute the active servlet contexts: the highest ranked valid
        // context for each name
        Map<String, ContextInfo> active = new HashMap<String, ContextInfo>();
        for (ContextInfo ctx : contexts.values()) {
            ctx.failureReason = ctx.validate();
            if (ctx.failureReason >= 0) {
                continue;
            }
            ContextInfo current = active.get(ctx.name);
            if (current == null || ctx.ref.compareTo(current.ref) > 0) {
                if (current != null) {
                    current.failureReason =
                        DTOConstants.FAILURE_REASON_SHADOWED_BY_OTHER_SERVICE;
                }
                active.put(ctx.name, ctx);
            } else {
                ctx.failureReason =
                        DTOConstants.FAILURE_REASON_SHADOWED_BY_OTHER_SERVICE;
            }
        }

        // 2. unbind the services whose servlet context has changed
        List<Element> sorted = sortedForUnbind(elements.values());
        for (Element e : sorted) {
            if (e.context != null) {
                ContextInfo selected = select(e, active);
                if (selected != e.context) {
                    unbind(e);
                }
            }
        }

        // 3. bind the unbound services, highest ranked first
        List<Element> toBind = new ArrayList<Element>(elements.values());
        toBind.sort(Comparator.comparing((Element e) -> e.kind)
                .thenComparing((Element e) -> e.ref,
                        Comparator.<ServiceReference<?>>reverseOrder()));
        for (Element e : toBind) {
            if (e.context == null) {
                ContextInfo selected = select(e, active);
                if (selected == null) {
                    if (e.failureReason < 0) {
                        e.failureReason = DTOConstants
                                .FAILURE_REASON_NO_SERVLET_CONTEXT_MATCHING;
                    }
                } else {
                    bind(e, selected);
                }
            }
        }
        updateSnapshots();
        changeCount.incrementAndGet();
        if (runtimeReg != null) {
            runtimeProps.put(Constants.SERVICE_CHANGECOUNT,
                    changeCount.get());
            try {
                runtimeReg.setProperties(runtimeProps);
            } catch (IllegalStateException ise) {
                // runtime being stopped
            }
        }
    }

    /**
     * Sort elements in the order they must be unbound: servlets and
     * resources, then filters, then listeners.
     * @param values the elements
     * @return sorted list
     */
    private static List<Element> sortedForUnbind(
            final Collection<Element> values) {

        List<Element> sorted = new ArrayList<Element>(values);
        sorted.sort(Comparator.comparing((Element e) -> e.kind).reversed());
        return sorted;
    }

    /**
     * Select the servlet context of a whiteboard service.
     * @param e the whiteboard service
     * @param active the active servlet contexts
     * @return the servlet context, or {@code null} if none matches
     */
    private ContextInfo select(final Element e,
            final Map<String, ContextInfo> active) {

        Object select = e.ref.getProperty(
                HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_SELECT);
        String selectFilter = Objects.toString(select,
                DEFAULT_CONTEXT_SELECT);
        Filter filter;
        try {
            filter = bctx.createFilter(selectFilter);
        } catch (InvalidSyntaxException ex) {
            e.failureReason = DTOConstants.FAILURE_REASON_VALIDATION_FAILED;
            return null;
        }
        ContextInfo best = null;
        for (ContextInfo ctx : active.values()) {
            if (filter.match(ctx.ref)
                    && (best == null || ctx.ref.compareTo(best.ref) > 0)) {
                best = ctx;
            }
        }
        return best;
    }

    /**
     * Bind a whiteboard service to a servlet context.
     * @param e the whiteboard service
     * @param ctx the servlet context
     */
    @SuppressWarnings("unchecked")
    private void bind(final Element e, final ContextInfo ctx) {
        e.failureReason = -1;
        Bundle bundle = e.ref.getBundle();
        BundleContext ebc = null;
        if (bundle != null) {
            ebc = bundle.getBundleContext();
        }
        if (ebc == null) {
            e.failureReason = DTOConstants.FAILURE_REASON_SERVICE_NOT_GETTABLE;
            return;
        }
        int reason = e.validate(ctx);
        if (reason >= 0) {
            e.failureReason = reason;
            return;
        }
        ServiceObjects<ServletContextHelper> helperObjects =
                (ServiceObjects<ServletContextHelper>) ebc.getServiceObjects(
                        ctx.ref);
        ServletContextHelper helper = null;
        if (helperObjects != null) {
            helper = helperObjects.getService();
        }
        if (helper == null) {
            e.failureReason =
                    DTOConstants.FAILURE_REASON_SERVLET_CONTEXT_FAILURE;
            return;
        }
        ServiceObjects<Object> serviceObjects = null;
        Object service = null;
        if (e.kind != Kind.RESOURCE) {
            serviceObjects = (ServiceObjects<Object>) bctx.getServiceObjects(
                    e.ref);
            service = null;
            if (serviceObjects != null) {
                service = serviceObjects.getService();
            }
            if (service == null) {
                helperObjects.ungetService(helper);
                e.failureReason =
                        DTOConstants.FAILURE_REASON_SERVICE_NOT_GETTABLE;
                return;
            }
        }
        e.helperObjects = helperObjects;
        e.helper = helper;
        e.serviceObjects = serviceObjects;
        e.service = service;
        try {
            switch (e.kind) {
                case SERVLET:
                    bindServlet(e, ctx, (Servlet) service);
                    break;
                case RESOURCE:
                    bindResource(e, ctx);
                    break;
                case FILTER:
                    bindFilter(e, ctx, (jakarta.servlet.Filter) service);
                    break;
                case LISTENER:
                    bindListener(e, ctx, service);
                    break;
                default:
                    throw new IllegalStateException(String.valueOf(e.kind));
            }
            e.context = ctx;
            LOGGER.log(Level.FINE, "Bound {0} to servlet context {1}",
                    new Object[]{e, ctx.name});
        } catch (IllegalStateException ise) {
            LOGGER.log(Level.WARNING, "Unable to register " + e + ": "
                    + ise.getMessage());
            e.failureReason =
                    DTOConstants.FAILURE_REASON_SHADOWED_BY_OTHER_SERVICE;
            release(e);
        } catch (ServletException | RuntimeException ex) {
            LOGGER.log(Level.WARNING, "Unable to register " + e, ex);
            e.failureReason = DTOConstants.FAILURE_REASON_EXCEPTION_ON_INIT;
            release(e);
        }
    }

    /**
     * Register a servlet.
     * @param e the whiteboard service
     * @param ctx the servlet context
     * @param servlet the servlet
     * @throws ServletException if the servlet fails to initialize
     */
    private void bindServlet(final Element e, final ContextInfo ctx,
            final Servlet servlet) throws ServletException {

        String name = e.name(servlet);
        WhiteboardServlet ws = new WhiteboardServlet(this, ctx.id, name,
                servlet);
        String regName = "whiteboard.servlet." + e.serviceId();
        http.registerServlet(regName, name, e.effectivePatterns,
                ws, e.initParams(
                HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_INIT_PARAM_PREFIX),
                e.helper);
        e.registrationNames.add(regName);
    }

    /**
     * Register resources.
     * @param e the whiteboard service
     * @param ctx the servlet context
     * @throws ServletException if the servlet fails to initialize
     */
    private void bindResource(final Element e, final ContextInfo ctx)
            throws ServletException {

        String prefix = String.valueOf(e.ref.getProperty(
                HttpWhiteboardConstants.HTTP_WHITEBOARD_RESOURCE_PREFIX));
        try {
            for (int i = 0; i < e.effectivePatterns.length; i++) {
                String pattern = e.effectivePatterns[i];
                String alias;
                if (pattern.endsWith("/*")) {
                    alias = pattern.substring(0, pattern.length() - 2);
                } else if (pattern.startsWith("*.") || "/".equals(pattern)) {
                    alias = "";
                } else {
                    alias = pattern;
                }
                String name = "whiteboard.resource." + e.serviceId() + "." + i;
                WhiteboardServlet ws = new WhiteboardServlet(this, ctx.id,
                        name, new OSGiResourceServlet(alias, prefix,
                                e.helper));
                http.registerServlet(name, name, new String[]{pattern}, ws,
                        null, e.helper);
                e.registrationNames.add(name);
            }
        } catch (ServletException | RuntimeException ex) {
            unregisterServlets(e);
            throw ex;
        }
    }

    /**
     * Initialize a filter.
     * @param e the whiteboard service
     * @param ctx the servlet context
     * @param filter the filter
     * @throws ServletException if the filter fails to initialize
     */
    private void bindFilter(final Element e, final ContextInfo ctx,
            final jakarta.servlet.Filter filter) throws ServletException {

        ServletContext sc = http.getServletContext(e.helper)
                .getServletContext();
        filter.init(new WhiteboardFilterConfig(e.name(filter), sc,
                e.initParams(HttpWhiteboardConstants
                        .HTTP_WHITEBOARD_FILTER_INIT_PARAM_PREFIX)));
    }

    /**
     * Initialize a listener.
     * @param e the whiteboard service
     * @param ctx the servlet context
     * @param listener the listener
     */
    private void bindListener(final Element e, final ContextInfo ctx,
            final Object listener) {

        if (listener instanceof ServletContextListener) {
            ServletContext sc = http.getServletContext(e.helper)
                    .getServletContext();
            ((ServletContextListener) listener).contextInitialized(
                    new ServletContextEvent(sc));
        }
    }

    /**
     * Unbind a whiteboard service.
     * @param e the whiteboard service
     */
    private void unbind(final Element e) {
        if (e.context == null) {
            return;
        }
        try {
            switch (e.kind) {
                case SERVLET:
                case RESOURCE:
                    unregisterServlets(e);
                    break;
                case FILTER:
                    ((jakarta.servlet.Filter) e.service).destroy();
                    break;
                case LISTENER:
                    if (e.service instanceof ServletContextListener) {
                        ServletContext sc = http.getServletContext(e.helper)
                                .getServletContext();
                        ((ServletContextListener) e.service).contextDestroyed(
                                new ServletContextEvent(sc));
                    }
                    break;
                default:
                    break;
            }
        } catch (RuntimeException ex) {
            LOGGER.log(Level.WARNING, "Error while unregistering " + e, ex);
        } finally {
            e.context = null;
            release(e);
        }
    }

    /**
     * Unregister the servlets of a whiteboard service.
     * @param e the whiteboard service
     */
    private void unregisterServlets(final Element e) {
        for (String name : e.registrationNames) {
            http.unregister(name, true);
        }
        e.registrationNames.clear();
    }

    /**
     * Release the service objects of a whiteboard service.
     * @param e the whiteboard service
     */
    private void release(final Element e) {
        if (e.helper != null && e.kind != Kind.SERVLET
                && e.kind != Kind.RESOURCE) {
            http.releaseServletContext(e.helper);
        }
        try {
            if (e.serviceObjects != null && e.service != null) {
                e.serviceObjects.ungetService(e.service);
            }
            if (e.helperObjects != null && e.helper != null) {
                e.helperObjects.ungetService(e.helper);
            }
        } catch (IllegalStateException | IllegalArgumentException ex) {
            // bundle being stopped
        }
        e.serviceObjects = null;
        e.service = null;
        e.helperObjects = null;
        e.helper = null;
    }

    /**
     * Update the snapshots used at request time.
     */
    private void updateSnapshots() {
        List<Element> filters = new ArrayList<Element>();
        List<Element> listeners = new ArrayList<Element>();
        for (Element e : elements.values()) {
            if (e.context == null) {
                continue;
            }
            if (e.kind == Kind.FILTER) {
                filters.add(e);
            } else if (e.kind == Kind.LISTENER
                    && e.service instanceof ServletRequestListener) {
                listeners.add(e);
            }
        }
        Comparator<Element> byRanking = Comparator.comparing(
                (Element e) -> e.ref,
                Comparator.<ServiceReference<?>>reverseOrder());
        filters.sort(byRanking);
        listeners.sort(byRanking);
        boundFilters = Collections.unmodifiableList(filters);
        boundRequestListeners = Collections.unmodifiableList(listeners);
    }

    /**
     * Get the filters to apply to a request.
     * @param contextId the servlet context id
     * @param servletName the servlet name
     * @param path the request path, relative to the root web module
     * @return filters, highest ranked first
     */
    List<jakarta.servlet.Filter> getFilters(final long contextId,
            final String servletName, final String path) {

        List<jakarta.servlet.Filter> result =
                new ArrayList<jakarta.servlet.Filter>();
        for (Element e : boundFilters) {
            ContextInfo ctx = e.context;
            Object filter = e.service;
            if (ctx != null && ctx.id == contextId && filter != null
                    && e.matchesRequest(servletName, path)) {
                result.add((jakarta.servlet.Filter) filter);
            }
        }
        return result;
    }

    /**
     * Get the request listeners of a servlet context.
     * @param contextId the servlet context id
     * @return listeners
     */
    List<ServletRequestListener> getRequestListeners(final long contextId) {
        List<ServletRequestListener> result =
                new ArrayList<ServletRequestListener>();
        for (Element e : boundRequestListeners) {
            ContextInfo ctx = e.context;
            Object listener = e.service;
            if (ctx != null && ctx.id == contextId && listener != null) {
                result.add((ServletRequestListener) listener);
            }
        }
        return result;
    }

    /**
     * Create the runtime DTO.
     * @return RuntimeDTO
     */
    synchronized RuntimeDTO getRuntimeDTO() {
        RuntimeDTO dto = new RuntimeDTO();
        if (runtimeReg != null) {
            dto.serviceDTO = runtimeReg.getReference()
                    .adapt(ServiceReferenceDTO.class);
        }
        dto.preprocessorDTOs = new PreprocessorDTO[0];
        dto.failedPreprocessorDTOs = new FailedPreprocessorDTO[0];
        dto.failedErrorPageDTOs = new FailedErrorPageDTO[0];

        List<ServletContextDTO> contextDTOs =
                new ArrayList<ServletContextDTO>();
        List<FailedServletContextDTO> failedContexts =
                new ArrayList<FailedServletContextDTO>();
        for (ContextInfo ctx : contexts.values()) {
            if (ctx.failureReason >= 0) {
                FailedServletContextDTO f = new FailedServletContextDTO();
                fillContextDTO(f, ctx);
                f.failureReason = ctx.failureReason;
                failedContexts.add(f);
            } else {
                ServletContextDTO c = new ServletContextDTO();
                fillContextDTO(c, ctx);
                contextDTOs.add(c);
            }
        }
        dto.servletContextDTOs = contextDTOs.toArray(
                new ServletContextDTO[0]);
        dto.failedServletContextDTOs = failedContexts.toArray(
                new FailedServletContextDTO[0]);

        List<FailedServletDTO> failedServlets =
                new ArrayList<FailedServletDTO>();
        List<FailedResourceDTO> failedResources =
                new ArrayList<FailedResourceDTO>();
        List<FailedFilterDTO> failedFilters = new ArrayList<FailedFilterDTO>();
        List<FailedListenerDTO> failedListeners =
                new ArrayList<FailedListenerDTO>();
        for (Element e : elements.values()) {
            if (e.context != null) {
                continue;
            }
            int reason = e.failureReason;
            if (reason < 0) {
                reason = DTOConstants.FAILURE_REASON_UNKNOWN;
            }
            switch (e.kind) {
                case SERVLET:
                    FailedServletDTO fs = new FailedServletDTO();
                    fillServletDTO(fs, e, 0L);
                    fs.failureReason = reason;
                    failedServlets.add(fs);
                    break;
                case RESOURCE:
                    FailedResourceDTO fr = new FailedResourceDTO();
                    fillResourceDTO(fr, e, 0L);
                    fr.failureReason = reason;
                    failedResources.add(fr);
                    break;
                case FILTER:
                    FailedFilterDTO ff = new FailedFilterDTO();
                    fillFilterDTO(ff, e, 0L);
                    ff.failureReason = reason;
                    failedFilters.add(ff);
                    break;
                default:
                    FailedListenerDTO fl = new FailedListenerDTO();
                    fillListenerDTO(fl, e, 0L);
                    fl.failureReason = reason;
                    failedListeners.add(fl);
                    break;
            }
        }
        dto.failedServletDTOs = failedServlets.toArray(
                new FailedServletDTO[0]);
        dto.failedResourceDTOs = failedResources.toArray(
                new FailedResourceDTO[0]);
        dto.failedFilterDTOs = failedFilters.toArray(new FailedFilterDTO[0]);
        dto.failedListenerDTOs = failedListeners.toArray(
                new FailedListenerDTO[0]);
        return dto;
    }

    /**
     * Calculate the request info DTO for a path.
     * @param path the path, relative to the root web module
     * @return RequestInfoDTO
     */
    synchronized RequestInfoDTO calculateRequestInfoDTO(final String path) {
        RequestInfoDTO dto = new RequestInfoDTO();
        dto.path = path;
        dto.filterDTOs = new FilterDTO[0];
        Element match = null;
        String matchedPattern = null;
        for (Element e : elements.values()) {
            if (e.context == null
                    || (e.kind != Kind.SERVLET && e.kind != Kind.RESOURCE)) {
                continue;
            }
            for (String p : e.effectivePatterns) {
                if (PathPatterns.matches(p, path) && (matchedPattern == null
                        || specificity(p) > specificity(matchedPattern))) {
                    match = e;
                    matchedPattern = p;
                }
            }
        }
        if (match == null) {
            return dto;
        }
        dto.servletContextId = match.context.id;
        if (match.kind == Kind.SERVLET) {
            dto.servletDTO = new ServletDTO();
            fillServletDTO(dto.servletDTO, match, match.context.id);
        } else {
            dto.resourceDTO = new ResourceDTO();
            fillResourceDTO(dto.resourceDTO, match, match.context.id);
        }
        String servletName = null;
        if (match.kind == Kind.SERVLET) {
            servletName = match.name(match.service);
        }
        List<FilterDTO> filters = new ArrayList<FilterDTO>();
        for (Element f : boundFilters) {
            if (f.context == match.context
                    && f.matchesRequest(servletName, path)) {
                FilterDTO fd = new FilterDTO();
                fillFilterDTO(fd, f, f.context.id);
                filters.add(fd);
            }
        }
        dto.filterDTOs = filters.toArray(new FilterDTO[0]);
        return dto;
    }

    /**
     * Compute how specific a servlet URL pattern is.
     * @param pattern the pattern
     * @return specificity, higher is more specific
     */
    private static int specificity(final String pattern) {
        if (!pattern.contains("*")) {
            return Integer.MAX_VALUE;
        }
        if (pattern.startsWith("*.")) {
            return 1;
        }
        return pattern.length() + 1;
    }

    /**
     * Fill a servlet context DTO.
     * @param dto the DTO
     * @param ctx the servlet context
     */
    private void fillContextDTO(final ServletContextDTO dto,
            final ContextInfo ctx) {

        dto.name = ctx.name;
        dto.contextPath = http.getContextPath();
        if (ctx.path != null && !"/".equals(ctx.path)) {
            dto.contextPath += ctx.path;
        }
        dto.serviceId = ctx.id;
        dto.initParams = ctx.initParams();
        dto.attributes = Collections.<String, Object>emptyMap();
        List<ServletDTO> servlets = new ArrayList<ServletDTO>();
        List<ResourceDTO> resources = new ArrayList<ResourceDTO>();
        List<FilterDTO> filters = new ArrayList<FilterDTO>();
        List<ListenerDTO> listeners = new ArrayList<ListenerDTO>();
        for (Element e : elements.values()) {
            if (e.context != ctx) {
                continue;
            }
            switch (e.kind) {
                case SERVLET:
                    ServletDTO s = new ServletDTO();
                    fillServletDTO(s, e, ctx.id);
                    servlets.add(s);
                    break;
                case RESOURCE:
                    ResourceDTO r = new ResourceDTO();
                    fillResourceDTO(r, e, ctx.id);
                    resources.add(r);
                    break;
                case FILTER:
                    FilterDTO f = new FilterDTO();
                    fillFilterDTO(f, e, ctx.id);
                    filters.add(f);
                    break;
                default:
                    ListenerDTO l = new ListenerDTO();
                    fillListenerDTO(l, e, ctx.id);
                    listeners.add(l);
                    break;
            }
        }
        dto.servletDTOs = servlets.toArray(new ServletDTO[0]);
        dto.resourceDTOs = resources.toArray(new ResourceDTO[0]);
        dto.filterDTOs = filters.toArray(new FilterDTO[0]);
        dto.errorPageDTOs = new ErrorPageDTO[0];
        dto.listenerDTOs = listeners.toArray(new ListenerDTO[0]);
    }

    /**
     * Fill a servlet DTO.
     * @param dto the DTO
     * @param e the whiteboard service
     * @param contextId the servlet context id
     */
    private static void fillServletDTO(final ServletDTO dto, final Element e,
            final long contextId) {

        dto.serviceId = e.serviceId();
        dto.servletContextId = contextId;
        dto.name = e.name(e.service);
        dto.patterns = e.patterns(
                HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN);
        dto.initParams = e.initParams(HttpWhiteboardConstants
                .HTTP_WHITEBOARD_SERVLET_INIT_PARAM_PREFIX);
        dto.asyncSupported = false;
        dto.multipartEnabled = false;
        if (e.service instanceof Servlet) {
            dto.servletInfo = ((Servlet) e.service).getServletInfo();
        }
    }

    /**
     * Fill a resource DTO.
     * @param dto the DTO
     * @param e the whiteboard service
     * @param contextId the servlet context id
     */
    private static void fillResourceDTO(final ResourceDTO dto,
            final Element e, final long contextId) {

        dto.serviceId = e.serviceId();
        dto.servletContextId = contextId;
        dto.patterns = e.patterns(
                HttpWhiteboardConstants.HTTP_WHITEBOARD_RESOURCE_PATTERN);
        Object prefix = e.ref.getProperty(
                HttpWhiteboardConstants.HTTP_WHITEBOARD_RESOURCE_PREFIX);
        dto.prefix = Objects.toString(prefix, null);
    }

    /**
     * Fill a filter DTO.
     * @param dto the DTO
     * @param e the whiteboard service
     * @param contextId the servlet context id
     */
    private static void fillFilterDTO(final FilterDTO dto, final Element e,
            final long contextId) {

        dto.serviceId = e.serviceId();
        dto.servletContextId = contextId;
        dto.name = e.name(e.service);
        dto.patterns = e.patterns(
                HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_PATTERN);
        dto.servletNames = e.patterns(
                HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_SERVLET);
        dto.regexs = e.patterns(
                HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_REGEX);
        dto.dispatcher = e.dispatchers();
        dto.asyncSupported = false;
        dto.initParams = e.initParams(
                HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_INIT_PARAM_PREFIX);
    }

    /**
     * Fill a listener DTO.
     * @param dto the DTO
     * @param e the whiteboard service
     * @param contextId the servlet context id
     */
    private static void fillListenerDTO(final ListenerDTO dto,
            final Element e, final long contextId) {

        dto.serviceId = e.serviceId();
        dto.servletContextId = contextId;
        dto.types = PathPatterns.toStringArray(
                e.ref.getProperty(Constants.OBJECTCLASS));
    }

    /**
     * A tracked servlet context helper.
     */
    private static final class ContextInfo {

        /**
         * The service reference.
         */
        private final ServiceReference<?> ref;

        /**
         * The service id.
         */
        private final long id;

        /**
         * The context name.
         */
        private final String name;

        /**
         * The context path.
         */
        private final String path;

        /**
         * The failure reason, -1 if none.
         */
        private int failureReason = -1;

        /**
         * Create a new instance.
         * @param sref the service reference
         */
        ContextInfo(final ServiceReference<?> sref) {
            this.ref = sref;
            this.id = (Long) sref.getProperty(Constants.SERVICE_ID);
            Object n = sref.getProperty(
                    HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME);
            this.name = stringValue(n);
            Object p = sref.getProperty(
                    HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_PATH);
            this.path = stringValue(p);
        }

        /**
         * Validate the service properties.
         * @return the failure reason, or -1 if valid
         */
        int validate() {
            if (name == null || path == null
                    || !CONTEXT_NAME_PATTERN.matcher(name).matches()
                    || !CONTEXT_PATH_PATTERN.matcher(path).matches()) {
                return DTOConstants.FAILURE_REASON_VALIDATION_FAILED;
            }
            return -1;
        }

        /**
         * Get the init parameters.
         * @return map
         */
        Map<String, String> initParams() {
            return prefixedProperties(ref, HttpWhiteboardConstants
                    .HTTP_WHITEBOARD_CONTEXT_INIT_PARAM_PREFIX);
        }
    }

    /**
     * Get a property value if it is a string.
     * @param value the property value
     * @return the string, or {@code null}
     */
    private static String stringValue(final Object value) {
        if (value instanceof String) {
            return (String) value;
        }
        return null;
    }

    /**
     * Collect the service properties having a given prefix.
     * @param ref the service reference
     * @param prefix the prefix
     * @return map of the properties, without the prefix
     */
    private static Map<String, String> prefixedProperties(
            final ServiceReference<?> ref, final String prefix) {

        Map<String, String> result = new HashMap<String, String>();
        for (String key : ref.getPropertyKeys()) {
            if (key.startsWith(prefix)) {
                Object value = ref.getProperty(key);
                result.put(key.substring(prefix.length()),
                        Objects.toString(value, null));
            }
        }
        return result;
    }

    /**
     * A tracked whiteboard service.
     */
    private final class Element {

        /**
         * The kind of service.
         */
        private final Kind kind;

        /**
         * The service reference.
         */
        private final ServiceReference<?> ref;

        /**
         * The bound servlet context, {@code null} if not bound.
         */
        private volatile ContextInfo context;

        /**
         * The failure reason, -1 if none.
         */
        private int failureReason = -1;

        /**
         * The patterns, prefixed with the servlet context path.
         */
        private String[] effectivePatterns = new String[0];

        /**
         * The compiled regular expressions of a filter.
         */
        private final List<Pattern> regexes = new ArrayList<Pattern>();

        /**
         * The registration names in {@link GlassFishHttpService}.
         */
        private final List<String> registrationNames =
                new ArrayList<String>();

        /**
         * The service objects.
         */
        private ServiceObjects<Object> serviceObjects;

        /**
         * The service.
         */
        private volatile Object service;

        /**
         * The servlet context helper service objects.
         */
        private ServiceObjects<ServletContextHelper> helperObjects;

        /**
         * The servlet context helper.
         */
        private ServletContextHelper helper;

        /**
         * Create a new instance.
         * @param k the kind
         * @param sref the service reference
         */
        Element(final Kind k, final ServiceReference<?> sref) {
            this.kind = k;
            this.ref = sref;
        }

        /**
         * Get the service id.
         * @return service id
         */
        long serviceId() {
            return (Long) ref.getProperty(Constants.SERVICE_ID);
        }

        /**
         * Get a multi-valued string property.
         * @param property the property name
         * @return the values
         */
        String[] patterns(final String property) {
            return PathPatterns.toStringArray(ref.getProperty(property));
        }

        /**
         * Get the filter dispatchers.
         * @return the dispatchers
         */
        String[] dispatchers() {
            String[] dispatchers = patterns(
                    HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_DISPATCHER);
            if (dispatchers.length == 0) {
                return new String[]{
                    HttpWhiteboardConstants.DISPATCHER_REQUEST};
            }
            return dispatchers;
        }

        /**
         * Get the init parameters.
         * @param prefix the init parameter prefix
         * @return map
         */
        Map<String, String> initParams(final String prefix) {
            return prefixedProperties(ref, prefix);
        }

        /**
         * Get the servlet or filter name.
         * @param obj the service object, may be {@code null}
         * @return name
         */
        String name(final Object obj) {
            String property =
                    HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_NAME;
            if (kind == Kind.FILTER) {
                property = HttpWhiteboardConstants.HTTP_WHITEBOARD_FILTER_NAME;
            }
            Object n = ref.getProperty(property);
            if (n != null) {
                return String.valueOf(n);
            }
            if (obj != null) {
                return obj.getClass().getName();
            }
            return String.valueOf(ref.getProperty(Constants.SERVICE_ID));
        }

        /**
         * Validate the service properties and compute the effective
         * patterns for a given servlet context.
         * @param ctx the servlet context
         * @return the failure reason, or -1 if valid
         */
        int validate(final ContextInfo ctx) {
            String patternProperty;
            switch (kind) {
                case SERVLET:
                    if (ref.getProperty(HttpWhiteboardConstants
                            .HTTP_WHITEBOARD_SERVLET_ERROR_PAGE) != null) {
                        // error pages are not supported
                        return DTOConstants.FAILURE_REASON_VALIDATION_FAILED;
                    }
                    patternProperty = HttpWhiteboardConstants
                            .HTTP_WHITEBOARD_SERVLET_PATTERN;
                    break;
                case RESOURCE:
                    patternProperty = HttpWhiteboardConstants
                            .HTTP_WHITEBOARD_RESOURCE_PATTERN;
                    Object prefix = ref.getProperty(HttpWhiteboardConstants
                            .HTTP_WHITEBOARD_RESOURCE_PREFIX);
                    if (!(prefix instanceof String)
                            || ((String) prefix).endsWith("/")
                                && !"/".equals(prefix)) {
                        return DTOConstants.FAILURE_REASON_VALIDATION_FAILED;
                    }
                    break;
                case FILTER:
                    patternProperty = HttpWhiteboardConstants
                            .HTTP_WHITEBOARD_FILTER_PATTERN;
                    regexes.clear();
                    try {
                        for (String regex : patterns(HttpWhiteboardConstants
                                .HTTP_WHITEBOARD_FILTER_REGEX)) {
                            regexes.add(Pattern.compile(regex));
                        }
                    } catch (PatternSyntaxException ex) {
                        return DTOConstants.FAILURE_REASON_VALIDATION_FAILED;
                    }
                    break;
                default:
                    if (!"true".equals(String.valueOf(ref.getProperty(
                            HttpWhiteboardConstants
                                    .HTTP_WHITEBOARD_LISTENER)))) {
                        return DTOConstants.FAILURE_REASON_VALIDATION_FAILED;
                    }
                    String[] types = patterns(Constants.OBJECTCLASS);
                    for (String type : types) {
                        for (Class<?> supported : LISTENER_TYPES) {
                            if (supported.getName().equals(type)) {
                                effectivePatterns = new String[0];
                                return -1;
                            }
                        }
                    }
                    return DTOConstants.FAILURE_REASON_VALIDATION_FAILED;
            }
            String[] patterns = patterns(patternProperty);
            List<String> effective = new ArrayList<String>();
            for (String pattern : patterns) {
                if (!PathPatterns.isValid(pattern)) {
                    return DTOConstants.FAILURE_REASON_VALIDATION_FAILED;
                }
                String p = PathPatterns.prefix(ctx.path, pattern);
                if (p == null) {
                    return DTOConstants.FAILURE_REASON_VALIDATION_FAILED;
                }
                effective.add(p);
            }
            effectivePatterns = effective.toArray(new String[0]);
            return -1;
        }

        /**
         * Test if a filter must be applied to a request.
         * @param servletName the name of the target servlet, may be
         * {@code null}
         * @param path the request path, relative to the root web module
         * @return {@code true} if the filter must be applied
         */
        boolean matchesRequest(final String servletName, final String path) {
            boolean request = false;
            for (String d : dispatchers()) {
                if (HttpWhiteboardConstants.DISPATCHER_REQUEST.equals(d)) {
                    request = true;
                }
            }
            if (!request) {
                return false;
            }
            for (String p : effectivePatterns) {
                if (PathPatterns.matches(p, path)) {
                    return true;
                }
            }
            if (servletName != null) {
                for (String n : patterns(HttpWhiteboardConstants
                        .HTTP_WHITEBOARD_FILTER_SERVLET)) {
                    if (servletName.equals(n)) {
                        return true;
                    }
                }
            }
            for (Pattern regex : regexes) {
                if (regex.matcher(path).matches()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String toString() {
            String bsn = "?";
            if (ref.getBundle() != null) {
                bsn = ref.getBundle().getSymbolicName();
            }
            return kind.name().toLowerCase() + " service "
                    + ref.getProperty(Constants.SERVICE_ID) + " of bundle "
                    + bsn;
        }
    }

    /**
     * Service factory of the default servlet context helper: a new helper
     * backed by the using bundle is created for each bundle.
     */
    private static final class DefaultServletContextHelperFactory
            implements ServiceFactory<ServletContextHelper> {

        @Override
        public ServletContextHelper getService(final Bundle bundle,
                final ServiceRegistration<ServletContextHelper> reg) {

            return new ServletContextHelper(bundle) {
            };
        }

        @Override
        public void ungetService(final Bundle bundle,
                final ServiceRegistration<ServletContextHelper> reg,
                final ServletContextHelper service) {
        }
    }
}
