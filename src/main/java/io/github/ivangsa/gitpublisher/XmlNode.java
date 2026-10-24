/*
 * Copyright 2026 Ivan Garcia Sainz-Aja
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.ivangsa.gitpublisher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;

/**
 * Minimal XML element tree used by {@link MetadataModel}. It keeps every element, attribute and element order of the
 * input, so unknown elements survive a read and write. Comments and processing instructions are dropped; mixed content
 * keeps only the child elements. Namespaces are not interpreted: prefixes are part of the name.
 */
final class XmlNode {

    private final String name;
    private final Map<String, String> attributes = new LinkedHashMap<>();
    private final List<XmlNode> children = new ArrayList<>();
    private String text;

    XmlNode(String name) {
        this.name = name;
    }

    XmlNode(String name, String text) {
        this(name);
        this.text = text;
    }

    String name() {
        return name;
    }

    Map<String, String> attributes() {
        return attributes;
    }

    List<XmlNode> children() {
        return children;
    }

    /** Text of a leaf element; {@code null} when the element has children. */
    String text() {
        return children.isEmpty() ? (text == null ? "" : text) : null;
    }

    void setText(String text) {
        this.text = text;
    }

    Optional<XmlNode> child(String childName) {
        return children.stream().filter(c -> c.name.equals(childName)).findFirst();
    }

    List<XmlNode> children(String childName) {
        return children.stream().filter(c -> c.name.equals(childName)).toList();
    }

    Optional<String> childText(String childName) {
        return child(childName).map(XmlNode::text);
    }

    /** Sets the text of the first child with this name, appending a new child when absent. */
    XmlNode setChildText(String childName, String value) {
        XmlNode node = child(childName).orElseGet(() -> {
            XmlNode created = new XmlNode(childName);
            children.add(created);
            return created;
        });
        node.children.clear();
        node.text = value;
        return node;
    }

    void removeChildren(String childName) {
        children.removeIf(c -> c.name.equals(childName));
    }

    // ---- reading ----

    static XmlNode read(InputStream in) throws IOException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        XMLStreamReader reader = null;
        try {
            reader = factory.createXMLStreamReader(in);
            Deque<XmlNode> stack = new ArrayDeque<>();
            Deque<StringBuilder> texts = new ArrayDeque<>();
            XmlNode root = null;
            while (reader.hasNext()) {
                switch (reader.next()) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        XmlNode node = new XmlNode(reader.getLocalName());
                        for (int i = 0; i < reader.getAttributeCount(); i++) {
                            node.attributes.put(reader.getAttributeLocalName(i), reader.getAttributeValue(i));
                        }
                        if (stack.isEmpty()) {
                            if (root != null) {
                                throw new IOException("more than one root element");
                            }
                            root = node;
                        } else {
                            stack.peek().children.add(node);
                        }
                        stack.push(node);
                        texts.push(new StringBuilder());
                    }
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                        if (!texts.isEmpty()) {
                            texts.peek().append(reader.getText());
                        }
                    }
                    case XMLStreamConstants.END_ELEMENT -> {
                        XmlNode node = stack.pop();
                        StringBuilder text = texts.pop();
                        if (node.children.isEmpty()) {
                            node.text = text.toString().trim();
                        }
                    }
                    default -> {}
                }
            }
            if (root == null) {
                throw new IOException("no root element");
            }
            return root;
        } catch (XMLStreamException e) {
            throw new IOException("malformed XML: " + e.getMessage(), e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (XMLStreamException ignored) {
                    // nothing to do
                }
            }
        }
    }

    // ---- writing ----

    /** Writes UTF-8 with an XML declaration and two-space indentation, like Maven's own writer. */
    void write(OutputStream out) throws IOException {
        try {
            XMLStreamWriter writer = XMLOutputFactory.newFactory().createXMLStreamWriter(out, "UTF-8");
            writer.writeStartDocument("UTF-8", "1.0");
            writer.writeCharacters("\n");
            writeElement(writer, 0);
            writer.writeCharacters("\n");
            writer.writeEndDocument();
            writer.flush();
            writer.close();
        } catch (XMLStreamException e) {
            throw new IOException("cannot write XML: " + e.getMessage(), e);
        }
    }

    byte[] toBytes() {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            write(out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    String toXml() {
        return new String(toBytes(), StandardCharsets.UTF_8);
    }

    private void writeElement(XMLStreamWriter writer, int depth) throws XMLStreamException {
        if (children.isEmpty()) {
            String value = text();
            if (value.isEmpty()) {
                writer.writeEmptyElement(name);
                writeAttributes(writer);
            } else {
                writer.writeStartElement(name);
                writeAttributes(writer);
                writer.writeCharacters(value);
                writer.writeEndElement();
            }
            return;
        }
        writer.writeStartElement(name);
        writeAttributes(writer);
        for (XmlNode child : children) {
            writer.writeCharacters("\n" + "  ".repeat(depth + 1));
            child.writeElement(writer, depth + 1);
        }
        writer.writeCharacters("\n" + "  ".repeat(depth));
        writer.writeEndElement();
    }

    private void writeAttributes(XMLStreamWriter writer) throws XMLStreamException {
        for (Map.Entry<String, String> attribute : attributes.entrySet()) {
            writer.writeAttribute(attribute.getKey(), attribute.getValue());
        }
    }
}
