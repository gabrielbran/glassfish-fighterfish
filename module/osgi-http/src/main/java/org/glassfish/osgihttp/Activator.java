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

import com.sun.enterprise.config.serverbeans.Config;
import com.sun.enterprise.config.serverbeans.Domain;
import com.sun.enterprise.config.serverbeans.Server;
import com.sun.enterprise.config.serverbeans.VirtualServer;
import com.sun.enterprise.deployment.WebBundleDescriptor;
import com.sun.enterprise.web.WebContainer;
import com.sun.enterprise.web.WebModule;
import com.sun.enterprise.web.WebModuleConfig;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.session.StandardManager;
import org.apache.catalina.startup.ContextConfig;
import org.glassfish.grizzly.config.dom.NetworkListener;
import org.glassfish.api.admin.ServerEnvironment;
import org.glassfish.embeddable.GlassFish;
import org.glassfish.embeddable.GlassFishException;
import org.glassfish.internal.api.ClassLoaderHierarchy;
import org.glassfish.osgihttp.compat.HttpServiceCompat;
import org.glassfish.osgihttp.whiteboard.WhiteboardRuntime;
import org.glassfish.osgijavaeebase.Extender;
import org.glassfish.web.deployment.descriptor.WebBundleDescriptorImpl;
import org.glassfish.web.valve.GlassFishValve;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Hashtable;
import java.util.StringTokenizer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.catalina.Container;
import org.apache.catalina.Engine;
import org.apache.catalina.Host;
import org.apache.catalina.Manager;
import org.apache.catalina.Realm;

/**
 * This is the entry point to our implementation of the OSGi Servlet
 * Whiteboard and of the legacy OSGi/HTTP service. For every virtual server in
 * the configuration, it creates a root web module. Every root web module has
 * same context path. The context path can be defined by user using
 * configuration property org.glassfish.osgihttp.ContextPath. If it is
 * absent, we use a default value of "/osgi".
 * <p>
 * The OSGi Servlet Whiteboard runtime is started for the default virtual
 * server. A legacy HttpService factory is registered for every virtual server
 * if the javax.servlet based compatibility layer is available.
 */
public final class Activator implements BundleActivator {

    /**
     * Configuration property used to select context root under which this
     * service is deployed.
     */
    private static final String CONTEXT_PATH_PROP = Activator.class
            .getPackage().getName() + ".ContextPath";

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(
            Activator.class.getPackage().getName());

    // TODO(Sahoo): Use config admin to configure context path, virtual server.

    /**
     * Bundle context.
     */
    private BundleContext bctx;

    /**
     * Virtual server name mapping.
     */
    private final Map<String, Host> vss = new HashMap<String, Host>();

    /**
     * Context path.
     */
    private String contextPath;

    /**
     * List of HTTPService OSGi service registration.
     */
    private final List<ServiceRegistration<?>> registrations =
            new ArrayList<ServiceRegistration<?>>();

    /**
     * The OSGi Servlet Whiteboard runtime.
     */
    private WhiteboardRuntime whiteboard;

    /**
     * The GlassFish service.
     */
    private GlassFish gf;

    /**
     * Service registration for the extender service.
     */
    private ServiceRegistration extenderReg;

    @Override
    public void start(final BundleContext context) throws Exception {
        bctx = context;
        Extender extender = new OSGiHtttpExtender();
        extenderReg = context.registerService(Extender.class.getName(),
                extender, null);
    }

    /**
     * This method is responsible for registering a HTTPService for every
     * virtual server. Each service is registered with a service property called
     * "VirtualServer," which can be used by clients to select a service. e.g.,
     * web console can use this to select __asadmin virtual server. While
     * registering the service for the default virtual server, it sets the
     * service.ranking to the maximum value so that any client just looking for
     * an HTTPService gets to see the HTTPService bound to default virtual
     * server.
     * @param webContainer GlassFish web container
     * @throws GlassFishException if an error occurs
     */
    @SuppressWarnings("unchecked")
    private void doActualWork(final WebContainer webContainer)
            throws GlassFishException {

        String defaultVsId = getDefaultVirtualServer();
        final StringTokenizer vsIds = new StringTokenizer(
                getAllVirtualServers(), ",");
        while (vsIds.hasMoreTokens()) {
            String vsId = vsIds.nextToken().trim();
            try {
                WebModule standardContext = createRootWebModule(webContainer,
                        vsId);
                if (standardContext == null) {
                    LOGGER.logp(Level.WARNING, "Activator", "doActualWork",
                            "GlassFishHttpService will not be available"
                            + " for virtual server = {0}, "
                            + "because we are not able to create root web app.",
                            new Object[]{vsId});
                    continue;
                }
                GlassFishHttpService httpService =
                        new GlassFishHttpService(standardContext);
                if (vsId.equals(defaultVsId)) {
                    whiteboard = new WhiteboardRuntime(bctx, httpService);
                    whiteboard.start();
                }
                if (HttpServiceCompat.isAvailable()) {
                    Hashtable<String, Object> props =
                            new Hashtable<String, Object>();
                    props.put("VirtualServer", vsId);
                    if (vsId.equals(defaultVsId)) {
                        props.put(Constants.SERVICE_RANKING,
                                Integer.MAX_VALUE);
                    }
                    registrations.add(HttpServiceCompat.register(bctx,
                            httpService, props));
                } else if (vsId.equals(defaultVsId)) {
                    LOGGER.logp(Level.INFO, "Activator", "doActualWork",
                            "Legacy OSGi HttpService not available: install"
                            + " the javax.servlet 4 API and the Apache Felix"
                            + " HTTP wrappers bundles to enable it.");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Create the root web module.
     * @param webContainer the glassfish web container
     * @param vsId the virtual server id
     * @return WebModule
     * @throws Exception if an error occurs
     */
    private WebModule createRootWebModule(final WebContainer webContainer,
            final String vsId) throws Exception {

        Engine engine = webContainer.getEngine();
        Host vs = (Host) engine.findChild(vsId);
        if (vs == null) {
            // this can happen if someone deleted a virtual server after we
            // read domain.xml
            return null;
        }
        vss.put(vsId, vs);
        contextPath = bctx.getProperty(CONTEXT_PATH_PROP);
        if (contextPath == null) {
            contextPath = "/osgi"; // default value
        }
        // create a new context under which all OSGi HTTP wrappers
        // will be registered.
        final WebModule standardContext = new WebModule();
        standardContext.setWebContainer(webContainer);
        standardContext.setName(contextPath);
        standardContext.setPath(contextPath);
        // GlassFish 8 expects the document base to look like an exploded
        // web application (it looks up WEB-INF/classes before the web
        // application class loader is started).
        File docBase = bctx.getDataFile("docroot-" + vsId);
        new File(docBase, "WEB-INF/classes").mkdirs();
        new File(docBase, "WEB-INF/lib").mkdirs();
        standardContext.setDocBase(docBase.getAbsolutePath());
        standardContext.setWorkDir(System.getProperty("java.io.tmpdir"));
        standardContext.addLifecycleListener(new ContextConfig());
        Realm realm = gf.getService(Realm.class);
        standardContext.setRealm(realm);
        WebModuleConfig wmConfig = new WebModuleConfig();
        wmConfig.setWorkDirBase(System.getProperty("java.io.tmpdir"));
        wmConfig.setVirtualServers(vsId);

        // Setting it in WebModuleConfig does not work, Ceck with Jan.
//        wmConfig.setAppClassLoader(getCommonClassLoader());
        standardContext.setParentClassLoader(getCommonClassLoader(gf));
        standardContext.setWebModuleConfig(wmConfig);
        setEmptyDescriptor(standardContext, wmConfig);

        // See  See GLASSFISH-16764 for more details about this valve
        standardContext.addValve((GlassFishValve) new OSGiHttpContextValve());
        // Since there is issue about locating user classes that are part
        // of some OSGi bundle while deserializing, we switch off session
        // persistence.
        switchOffSessionPersistence(standardContext);
        vs.addChild(standardContext);
        LOGGER.logp(Level.INFO, "Activator", "createRootWebModule",
                "standardContext = {0}",
                new Object[]{standardContext});
        return standardContext;
    }

    /**
     * Get the GlassFish common class-loader.
     * @param gfService the GlassFish service
     * @return ClassLoader
     * @throws GlassFishException if an error occurs
     */
    /**
     * Set an empty deployment descriptor on the root web module.
     * GlassFish 8 expects every {@link WebModule} to have a descriptor when
     * it starts (see {@code WebModule.contextListenerStart}), but the setter
     * is not public.
     *
     * @param webModule the root web module
     * @param wmConfig the web module configuration
     */
    private void setEmptyDescriptor(final WebModule webModule,
            final WebModuleConfig wmConfig) {

        WebBundleDescriptorImpl wbd = new WebBundleDescriptorImpl();
        wbd.setContextRoot(webModule.getPath());
        wmConfig.setDescriptor(wbd);
        try {
            Method setter = WebModule.class.getDeclaredMethod(
                    "setWebBundleDescriptor", WebBundleDescriptor.class);
            setter.setAccessible(true);
            setter.invoke(webModule, wbd);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            LOGGER.log(Level.WARNING,
                    "Unable to set the descriptor of " + webModule, ex);
        }
    }

    private ClassLoader getCommonClassLoader(final GlassFish gfService)
            throws GlassFishException {

        ClassLoaderHierarchy clh
                = gfService.getService(ClassLoaderHierarchy.class);
        return clh.getAPIClassLoader();
    }

    @Override
    public void stop(final BundleContext context) throws Exception {
        extenderReg.unregister();
        // let everything else happen in Extender.stop which will get called by
        // ExtenderManager.stop()
    }

    /**
     * Unregister the HTTP service.
     */
    private void undoActualWork() {
        if (whiteboard != null) {
            whiteboard.stop();
            whiteboard = null;
        }
        for (ServiceRegistration<?> registration : registrations) {
            registration.unregister();
        }
        registrations.clear();
        for (Host vs : vss.values()) {
            StandardContext standardContext
                    = StandardContext.class.cast(vs.findChild(contextPath));
            if (standardContext == null) {
                continue;
            }
            for (Container child : standardContext.findChildren()) {
                standardContext.removeChild(child);
            }
            vs.removeChild(standardContext);
        }
        // TODO(Sahoo): Need to call stop on all wrappers if they are not
        // automatically stopped when removed from context.
    }

    /**
     * Turn-off the session persistence.
     * @param ctx internal servlet context
     */
    private void switchOffSessionPersistence(final StandardContext ctx) {
        // See Jan's blog about how to switch off
        // Session persistence:
        // http://blogs.sun.com/jluehe/entry/how_to_disable_persisting_of
        Manager mgr = ctx.getManager();
        if (mgr == null) {
            mgr = new StandardManager();
            StandardManager.class.cast(mgr).setPathname(null);
            ctx.setManager(mgr);
        } else {
            try {
                StandardManager.class.cast(mgr).setPathname(null);
            } catch (ClassCastException cce) {
                LOGGER.logp(Level.INFO, "Activator",
                        "switchOffSessionPersistence",
                        "SessionManager {0} does not allow path name of"
                        + " session store to be configured.",
                        new Object[]{mgr});
            }
        }
    }

    /**
     * Get all GlassFish virtual servers.
     * @return comma-separated list of all defined virtual servers (including
     * __asadmin)
     * @throws GlassFishException if an error occurs
     */
    private String getAllVirtualServers() throws GlassFishException {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        Domain domain = gf.getService(Domain.class);
        String target = getInstanceName();
        Server server = domain.getServerNamed(target);
        if (server != null) {
            Config config = server.getConfig();
            if (config != null) {
                com.sun.enterprise.config.serverbeans.HttpService httpService =
                        config.getHttpService();
                if (httpService != null) {
                    List<VirtualServer> hosts = httpService.getVirtualServer();
                    if (hosts != null) {
                        for (VirtualServer host : hosts) {
                            if (first) {
                                sb.append(host.getId());
                                first = false;
                            } else {
                                sb.append(",");
                                sb.append(host.getId());
                            }
                        }
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * Get the GlassFish instance name.
     * @return name
     * @throws GlassFishException if an error occurs
     */
    private String getInstanceName() throws GlassFishException {
        ServerEnvironment se = gf.getService(ServerEnvironment.class);
        String target = se.getInstanceName();
        return target;
    }

    /**
     * Get the default GlassFish virtual server.
     * @return the default virtual server
     * @throws GlassFishException if an error occurs
     */
    private String getDefaultVirtualServer() throws GlassFishException {
        final String defaultVirtualServer = gf
                .getService(NetworkListener.class).findHttpProtocol()
                .getHttp().getDefaultVirtualServer();
        LOGGER.logp(Level.INFO, "Activator", "getDefaultVirtualServer",
                "defaultVirtualServer = {0}",
                new Object[]{defaultVirtualServer});
        return defaultVirtualServer;
    }


    /**
     * Extender for the OSGi HTTP module.
     */
    private class OSGiHtttpExtender implements Extender {

        /**
         * Get the GlassFish service.
         * @return GlassFish
         */
        private GlassFish getGlassFish() {
            GlassFish gfService = (GlassFish) bctx.getService(bctx
                    .getServiceReference(GlassFish.class.getName()));
            try {
                assert (gfService.getStatus() == GlassFish.Status.STARTED);
            } catch (GlassFishException e) {
                // TODO(Sahoo): Proper Exception Handling
                throw new RuntimeException(e);
            }
            return gfService;
        }

        /**
         * Get the GlassFish web container.
         * @return WebContainer
         * @throws GlassFishException if an error occurs
         */
        private WebContainer getWebContainer() throws GlassFishException {
            return gf.getService(WebContainer.class);
        }

        @Override
        public void start() {
            gf = getGlassFish();
            try {
                doActualWork(getWebContainer());
            } catch (GlassFishException e) {
                // TODO(Sahoo): Proper Exception Handling
                throw new RuntimeException(e);
            }
        }

        @Override
        public void stop() {
            undoActualWork();
        }
    }
}
