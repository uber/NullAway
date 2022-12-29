/*
 * Copyright (c) 2022 Uber Technologies, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.uber.nullaway.fixserialization;

import java.io.File;
import javax.annotation.Nullable;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.jetbrains.annotations.Contract;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Helper for class for parsing/writing xml files. */
public class XMLUtil {

  /**
   * Helper method for reading attributes of node located at /key_1/key_2/.../key_n (in the form of
   * {@code Xpath} query) from a {@link Document}.
   *
   * @param doc XML object to read values from.
   * @param key Key to locate the value, can be nested in the form of {@code Xpath} query (e.g.
   *     /key1/key2:.../key_n).
   * @param klass Class type of the value in doc.
   * @return The value in the specified keychain cast to the class type given in parameter.
   */
  public static <T> DefaultXMLValueProvider<T> getValueFromAttribute(
      Document doc, String key, String attr, Class<T> klass) {
    try {
      XPath xPath = XPathFactory.newInstance().newXPath();
      Node node = (Node) xPath.compile(key).evaluate(doc, XPathConstants.NODE);
      if (node != null && node.getNodeType() == Node.ELEMENT_NODE) {
        Element eElement = (Element) node;
        return new DefaultXMLValueProvider<>(eElement.getAttribute(attr), klass);
      }
    } catch (XPathExpressionException ignored) {
      return new DefaultXMLValueProvider<>(null, klass);
    }
    return new DefaultXMLValueProvider<>(null, klass);
  }

  /**
   * Helper method for reading value of a node located at /key_1/key_2/.../key_n (in the form of
   * {@code Xpath} query) from a {@link Document}.
   *
   * @param doc XML object to read values from.
   * @param key Key to locate the value, can be nested in the form of {@code Xpath} query (e.g.
   *     /key1/key2/.../key_n).
   * @param klass Class type of the value in doc.
   * @return The value in the specified keychain cast to the class type given in parameter.
   */
  public static <T> DefaultXMLValueProvider<T> getValueFromTag(
      Document doc, String key, Class<T> klass) {
    try {
      XPath xPath = XPathFactory.newInstance().newXPath();
      Node node = (Node) xPath.compile(key).evaluate(doc, XPathConstants.NODE);
      if (node != null && node.getNodeType() == Node.ELEMENT_NODE) {
        Element eElement = (Element) node;
        return new DefaultXMLValueProvider<>(eElement.getTextContent(), klass);
      }
    } catch (XPathExpressionException ignored) {
      return new DefaultXMLValueProvider<>(null, klass);
    }
    return new DefaultXMLValueProvider<>(null, klass);
  }

  /**
   * Writes the {@link FixSerializationConfig} in {@code XML} format.
   *
   * @param config Config file to write.
   * @param path Path to write the config at.
   */
  public static void writeInXMLFormat(FixSerializationConfig config, String path) {
    DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
    try {
      DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
      Document doc = docBuilder.newDocument();

      // Root
      Element rootElement = doc.createElement("serialization");
      doc.appendChild(rootElement);

      // Suggest
      Element suggestElement = doc.createElement("suggest");
      suggestElement.setAttribute("active", String.valueOf(config.suggestEnabled));
      suggestElement.setAttribute("enclosing", String.valueOf(config.suggestEnclosing));
      rootElement.appendChild(suggestElement);

      // Field Initialization
      Element fieldInitInfoEnabled = doc.createElement("fieldInitInfo");
      fieldInitInfoEnabled.setAttribute("active", String.valueOf(config.fieldInitInfoEnabled));
      rootElement.appendChild(fieldInitInfoEnabled);

      // Output dir
      Element outputDir = doc.createElement("path");
      outputDir.setTextContent(config.outputDirectory);
      rootElement.appendChild(outputDir);

      // Serialization version
      if (config.getSerializer() != null) {
        Element serializationVersion = doc.createElement("version");
        serializationVersion.setTextContent(
            String.valueOf(config.getSerializer().getSerializationVersion()));
        rootElement.appendChild(serializationVersion);
      }

      // Writings
      TransformerFactory transformerFactory = TransformerFactory.newInstance();
      Transformer transformer = transformerFactory.newTransformer();
      DOMSource source = new DOMSource(doc);
      StreamResult result = new StreamResult(new File(path));
      transformer.transform(source, result);
    } catch (ParserConfigurationException | TransformerException e) {
      throw new RuntimeException("Error happened in writing config.", e);
    }
  }

  /** Helper class for setting default values when the key is not found. */
  static class DefaultXMLValueProvider<T> {
    @Nullable final Object value;
    final Class<T> klass;

    DefaultXMLValueProvider(@Nullable Object value, Class<T> klass) {
      this.klass = klass;
      if (value == null) {
        this.value = null;
      } else {
        String content = value.toString();
        switch (klass.getSimpleName()) {
          case "Integer":
            this.value = Integer.valueOf(content);
            break;
          case "Boolean":
            this.value = Boolean.valueOf(content);
            break;
          case "String":
            this.value = String.valueOf(content);
            break;
          default:
            throw new IllegalArgumentException(
                "Cannot extract values of type: "
                    + klass
                    + ", only Double|Boolean|String accepted.");
        }
      }
    }

    @Contract("!null -> !null")
    @Nullable
    T orElse(@Nullable T other) {
      return value == null ? other : klass.cast(this.value);
    }
  }
}
