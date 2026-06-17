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

import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.media.XML;
import org.junit.Test;
import org.mockserver.openapi.examples.models.ArrayExample;
import org.mockserver.openapi.examples.models.BooleanExample;
import org.mockserver.openapi.examples.models.Example;
import org.mockserver.openapi.examples.models.IntegerExample;
import org.mockserver.openapi.examples.models.ObjectExample;
import org.mockserver.openapi.examples.models.StringExample;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertNotNull;

/**
 * Verifies {@link XmlExampleSerializer} produces well-formed XML — every output is parsed with a
 * namespace-aware {@link DocumentBuilder} (the regression guard: a namespaced schema must now yield a
 * non-empty, well-formed body rather than an empty/malformed one).
 */
public class XmlExampleSerializerTest {

    private static final String NS = "http://example.com/books";

    /**
     * Parses the serialized XML with namespace-awareness on, proving it is well-formed, and returns the
     * document root for further structural assertions.
     */
    private Document parseNamespaceAware(String xml) throws Exception {
        assertNotNull("serializer returned null (malformed/failed serialization)", xml);
        assertThat("serialized XML must be non-empty", xml.trim().isEmpty(), is(false));
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    // -------------------------------------------------------------------------------------------------
    // Fix 1 — namespace declarations
    // -------------------------------------------------------------------------------------------------

    @Test
    public void shouldSerializeElementWithDefaultNamespace() throws Exception {
        ObjectExample book = new ObjectExample();
        book.setName("Book");
        book.setNamespace(NS);
        book.put("title", new StringExample("Moby Dick"));

        String xml = new XmlExampleSerializer().serialize(book);
        Document document = parseNamespaceAware(xml);

        Element root = document.getDocumentElement();
        assertThat(root.getLocalName(), is("Book"));
        assertThat("default namespace declared via xmlns=\"...\"", root.getNamespaceURI(), is(NS));
        assertThat("no prefix for a default namespace", root.getPrefix(), nullValue());
    }

    @Test
    public void shouldSerializeElementWithPrefixedNamespace() throws Exception {
        ObjectExample book = new ObjectExample();
        book.setName("Book");
        book.setNamespace(NS);
        book.setPrefix("ex");
        book.put("title", new StringExample("Moby Dick"));

        String xml = new XmlExampleSerializer().serialize(book);
        Document document = parseNamespaceAware(xml);

        Element root = document.getDocumentElement();
        assertThat(root.getLocalName(), is("Book"));
        assertThat(root.getNamespaceURI(), is(NS));
        assertThat("prefix bound via xmlns:ex=\"...\"", root.getPrefix(), is("ex"));
    }

    @Test
    public void shouldSerializeNamespacedAttribute() throws Exception {
        ObjectExample book = new ObjectExample();
        book.setName("Book");
        book.setNamespace(NS);
        book.setPrefix("ex");

        StringExample id = new StringExample("123");
        id.setName("id");
        id.setAttribute(true);
        id.setNamespace(NS);
        id.setPrefix("ex");
        book.put("id", id);

        String xml = new XmlExampleSerializer().serialize(book);
        Document document = parseNamespaceAware(xml);

        Element root = document.getDocumentElement();
        assertThat(root.getAttributeNS(NS, "id"), is("123"));
    }

    @Test
    public void shouldSerializeAttributeWithoutNamespace() throws Exception {
        ObjectExample book = new ObjectExample();
        book.setName("Book");

        StringExample id = new StringExample("123");
        id.setName("id");
        id.setAttribute(true);
        book.put("id", id);

        String xml = new XmlExampleSerializer().serialize(book);
        Document document = parseNamespaceAware(xml);

        Element root = document.getDocumentElement();
        assertThat(root.getAttribute("id"), is("123"));
    }

    // -------------------------------------------------------------------------------------------------
    // structure matrix — every cell must parse well-formed
    // -------------------------------------------------------------------------------------------------

    @Test
    public void shouldSerializeNestedObjectWithNamespace() throws Exception {
        ObjectExample author = new ObjectExample();
        author.put("name", new StringExample("Herman Melville"));

        ObjectExample book = new ObjectExample();
        book.setName("Book");
        book.setNamespace(NS);
        book.setPrefix("ex");
        book.put("title", new StringExample("Moby Dick"));
        book.put("author", author);

        String xml = new XmlExampleSerializer().serialize(book);
        Document document = parseNamespaceAware(xml);

        Element root = document.getDocumentElement();
        assertThat(root.getNamespaceURI(), is(NS));
        // nested children inherit the prefixed namespace — declared once on the root only
        assertThat(countXmlnsDeclarations(xml, "xmlns:ex"), is(1));
    }

    @Test
    public void shouldSerializeArrayOfObjectsWithNamespace() throws Exception {
        ArrayExample books = new ArrayExample();
        books.setName("book");
        books.setNamespace(NS);
        books.setPrefix("ex");
        books.setWrapped(true);
        books.setWrappedName("books");

        ObjectExample book1 = new ObjectExample();
        book1.put("title", new StringExample("Moby Dick"));
        ObjectExample book2 = new ObjectExample();
        book2.put("title", new StringExample("Bartleby"));
        books.add(book1);
        books.add(book2);

        String xml = new XmlExampleSerializer().serialize(books);
        Document document = parseNamespaceAware(xml);

        Element root = document.getDocumentElement();
        assertThat(root.getLocalName(), is("books"));
        assertThat(root.getNamespaceURI(), is(NS));
    }

    @Test
    public void shouldSerializeTopLevelArrayWithDefaultNamespace() throws Exception {
        ArrayExample books = new ArrayExample();
        books.setName("book");
        books.setNamespace(NS);
        books.setWrapped(true);
        books.setWrappedName("books");
        books.add(new StringExample("Moby Dick"));
        books.add(new StringExample("Bartleby"));

        String xml = new XmlExampleSerializer().serialize(books);
        Document document = parseNamespaceAware(xml);

        Element root = document.getDocumentElement();
        assertThat(root.getLocalName(), is("books"));
        assertThat(root.getNamespaceURI(), is(NS));
        assertThat(root.getPrefix(), nullValue());
    }

    @Test
    public void shouldSerializeTopLevelScalar() throws Exception {
        StringExample scalar = new StringExample("hello");
        scalar.setName("greeting");

        String xml = new XmlExampleSerializer().serialize(scalar);
        Document document = parseNamespaceAware(xml);

        assertThat(document.getDocumentElement().getLocalName(), is("greeting"));
        assertThat(document.getDocumentElement().getTextContent(), is("hello"));
    }

    @Test
    public void shouldSerializeNumericAndBooleanValues() throws Exception {
        ObjectExample record = new ObjectExample();
        record.setName("Record");
        record.setNamespace(NS);
        record.put("count", new IntegerExample(42));
        record.put("active", new BooleanExample(true));

        String xml = new XmlExampleSerializer().serialize(record);
        Document document = parseNamespaceAware(xml);

        Element root = document.getDocumentElement();
        assertThat(root.getNamespaceURI(), is(NS));
    }

    @Test
    public void shouldSerializeNoNamespaceObject() throws Exception {
        // regression of the existing (already-working) non-namespaced behaviour
        ObjectExample book = new ObjectExample();
        book.setName("Book");
        book.put("title", new StringExample("Moby Dick"));
        book.put("pages", new IntegerExample(635));

        String xml = new XmlExampleSerializer().serialize(book);
        Document document = parseNamespaceAware(xml);

        Element root = document.getDocumentElement();
        assertThat(root.getLocalName(), is("Book"));
        assertThat("no namespace declared", root.getNamespaceURI(), nullValue());
        assertThat(xml.contains("xmlns"), is(false));
    }

    @Test
    public void shouldDeclarePrefixedNamespaceOnlyOnceAcrossWrappedArray() throws Exception {
        ArrayExample books = new ArrayExample();
        books.setName("book");
        books.setNamespace(NS);
        books.setPrefix("ex");
        books.setWrapped(true);
        books.setWrappedName("books");
        books.add(new StringExample("Moby Dick"));
        books.add(new StringExample("Bartleby"));

        String xml = new XmlExampleSerializer().serialize(books);
        parseNamespaceAware(xml);

        // declared on the wrapper element only; the repeated item elements inherit it
        assertThat(countXmlnsDeclarations(xml, "xmlns:ex"), is(1));
    }

    // -------------------------------------------------------------------------------------------------
    // Fix 1 regression — integrated path through ExampleBuilder must yield a non-empty well-formed body
    // -------------------------------------------------------------------------------------------------

    @Test
    public void shouldYieldNonEmptyWellFormedBodyForNamespacedSchemaViaExampleBuilder() throws Exception {
        ObjectSchema book = new ObjectSchema();
        book.setXml(new XML().name("Book").namespace(NS).prefix("ex"));
        book.addProperty("title", new StringSchema().example("Moby Dick"));
        book.addProperty("pages", new IntegerSchema().example(635));

        Example generated = ExampleBuilder.fromSchema(book, null);
        assertThat(generated, is(notNullValue()));

        String xml = new XmlExampleSerializer().serialize(generated);
        Document document = parseNamespaceAware(xml);

        Element root = document.getDocumentElement();
        assertThat(root.getLocalName(), is("Book"));
        assertThat(root.getNamespaceURI(), is(NS));
        assertThat(root.getPrefix(), is("ex"));
    }

    @Test
    public void shouldYieldNonEmptyWellFormedBodyForDefaultNamespaceSchemaViaExampleBuilder() throws Exception {
        ObjectSchema book = new ObjectSchema();
        book.setXml(new XML().name("Book").namespace(NS));
        book.addProperty("title", new StringSchema().example("Moby Dick"));

        Example generated = ExampleBuilder.fromSchema(book, null);
        assertThat(generated, is(notNullValue()));

        String xml = new XmlExampleSerializer().serialize(generated);
        Document document = parseNamespaceAware(xml);

        Element root = document.getDocumentElement();
        assertThat(root.getNamespaceURI(), is(NS));
        assertThat(root.getPrefix(), nullValue());
    }

    private int countXmlnsDeclarations(String xml, String declaration) {
        int count = 0;
        int index = 0;
        while ((index = xml.indexOf(declaration + "=", index)) != -1) {
            count++;
            index += declaration.length();
        }
        return count;
    }
}
