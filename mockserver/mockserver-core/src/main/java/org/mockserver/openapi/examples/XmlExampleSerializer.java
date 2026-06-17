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

import static org.slf4j.event.Level.WARN;

/**
 * See: https://github.com/swagger-api/swagger-inflector
 */
public class XmlExampleSerializer {
    private static final MockServerLogger MOCK_SERVER_LOGGER = new MockServerLogger(XmlExampleSerializer.class);

    int depth = 0;

    public String serialize(Example o) {
        XMLStreamWriter writer = null;
        try {
            XMLOutputFactory f = XMLOutputFactory.newFactory();
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            writer = f.createXMLStreamWriter(out, StandardCharsets.UTF_8.name());

            writer.writeStartDocument("UTF-8", "1.0");
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

            writeStartElement(writer, o.getPrefix(), name, o.getNamespace());

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
        } else if (o instanceof ArrayExample) {
            ArrayExample ar = (ArrayExample) o;
            if (o.getWrapped() != null && o.getWrapped()) {
                if (o.getWrappedName() != null) {
                    writeStartElement(writer, o.getPrefix(), o.getWrappedName(), o.getNamespace());
                } else {
                    writeStartElement(writer, o.getPrefix(), o.getName() + "s", o.getNamespace());
                }
            }
            for (Example item : ar.getItems()) {
                if (item.getName() == null) {

                    String name = o.getName();
                    if (name == null) {
                        name = item.getTypeName();
                    }

                    writeStartElement(writer, o.getPrefix(), name, o.getNamespace());
                }
                writeTo(writer, item);
                if (item.getName() == null && o.getName() != null) {
                    writer.writeEndElement();
                }
            }
            if (o.getWrapped() != null && o.getWrapped()) {
                writer.writeEndElement();
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
                writer.writeCharacters(stripIllegalXmlChars(o.asString()));
            } else {
                writeStartElement(writer, o.getPrefix(), name, o.getNamespace());
                writer.writeCharacters(stripIllegalXmlChars(o.asString()));
                writer.writeEndElement();
            }
        }
        depth -= 1;
    }

    /**
     * Writes a start element, declaring its XML namespace via the StAX namespace API so the output is
     * well-formed. The namespace declaration is emitted on every element that carries one and StAX scopes
     * it correctly to the nearest in-scope binding, so a descendant that shadows an ancestor binding (a
     * prefix rebind, or a default-namespace shadow) resolves to the right namespace. A child that carries
     * no namespace metadata does not re-declare anything; a redundant identical re-declaration where a
     * parent already declared the same binding is harmless and semantically identical.
     */
    private void writeStartElement(XMLStreamWriter writer, String prefix, String name, String namespace) throws XMLStreamException {
        name = sanitiseXmlName(name);
        if (namespace == null) {
            writer.writeStartElement(name);
            return;
        }
        if (prefix != null && !prefix.isEmpty()) {
            // prefixed namespace -> <prefix:name xmlns:prefix="ns">
            writer.setPrefix(prefix, namespace);
            writer.writeStartElement(prefix, name, namespace);
            writer.writeNamespace(prefix, namespace);
            return;
        }
        // default namespace -> <name xmlns="ns">
        writer.setDefaultNamespace(namespace);
        writer.writeStartElement(namespace, name);
        writer.writeDefaultNamespace(namespace);
    }

    /**
     * Writes an attribute, binding and declaring its prefix when the attribute is namespaced. A namespaced
     * attribute with no prefix is invalid in XML (attributes are not covered by a default namespace), so in
     * that case the attribute is written without a namespace to keep the output well-formed.
     */
    private void writeAttribute(XMLStreamWriter writer, String prefix, String name, String namespace, String value) throws XMLStreamException {
        name = sanitiseXmlName(name);
        value = stripIllegalXmlChars(value);
        if (namespace != null && prefix != null && !prefix.isEmpty()) {
            // only declare the prefix binding if it is not already in scope on this element (the owning
            // element commonly declares the same prefix), otherwise a duplicate xmlns:prefix is emitted
            if (!namespace.equals(writer.getNamespaceContext().getNamespaceURI(prefix))) {
                writer.setPrefix(prefix, namespace);
                writer.writeNamespace(prefix, namespace);
            }
            writer.writeAttribute(prefix, namespace, name, value);
        } else {
            writer.writeAttribute(name, value);
        }
    }

    /**
     * Removes characters that are illegal in XML 1.0 (control characters other than tab, newline and
     * carriage return, plus a handful of non-characters) so a control char embedded in a schema
     * example/default/enum cannot make the serialized body fail to parse. Ordinary XML metacharacters
     * ({@code < & "}) are left untouched — StAX escapes those itself.
     */
    static String stripIllegalXmlChars(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        StringBuilder builder = null;
        // iterate by code point, not char, so that supplementary-plane characters (emoji, CJK Ext-B,
        // etc.) — which are legal XML 1.0 chars encoded as a surrogate pair — are preserved, not stripped.
        int i = 0;
        while (i < value.length()) {
            int codePoint = value.codePointAt(i);
            if (isLegalXmlChar(codePoint)) {
                if (builder != null) {
                    builder.appendCodePoint(codePoint);
                }
            } else if (builder == null) {
                builder = new StringBuilder(value.length());
                builder.append(value, 0, i);
            }
            i += Character.charCount(codePoint);
        }
        if (builder == null) {
            return value;
        }
        MOCK_SERVER_LOGGER.logEvent(
            new LogEntry()
                .setLogLevel(WARN)
                .setMessageFormat("stripped XML-illegal character(s) from an example value when serialising XML")
        );
        return builder.toString();
    }

    private static boolean isLegalXmlChar(int codePoint) {
        // XML 1.0 Char production: #x9 | #xA | #xD | [#x20-#xD7FF] | [#xE000-#xFFFD] | [#x10000-#x10FFFF]
        // (lone surrogates 0xD800-0xDFFF are excluded; a valid surrogate pair forms a code point >= 0x10000)
        return codePoint == 0x9 || codePoint == 0xA || codePoint == 0xD
            || (codePoint >= 0x20 && codePoint <= 0xD7FF)
            || (codePoint >= 0xE000 && codePoint <= 0xFFFD)
            || (codePoint >= 0x10000 && codePoint <= 0x10FFFF);
    }

    /**
     * Returns a valid XML name, sanitising an invalid one to a valid NCName so the output stays
     * well-formed (a malformed {@code xml.name} such as {@code "bad name!"} would otherwise produce a
     * silently malformed document). Illegal name characters are replaced with {@code _}; a name that does
     * not start with a valid name-start character is prefixed with {@code _}. The substitution is logged at
     * WARN so the rewrite is visible.
     */
    static String sanitiseXmlName(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        if (isValidXmlName(name)) {
            return name;
        }
        StringBuilder builder = new StringBuilder(name.length() + 1);
        char first = name.charAt(0);
        if (isXmlNameStartChar(first)) {
            builder.append(first);
        } else if (isXmlNameChar(first)) {
            builder.append('_').append(first);
        } else {
            builder.append('_');
        }
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            builder.append(isXmlNameChar(c) ? c : '_');
        }
        String sanitised = builder.toString();
        MOCK_SERVER_LOGGER.logEvent(
            new LogEntry()
                .setLogLevel(WARN)
                .setMessageFormat("invalid XML name \"{}\" in example schema sanitised to \"{}\"")
                .setArguments(name, sanitised)
        );
        return sanitised;
    }

    private static boolean isValidXmlName(String name) {
        if (!isXmlNameStartChar(name.charAt(0))) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            if (!isXmlNameChar(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isXmlNameStartChar(char c) {
        // ':' is intentionally NOT treated as a valid name char here: this serializer manages namespace
        // prefixes itself, so a colon in an element/attribute name would emit an unbound prefix. Treating
        // it as illegal makes sanitiseXmlName replace it with '_', keeping the output well-formed.
        return c == '_'
            || (c >= 'A' && c <= 'Z')
            || (c >= 'a' && c <= 'z')
            || (c >= 0xC0 && c <= 0xD6)
            || (c >= 0xD8 && c <= 0xF6)
            || (c >= 0xF8 && c <= 0x2FF)
            || (c >= 0x370 && c <= 0x37D)
            || (c >= 0x37F && c <= 0x1FFF)
            || (c >= 0x200C && c <= 0x200D)
            || (c >= 0x2070 && c <= 0x218F)
            || (c >= 0x2C00 && c <= 0x2FEF)
            || (c >= 0x3001 && c <= 0xD7FF)
            || (c >= 0xF900 && c <= 0xFDCF)
            || (c >= 0xFDF0 && c <= 0xFFFD);
    }

    private static boolean isXmlNameChar(char c) {
        return isXmlNameStartChar(c)
            || c == '-' || c == '.'
            || (c >= '0' && c <= '9')
            || c == 0xB7
            || (c >= 0x0300 && c <= 0x036F)
            || (c >= 0x203F && c <= 0x2040);
    }

    public String getTypeName(Example o) {
        return o.getTypeName();
    }
}
