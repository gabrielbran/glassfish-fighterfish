/*
 * Copyright (c) 2011, 2019 Oracle and/or its affiliates. All rights reserved.
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
package org.glassfish.fighterfish.test.util;

import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.options.ProvisionOption;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.FrameworkFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.glassfish.fighterfish.test.util.Constants.FW_CONFIG_FILE_NAME;
import static org.ops4j.pax.exam.CoreOptions.bundle;
import static org.ops4j.pax.exam.CoreOptions.bundleStartLevel;
import static org.ops4j.pax.exam.CoreOptions.cleanCaches;
import static org.ops4j.pax.exam.CoreOptions.frameworkProperty;
import static org.ops4j.pax.exam.CoreOptions.frameworkStartLevel;
import static org.ops4j.pax.exam.CoreOptions.junitBundles;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;
import static org.ops4j.pax.exam.CoreOptions.options;
import static org.ops4j.pax.exam.CoreOptions.systemTimeout;
import static org.ops4j.pax.exam.CoreOptions.workingDirectory;
import static org.ops4j.pax.exam.OptionUtils.combine;

/**
 * Provides common PAX configuration for all tests.
 */
public final class PaxExamConfigurator {

    /**
     * Logger.
     */
    protected static final Logger LOGGER = Logger.getLogger(
            PaxExamConfigurator.class.getPackage().getName());

    /**
     * GlassFish install home.
     */
    private final File gfHome;

    /**
     * Start level of the bundles that must be started after GlassFish.
     */
    private static final int GLASSFISH_STARTED_LEVEL = 4;

    /**
     * File name of the GlassFish bootstrap bundle.
     */
    private static final String GF_BOOTSTRAP_JAR =
            "glassfish-osgi-bootstrap.jar";

    /**
     * Timeout configuration for PAX-EXAM system timeout.
     */
    private final long timeout;

    /**
     * Create a new instance.
     * @param glassFishHome GlassFish install home
     * @param sysTimeout PAX-EXAM system timeout
     */
    public PaxExamConfigurator(final File glassFishHome,
            final long sysTimeout) {

        this.gfHome = glassFishHome;
        this.timeout = sysTimeout;
    }

    /**
     * Create all PAX-EXAM configuration options.
     * @return Option[]
     * @throws IOException if an error occurs while reading the config files
     */
    public Option[] configure() throws IOException {
        return combine(combine(frameworkConfiguration(),
                provisioningBundles()), paxConfiguration());
    }

    /**
     * Get the provisioning bundles options.
     * @return Option[]
     */
    private Option[] provisioningBundles() {
        final String version = Version.getVersion();
        LOGGER.logp(Level.INFO, "PaxExamConfigurator", "provisioningBundles",
                "FighterFish Test Util Version = {0}", new Object[]{version});

        final Option[] gfBundles = glassFishBundles();
        final Option testUtilBundle;

        File testUtilBundleFile = new File("target/fighterfish-test-util.jar");
        if (testUtilBundleFile.exists()) {
            testUtilBundle = bundle(testUtilBundleFile.toURI().toString());
        } else {
            testUtilBundle = mavenBundle()
                    .groupId("org.glassfish.fighterfish")
                    .artifactId("fighterfish-test-util")
                    .versionAsInProject();
        }
        return combine(gfBundles,
                bundleStartLevel(GLASSFISH_STARTED_LEVEL),
                frameworkStartLevel(GLASSFISH_STARTED_LEVEL + 1),
                junitBundles(),
                mavenBundle()
                        .groupId("org.apache.httpcomponents")
                        .artifactId("httpclient-osgi")
                        .versionAsInProject(),
                mavenBundle()
                        .groupId("org.apache.httpcomponents")
                        .artifactId("httpcore-osgi")
                        .versionAsInProject(),
                mavenBundle()
                        .groupId("org.slf4j")
                        .artifactId("jcl-over-slf4j")
                        .versionAsInProject(),
                mavenBundle()
                        .groupId("org.slf4j")
                        .artifactId("slf4j-api")
                        .versionAsInProject(),
                mavenBundle()
                        .groupId("org.slf4j")
                        .artifactId("slf4j-jdk14")
                        .versionAsInProject()
                        .noStart(),
                // GlassFish 8 does not ship OSGi Declarative Services
                mavenBundle()
                        .groupId("org.osgi")
                        .artifactId("org.osgi.service.component")
                        .versionAsInProject(),
                mavenBundle()
                        .groupId("org.osgi")
                        .artifactId("org.osgi.service.metatype")
                        .versionAsInProject(),
                mavenBundle()
                        .groupId("org.osgi")
                        .artifactId("org.osgi.util.function")
                        .versionAsInProject(),
                mavenBundle()
                        .groupId("org.osgi")
                        .artifactId("org.osgi.util.promise")
                        .versionAsInProject(),
                mavenBundle()
                        .groupId("org.apache.felix")
                        .artifactId("org.apache.felix.scr")
                        .versionAsInProject(),
                // enables the legacy (javax.servlet) OSGi HttpService
                mavenBundle()
                        .groupId("javax.servlet")
                        .artifactId("javax.servlet-api")
                        .versionAsInProject(),
                mavenBundle()
                        .groupId("org.apache.felix")
                        .artifactId("org.apache.felix.http.wrappers")
                        .versionAsInProject(),
                testUtilBundle
        );
    }

    /**
     * Read the framework configuration file and return corresponding PAX
     * options.
     * @return Option[]
     * @throws IOException if an error occurs while reading the config file
     */
    /**
     * Get the GlassFish bundles to provision.
     *
     * GlassFish is started first (start level 1), so that the packages of its
     * modules are available when the other bundles start. The jars of
     * {@code lib/bootstrap} must be on the class path, their packages are
     * exported by the system bundle. Since GlassFish 8,
     * the bootstrap bundle depends on HK2 and on other GlassFish modules: all
     * the modules are installed (as done by the GlassFish launcher) and only
     * the bootstrap bundle is started. GlassFish reuses the installed bundles
     * when it provisions its modules, since their locations are the same.
     *
     * @return options
     */
    private Option[] glassFishBundles() {
        List<Option> result = new ArrayList<Option>();
        Set<File> coreBundles = coreBundles();
        Set<File> jars = new TreeSet<File>(coreBundles);
        File[] modules = new File(gfHome, "modules").listFiles();
        if (modules != null) {
            for (File module : modules) {
                if (module.isFile() && module.getName().endsWith(".jar")
                        && !module.getName().equals(GF_BOOTSTRAP_JAR)) {
                    jars.add(module);
                }
            }
        }
        for (File jar : jars) {
            ProvisionOption<?> option = bundle(jar.toURI().toString())
                    .startLevel(1);
            if (!coreBundles.contains(jar)) {
                option.noStart();
            }
            result.add(option);
        }
        result.add(bundle(new File(gfHome, "modules/" + GF_BOOTSTRAP_JAR)
                .toURI().toString()).startLevel(1));
        return result.toArray(new Option[0]);
    }

    /**
     * Get the core bundles, i.e. the bundles started by the GlassFish launcher
     * before the bootstrap bundle ({@code hk2.bundles} in
     * {@code config/osgi.properties}).
     *
     * @return the core bundle files
     */
    private Set<File> coreBundles() {
        Set<File> result = new TreeSet<File>();
        Properties osgiProps = new Properties();
        File file = new File(gfHome, "config/osgi.properties");
        try (InputStream is = new FileInputStream(file)) {
            osgiProps.load(is);
        } catch (IOException ex) {
            LOGGER.log(Level.WARNING, "Unable to read " + file, ex);
            return result;
        }
        String prefix = "${com.sun.aas.installRootURI}";
        String value = osgiProps.getProperty("hk2.bundles", "");
        for (String entry : value.split("\\s+")) {
            if (entry.startsWith(prefix)) {
                File jar = new File(gfHome,
                        entry.substring(prefix.length()));
                if (jar.isFile()) {
                    result.add(jar);
                }
            }
        }
        return result;
    }

    private Option[] frameworkConfiguration() throws IOException {
        // We currently read framework options from a separate file,
        // but we could as well inline them here in code.
        final Properties properties = readFrameworkConfiguration();

        // override by system properties if set in system. We override
        // everything except fileinstall properties as GlassFish's domain.xml
        // is known to set them incorrectly.
        for (Map.Entry<Object, Object> entry : properties.entrySet()) {
            Object orig = properties.get(entry.getKey());
            Object override = System.getProperty((String) entry.getKey());
            if (override != null) {
                if (String.class.cast(entry.getKey())
                        .startsWith("felix.fileinstall.")) {

                    LOGGER.logp(Level.INFO, "PaxExamConfigurator",
                            "frameworkConfiguration",
                            "Ignoring overriding of {0}", new Object[]{entry});
                    continue;
                }
                properties.put(entry.getKey(), override);
                LOGGER.logp(Level.INFO, "PaxExamConfigurator",
                        "frameworkConfiguration",
                        "entry = {0}, original = {1}, override = {2}",
                        new Object[]{entry.getKey(), orig, override});
            }
        }
        properties.put(Constants.FRAMEWORK_SYSTEMPACKAGES,
                systemPackages());
        List<Option> options = convertToOptions(properties);
        return options.toArray(new Option[options.size()]);
    }

    /**
     * Compute the packages exported by the system bundle, as done by the
     * GlassFish launcher: the packages exported by the OSGi framework, by
     * the jars in {@code lib/bootstrap} and by the JDK modules.
     *
     * @return the value of {@code org.osgi.framework.system.packages}
     * @throws IOException if an error occurs while reading a manifest
     */
    private String systemPackages() throws IOException {
        List<String> exports = new ArrayList<String>();
        URL framework = FrameworkFactory.class.getProtectionDomain()
                .getCodeSource().getLocation();
        addExports(exports, framework);
        File[] libs = new File(gfHome, "lib/bootstrap").listFiles();
        if (libs != null) {
            Arrays.sort(libs);
            for (File lib : libs) {
                if (lib.getName().endsWith(".jar")) {
                    addExports(exports, lib.toURI().toURL());
                }
            }
        }
        Set<String> javaPackages = new TreeSet<String>();
        for (Module module : ModuleLayer.boot().modules()) {
            for (String pkg : module.getPackages()) {
                if (module.isExported(pkg) || module.isOpen(pkg)) {
                    javaPackages.add(pkg);
                }
            }
        }
        exports.addAll(javaPackages);
        return String.join(", ", exports);
    }

    /**
     * Add the packages exported by a jar.
     * @param exports the list to add to
     * @param jar the jar URL
     * @throws IOException if an error occurs while reading the manifest
     */
    private static void addExports(final List<String> exports, final URL jar)
            throws IOException {

        try (JarInputStream jis = new JarInputStream(jar.openStream())) {
            Manifest mf = jis.getManifest();
            String value = mf == null
                    ? null
                    : mf.getMainAttributes().getValue(Constants.EXPORT_PACKAGE);
            if (value != null && !value.trim().isEmpty()) {
                exports.add(value);
            }
        }
    }

    /**
     * Create the PAX specific configuration.
     * @return Option[]
     */
    private Option[] paxConfiguration() {
        return options(systemTimeout(timeout), cleanCaches(true));
    }

    /**
     * Adapts properties to pax-exam options.
     *
     * @param properties properties to convert to options
     * @return List<Option>
     */
    private List<Option> convertToOptions(final Properties properties) {
        List<Option> options = new ArrayList<Option>();
        for (Map.Entry<Object, Object> entry : properties.entrySet()) {
            if (entry.getKey().equals(Constants.FRAMEWORK_STORAGE)) {
                // Starting with pax-exam 2.1.0, we need to specify framework
                // storage using workingDirectory option
                options.add(workingDirectory((String) entry.getValue()));
                LOGGER.logp(Level.INFO, "PaxExamConfigurator",
                        "convertToOptions", "OSGi cache dir = {0}",
                        new Object[]{entry.getValue()});
            }
            options.add(frameworkProperty((String) entry.getKey())
                    .value(entry.getValue()));
        }
        return options;
    }

    /**
     * Read the framework config file into properties.
     * @return Properties
     * @throws IOException if an error occurs while reading the config file
     */
    private Properties readFrameworkConfiguration() throws IOException {
        Properties properties = new Properties();
        LOGGER.logp(Level.INFO, "DefaultPaxExamConfiguration",
                "readFrameworkConfiguration", "fwConfigFileName = {0}",
                new Object[]{FW_CONFIG_FILE_NAME});
        InputStream stream = getClass()
                .getResourceAsStream(FW_CONFIG_FILE_NAME);
        if (stream != null) {
            try {
                properties.load(stream);
            } finally {
                stream.close();
            }
            PropertiesUtil.substVars(properties);
        } else {
            LOGGER.logp(Level.WARNING, "DefaultPaxExamConfiguration",
                    "readFrameworkConfiguration",
                    "{0} not found. Using default values",
                    new Object[]{FW_CONFIG_FILE_NAME});
        }
        return properties;
    }
}
