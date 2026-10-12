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

import org.osgi.service.servlet.context.ServletContextHelper;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;

/**
 * Servlet that serves resources resolved with a
 * {@link ServletContextHelper}.
 *
 * The requested path is the part of the request URI that follows the context
 * path. The given alias (a path prefix) is replaced by the given internal
 * name before the resource is looked up.
 */
public final class OSGiResourceServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    /**
     * The path prefix that is mapped to {@link #name}.
     */
    private final String alias;

    /**
     * The internal name (resource prefix).
     */
    private final String name;

    /**
     * The servlet context helper used to resolve the resources.
     */
    private final transient ServletContextHelper httpContext;

    /**
     * Create a new instance.
     * @param sAlias the path prefix that is mapped to {@code sName}, e.g.
     * {@code /files}; {@code "/"} or an empty string maps every path
     * @param sName the internal name (resource prefix)
     * @param sHttpCtx the servlet context helper
     */
    public OSGiResourceServlet(final String sAlias, final String sName,
            final ServletContextHelper sHttpCtx) {

        this.alias = "/".equals(sAlias) ? "" : sAlias;
        this.name = sName;
        this.httpContext = sHttpCtx;
    }

    @Override
    protected void service(final HttpServletRequest req,
            final HttpServletResponse resp)
            throws ServletException, IOException {

        final String resPath = getResourcePath(req);
        if (resPath == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        URL url = httpContext.getResource(resPath);
        if (url == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        // contentType must be set before writing anything to the stream
        // as for long data, stream gets flushed before we have finished
        // writing everything.
        String mimeType = httpContext.getMimeType(resPath);
        if (mimeType == null) {
            mimeType = getServletConfig().getServletContext()
                    .getMimeType(resPath);
        }
        if (mimeType != null) {
            resp.setContentType(mimeType);
        }
        URLConnection conn = url.openConnection();
        int writeCount = writeToStream(conn, resp.getOutputStream());
        resp.setContentLength(writeCount);
        resp.setStatus(HttpServletResponse.SC_OK);
    }

    /**
     * Compute the resource path for a given request.
     * @param req the request
     * @return the resource path, or {@code null} if the path does not start
     * with the alias or tries to escape it
     */
    private String getResourcePath(final HttpServletRequest req) {
        String servletPath = req.getServletPath();
        String pathInfo = req.getPathInfo();
        String requestedPath = pathInfo == null
                ? servletPath
                : servletPath + pathInfo;
        if (requestedPath.contains("..")) {
            return null;
        }
        if (!requestedPath.startsWith(alias)) {
            return null;
        }
        String internalName;
        if ("/".equals(name)) {
            internalName = "";
        } else {
            internalName = name;
        }
        return internalName + requestedPath.substring(alias.length());
    }

    /**
     * Copy the content of a connection to an output stream.
     * @param connection the connection to read from
     * @param os the output stream to write to
     * @return the number of bytes written
     * @throws IOException if an error occurs
     */
    @SuppressWarnings("checkstyle:magicnumber")
    private int writeToStream(final URLConnection connection,
            final OutputStream os) throws IOException {

        try (InputStream is = connection.getInputStream()) {
            byte[] buf = new byte[8192];
            int readCount = is.read(buf);
            int writeCount = 0;
            while (readCount != -1) {
                os.write(buf, 0, readCount);
                writeCount += readCount;
                readCount = is.read(buf);
            }
            return writeCount;
        }
    }
}
