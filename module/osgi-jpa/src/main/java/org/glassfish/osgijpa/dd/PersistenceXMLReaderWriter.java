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
package org.glassfish.osgijpa.dd;

import org.glassfish.internal.api.Globals;
import org.glassfish.internal.api.ClassLoaderHierarchy;

import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Unmarshaller;
import jakarta.xml.bind.UnmarshallerHandler;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.net.URL;
import java.util.Arrays;
import java.util.Collection;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLFilterImpl;

/**
 * Utility to read persistence XML with JAX-B.
 */
public final class PersistenceXMLReaderWriter {

    /**
     * Namespace of the Jakarta Persistence 3.x deployment descriptor, used
     * by the JAX-B model of this package.
     */
    static final String PERSISTENCE_NS =
            "https://jakarta.ee/xml/ns/persistence";

    /**
     * Namespaces of older persistence.xml versions, mapped to
     * {@link #PERSISTENCE_NS} when reading.
     */
    private static final Collection<String> LEGACY_PERSISTENCE_NS =
            Arrays.asList("http://java.sun.com/xml/ns/persistence",
                    "http://xmlns.jcp.org/xml/ns/persistence");

    /**
     * Unmarshall from URL.
     * @param pxmlURL url
     * @return Persistence
     * @throws IOException if an error occurs
     */
    public Persistence read(final URL pxmlURL) throws IOException {
        InputStream is = pxmlURL.openStream();
        try {
            return read(is);
        } finally {
            is.close();
        }
    }

    /**
     * Unmarshall from input stream
     * @param is input stream
     * @return Persistence
     * @throws IOException if an error occurs
     */
    public Persistence read(final InputStream is) throws IOException {
        try {
            Unmarshaller unmarshaller = getUnmarshaller();
            UnmarshallerHandler handler =
                    unmarshaller.getUnmarshallerHandler();
            XMLFilterImpl filter = new NamespaceMappingFilter(
                    newXMLReader());
            filter.setContentHandler(handler);
            filter.parse(new InputSource(is));
            return (Persistence) handler.getResult();
        } catch (SAXException | ParserConfigurationException e) {
            IOException ioe = new IOException(e);
            throw ioe;
        } catch (JAXBException je) {
            je.printStackTrace();
            IOException ioe = new IOException();
            ioe.initCause(je);
            throw ioe;
        }
    }

    /**
     * Marshall.
     * @param persistence instance to write out
     * @param os output stream
     * @throws IOException if an error occurs
     */
    public void write(final Persistence persistence, final OutputStream os)
            throws IOException {

        try {
            getMarshaller(persistence.getClass()).marshal(persistence,
                    os);
        } catch (JAXBException je) {
            je.printStackTrace();
            IOException ioe = new IOException();
            ioe.initCause(je);
            throw ioe;
        }
    }

    /**
     * Marshall.
     * @param persistence instance to write out
     * @param writer writer to use
     * @throws IOException if an error occurs
     */
    public void write(final Persistence persistence, final Writer writer)
            throws IOException {

        try {
            getMarshaller(persistence.getClass()).marshal(persistence,
                    writer);
        } catch (JAXBException je) {
            je.printStackTrace();
            IOException ioe = new IOException();
            ioe.initCause(je);
            throw ioe;
        }
    }

    /**
     * Get marshaller for a given class.
     * @param clazz the class
     * @return Marshaller
     * @throws JAXBException if an error occurs
     */
    private Marshaller getMarshaller(final Class<?> clazz)
            throws JAXBException {

        JAXBContext jc = getJAXBContext();
        Marshaller marshaller = jc.createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT,
                Boolean.TRUE);
        return marshaller;
    }

    /**
     * Get unmarshaller
     * @return Unmarshaller
     * @throws JAXBException if an error occurs
     */
    private Unmarshaller getUnmarshaller() throws JAXBException {
        JAXBContext jc = getJAXBContext();
        return jc.createUnmarshaller();
    }

    /**
     * Get the JAXB context.
     * @return JAXBContext
     * @throws JAXBException if an error occurs
     */
    private JAXBContext getJAXBContext() throws JAXBException {
        // We need to set context class loader to be CommonClassLoader,
        // otherwise our stupid JAXB implementation
        // won't be able to locate the default JAXB context factory class.
        final Thread thread = Thread.currentThread();
        ClassLoader oldCL = thread.getContextClassLoader();
        try {
            ClassLoader ccl = Globals.get(ClassLoaderHierarchy.class)
                    .getCommonClassLoader();
            thread.setContextClassLoader(ccl);
            JAXBContext jc = JAXBContext.newInstance(ObjectFactory.class);
            return jc;
        } finally {
            thread.setContextClassLoader(oldCL);
        }
    }

    /**
     * Create a namespace aware XML reader.
     * @return XMLReader
     * @throws ParserConfigurationException if an error occurs
     * @throws SAXException if an error occurs
     */
    private static XMLReader newXMLReader()
            throws ParserConfigurationException, SAXException {

        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(
                "http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newSAXParser().getXMLReader();
    }

    /**
     * SAX filter that maps the namespaces of older persistence.xml versions
     * to the Jakarta Persistence namespace.
     */
    private static final class NamespaceMappingFilter extends XMLFilterImpl {

        /**
         * Create a new instance.
         * @param parent the reader to filter
         */
        NamespaceMappingFilter(final XMLReader parent) {
            super(parent);
        }

        /**
         * Map a namespace URI.
         * @param uri the namespace URI
         * @return the mapped namespace URI
         */
        private static String map(final String uri) {
            if (LEGACY_PERSISTENCE_NS.contains(uri)) {
                return PERSISTENCE_NS;
            }
            return uri;
        }

        @Override
        public void startPrefixMapping(final String prefix, final String uri)
                throws SAXException {

            super.startPrefixMapping(prefix, map(uri));
        }

        @Override
        public void startElement(final String uri, final String localName,
                final String qName, final Attributes atts)
                throws SAXException {

            super.startElement(map(uri), localName, qName, atts);
        }

        @Override
        public void endElement(final String uri, final String localName,
                final String qName) throws SAXException {

            super.endElement(map(uri), localName, qName);
        }
    }
}
