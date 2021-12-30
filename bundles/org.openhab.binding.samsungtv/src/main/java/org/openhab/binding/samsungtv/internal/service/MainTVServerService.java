/**
 * Copyright (c) 2010-2021 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.samsungtv.internal.service;

import static org.openhab.binding.samsungtv.internal.SamsungTvBindingConstants.*;

import java.io.IOException;
import java.io.StringReader;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.samsungtv.internal.handler.SamsungTvHandler;
import org.openhab.binding.samsungtv.internal.service.api.SamsungTvService;
import org.openhab.core.io.transport.upnp.UpnpIOParticipant;
import org.openhab.core.io.transport.upnp.UpnpIOService;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * The {@link MainTVServerService} is responsible for handling MainTVServer
 * commands.
 *
 * @author Pauli Anttila - Initial contribution
 * @author Nick Waterton - add checkConnection(), getServiceName, some refactoring
 */
@NonNullByDefault
public class MainTVServerService implements UpnpIOParticipant, SamsungTvService {

    public static final String SERVICE_NAME = "MainTVServer2";
    private static final List<String> SUPPORTED_CHANNELS = Arrays.asList(SOURCE_NAME, SOURCE_ID, BROWSER_URL,
            STOP_BROWSER);
    private static final List<String> REFRESH_CHANNELS = Arrays.asList(CHANNEL, SOURCE_NAME, SOURCE_ID, PROGRAM_TITLE,
            CHANNEL_NAME, BROWSER_URL);

    private final Logger logger = LoggerFactory.getLogger(MainTVServerService.class);

    private final UpnpIOService service;

    private final String udn;
    private String host = "Unknown";

    private final SamsungTvHandler handler;

    private Map<String, String> stateMap = Collections.synchronizedMap(new HashMap<>());

    private boolean started;
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

    public MainTVServerService(UpnpIOService upnpIOService, String udn, String host, SamsungTvHandler handler) {
        this.service = upnpIOService;
        this.udn = udn;
        this.handler = handler;
        this.host = host;
        configureXMLParser();
        logger.debug("{}: Creating a Samsung TV MainTVServer service", host);
    }

    @Override
    public String getServiceName() {
        return SERVICE_NAME;
    }

    @Override
    public List<String> getSupportedChannelNames(boolean refresh) {
        if (refresh) {
            return REFRESH_CHANNELS;
        }
        logger.trace("{}: getSupportedChannelNames: {}", host, SUPPORTED_CHANNELS);
        return SUPPORTED_CHANNELS;
    }

    @Override
    public void start() {
        service.registerParticipant(this);
        started = true;
    }

    @Override
    public void stop() {
        service.unregisterParticipant(this);
        started = false;
    }

    @Override
    public void clearCache() {
        stateMap.clear();
    }

    @Override
    public boolean isUpnp() {
        return true;
    }

    @Override
    public boolean checkConnection() {
        return started;
    }

    @Override
    public boolean handleCommand(String channel, Command command) {
        logger.trace("{}: Received channel: {}, command: {}", host, channel, command);
        boolean result = false;

        if (!checkConnection()) {
            return false;
        }

        if (command == RefreshType.REFRESH) {
            if (isRegistered()) {
                switch (channel) {
                    case CHANNEL:
                        updateResourceState("GetCurrentMainTVChannel");
                        break;
                    case SOURCE_NAME:
                    case SOURCE_ID:
                        updateResourceState("GetCurrentExternalSource");
                        break;
                    case PROGRAM_TITLE:
                    case CHANNEL_NAME:
                        updateResourceState("GetCurrentContentRecognition");
                        break;
                    case BROWSER_URL:
                        updateResourceState("GetCurrentBrowserURL");
                        break;
                    default:
                        break;
                }
            }
            return true;
        }

        switch (channel) {
            case SOURCE_NAME:
                if (command instanceof StringType) {
                    setSourceName(command);
                    // Clear value on cache to force update
                    stateMap.remove("CurrentExternalSource");
                    result = true;
                }
                break;
            case BROWSER_URL:
                if (command instanceof StringType) {
                    setBrowserUrl(command);
                    // Clear value on cache to force update
                    stateMap.remove("BrowserURL");
                    stateMap.remove("GetCurrentBrowserURL");
                    result = true;
                }
                break;
            case STOP_BROWSER:
                if (command instanceof OnOffType) {
                    stopBrowser(command);
                    // Clear value on cache to force update
                    stateMap.remove("BrowserURL");
                    stateMap.remove("GetCurrentBrowserURL");
                    result = true;
                }
                break;
            default:
                logger.warn("{}: Samsung TV doesn't support transmitting for channel '{}'", host, channel);
                return false;
        }
        if (!result) {
            logger.warn("{}: main tvservice: wrong command type {} channel {}", host, command, channel);
        }
        return result;
    }

    private boolean isRegistered() {
        return service.isRegistered(this);
    }

    @Override
    public String getUDN() {
        return udn;
    }

    @Override
    public void onServiceSubscribed(@Nullable String service, boolean succeeded) {
    }

    @Override
    public void onValueReceived(@Nullable String variable, @Nullable String value, @Nullable String service) {
        if (variable == null || value == null || service == null || variable.isBlank()) {
            return;
        }

        String oldValue = stateMap.getOrDefault(variable, "None");
        if (value.equals(oldValue)) {
            logger.trace("{}: Value '{}' for {} hasn't changed, ignoring update", host, value, variable);
            return;
        }

        stateMap.put(variable, value);

        switch (variable) {
            case "GetCurrentContentRecognition":
                handler.valueReceived(PROGRAM_TITLE, new StringType(value));
                handler.valueReceived(CHANNEL_NAME, new StringType(value));
                break;
            case "ProgramTitle":
                handler.valueReceived(PROGRAM_TITLE, new StringType(value));
                stateMap.remove("GetCurrentContentRecognition");
                break;
            case "ChannelName":
                handler.valueReceived(CHANNEL_NAME, new StringType(value));
                stateMap.remove("GetCurrentContentRecognition");
                break;
            case "CurrentExternalSource":
                handler.valueReceived(SOURCE_NAME, new StringType(value));
                break;
            case "CurrentChannel":
                handler.valueReceived(CHANNEL, new DecimalType(parseCurrentChannel(value)));
                break;
            case "ID":
                handler.valueReceived(SOURCE_ID, new DecimalType(value));
                break;
            case "GetCurrentBrowserURL":
            case "BrowserURL":
                handler.valueReceived(BROWSER_URL, new StringType(value));
                break;
        }
    }

    protected Map<String, String> updateResourceState(String actionId) {
        return updateResourceState(actionId, Map.of());
    }

    protected synchronized Map<String, String> updateResourceState(String actionId, Map<String, String> inputs) {
        @SuppressWarnings("null")
        Map<String, String> result = Optional.of(service)
                .map(a -> a.invokeAction(this, "MainTVAgent2", actionId, inputs)).filter(a -> !a.isEmpty())
                .orElse(Map.of(actionId, ""));
        result.keySet().stream().filter(a -> !"Result".equals(a))
                .forEach(a -> onValueReceived(a, result.get(a), "MainTVAgent2"));
        return result;
    }

    private void setSourceName(Command command) {
        String source = command.toString();
        Map<String, String> result = updateResourceState("GetSourceList");
        result = Optional.ofNullable(result).filter(a -> "OK".equals(a.get("Result"))).map(a -> a.get("SourceList"))
                .map(a -> parseSourceList(a)).map(a -> a.get(source))
                .map(a -> updateResourceState("SetMainTVSource", Map.of("Source", source, "ID", a, "UiID", "0")))
                .orElse(Map.of());
        logResult(result.getOrDefault("Result", "Unable to Set Source Name: " + source));
    }

    private void setBrowserUrl(Command command) {
        Map<String, String> result = updateResourceState("RunBrowser", Map.of("BrowserURL", command.toString()));
        logResult(result.getOrDefault("Result", "Unable to Set browser URL: " + command.toString()));
    }

    private void stopBrowser(Command command) {
        Map<String, String> result = updateResourceState("StopBrowser");
        logResult(result.getOrDefault("Result", "Unable to Stop Browser"));
    }

    private void logResult(String ok) {
        if ("OK".equals(ok)) {
            logger.debug("{}: Command successfully executed", host);
        } else {
            logger.warn("{}: Command execution failed, result='{}'", host, ok);
        }
    }

    private String parseCurrentChannel(String xml) {
        return Optional.ofNullable(xml).flatMap(a -> loadXMLFromString(a)).map(a -> a.getDocumentElement())
                .map(a -> getFirstNodeValue(a, "MajorCh", "-1")).orElse("-1");
    }

    private Map<String, String> parseSourceList(String xml) {
        Map<String, String> list = new HashMap<>();
        Optional<NodeList> nodeList = Optional.ofNullable(xml).flatMap(a -> loadXMLFromString(a))
                .map(a -> a.getDocumentElement()).map(a -> a.getElementsByTagName("Source"));
        // NodeList doesn't have a stream, so do this
        nodeList.ifPresent(nList -> IntStream.range(0, nList.getLength()).mapToObj(i -> (Element) nList.item(i))
                .forEach(a -> list.put(getFirstNodeValue(a, "SourceType", ""), getFirstNodeValue(a, "ID", ""))));
        return list;
    }

    private String getFirstNodeValue(Element nodeList, String node, String ifNone) {
        return Optional.ofNullable(nodeList).map(a -> a.getElementsByTagName(node)).filter(a -> a.getLength() > 0)
                .map(a -> a.item(0)).map(a -> a.getFirstChild()).map(a -> a.getNodeValue()).orElse(ifNone);
    }

    private void configureXMLParser() {
        try {
            // see https://cheatsheetseries.owasp.org/cheatsheets/XML_External_Entity_Prevention_Cheat_Sheet.html
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
        } catch (ParserConfigurationException e) {
            logger.debug("{}: XMLParser Configuration Error: {}", host, e.getMessage());
        }
    }

    /**
     * Build {@link Document} from {@link String} which contains XML content.
     *
     * @param xml
     *            {@link String} which contains XML content.
     * @return {@link Optional Document} or empty if convert has failed.
     */
    public Optional<Document> loadXMLFromString(String xml) {
        try {
            return Optional.ofNullable(factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml))));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            logger.debug("{}: Error loading XML: {}", host, e.getMessage());
        }
        return Optional.empty();
    }

    @Override
    public void onStatusChanged(boolean status) {
        logger.debug("{}: onStatusChanged: status={}", host, status);
        if (!status) {
            handler.setOffline();
        }
    }
}
