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
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.http.HttpService;

import java.util.Dictionary;

/**
 * Entry point of the legacy OSGi HttpService compatibility layer.
 *
 * The classes of this package depend on javax.servlet and on the Apache Felix
 * javax/jakarta servlet wrappers; they must only be loaded when
 * {@link #isAvailable()} returns {@code true}.
 */
public final class HttpServiceCompat {

    /**
     * Cannot be instanciated.
     */
    private HttpServiceCompat() {
    }

    /**
     * Test if the compatibility layer can be used, i.e. if javax.servlet,
     * the OSGi HttpService API and the Apache Felix wrappers are wired to
     * this bundle.
     * @return {@code true} if available
     */
    public static boolean isAvailable() {
        ClassLoader cl = HttpServiceCompat.class.getClassLoader();
        try {
            Class.forName("javax.servlet.Servlet", false, cl);
            Class.forName("org.osgi.service.http.HttpService", false, cl);
            Class.forName(
                    "org.apache.felix.http.jakartawrappers.ServletWrapper",
                    false, cl);
            Class.forName(
                    "org.apache.felix.http.javaxwrappers.ServletRequestWrapper",
                    false, cl);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /**
     * Register a legacy {@link HttpService} backed by the given service.
     * @param bctx the bundle context used to register the service
     * @param gfHttpService the underlying service
     * @param props the service properties
     * @return the service registration
     */
    public static ServiceRegistration<?> register(final BundleContext bctx,
            final GlassFishHttpService gfHttpService,
            final Dictionary<String, Object> props) {

        return bctx.registerService(HttpService.class.getName(),
                new HttpServiceFactory(new HttpServiceRegistry(gfHttpService)),
                props);
    }

    /**
     * Service factory creating one {@link HttpServiceWrapper} per client
     * bundle.
     */
    private static final class HttpServiceFactory
            implements ServiceFactory<HttpService> {

        /**
         * The shared registry.
         */
        private final HttpServiceRegistry registry;

        /**
         * Create a new instance.
         * @param reg the shared registry
         */
        HttpServiceFactory(final HttpServiceRegistry reg) {
            this.registry = reg;
        }

        @Override
        public HttpService getService(final Bundle bnd,
                final ServiceRegistration<HttpService> registration) {

            return new HttpServiceWrapper(registry, bnd);
        }

        @Override
        public void ungetService(final Bundle bundle,
                final ServiceRegistration<HttpService> registration,
                final HttpService service) {

            HttpServiceWrapper.class.cast(service).unregisterAll();
        }
    }
}
