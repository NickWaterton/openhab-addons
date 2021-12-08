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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.samsungtv.internal.handler.SamsungTvHandler;
import org.openhab.binding.samsungtv.internal.service.api.SamsungTvService;
import org.openhab.core.io.transport.upnp.UpnpIOParticipant;
import org.openhab.core.io.transport.upnp.UpnpIOService;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link MediaRendererService} is responsible for handling MediaRenderer
 * commands.
 *
 * @author Pauli Anttila - Initial contribution
 * @author Nick Waterton - added checkConnection(), getServiceName
 */
@NonNullByDefault
public class MediaRendererService implements UpnpIOParticipant, SamsungTvService {

    public static final String SERVICE_NAME = "MediaRenderer";
    private static final List<String> SUPPORTED_CHANNELS = Arrays.asList(VOLUME, MUTE, BRIGHTNESS, CONTRAST, SHARPNESS,
            COLOR_TEMPERATURE);

    private final Logger logger = LoggerFactory.getLogger(MediaRendererService.class);

    private final UpnpIOService service;

    private final String udn;
    private String host = "Unknown";

    private final SamsungTvHandler handler;

    private Map<String, String> stateMap = Collections.synchronizedMap(new HashMap<>());

    private boolean started;

    public MediaRendererService(UpnpIOService upnpIOService, String udn, String host, SamsungTvHandler handler) {
        this.service = upnpIOService;
        this.udn = udn;
        this.handler = handler;
        this.host = host;
        logger.debug("{}: Creating a Samsung TV MediaRenderer service", host);
    }

    @Override
    public String getServiceName() {
        return SERVICE_NAME;
    }

    @Override
    public List<String> getSupportedChannelNames(boolean refresh) {
        if (!refresh) {
            logger.trace("{}: getSupportedChannelNames: {}", host, SUPPORTED_CHANNELS);
        }
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
        logger.debug("{}: Received channel: {}, command: {}", host, channel, command);
        boolean result = false;

        if (!checkConnection()) {
            return false;
        }

        if (command == RefreshType.REFRESH) {
            if (isRegistered()) {
                switch (channel) {
                    case VOLUME:
                        updateResourceState("GetVolume");
                        break;
                    case MUTE:
                        updateResourceState("GetMute");
                        break;
                    case BRIGHTNESS:
                        updateResourceState("GetBrightness");
                        break;
                    case CONTRAST:
                        updateResourceState("GetContrast");
                        break;
                    case SHARPNESS:
                        updateResourceState("GetSharpness");
                        break;
                    case COLOR_TEMPERATURE:
                        updateResourceState("GetColorTemperature");
                        break;
                    default:
                        break;
                }
            }
            return true;
        }

        switch (channel) {
            case VOLUME:
                if (command instanceof DecimalType) {
                    setVolume(command);
                    result = true;
                }
                break;
            case MUTE:
                if (command instanceof OnOffType) {
                    setMute(command);
                    result = true;
                }
                break;
            case BRIGHTNESS:
                if (command instanceof DecimalType) {
                    setBrightness(command);
                    result = true;
                }
                break;
            case CONTRAST:
                if (command instanceof DecimalType) {
                    setContrast(command);
                    result = true;
                }
                break;
            case SHARPNESS:
                if (command instanceof DecimalType) {
                    setSharpness(command);
                    result = true;
                }
                break;
            case COLOR_TEMPERATURE:
                if (command instanceof DecimalType) {
                    setColorTemperature(command);
                    result = true;
                }
                break;
            default:
                logger.warn("{}: Samsung TV doesn't support transmitting for channel '{}'", host, channel);
                return false;
        }
        if (!result) {
            logger.warn("{}: media renderer: wrong command type {} channel {}", host, command, channel);
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
            case "CurrentVolume":
                handler.valueReceived(VOLUME, new PercentType(value));
                break;

            case "CurrentMute":
                handler.valueReceived(MUTE, "true".equals(value) ? OnOffType.ON : OnOffType.OFF);
                break;
            case "CurrentBrightness":
                handler.valueReceived(BRIGHTNESS, new PercentType(value));
                break;
            case "CurrentContrast":
                handler.valueReceived(CONTRAST, new PercentType(value));
                break;
            case "CurrentSharpness":
                handler.valueReceived(SHARPNESS, new PercentType(value));
                break;
            case "CurrentColorTemperature":
                handler.valueReceived(COLOR_TEMPERATURE, new DecimalType(value));
                break;
        }
    }

    protected Map<String, String> updateResourceState(String actionId) {
        return updateResourceState(actionId, Map.of());
    }

    @SuppressWarnings("null")
    protected synchronized Map<String, String> updateResourceState(String actionId, Map<String, String> inputs) {
        Map<String, String> inputsMap = new HashMap<>(inputs);
        inputsMap.put("InstanceID", "0");
        if (actionId.contains("Volume") || actionId.contains("Mute")) {
            inputsMap.put("Channel", "Master");
        }
        Map<String, String> result = service.invokeAction(this, "RenderingControl", actionId, inputsMap);
        result.keySet().stream().filter(a -> !"Result".equals(a))
                .forEach(a -> onValueReceived(a, result.get(a), "RenderingControl"));
        return result;
    }

    private void setVolume(Command command) {
        updateResourceState("SetVolume", Map.of("DesiredVolume", cmdToString(command)));
        updateResourceState("GetVolume");
    }

    private void setMute(Command command) {
        updateResourceState("SetMute", Map.of("DesiredMute", cmdToString(command)));
        updateResourceState("GetMute");
    }

    private void setBrightness(Command command) {
        updateResourceState("SetBrightness", Map.of("DesiredBrightness", cmdToString(command)));
        updateResourceState("GetBrightness");
    }

    private void setContrast(Command command) {
        updateResourceState("SetContrast", Map.of("DesiredContrast", cmdToString(command)));
        updateResourceState("GetContrast");
    }

    private void setSharpness(Command command) {
        updateResourceState("SetSharpness", Map.of("DesiredSharpness", cmdToString(command)));
        updateResourceState("GetSharpness");
    }

    private void setColorTemperature(Command command) {
        int newValue = Math.max(0, Math.min(((DecimalType) command).intValue(), 4));
        updateResourceState("SetColorTemperature", Map.of("DesiredColorTemperature", Integer.toString(newValue)));
        updateResourceState("GetColorTemperature");
    }

    private String cmdToString(Command command) {
        if (command instanceof DecimalType) {
            return Integer.toString(((DecimalType) command).intValue());
        }
        if (command instanceof OnOffType) {
            return Boolean.toString(command.equals(OnOffType.ON));
        }
        return command.toString();
    }

    @Override
    public void onStatusChanged(boolean status) {
        logger.debug("{}: onStatusChanged: status={}", host, status);
        if (!status) {
            handler.setOffline();
        }
    }
}
