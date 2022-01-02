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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Helper for class for parsing/writing xml files. */
public class XMLUtil {

  /**
   * Helper method for reading attributes of node located at key_1:key_2:...:key_n from a {@link
   * Document}.
   *
   * @param doc XML object to read values from.
   * @param key Key to locate the value, can be nested (e.g. key1:key2:...:key_n).
   * @param klass Class type of the value in doc.
   * @return The value in the specified keychain cast to the class type given in parameter.
   */
  public static <T> OrElse<T> getValueFromAttribute(
      Document doc, String key, String attr, Class<T> klass) {
    Node node = getNodeFromDocument(doc, key);
    if (node == null) {
      return new OrElse<>(null, klass);
    }
    return new OrElse<>(node.getAttributes().getNamedItem(attr), klass);
  }

  /**
   * Helper method for reading value of a node located at key_1:key_2:...:key_n from a {@link
   * Document}.
   *
   * @param doc XML object to read values from.
   * @param key Key to locate the value, can be nested (e.g. key1:key2:...:key_n).
   * @param klass Class type of the value in doc.
   * @return The value in the specified keychain cast to the class type given in parameter.
   */
  public static <T> OrElse<T> getValueFromTag(Document doc, String key, Class<T> klass) {
    Node node = getNodeFromDocument(doc, key);
    if (node == null) {
      return new OrElse<>(null, klass);
    }
    return new OrElse<>(node, klass);
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

      // Suggest
      Element suggestElement = doc.createElement("suggest");
      suggestElement.setAttribute("active", String.valueOf(config.suggestEnabled));
      suggestElement.setAttribute("enclosing", String.valueOf(config.suggestEnclosing));
      rootElement.appendChild(suggestElement);

      // Error
      Element errorElement = doc.createElement("error");
      errorElement.setAttribute("active", String.valueOf(config.logErrorEnabled));
      errorElement.setAttribute("enclosing", String.valueOf(config.logErrorEnclosing));
      rootElement.appendChild(errorElement);

      // Annotations
      Element annots = doc.createElement("annotation");
      Element nonnull = doc.createElement("nonnull");
      nonnull.setTextContent(config.annotationFactory.getNonNull().fullName);
      Element nullable = doc.createElement("nullable");
      nullable.setTextContent(config.annotationFactory.getNullable().fullName);
      annots.appendChild(nullable);
      annots.appendChild(nonnull);
      rootElement.appendChild(annots);
      doc.appendChild(rootElement);
      TransformerFactory transformerFactory = TransformerFactory.newInstance();
      Transformer transformer = transformerFactory.newTransformer();
      DOMSource source = new DOMSource(doc);
      StreamResult result = new StreamResult(new File(path));
      transformer.transform(source, result);
    } catch (Exception e) {
      System.err.println("Error happened in writing config." + e);
    }
  }

  /**
   * Locates the node at the given key. Key can be nested in format key_1:key_2:...:key_n.
   *
   * @param doc Document object of XML.
   * @param key Nested key.
   * @return Node if exists with the hierarchy specified by the key, otherwise {@code null}.
   */
  private static Node getNodeFromDocument(Document doc, String key) {
    if (doc == null || key == null || key.equals("")) {
      return null;
    }
    ArrayList<String> keys = new ArrayList<>(Arrays.asList(key.split(":")));
    Collections.reverse(keys);
    NodeList candidates = doc.getElementsByTagName(keys.get(0));
    for (int i = 0; i < candidates.getLength(); i++) {
      Node candidate = candidates.item(i);
      if (hasTheEnclosingParents(candidate, keys)) {
        return candidate;
      }
    }
    return null;
  }

  /**
   * Verifies if the node's hierarchy matches with the keys.
   *
   * @param node Node to examine.
   * @param keys List of parents simple name.
   * @return {@code true} if node's matches with keys, {@code false} otherwise.
   */
  private static boolean hasTheEnclosingParents(Node node, List<String> keys) {
    for (String key : keys) {
      if (node == null || !node.getNodeName().equals(key)) {
        return false;
      }
      node = node.getParentNode();
    }
    return true;
  }

  /** Helper class for setting default values when the key is not found. */
  static class OrElse<T> {
    final Object value;
    final Class<T> klass;

    OrElse(Node value, Class<T> klass) {
      this.klass = klass;
      if (value == null) {
        this.value = null;
      } else {
        String content = value.getTextContent();
        switch (klass.getSimpleName()) {
          case "Double":
            this.value = Double.valueOf(content);
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

    T orElse(T other) {
      return value == null ? other : klass.cast(this.value);
    }
  }
}
