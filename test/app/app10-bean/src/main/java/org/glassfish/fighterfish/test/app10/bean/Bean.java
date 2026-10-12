/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation. All rights reserved.
 * Copyright (c) 2011, 2019 Oracle and/or its affiliates. All rights reserved.
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
package org.glassfish.fighterfish.test.app10.bean;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Named;

/**
 * Simple CDI bean used by a JSF page (Faces managed beans were removed in
 * Jakarta Faces 4).
 */
@Named("bean")
@RequestScoped
public class Bean {

    /**
     * Foo.
     */
    private String foo = "bar";

    /**
     * Get foo.
     * @return String
     */
    public String getFoo() {
        return foo;
    }

    /**
     * Set foo.
     * @param newFoo the new value of foo
     */
    public void setFoo(final String newFoo) {
        this.foo = newFoo;
    }
}
