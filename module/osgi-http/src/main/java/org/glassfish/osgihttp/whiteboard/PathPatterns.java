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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Servlet URL pattern utilities.
 */
final class PathPatterns {

    /**
     * Cannot be instanciated.
     */
    private PathPatterns() {
    }

    /**
     * Convert a service property value to an array of strings.
     * @param value the property value ({@code String}, {@code String[]} or
     * {@code Collection})
     * @return array, empty if the value is {@code null}
     */
    static String[] toStringArray(final Object value) {
        if (value == null) {
            return new String[0];
        }
        if (value instanceof String[]) {
            return (String[]) value;
        }
        if (value instanceof Collection) {
            List<String> list = new ArrayList<String>();
            for (Object o : (Collection<?>) value) {
                list.add(String.valueOf(o));
            }
            return list.toArray(new String[0]);
        }
        return new String[]{String.valueOf(value)};
    }

    /**
     * Test if a servlet URL pattern is valid.
     * @param pattern the pattern
     * @return {@code true} if valid
     */
    static boolean isValid(final String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return false;
        }
        if (pattern.startsWith("*.")) {
            return pattern.indexOf('/') < 0 && pattern.length() > 2;
        }
        if (!pattern.startsWith("/")) {
            return false;
        }
        int star = pattern.indexOf('*');
        return star < 0 || (star == pattern.length() - 1
                && pattern.endsWith("/*"));
    }

    /**
     * Prefix a servlet URL pattern with the path of a servlet context.
     * @param contextPath the servlet context path (e.g. {@code "/"} or
     * {@code "/ctx"})
     * @param pattern the pattern
     * @return the prefixed pattern, or {@code null} if the pattern cannot be
     * expressed relative to the root web module
     */
    static String prefix(final String contextPath, final String pattern) {
        if (contextPath == null || "/".equals(contextPath)
                || contextPath.isEmpty()) {
            return pattern;
        }
        if (pattern.startsWith("*.")) {
            // extension mappings cannot be scoped to a path
            return null;
        }
        if ("/".equals(pattern)) {
            // default servlet of the servlet context
            return contextPath + "/*";
        }
        return contextPath + pattern;
    }

    /**
     * Test if a request path matches a servlet URL pattern.
     * @param pattern the pattern
     * @param path the request path, relative to the root web module
     * @return {@code true} if it matches
     */
    static boolean matches(final String pattern, final String path) {
        if ("/*".equals(pattern) || "/".equals(pattern)) {
            return true;
        }
        if (pattern.startsWith("*.")) {
            return path.endsWith(pattern.substring(1));
        }
        if (pattern.endsWith("/*")) {
            String base = pattern.substring(0, pattern.length() - 2);
            return path.equals(base) || path.startsWith(base + "/");
        }
        return pattern.equals(path);
    }
}
