package com.hamiltel.gmlc.mlp;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Parses the OMA MLP 3.x request envelope this adapter accepts. Only the
 * standard location immediate request ({@code slir}) is understood; every
 * other service is returned to the caller so it can be refused by name.
 *
 * <p>The parser allows the DOCTYPE that MLP examples carry but never loads an
 * external DTD or entity: the request is data, not a document that may reach
 * out to a URL. Anything the gateway cannot honour is a fault with the OMA
 * result code that names it.
 */
public class MlpParser {

    /** A multi-MSID request is capped: each subject is a separate Le request. */
    static final int MAX_SUBJECTS = 10;

    private static final Pattern MSISDN = Pattern.compile("[0-9]{7,15}");
    private static final Pattern IMSI = Pattern.compile("[0-9]{14,15}");
    private static final Pattern PURPOSE = Pattern.compile("[A-Z_]{3,32}");
    private static final Pattern SERVICE_ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    private final DocumentBuilder builder;

    public MlpParser() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            // MLP examples carry a DOCTYPE; it must not resolve anywhere.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(
                    "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.ErrorHandler() {
                @Override
                public void warning(SAXParseException e) {
                    // A warning does not change the meaning of the document.
                }

                @Override
                public void error(SAXParseException e) throws SAXException {
                    throw e;
                }

                @Override
                public void fatalError(SAXParseException e) throws SAXException {
                    throw e;
                }
            });
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("the XML parser refused the required security settings", e);
        }
    }

    public MlpRequest parse(String body) {
        Document document;
        try {
            document = builder.parse(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        } catch (SAXParseException e) {
            throw new MlpFault(106, "SYNTAX ERROR",
                    "the request is not well-formed XML at line " + e.getLineNumber());
        } catch (SAXException | IOException e) {
            throw new MlpFault(106, "SYNTAX ERROR", "the request is not well-formed XML");
        }

        Element root = document.getDocumentElement();
        if (root == null || !"svc_init".equals(localName(root))) {
            throw new MlpFault(106, "SYNTAX ERROR", "the root element must be svc_init");
        }
        String version = attribute(root, "ver");
        if (version != null && !version.startsWith("3.")) {
            throw new MlpFault(103, "UNSUPPORTED VERSION",
                    "this gateway accepts MLP 3.x, not " + version);
        }

        Element service = firstServiceElement(root);
        if (service == null) {
            throw new MlpFault(106, "SYNTAX ERROR",
                    "svc_init must contain a service element such as slir");
        }
        String name = localName(service).toLowerCase(Locale.ROOT);
        String serviceVersion = attribute(service, "ver");
        if (serviceVersion != null && !serviceVersion.startsWith("3.")) {
            throw new MlpFault(103, "UNSUPPORTED VERSION",
                    "this gateway accepts MLP 3.x, not " + serviceVersion);
        }

        String answer = switch (name) {
            case "slir" -> "slia";
            case "tlrr" -> "tlra";
            case "tlsr" -> "tlsa";
            default -> "slia";
        };
        String answerVersion = "slir".equals(name) ? "3.0.0" : "3.2.0";

        String serviceId = serviceId(root);
        if (!"slir".equals(name)) {
            return new MlpRequest(name, answer, answerVersion, serviceId, List.of(), null, null);
        }

        List<MlpRequest.MlpSubject> subjects = subjects(service);
        if (subjects.isEmpty()) {
            throw new MlpFault(105, "FORMAT ERROR", "slir names no msid");
        }
        if (subjects.size() > MAX_SUBJECTS) {
            throw new MlpFault(104, "TOO MANY POSITION ITEMS",
                    "at most " + MAX_SUBJECTS + " msids are accepted per request");
        }

        String resType = attribute(service, "res_type");
        if (resType != null && !"SYNC".equalsIgnoreCase(resType)) {
            throw new MlpFault(108, "SERVICE NOT SUPPORTED",
                    "only the synchronous answer (res_type=SYNC) is implemented");
        }
        if (child(service, "pushaddr") != null) {
            throw new MlpFault(108, "SERVICE NOT SUPPORTED",
                    "push mode is not implemented; use the synchronous answer");
        }
        for (String unsupported : List.of("geo_info", "codeword", "gsm_net_param", "trans_id",
                "service_coverage")) {
            if (child(service, unsupported) != null) {
                throw new MlpFault(107, "PROTOCOL ELEMENT NOT SUPPORTED",
                        "'" + unsupported + "' is not supported");
            }
        }

        String locationType = "CURRENT";
        Element locType = child(service, "loc_type");
        if (locType != null) {
            locationType = attributeOrDefault(locType, "type", "CURRENT");
            if (!"CURRENT".equals(locationType) && !"CURRENT_OR_LAST".equals(locationType)) {
                throw new MlpFault(107, "PROTOCOL ELEMENT NOT SUPPORTED",
                        "only loc_type CURRENT is available; this gateway returns a fresh position"
                                + " or a failure");
            }
        }

        Integer accuracy = null;
        String priority = null;
        Element eqop = child(service, "eqop");
        if (eqop != null) {
            if (child(eqop, "resp_timer") != null) {
                throw new MlpFault(107, "PROTOCOL ELEMENT NOT SUPPORTED",
                        "'resp_timer' is not supported");
            }
            accuracy = accuracy(child(eqop, "hor_acc"));
            Element respReq = child(eqop, "resp_req");
            if (respReq != null) {
                String type = attributeOrDefault(respReq, "type", "NO_DELAY");
                switch (type) {
                    case "NO_DELAY", "LOW_DELAY" -> priority = "HIGH";
                    case "HIGH_ACCURACY" -> priority = "NORMAL";
                    default -> throw new MlpFault(111, "INVALID PROTOCOL ELEMENT ATTRIBUTE VALUE",
                            "resp_req type " + type + " is not defined");
                }
            }
        }
        Element prio = child(service, "prio");
        if (prio != null) {
            String type = attributeOrDefault(prio, "type", "NORMAL");
            if (!"HIGH".equals(type) && !"NORMAL".equals(type)) {
                throw new MlpFault(111, "INVALID PROTOCOL ELEMENT ATTRIBUTE VALUE",
                        "prio type " + type + " is not defined");
            }
            priority = type;
        }

        return new MlpRequest(name, answer, answerVersion, serviceId, subjects, accuracy,
                priority == null ? "NORMAL" : priority);
    }

    /**
     * The purpose this request is for. MLP's {@code serviceid} is the LCS
     * service identity; the operator registers the same codes as Le purposes.
     * A client with exactly one entitlement may omit it.
     */
    public static String purpose(MlpRequest request, java.util.Set<String> allowedPurposes) {
        String serviceId = request.serviceId();
        if (serviceId != null && PURPOSE.matcher(serviceId).matches()) {
            return serviceId;
        }
        if (allowedPurposes != null && allowedPurposes.size() == 1) {
            return allowedPurposes.iterator().next();
        }
        if (serviceId == null) {
            throw new MlpFault(111, "INVALID PROTOCOL ELEMENT ATTRIBUTE VALUE",
                    "hdr/client/serviceid must name one of this client's purposes");
        }
        throw new MlpFault(111, "INVALID PROTOCOL ELEMENT ATTRIBUTE VALUE",
                "serviceid '" + serviceId + "' does not name one of this client's purposes;"
                        + " send the Le purpose code as serviceid");
    }

    private List<MlpRequest.MlpSubject> subjects(Element service) {
        List<MlpRequest.MlpSubject> subjects = new ArrayList<>();
        for (Element element : children(service)) {
            switch (localName(element)) {
                case "msids" -> {
                    if (child(element, "msid_range") != null) {
                        throw new MlpFault(107, "PROTOCOL ELEMENT NOT SUPPORTED",
                                "'msid_range' is not supported");
                    }
                    for (Element msid : children(element, "msid")) {
                        subjects.add(subject(msid));
                    }
                }
                case "msid" -> subjects.add(subject(element));
                case "eqop", "geo_info", "loc_type", "prio", "pushaddr", "service_coverage",
                        "codeword", "gsm_net_param", "trans_id" -> {
                    // Parsed, or refused, by name above.
                }
                default -> throw new MlpFault(201, "PROTOCOL ELEMENT VALUE NOT SUPPORTED",
                        "element '" + localName(element) + "' is not supported in slir");
            }
        }
        return subjects;
    }

    private MlpRequest.MlpSubject subject(Element msid) {
        String type = attributeOrDefault(msid, "type", "MSISDN");
        String enc = attributeOrDefault(msid, "enc", "ASC");
        if (!"ASC".equalsIgnoreCase(enc)) {
            throw new MlpFault(109, "PROTOCOL ELEMENT ATTRIBUTE NOT SUPPORTED",
                    "msid enc=" + enc + " is not supported; only ASC is accepted");
        }
        String value = msid.getTextContent() == null ? "" : msid.getTextContent().trim();
        switch (type) {
            case "MSISDN" -> {
                if (!MSISDN.matcher(value).matches()) {
                    throw new MlpFault(110, "INVALID PROTOCOL ELEMENT VALUE",
                            "an MSISDN msid must be 7-15 digits");
                }
            }
            case "IMSI" -> {
                if (!IMSI.matcher(value).matches()) {
                    throw new MlpFault(110, "INVALID PROTOCOL ELEMENT VALUE",
                            "an IMSI msid must be 14 or 15 digits");
                }
            }
            default -> throw new MlpFault(107, "PROTOCOL ELEMENT NOT SUPPORTED",
                    "msid type " + type + " is not supported; use MSISDN or IMSI");
        }
        return new MlpRequest.MlpSubject(type, value);
    }

    private Integer accuracy(Element horAcc) {
        if (horAcc == null) {
            return null;
        }
        String text = horAcc.getTextContent() == null ? "" : horAcc.getTextContent().trim();
        try {
            int value = Integer.parseInt(text);
            if (value < 1 || value > 100_000) {
                throw new NumberFormatException("out of range");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new MlpFault(110, "INVALID PROTOCOL ELEMENT VALUE",
                    "eqop/hor_acc must be a whole number of metres between 1 and 100000");
        }
    }

    private static String serviceId(Element root) {
        Element hdr = child(root, "hdr");
        if (hdr == null) {
            return null;
        }
        Element client = child(hdr, "client");
        if (client == null) {
            return null;
        }
        Element serviceId = child(client, "serviceid");
        if (serviceId == null) {
            return null;
        }
        String text = serviceId.getTextContent() == null ? "" : serviceId.getTextContent().trim();
        if (text.isEmpty()) {
            return null;
        }
        if (!SERVICE_ID.matcher(text).matches()) {
            throw new MlpFault(111, "INVALID PROTOCOL ELEMENT ATTRIBUTE VALUE",
                    "serviceid must be 1-64 letters, digits, dot, dash or underscore");
        }
        return text;
    }

    private static Element firstServiceElement(Element root) {
        for (Element element : children(root)) {
            String name = localName(element);
            if (!"hdr".equals(name)) {
                return element;
            }
        }
        return null;
    }

    private static Element child(Element parent, String name) {
        for (Element element : children(parent)) {
            if (name.equals(localName(element))) {
                return element;
            }
        }
        return null;
    }

    private static List<Element> children(Element parent) {
        return children(parent, null);
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> elements = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE
                    && (name == null || name.equals(localName(node)))) {
                elements.add((Element) node);
            }
        }
        return elements;
    }

    private static String localName(Node node) {
        String name = node.getNodeName();
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    private static String attribute(Element element, String name) {
        return element.hasAttribute(name) ? element.getAttribute(name).trim() : null;
    }

    private static String attributeOrDefault(Element element, String name, String fallback) {
        String value = attribute(element, name);
        return value == null || value.isEmpty() ? fallback : value;
    }
}
