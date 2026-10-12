/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation. All rights reserved.
 * Copyright (c) 2011, 2019 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0, which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.glassfish.fighterfish.sample.uas.entities;

import java.io.Serializable;
import java.util.Date;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.NamedQueries;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;

/**
 * Entity implementation class for Entity: LoginAttempt.
 */
@SuppressWarnings("checkstyle:DesignForExtension")
@Entity
@NamedQueries(
        @NamedQuery(name = "LoginAttempt.findAll",
                query = "select o from LoginAttempt o"))
public class LoginAttempt implements Serializable {

    /**
     * Serial number.
     */
    @Id
    @GeneratedValue
    private long serialNumber;

    /**
     * Login result.
     */
    private boolean successful;

    /**
     * User credentials.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    private UserCredential userCredential;

    /**
     * Timestamp.
     */
    @Temporal(TemporalType.TIMESTAMP)
    private Date timeStamp = new Date();

    /**
     * Serialization UID.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Create a new instance.
     */
    public LoginAttempt() {
        super();
    }

    /**
     * Get serial number.
     * @return serial numner
     */
    public long getSerialNumber() {
        return this.serialNumber;
    }

    /**
     * Test if the login attempt is successful.
     * @return {@code true} if successful, {@code false} otherwise
     */
    public boolean isSuccessful() {
        return this.successful;
    }

    /**
     * Set the login result.
     * @param isSuccessful login result
     */
    public void setSuccessful(final boolean isSuccessful) {
        this.successful = isSuccessful;
    }

    /**
     * Get the user credentials.
     * @return UserCredential
     */
    public UserCredential getUserCredential() {
        return this.userCredential;
    }

    /**
     * Set the user credentials.
     * @param creds new user credentials
     */
    public void setUserCredential(final UserCredential creds) {
        this.userCredential = creds;
    }

    /**
     * Get the timestamp.
     * @return Date
     */
    public Date getTimeStamp() {
        return timeStamp;
    }

    /**
     * Set the new timestamp.
     * @param tstamp new timestamp
     */
    public void setTimeStamp(final Date tstamp) {
        this.timeStamp = tstamp;
    }

    @Override
    public String toString() {
        return "LoginAttempt: ("
                + "serialNumber = " + serialNumber
                + "user = " + getUserCredential().getName()
                + "isSucecssful = " + isSuccessful()
                + ")";
    }
}
