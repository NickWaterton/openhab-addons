/**
 * Copyright (c) 2010-2022 Contributors to the openHAB project
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
import static org.openhab.binding.samsungtv.internal.config.SamsungTvConfiguration.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.samsungtv.internal.handler.SamsungTvHandler;
import org.openhab.binding.samsungtv.internal.service.api.SamsungTvService;
import org.openhab.core.io.net.http.HttpUtil;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

/**
 * The {@link SmartThingsApiService} is responsible for handling the Smartthings cloud interface
 * 
 *
 * @author Nick Waterton - Initial contribution
 */
@NonNullByDefault
public class SmartThingsApiService implements SamsungTvService {

    public static final String SERVICE_NAME = "SmartthingsApi";
    private static final List<String> SUPPORTED_CHANNELS = Arrays.asList(SOURCE_NAME, SOURCE_ID);
    private static final List<String> REFRESH_CHANNELS = Arrays.asList(CHANNEL, CHANNEL_NAME, SOURCE_NAME, SOURCE_ID);
    // Smarttings URL
    private static final String SMARTTHINGS_URL = "api.smartthings.com";
    // Path for the information endpoint note the final /
    private static final String API_ENDPOINT_V1 = "/v1/";
    // private static final String INPUT_SOURCE = "/components/main/capabilities/mediaInputSource/status";
    // private static final String CURRENT_CHANNEL = "/components/main/capabilities/tvChannel/status";
    private static final String COMPONENTS = "/components/main/status";
    private static final String DEVICES = "devices";
    private static final String COMMAND = "/commands";

    private final Logger logger = LoggerFactory.getLogger(SmartThingsApiService.class);

    private String host = "Unknown";
    private String apiKey = "";
    private String deviceId = "";
    private int RATE_LIMIT = 1000;
    private long prevUpdate = 0;
    private boolean online = false;

    private final SamsungTvHandler handler;

    @Nullable
    private TvValues tvInfo = null;

    private Map<String, Object> stateMap = Collections.synchronizedMap(new HashMap<>());

    public SmartThingsApiService(String host, SamsungTvHandler handler) {
        this.handler = handler;
        this.host = host;
        this.apiKey = handler.configuration.smartThingsApiKey;
        this.deviceId = handler.configuration.smartThingsDeviceId;
        logger.debug("{}: Creating a Samsung TV Smartthings Api service", host);
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

    // Description of tvValues
    @NonNullByDefault({})
    // @SuppressWarnings("unused")
    class TvValues {
        class MediaInputSource {
            ValuesList supportedInputSources;
            Values inputSource;
        }

        class TvChannel {
            Values tvChannel;
            Values tvChannelName;
        }

        class Values {
            String value;
            String timestamp;
        }

        class ValuesList {
            String[] value;
            String timestamp;
        }

        class Items {
            String deviceId;
            String name;
            String label;

            public String getDeviceId() {
                return Optional.ofNullable(deviceId).orElse("");
            }

            public String getName() {
                return Optional.ofNullable(name).orElse("");
            }

            public String getLabel() {
                return Optional.ofNullable(label).orElse("");
            }
        }

        class Error {
            String code;
            String message;
            Details[] details;
        }

        class Details {
            String code;
            String target;
            String message;
        }

        MediaInputSource mediaInputSource;
        TvChannel tvChannel;
        Items[] items;
        Error error;

        public Items[] getItems() {
            return Optional.ofNullable(items).orElse(new Items[0]);
        }

        public String[] getSources() {
            return Optional.ofNullable(mediaInputSource).map(a -> a.supportedInputSources).map(a -> a.value)
                    .orElse(new String[0]);
        }

        public String getSourcesString() {
            return Arrays.asList(getSources()).stream().collect(Collectors.joining(","));
        }

        public String getInputSource() {
            return Optional.ofNullable(mediaInputSource).map(a -> a.inputSource).map(a -> a.value).orElse("");
        }

        public int getInputSourceId() {
            return IntStream.range(0, getSources().length).filter(i -> getSources()[i].equals(getInputSource()))
                    .findFirst().orElse(-1);
        }

        public int getTvChannel() {
            return Optional.ofNullable(tvChannel).map(a -> a.tvChannel).map(a -> a.value).filter(i -> !i.isBlank())
                    .map(Integer::parseInt).orElse(-1);
        }

        public String getTvChannelName() {
            return Optional.ofNullable(tvChannel).map(a -> a.tvChannelName).map(a -> a.value).orElse("");
        }

        public String getError() {
            String code = Optional.ofNullable(error).map(a -> a.code).orElse("");
            String message = Optional.ofNullable(error).map(a -> a.message).orElse("");
            return String.format("%s, %s", code, message);
        }
    }

    @NonNullByDefault({})
    class JSONContent {
        public JSONContent(String capability, String action, String value) {
            Command command = new Command();
            command.capability = capability;
            command.command = action;
            command.arguments = new String[] { value };
            commands = new Command[] { command };
        }

        class Command {
            String component = "main";
            String capability;
            String command;
            String[] arguments;
        }

        Command[] commands;
    }

    /**
     * Smartthings API HTTP getter
     * Currently rate limited to 350 requests/minute
     *
     * @param value the query to send
     *
     * @return TvValues
     */
    @Nullable
    public synchronized TvValues fetchTVProperties(String value) {
        if (apiKey.isBlank()) {
            return null;
        }
        @Nullable
        TvValues tvValues = null;
        try {
            String api = API_ENDPOINT_V1 + ((deviceId.isBlank()) ? "" : "devices/") + deviceId + value;
            URI uri = new URI("https", null, SMARTTHINGS_URL, 443, api, null, null);
            // need to add header "Authorization":"Bearer " + apiKey;
            Properties headers = new Properties();
            // headers.putAll(HTTP_HEADERS);
            headers.put("Authorization", "Bearer " + this.apiKey);
            logger.trace("{}: Sending {}", host, uri.toURL().toString());
            @Nullable
            String response = HttpUtil.executeUrl("GET", uri.toURL().toString(), headers, null, null, 500);
            if (response != null && !response.startsWith("{")) {
                logger.debug("{}: Got response: {}", host, response);
            }
            tvValues = new Gson().fromJson(response, TvValues.class);
            if (tvValues == null) {
                throw new IOException("No Data");
            }
            if (tvValues.error != null) {
                logger.debug("{}: Error: {}", host, tvValues.getError());
            }
        } catch (JsonSyntaxException | URISyntaxException | IOException e) {
            logger.debug("{}: Cannot connect to Smartthings Cloud: {}", host, e.getMessage());
        }
        return tvValues;
    }

    /**
     * Smartthings API HTTP setter
     * Currently rate limited to 350 requests/minute
     *
     * @param capability eg mediaInputSource
     *
     * @param command eg setInputSource
     *
     * @param value from acceptible list eg HDMI1, digitalTv, AM etc
     *
     * @return boolean true if successful
     */
    public synchronized boolean setTVProperties(String capability, String command, String value) {
        if (apiKey.isBlank() || deviceId.isBlank()) {
            return false;
        }
        @Nullable
        String response = null;
        try {
            String contentString = new Gson().toJson(new JSONContent(capability, command, value));
            logger.trace("{}: content: {}", host, contentString);
            InputStream content = new ByteArrayInputStream(contentString.getBytes());
            String api = API_ENDPOINT_V1 + "devices/" + deviceId + COMMAND;
            URI uri = new URI("https", null, SMARTTHINGS_URL, 443, api, null, null);
            // need to add header "Authorization":"Bearer " + apiKey;
            Properties headers = new Properties();
            // headers.putAll(HTTP_HEADERS);
            headers.put("Authorization", "Bearer " + this.apiKey);
            logger.trace("{}: Sending {}", host, uri.toURL().toString());
            response = HttpUtil.executeUrl("POST", uri.toURL().toString(), headers, content, "application/json", 500);
            if (response == null) {
                throw new IOException("No Data");
            } else if (!response.startsWith("{")) {
                logger.debug("{}: Got response: {}", host, response);
            }
        } catch (JsonSyntaxException | URISyntaxException | IOException e) {
            logger.debug("{}: Send Command to Smartthings Cloud failed: {}", host, e.getMessage());
        }
        return (response != null && response.contains("ACCEPTED"));
    }

    private boolean updateDeviceID(TvValues.Items item) {
        this.deviceId = item.getDeviceId();
        logger.info("{}: found {} device, adding device id {}", host, item.getName(), deviceId);
        handler.putConfig(SMARTTHINGS_DEVICEID, deviceId);
        prevUpdate = 0;
        return true;
    }

    @SuppressWarnings("null")
    public boolean fetchdata() {
        if (System.currentTimeMillis() >= prevUpdate + RATE_LIMIT) {
            if (deviceId.isBlank()) {
                tvInfo = fetchTVProperties(DEVICES);
                if (tvInfo != null) {
                    if (tvInfo.getItems().length == 0) {
                        logger.info("{}: No devices found - please add your TV to the Smartthings app", host);
                        stop();
                        return false;
                    }
                    if (tvInfo.getItems().length == 1) {
                        boolean found = Arrays.asList(tvInfo.getItems()).stream()
                                .filter(a -> "Samsung TV".equals(a.getName())).map(a -> updateDeviceID(a)).findFirst()
                                .orElse(false);
                        if (found) {
                            return fetchdata();
                        }
                    }
                    logger.info("{}: No device Id selected, please enter one of the following:", host);
                    Arrays.asList(tvInfo.getItems()).stream().forEach(
                            a -> logger.info("{}: '{}' : {}({})", host, a.getDeviceId(), a.getName(), a.getLabel()));
                    stop();
                }
                return false;
            }
            tvInfo = fetchTVProperties(COMPONENTS);
            prevUpdate = System.currentTimeMillis();
        }
        return (tvInfo != null);
    }

    @Override
    public void start() {
        online = true;
    }

    @Override
    public void stop() {
        online = false;
    }

    @Override
    public void clearCache() {
        stateMap.clear();
    }

    @Override
    public boolean isUpnp() {
        return false;
    }

    @Override
    public boolean checkConnection() {
        return online;
    }

    @Override
    @SuppressWarnings("null")
    public boolean handleCommand(String channel, Command command) {
        logger.trace("{}: Received channel: {}, command: {}", host, channel, command);
        if (!checkConnection()) {
            logger.trace("{}: Smartthings offline", host);
            return false;
        }

        boolean result = false;
        if (fetchdata()) {
            if (command == RefreshType.REFRESH) {
                switch (channel) {
                    case CHANNEL_NAME:
                        updateState(CHANNEL_NAME, tvInfo.getTvChannelName());
                        break;
                    case CHANNEL:
                        updateState(CHANNEL, tvInfo.getTvChannel());
                        break;
                    case SOURCE_ID:
                    case SOURCE_NAME:
                        updateState(SOURCE_NAME, tvInfo.getInputSource());
                        updateState(SOURCE_ID, tvInfo.getInputSourceId());
                        break;
                    default:
                        break;
                }
                return true;
            }

            switch (channel) {
                case SOURCE_ID:
                    if (command instanceof DecimalType) {
                        int val = ((DecimalType) command).intValue();
                        if (val >= 0 && val < tvInfo.getSources().length) {
                            result = setSourceName(tvInfo.getSources()[val]);
                        } else {
                            logger.warn("{}: Invalid source ID: {}, acceptable: 0..{}", host, command,
                                    tvInfo.getSources().length);
                        }
                    }
                    break;
                case SOURCE_NAME:
                    if (command instanceof StringType) {
                        if (tvInfo.getSourcesString().contains(command.toString())) {
                            result = setSourceName(command.toString());
                        } else {
                            logger.warn("{}: Invalid source Name: {}, acceptable: {}", host, command,
                                    tvInfo.getSourcesString());
                        }
                    }
                    break;
                default:
                    logger.warn("{}: Samsung TV doesn't support transmitting for channel '{}'", host, channel);
            }
        }
        if (!result) {
            logger.warn("{}: Smartthings: wrong command type {} channel {}", host, command, channel);
        }
        return result;
    }

    private void updateState(String channel, Object value) {
        if (!stateMap.getOrDefault(channel, "None").equals(value)) {
            switch (channel) {
                case CHANNEL:
                case SOURCE_ID:
                    handler.valueReceived(channel, new DecimalType((Integer) value));
                    break;
                default:
                    handler.valueReceived(channel, new StringType((String) value));
                    break;
            }
            stateMap.put(channel, value);
        } else {
            logger.trace("{}: Value '{}' for {} hasn't changed, ignoring update", host, value, channel);
        }
    }

    private boolean setSourceName(String value) {
        return setTVProperties("mediaInputSource", "setInputSource", value);
    }
}
