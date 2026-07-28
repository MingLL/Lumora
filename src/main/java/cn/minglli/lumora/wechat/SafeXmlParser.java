package cn.minglli.lumora.wechat;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

@Component
public final class SafeXmlParser {

    private static final String DISALLOW_DOCTYPE =
            "http://apache.org/xml/features/disallow-doctype-decl";
    private static final String EXTERNAL_GENERAL_ENTITIES =
            "http://xml.org/sax/features/external-general-entities";
    private static final String EXTERNAL_PARAMETER_ENTITIES =
            "http://xml.org/sax/features/external-parameter-entities";
    private static final String LOAD_EXTERNAL_DTD =
            "http://apache.org/xml/features/nonvalidating/load-external-dtd";

    public ParsedXml parse(byte[] xml) {
        try {
            DocumentBuilderFactory factory = secureFactory();
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(THROWING_ERROR_HANDLER);
            Document document = builder.parse(new ByteArrayInputStream(xml));
            Element root = document.getDocumentElement();
            return new ParsedXml(root.getTagName(), fields(root));
        } catch (SAXException | RuntimeException exception) {
            throw new WechatMalformedXmlException("Malformed WeChat XML", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to configure the secure XML parser", exception);
        }
    }

    private static DocumentBuilderFactory secureFactory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature(DISALLOW_DOCTYPE, true);
        factory.setFeature(EXTERNAL_GENERAL_ENTITIES, false);
        factory.setFeature(EXTERNAL_PARAMETER_ENTITIES, false);
        factory.setFeature(LOAD_EXTERNAL_DTD, false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory;
    }

    private static final ErrorHandler THROWING_ERROR_HANDLER = new ErrorHandler() {
        @Override
        public void warning(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void error(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXException {
            throw exception;
        }
    };

    private static Map<String, Object> fields(Element parent) {
        Map<String, Object> result = new LinkedHashMap<>();
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node node = children.item(index);
            if (!(node instanceof Element child)) {
                continue;
            }
            Object value = hasElementChild(child) ? fields(child) : child.getTextContent();
            merge(result, child.getTagName(), value);
        }
        return immutableMap(result);
    }

    private static boolean hasElementChild(Element element) {
        NodeList children = element.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            if (children.item(index) instanceof Element) {
                return true;
            }
        }
        return false;
    }

    private static void merge(Map<String, Object> fields, String name, Object value) {
        Object existing = fields.get(name);
        if (existing == null) {
            fields.put(name, value);
        } else if (existing instanceof List<?> existingList) {
            List<Object> values = new ArrayList<>(existingList);
            values.add(value);
            fields.put(name, List.copyOf(values));
        } else {
            fields.put(name, List.of(existing, value));
        }
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, immutableValue(value)));
        return Collections.unmodifiableMap(result);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> typed = new LinkedHashMap<>();
            map.forEach((key, nested) -> typed.put((String) key, immutableValue(nested)));
            return Collections.unmodifiableMap(typed);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(SafeXmlParser::immutableValue).toList();
        }
        return value;
    }

    public record ParsedXml(String rootName, Map<String, Object> fields) {

        public String text(String name) {
            Object value = fields.get(name);
            return value instanceof String text ? text : null;
        }

        @SuppressWarnings("unchecked")
        public Map<String, Object> object(String name) {
            Object value = fields.get(name);
            return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
        }
    }
}
