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

import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;

/**
 * {@link FilterConfig} of a whiteboard filter.
 */
final class WhiteboardFilterConfig implements FilterConfig {

    /**
     * The filter name.
     */
    private final String filterName;

    /**
     * The servlet context.
     */
    private final ServletContext servletContext;

    /**
     * The init parameters.
     */
    private final Map<String, String> initParams;

    /**
     * Create a new instance.
     * @param name the filter name
     * @param sc the servlet context
     * @param params the init parameters
     */
    WhiteboardFilterConfig(final String name, final ServletContext sc,
            final Map<String, String> params) {

        this.filterName = name;
        this.servletContext = sc;
        this.initParams = params;
    }

    @Override
    public String getFilterName() {
        return filterName;
    }

    @Override
    public ServletContext getServletContext() {
        return servletContext;
    }

    @Override
    public String getInitParameter(final String name) {
        return initParams.get(name);
    }

    @Override
    public Enumeration<String> getInitParameterNames() {
        return Collections.enumeration(initParams.keySet());
    }
}
