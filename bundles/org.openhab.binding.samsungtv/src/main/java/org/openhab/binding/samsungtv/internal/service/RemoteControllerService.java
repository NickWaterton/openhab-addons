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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.samsungtv.internal.Utils;
import org.openhab.binding.samsungtv.internal.handler.SamsungTvHandler;
import org.openhab.binding.samsungtv.internal.protocol.KeyCode;
import org.openhab.binding.samsungtv.internal.protocol.RemoteController;
import org.openhab.binding.samsungtv.internal.protocol.RemoteControllerException;
import org.openhab.binding.samsungtv.internal.protocol.RemoteControllerLegacy;
import org.openhab.binding.samsungtv.internal.protocol.RemoteControllerWebSocket;
import org.openhab.binding.samsungtv.internal.service.api.SamsungTvService;
import org.openhab.core.io.net.http.WebSocketFactory;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.IncreaseDecreaseType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.RawType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.types.UpDownType;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link RemoteControllerService} is responsible for handling remote
 * controller commands.
 *
 * @author Pauli Anttila - Initial contribution
 * @author Martin van Wingerden - Some changes for manually configured devices
 * @author Arjan Mels - Implemented websocket interface for recent TVs
 * @author Nick Waterton - added power state monitoring for Frame TV's, some refactoring, sendkeys()
 */
@NonNullByDefault
public class RemoteControllerService implements SamsungTvService {

    private final Logger logger = LoggerFactory.getLogger(RemoteControllerService.class);

    public static final String SERVICE_NAME = "RemoteControlReceiver";

    private final List<String> supportedCommandsUpnp = Arrays.asList(KEY_CODE, POWER, CHANNEL);
    private final List<String> supportedCommandsNonUpnp = Arrays.asList(KEY_CODE, VOLUME, MUTE, POWER, CHANNEL,
            BROWSER_URL, STOP_BROWSER, SOURCE_APP);
    private final List<String> supportedCommandsArt = Arrays.asList(ART_MODE, ART_JSON, ART_LABEL, ART_IMAGE,
            ART_BRIGHTNESS, ART_COLOR_TEMPERATURE);
    private static final List<String> REFRESH_CHANNELS = Arrays.asList();
    private static final List<String> refreshArt = Arrays.asList(ART_BRIGHTNESS);
    private static final List<String> refreshApps = Arrays.asList(SOURCE_APP);

    private String host;
    private boolean upnp;
    private String previousApp = "None";
    private final int keyTiming = 300;

    private long busyUntil = System.currentTimeMillis();

    public boolean artMode = false;

    public final SamsungTvHandler handler;

    private final RemoteController remoteController;

    public RemoteControllerService(String host, int port, boolean upnp, SamsungTvHandler handler)
            throws RemoteControllerException {
        logger.debug("{}: Creating a Samsung TV RemoteController service: is UPNP:{}", host, upnp);
        this.upnp = upnp;
        this.host = host;
        this.handler = handler;
        try {
            if (upnp) {
                remoteController = new RemoteControllerLegacy(host, port, "openHAB", "openHAB");
                remoteController.openConnection();
            } else {
                remoteController = new RemoteControllerWebSocket(host, port, "openHAB", "openHAB", this);
            }
        } catch (RemoteControllerException e) {
            throw new RemoteControllerException("Cannot create RemoteControllerService", e);
        }
    }

    @Override
    public String getServiceName() {
        return SERVICE_NAME;
    }

    @Override
    public List<String> getSupportedChannelNames(boolean refresh) {
        // no refresh channels for UPNP remotecontroller
        List<String> supported = new ArrayList<>(refresh ? upnp ? Arrays.asList() : REFRESH_CHANNELS
                : upnp ? supportedCommandsUpnp : supportedCommandsNonUpnp);
        if (getArtModeSupported()) {
            supported.addAll(refresh ? refreshArt : supportedCommandsArt);
        }
        if (remoteController.noApps() && refresh) {
            supported.addAll(refreshApps);
        }
        if (!refresh) {
            logger.trace("{}: getSupportedChannelNames: {}", host, supported);
        }
        return supported;
    }

    @Override
    public boolean checkConnection() {
        return remoteController.isConnected();
    }

    @Override
    public void start() {
        try {
            if (!checkConnection()) {
                remoteController.openConnection();
            }
        } catch (RemoteControllerException e) {
            reportError("Cannot connect to remote control service", e);
        }
        previousApp = "";
    }

    @Override
    public void stop() {
        try {
            remoteController.close();
        } catch (RemoteControllerException ignore) {
            // ignore error
        }
    }

    @Override
    public void clearCache() {
        start();
    }

    @Override
    public boolean isUpnp() {
        return upnp;
    }

    @Override
    public boolean handleCommand(String channel, Command command) {
        logger.trace("{}: Received channel: {}, command: {}", host, channel, Utils.truncCmd(command));

        boolean result = false;
        if (!remoteController.isConnected()) {
            logger.warn("{}: RemoteController is not connected", host);
            try {
                remoteController.openConnection();
            } catch (RemoteControllerException e) {
                logger.warn("{}: Could not re-open connection {}", host, e.getMessage());
                return result;
            }
        }
        if (command == RefreshType.REFRESH) {
            switch (channel) {
                case SOURCE_APP:
                    if (getPowerState()) {
                        remoteController.updateCurrentApp();
                    }
                    break;
                case ART_IMAGE:
                case ART_LABEL:
                    remoteController.getArtmodeStatus("get_current_artwork");
                    break;
                case ART_BRIGHTNESS:
                    remoteController.getArtmodeStatus("get_brightness");
                    break;
                case ART_COLOR_TEMPERATURE:
                    remoteController.getArtmodeStatus("get_color_temperature");
                    break;
            }
            return true;
        }

        switch (channel) {
            case BROWSER_URL:
                if (command instanceof StringType) {
                    remoteController.sendUrl(command.toString());
                    result = true;
                }
                break;

            case STOP_BROWSER:
                if (command instanceof OnOffType) {
                    if (command.equals(OnOffType.ON)) {
                        return handleCommand(SOURCE_APP, new StringType(""));
                    } else {
                        sendKeys(KeyCode.KEY_EXIT, 2000);
                    }
                    result = true;
                }
                break;

            case SOURCE_APP:
                if (command instanceof StringType) {
                    remoteController.sendSourceApp(command.toString());
                    result = true;
                }
                break;

            case POWER:
                if (command instanceof OnOffType) {
                    if (!isUpnp()) {
                        // websocket uses KEY_POWER
                        // send key only to toggle state
                        if (OnOffType.ON.equals(command) != getPowerState()) {
                            sendKeys(KeyCode.KEY_POWER);
                        }
                    } else {
                        // legacy controller uses KEY_POWERON/OFF
                        if (command.equals(OnOffType.ON)) {
                            sendKeys(KeyCode.KEY_POWERON);
                        } else {
                            sendKeys(KeyCode.KEY_POWEROFF);
                        }
                    }
                    result = true;
                }
                break;

            case ART_MODE:
                if (command instanceof OnOffType) {
                    // websocket uses KEY_POWER
                    // send key only to toggle state when power = off
                    if (!getPowerState()) {
                        if (OnOffType.ON.equals(command)) {
                            if (!artMode) {
                                sendKeys(KeyCode.KEY_POWER);
                            }
                        } else {
                            // really switch off (long press of power)
                            sendKeys(KeyCode.KEY_POWER, 4000);
                        }
                    } else {
                        // switch TV off
                        sendKeys(KeyCode.KEY_POWER);
                    }
                    result = true;
                }
                break;

            case ART_JSON:
                if (command instanceof StringType) {
                    String artJson = command.toString();
                    if (!artJson.contains("\"id\"")) {
                        artJson = artJson.replaceFirst("}$", ",}");
                    }
                    remoteController.getArtmodeStatus(artJson);
                    result = true;
                }
                break;

            case ART_IMAGE:
            case ART_LABEL:
                if (command instanceof RawType) {
                    remoteController.getArtmodeStatus("send_image", command.toFullString());
                } else if (command instanceof StringType) {
                    if (command.toString().startsWith("data:image")) {
                        remoteController.getArtmodeStatus("send_image", command.toString());
                    } else if (channel.equals(ART_LABEL)) {
                        remoteController.getArtmodeStatus("select_image", command.toString());
                    }
                    result = true;
                }
                break;

            case ART_BRIGHTNESS:
                if (command instanceof DecimalType) {
                    DecimalType value = (DecimalType) command;
                    remoteController.getArtmodeStatus("set_brightness", String.valueOf(value.intValue() / 10));
                    result = true;
                }
                break;

            case ART_COLOR_TEMPERATURE:
                if (command instanceof DecimalType) {
                    DecimalType value = (DecimalType) command;
                    if (value.intValue() <= 5 && value.intValue() >= -5) {
                        remoteController.getArtmodeStatus("set_color_temperature", String.valueOf(value.intValue()));
                        result = true;
                    }
                }
                break;

            case KEY_CODE:
                if (command instanceof StringType) {
                    // split on [, +], but not if encloded in "" or {}
                    String[] cmds = command.toString().strip().split("(?=(?:(?:[^\"]*\"){2})*[^\"]*$)(?![^{]*})[, +]+",
                            0);
                    List<Object> commands = new ArrayList<>();
                    for (String cmd : cmds) {
                        try {
                            logger.trace("{}: Procesing command: {}", host, cmd);
                            if (cmd.startsWith("\"") || cmd.startsWith("{")) {
                                // remove leading and trailing "
                                cmd = cmd.replaceAll("^\"|\"$", "");
                                commands.add(cmd);
                                if (!cmd.startsWith("{")) {
                                    commands.add("");
                                }
                            } else if (cmd.matches("-?\\d{2,5}")) {
                                commands.add(Integer.parseInt(cmd));
                            } else {
                                String ucmd = cmd.toUpperCase();
                                commands.add(KeyCode.valueOf(ucmd.startsWith("KEY_") ? ucmd : "KEY_" + ucmd));
                            }
                        } catch (IllegalArgumentException e) {
                            logger.warn("{}: Remote control: unsupported cmd {} channel {}, {}", host, cmd, channel,
                                    e.getMessage());
                            return false;
                        }
                    }
                    if (!commands.isEmpty()) {
                        sendKeys(commands);
                    }
                    result = true;
                }
                break;

            case MUTE:
                if (command instanceof OnOffType) {
                    sendKeys(KeyCode.KEY_MUTE);
                    result = true;
                }
                break;

            case VOLUME:
                if (command instanceof UpDownType || command instanceof IncreaseDecreaseType) {
                    if (command.equals(UpDownType.UP) || command.equals(IncreaseDecreaseType.INCREASE)) {
                        sendKeys(KeyCode.KEY_VOLUP);
                    } else {
                        sendKeys(KeyCode.KEY_VOLDOWN);
                    }
                    result = true;
                }
                break;

            case CHANNEL:
                if (command instanceof DecimalType) {
                    KeyCode[] codes = command.toString().chars()
                            .mapToObj(c -> KeyCode.valueOf("KEY_" + String.valueOf((char) c))).toArray(KeyCode[]::new);
                    List<Object> commands = new ArrayList<>(Arrays.asList(codes));
                    commands.add(KeyCode.KEY_ENTER);
                    sendKeys(commands);
                    result = true;
                }
                break;
            default:
                logger.warn("{}: Remote control: unsupported channel: {}", host, channel);
                return false;
        }
        if (!result) {
            logger.warn("{}: Remote control: wrong command type {} channel {}", host, command, channel);
        }
        return result;
    }

    public synchronized void sendKeys(KeyCode key, int press) {
        sendKeys(Arrays.asList(key), press);
    }

    public synchronized void sendKeys(KeyCode key) {
        sendKeys(Arrays.asList(key), 0);
    }

    public synchronized void sendKeys(List<Object> keys) {
        sendKeys(keys, 0);
    }

    /**
     * Send sequence of key codes to Samsung TV RemoteController instance.
     * 300 ms between each key click (set by keyTiming). If press is > 0 then send key press/release
     *
     * @param keys List containing key codes/Integer delays to send.
     *            if integer delays are negative, send key press of abs(delay)
     * @param press int value of length of keypress in ms (0 means Click)
     */
    public synchronized void sendKeys(List<Object> keys, int press) {
        int timingInMs = keyTiming;
        int delay = (int) Math.max(0, busyUntil - System.currentTimeMillis());
        @Nullable
        ScheduledExecutorService scheduler = getScheduler();
        if (scheduler == null) {
            logger.warn("{}: Unable to schedule key sequence", host);
            return;
        }
        for (int i = 0; i < keys.size(); i++) {
            Object key = keys.get(i);
            if (key instanceof Integer) {
                if ((int) key > 0) {
                    delay += Math.max(0, (int) key - (2 * timingInMs));
                } else {
                    press = Math.max(timingInMs, Math.abs((int) key));
                    delay -= timingInMs;
                }
                continue;
            }
            if (press == 0 && key instanceof KeyCode && key.equals(KeyCode.KEY_BT_VOICE)) {
                press = 3000;
                delay -= timingInMs;
            }
            int duration = press;
            scheduler.schedule(() -> {
                if (duration > 0) {
                    remoteController.sendKeyPress((KeyCode) key, duration);
                } else {
                    if (key instanceof String) {
                        remoteController.sendKey((String) key);
                    } else {
                        remoteController.sendKey((KeyCode) key);
                    }
                }
            }, (i * timingInMs) + delay, TimeUnit.MILLISECONDS);
            delay += press;
            press = 0;
        }
        busyUntil = System.currentTimeMillis() + (keys.size() * timingInMs) + delay;
        logger.trace("{}: Key Sequence Queued", host);
    }

    private void reportError(String message, RemoteControllerException e) {
        reportError(ThingStatusDetail.COMMUNICATION_ERROR, message, e);
    }

    private void reportError(ThingStatusDetail statusDetail, String message, RemoteControllerException e) {
        handler.reportError(statusDetail, message, e);
    }

    public void appsUpdated(List<String> apps) {
        // do nothing
    }

    public void updateCurrentApp() {
        remoteController.updateCurrentApp();
    }

    public void currentAppUpdated(String app) {
        if (!previousApp.equals(app)) {
            handler.valueReceived(SOURCE_APP, new StringType(app));
            previousApp = app;
        }
    }

    public void powerUpdated(boolean on, boolean artMode) {
        setArtModeSupported(true);
        String powerState = fetchPowerState();
        if (checkConnection() && "off".equals(powerState)) {
            // retry if we are connected, but get "off' for powerState
            logger.warn("{}: Rechecking, received powerState '{}' but websocket is still connected", host, powerState);
            remoteController.getArtmodeStatus();
            // powerState = fetchPowerState();
        }
        if (!"on".equals(powerState)) {
            on = false;
            artMode = false;
        }
        setPowerState(on);
        this.artMode = artMode;
        // order of state updates is important to prevent extraneous transitions in overall state
        if (on) {
            handler.valueReceived(POWER, on ? OnOffType.ON : OnOffType.OFF);
            handler.valueReceived(ART_MODE, artMode ? OnOffType.ON : OnOffType.OFF);
        } else {
            handler.valueReceived(ART_MODE, artMode ? OnOffType.ON : OnOffType.OFF);
            handler.valueReceived(POWER, on ? OnOffType.ON : OnOffType.OFF);
        }
    }

    public void connectionError(@Nullable Throwable error) {
        logger.debug("{}: Connection error: {}", host, error != null ? error.getMessage() : "");
        // remoteControllers.clear();
    }

    public boolean getArtModeSupported() {
        return handler.getArtModeSupported();
    }

    public void setArtModeSupported(boolean artmode) {
        handler.setArtModeSupported(artmode);
    }

    public boolean getPowerState() {
        return handler.getPowerState();
    }

    public void setPowerState(boolean power) {
        handler.setPowerState(power);
    }

    public String fetchPowerState() {
        return handler.fetchPowerState();
    }

    public void setOffline() {
        handler.setOffline();
    }

    public void putConfig(String key, String value) {
        handler.putConfig(key, value);
    }

    public @Nullable Object getConfig(String key) {
        return handler.getConfig(key);
    }

    public @Nullable ScheduledExecutorService getScheduler() {
        return handler.getScheduler();
    }

    public @Nullable WebSocketFactory getWebSocketFactory() {
        return handler.getWebSocketFactory();
    }
}
