/*
 *  Copyright 2017 SmartBear Software
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.mockserver.openapi.examples;

import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.openapi.examples.models.ArrayExample;
import org.mockserver.openapi.examples.models.Example;
import org.mockserver.openapi.examples.models.ObjectExample;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.slf4j.event.Level.WARN;

/**
 * See: https://github.com/swagger-api/swagger-inflector
 */
public class XmlExampleSerializer {
    private static final MockServerLogger MOCK_SERVER_LOGGER = new MockServerLogger(XmlExampleSerializer.class);

    int depth = 0;

    // tracks namespace URIs already declared on an ancestor so each namespace is emitted only once
    // (children inherit the declaration); prefix -> uri for prefixed namespaces, uri for default namespace
    private final Set<String> declaredNamespaceUris = new HashSet<>();
    private final Set<String> declaredPrefixes = new HashSet<>();

    public String serialize(Example o) {
        XMLStreamWriter writer = null;
        try {
            XMLOutputFactory f = XMLOutputFactory.newFactory();
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            writer = f.createXMLStreamWriter(out, StandardCharsets.UTF_8.name());

            writer.writeStartDocument("UTF-8", "1.1");
            writeTo(writer, o);
            writer.close();
            return out.toString(StandardCharsets.UTF_8);
        } catch (XMLStreamException e) {
            MOCK_SERVER_LOGGER.logEvent(
                new LogEntry()
                    .setLogLevel(WARN)
                    .setMessageFormat("exception while serialising example to XML - {}")
                    .setArguments(e.getMessage())
                    .setThrowable(e)
            );
            return null;
        }
    }

    public void writeTo(XMLStreamWriter writer, Example o) throws XMLStreamException {
        depth += 1;
        if (o instanceof ObjectExample) {
            ObjectExample or = (ObjectExample) o;
            String name = o.getName();
            if (depth == 1 && name == null) {
                // write primitive type container
                name = getTypeName(o);
            }
            if (name == null) {
                name = "AnonymousModel";
            }

            String declaredUri = writeStartElement(writer, o.getPrefix(), name, o.getNamespace());

            for (String key : or.keySet()) {
                Object obj = or.get(key);
                if (obj instanceof Example) {
                    Example example = (Example) obj;
                    if (example.getName() == null) {
                        example.setName(key);
                    }

                    writeTo(writer, (Example) obj);
                }
            }
            writer.writeEndElement();
            undeclareNamespace(o.getPrefix(), declaredUri);
        } else if (o instanceof ArrayExample) {
            ArrayExample ar = (ArrayExample) o;
            String wrappedDeclaredUri = null;
            if (o.getWrapped() != null && o.getWrapped()) {
                if (o.getWrappedName() != null) {
                    wrappedDeclaredUri = writeStartElement(writer, o.getPrefix(), o.getWrappedName(), o.getNamespace());
                } else {
                    wrappedDeclaredUri = writeStartElement(writer, o.getPrefix(), o.getName() + "s", o.getNamespace());
                }
            }
            for (Example item : ar.getItems()) {
                String itemDeclaredUri = null;
                if (item.getName() == null) {

                    String name = o.getName();
                    if (name == null) {
                        name = item.getTypeName();
                    }

                    itemDeclaredUri = writeStartElement(writer, o.getPrefix(), name, o.getNamespace());
                }
                writeTo(writer, item);
                if (item.getName() == null && o.getName() != null) {
                    writer.writeEndElement();
                    undeclareNamespace(o.getPrefix(), itemDeclaredUri);
                }
            }
            if (o.getWrapped() != null && o.getWrapped()) {
                writer.writeEndElement();
                undeclareNamespace(o.getPrefix(), wrappedDeclaredUri);
            }
        } else {
            String name = o.getName();
            if (depth == 1 && name == null) {
                // write primitive type container
                name = getTypeName(o);
            }
            if (o.getAttribute() != null && o.getAttribute()) {
                writeAttribute(writer, o.getPrefix(), name, o.getNamespace(), o.asString());
            } else if (name == null) {
                writer.writeCharacters(o.asString());
            } else {
                String declaredUri = writeStartElement(writer, o.getPrefix(), name, o.getNamespace());
                writer.writeCharacters(o.asString());
                writer.writeEndElement();
                undeclareNamespace(o.getPrefix(), declaredUri);
            }
        }
        depth -= 1;
    }

    /**
     * Writes a start element, declaring its XML namespace via the StAX namespace API so the output is
     * well-formed. Each namespace is declared only once on the element that introduces it (children
     * inherit the declaration), avoiding redundant re-declaration.
     *
     * @return the namespace URI declared on this element (so the caller can un-declare it on close), or
     * {@code null} if no namespace was declared here
     */
    private String writeStartElement(XMLStreamWriter writer, String prefix, String name, String namespace) throws XMLStreamException {
        if (namespace == null) {
            writer.writeStartElement(name);
            return null;
        }
        if (prefix != null && !prefix.isEmpty()) {
            // prefixed namespace -> <prefix:name xmlns:prefix="ns">
            writer.setPrefix(prefix, namespace);
            writer.writeStartElement(prefix, name, namespace);
            if (!declaredPrefixes.contains(prefix)) {
                writer.writeNamespace(prefix, namespace);
                declaredPrefixes.add(prefix);
                return namespace;
            }
            return null;
        }
        // default namespace -> <name xmlns="ns">
        writer.setDefaultNamespace(namespace);
        writer.writeStartElement(namespace, name);
        if (!declaredNamespaceUris.contains(namespace)) {
            writer.writeDefaultNamespace(namespace);
            declaredNamespaceUris.add(namespace);
            return namespace;
        }
        return null;
    }

    /**
     * Writes an attribute, binding and declaring its prefix when the attribute is namespaced. A namespaced
     * attribute with no prefix is invalid in XML (attributes are not covered by a default namespace), so in
     * that case the attribute is written without a namespace to keep the output well-formed.
     */
    private void writeAttribute(XMLStreamWriter writer, String prefix, String name, String namespace, String value) throws XMLStreamException {
        if (namespace != null && prefix != null && !prefix.isEmpty()) {
            if (!declaredPrefixes.contains(prefix)) {
                writer.setPrefix(prefix, namespace);
                writer.writeNamespace(prefix, namespace);
                declaredPrefixes.add(prefix);
            }
            writer.writeAttribute(prefix, namespace, name, value);
        } else {
            writer.writeAttribute(name, value);
        }
    }

    private void undeclareNamespace(String prefix, String declaredUri) {
        if (declaredUri != null) {
            if (prefix != null && !prefix.isEmpty()) {
                declaredPrefixes.remove(prefix);
            } else {
                declaredNamespaceUris.remove(declaredUri);
            }
        }
    }

    public String getTypeName(Example o) {
        return o.getTypeName();
    }
}
