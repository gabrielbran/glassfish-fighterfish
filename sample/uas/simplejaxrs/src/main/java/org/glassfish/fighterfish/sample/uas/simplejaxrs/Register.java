/*
 * Copyright (c) 2012, 2019 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0, which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.glassfish.fighterfish.sample.uas.simplejaxrs;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.servlet.ServletException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.UriInfo;

import org.glassfish.fighterfish.sample.uas.api.UserAuthService;
import org.glassfish.osgicdi.OSGiService;
import org.osgi.framework.ServiceException;

/**
 * Example resource class hosted at the URI path "/register".
 */
@Path("/register")
@RequestScoped
@SuppressWarnings("checkstyle:DesignForExtension")
public class Register {

    /**
     * URI info.
     */
    @Context
    private UriInfo uriInfo;

    /**
     * Request.
     */
    @Context
    private Request request;

    /**
     * User authentication service.
     */
    @Inject
    @OSGiService(dynamic = true)
    private UserAuthService uas;

    /**
     * Method processing HTTP GET requests, producing "text/plain" MIME media
     * type.
     *
     * @return String that will be send back as a response of type "text/plain".
     */
    @POST
    @Path("/getIt")
    @Produces("text/plain")
    public String getIt() {
        return "Hi there!";
    }

    /**
     * Register a user.
     * @param name user name
     * @param password user password
     * @return {@code "Registered"} if successful, {@code "Failed"} otherwise
     * @throws ServletException if an error occurs
     */
    @POST
    @Produces(MediaType.TEXT_HTML)
    @Consumes(MediaType.TEXT_PLAIN)
    public String register(
            @QueryParam("name") final String name,
            @QueryParam("password") final String password)
            throws ServletException {

        try {
            if (uas.register(name, password)) {
                return "Registered";
            } else {
                return "Failed";
            }
        } catch (ServiceException e) {
        }
        return "none";
    }
}
