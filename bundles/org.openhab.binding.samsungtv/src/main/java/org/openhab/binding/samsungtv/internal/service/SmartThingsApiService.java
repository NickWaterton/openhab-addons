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
import org.openhab.binding.samsungtv.internal.config.SamsungTvConfiguration;
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
    private int timeout = 700;

    private final SamsungTvHandler handler;

    private TvValues tvInfo = new TvValues();

    private Map<String, Object> stateMap = Collections.synchronizedMap(new HashMap<>());

    public SmartThingsApiService(String host, SamsungTvHandler handler) {
        this.handler = handler;
        this.host = host;
        this.apiKey = handler.configuration.getSmartThingsApiKey();
        this.deviceId = handler.configuration.getSmartThingsDeviceId();
        this.timeout = Math.min(2000, (int) (handler.configuration.getRefreshInterval() * 0.7));
        logger.debug("{}: Creating a Samsung TV Smartthings Api service", host);
        if (deviceId.isBlank()) {
            fetchdata();
        }
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

        class Ocf {
            Values st;
            Values mndt;
            Values mnfv;
            Values mnhw;
            Values di;
            Values mnsl;
            Values dmv;
            Values n;
            Values mnmo;
            Values vid;
            Values mnmn;
            Values mnml;
            Values mnos;
            Values p;
            Values icv;

            public String getDeviceId() {
                return Optional.ofNullable(di).map(a -> a.value).orElse("");
            }

            public String getModel() {
                return Optional.ofNullable(mnmo).map(a -> a.value).orElse("");
            }
        }

        class Values {
            String value;
            String timestamp;
        }

        class ValuesList {
            String[] value;
            String timestamp;
        }

        class Item {
            String deviceId;
            String name;
            String label;
            String manufacturerName;

            public String getDeviceId() {
                return Optional.ofNullable(deviceId).orElse("");
            }

            public void setDeviceId(String deviceId) {
                this.deviceId = deviceId;
            }

            public String getName() {
                return Optional.ofNullable(name).orElse("");
            }

            public String getLabel() {
                return Optional.ofNullable(label).orElse("");
            }

            public String getManufacturer() {
                return Optional.ofNullable(manufacturerName).orElse("");
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
        Ocf ocf;
        Item[] items;
        Error error;
        boolean connecterror = false;

        public Item[] getItems() {
            return Optional.ofNullable(items).orElse(new Item[0]);
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

        public String getDeviceId() {
            return Optional.ofNullable(ocf).map(a -> a.getDeviceId()).orElse("");
        }

        public String getModel() {
            return Optional.ofNullable(ocf).map(a -> a.getModel()).orElse("");
        }

        public String getError() {
            String code = Optional.ofNullable(error).map(a -> a.code).orElse("");
            String message = Optional.ofNullable(error).map(a -> a.message).orElse("");
            return String.format("%s, %s", code, message);
        }

        public void setConnectError() {
            connecterror = true;
        }

        public boolean getConnectError() {
            return connecterror;
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
    public synchronized TvValues fetchTVProperties(String value) {
        TvValues tvValues = new TvValues();
        if (apiKey.isBlank()) {
            return tvValues;
        }
        try {
            String api = API_ENDPOINT_V1 + ((deviceId.isBlank()) ? "" : "devices/") + deviceId + value;
            URI uri = new URI("https", null, SMARTTHINGS_URL, 443, api, null, null);
            // need to add header "Authorization":"Bearer " + apiKey;
            Properties headers = new Properties();
            // headers.putAll(HTTP_HEADERS);
            headers.put("Authorization", "Bearer " + this.apiKey);
            logger.trace("{}: Sending {}", host, uri.toURL().toString());
            @Nullable
            String response = HttpUtil.executeUrl("GET", uri.toURL().toString(), headers, null, null, timeout);
            if (response != null && !response.startsWith("{")) {
                logger.debug("{}: Got response: {}", host, response);
            }
            tvValues = new Gson().fromJson(response, TvValues.class);
            if (tvValues == null) {
                throw new IOException("No Data");
            }
            if (tvValues.error != null) {
                throw new IOException(tvValues.getError());
            }
        } catch (JsonSyntaxException | URISyntaxException | IOException e) {
            logger.debug("{}: Cannot connect to Smartthings Cloud: {}", host, e.getMessage());
            tvValues = new TvValues();
            tvValues.setConnectError();
        }
        return tvValues;
    }

    /**
     * Smartthings API HTTP setter
     * Currently rate limited to 350 requests/minute
     *
     * @param capability eg mediaInputSource
     * @param command eg setInputSource
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
            response = HttpUtil.executeUrl("POST", uri.toURL().toString(), headers, content, "application/json",
                    timeout);
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

    private boolean updateDeviceID(TvValues.Item item) {
        deviceId = item.getDeviceId();
        logger.info("{}: found {} device, adding device id {}", host, item.getName(), deviceId);
        handler.putConfig(SamsungTvConfiguration.SMARTTHINGS_DEVICEID, deviceId);
        prevUpdate = 0;
        return true;
    }

    private TvValues.Item findTV(TvValues.Item item, String modelName) {
        deviceId = item.getDeviceId();
        TvValues tmpTvInfo = fetchTVProperties(COMPONENTS);
        deviceId = "";
        if (tmpTvInfo.getModel().equals(modelName)) {
            logger.trace("{}: Found TV model: {}, Name: {}, label: {}, Device id: {}", host, modelName, item.getName(),
                    item.getLabel(), item.getDeviceId());
            return item;
        }
        item.setDeviceId("");
        return item;
    }

    public synchronized boolean fetchdata() {
        if (deviceId.isBlank()) {
            logger.info("{}: No Device ID - Fetching Device ID's:", host);
            tvInfo = fetchTVProperties(DEVICES);
            if (tvInfo.getItems().length == 0) {
                if (!tvInfo.getConnectError()) {
                    logger.info("{}: No devices found - please add your TV to the Smartthings app", host);
                    stop();
                }
                return false;
            }
            if (tvInfo.getItems().length == 1) {
                boolean found = Arrays.asList(tvInfo.getItems()).stream().filter(a -> "Samsung TV".equals(a.getName()))
                        .map(a -> updateDeviceID(a)).findFirst().orElse(false);
                if (found) {
                    return fetchdata();
                }
            }
            String modelName = handler.getModelName();
            if (!modelName.isBlank()) {
                List<TvValues.Item> tvs = Arrays.asList(tvInfo.getItems()).stream()
                        .filter(a -> a.getManufacturer().contains("Samsung")).map(a -> findTV(a, modelName))
                        .filter(a -> !a.getDeviceId().isBlank()).collect(Collectors.toList());
                if (tvs.size() == 1) {
                    updateDeviceID(tvs.get(0));
                    return false;
                }
            }
            logger.info("{}: Unable to identify TV Device Id, please enter one of the following:", host);
            Arrays.asList(tvInfo.getItems()).stream().filter(a -> a.getManufacturer().contains("Samsung")).forEach(
                    a -> logger.info("{}: Device Id: '{}' : {}({})", host, a.getDeviceId(), a.getName(), a.getLabel()));
            // stop();
            return false;
        }
        if (System.currentTimeMillis() >= prevUpdate + RATE_LIMIT) {
            tvInfo = fetchTVProperties(COMPONENTS);
            prevUpdate = System.currentTimeMillis();
        }
        return !tvInfo.getConnectError();
    }

    @Override
    public void start() {
        online = true;
    }

    @Override
    public void stop() {
        online = false;
        logger.debug("{}: SmartThingsApiService: Offline", host);
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
    public boolean handleCommand(String channel, Command command) {
        logger.debug("{}: Received channel: {}, command: {}", host, channel, command);
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
                    } else {
                        logger.warn("{}: Smartthings: wrong command type {} channel {}", host, command, channel);
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
                    } else {
                        logger.warn("{}: Smartthings: wrong command type {} channel {}", host, command, channel);
                    }
                    break;
                default:
                    logger.warn("{}: Samsung TV doesn't support transmitting for channel '{}'", host, channel);
            }
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
