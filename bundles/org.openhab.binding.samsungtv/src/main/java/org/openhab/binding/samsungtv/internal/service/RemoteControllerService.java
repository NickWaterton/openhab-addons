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
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.samsungtv.internal.config.SamsungTvConfiguration;
import org.openhab.binding.samsungtv.internal.protocol.KeyCode;
import org.openhab.binding.samsungtv.internal.protocol.RemoteController;
import org.openhab.binding.samsungtv.internal.protocol.RemoteControllerException;
import org.openhab.binding.samsungtv.internal.protocol.RemoteControllerLegacy;
import org.openhab.binding.samsungtv.internal.protocol.RemoteControllerWebSocket;
import org.openhab.binding.samsungtv.internal.service.api.EventListener;
import org.openhab.binding.samsungtv.internal.service.api.SamsungTvService;
import org.openhab.core.io.net.http.WebSocketFactory;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
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
 * @author Nick Waterton - added power state monitoring for Frame TV's, some refactoring
 */
@NonNullByDefault
public class RemoteControllerService implements SamsungTvService {

    private final Logger logger = LoggerFactory.getLogger(RemoteControllerService.class);

    public static final String SERVICE_NAME = "RemoteControlReceiver";

    private final List<String> supportedCommandsUpnp = Arrays.asList(KEY_CODE, VOLUME, POWER, CHANNEL);
    private final List<String> supportedCommandsNonUpnp = Arrays.asList(KEY_CODE, VOLUME, MUTE, POWER, CHANNEL);
    private final List<String> extraSupportedCommandsWebSocket = Arrays.asList(BROWSER_URL, SOURCE_APP, ART_MODE);

    private String host;
    private int port;
    private boolean upnp;

    public boolean power = true;
    public boolean artMode = false;

    private boolean artModeSupported = false;

    // This is only here to prevent Null Pointer Warnings, there is only ever one listener (SamsungTvHandler)
    private Set<EventListener> listeners = new CopyOnWriteArraySet<>();

    // This is only here to prevent Null Pointer Warnings, there is only ever one remoteController (RemoteController)
    private Set<RemoteController> remoteControllers = new CopyOnWriteArraySet<>();

    public RemoteControllerService(String host, int port, boolean upnp, EventListener listener) {
        logger.debug("Creating a Samsung TV RemoteController service: is UPNP:{}", upnp);
        this.upnp = upnp;
        this.host = host;
        this.port = port;
        listeners.add(listener);
        this.power = getPowerState();
        this.artModeSupported = getArtModeIsSupported();
    }

    @Override
    public String getServiceName() {
        return SERVICE_NAME;
    }

    @Override
    public List<String> getSupportedChannelNames() {
        List<String> supported = upnp ? supportedCommandsUpnp : supportedCommandsNonUpnp;
        if (!isUpnp()) {
            supported = new ArrayList<>(supported);
            supported.addAll(extraSupportedCommandsWebSocket);
        }
        logger.trace("getSupportedChannelNames: {}", supported);
        return supported;
    }

    @Override
    public void addEventListener(EventListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeEventListener(EventListener listener) {
        listeners.remove(listener);
    }

    @Override
    public boolean checkConnection() {
        for (RemoteController remoteController : remoteControllers) {
            return remoteController.isConnected();
        }
        return false;
    }

    @Override
    public void start() {
        if (remoteControllers.isEmpty()) {
            String protocol = (String) getConfig(SamsungTvConfiguration.PROTOCOL);
            logger.info("Using {} interface", protocol);

            if (SamsungTvConfiguration.PROTOCOL_LEGACY.equals(protocol)) {
                remoteControllers.add(new RemoteControllerLegacy(host, port, "openHAB", "openHAB"));
            } else if (SamsungTvConfiguration.PROTOCOL_WEBSOCKET.equals(protocol)
                    || SamsungTvConfiguration.PROTOCOL_SECUREWEBSOCKET.equals(protocol)) {
                try {
                    remoteControllers.add(new RemoteControllerWebSocket(host, port, "openHAB", "openHAB", this));
                } catch (RemoteControllerException e) {
                    reportError("Cannot connect to remote control service", e);
                }
            }
        }

        for (RemoteController remoteController : remoteControllers) {
            try {
                remoteController.openConnection();
            } catch (RemoteControllerException e) {
                reportError("Cannot connect to remote control service", e);
            }
        }
    }

    @Override
    public void stop() {
        for (RemoteController remoteController : remoteControllers) {
            try {
                remoteController.close();
            } catch (RemoteControllerException ignore) {
            }
        }
    }

    @Override
    public void clearCache() {
    }

    @Override
    public boolean isUpnp() {
        return upnp;
    }

    @Override
    public void handleCommand(String channel, Command command) {
        logger.trace("Received channel: {}, command: {}", channel, command);
        if (command == RefreshType.REFRESH) {
            return;
        }

        KeyCode key = null;

        for (RemoteController remoteController : remoteControllers) {
            switch (channel) {
                case BROWSER_URL:
                    if (command instanceof StringType) {
                        remoteController.sendUrl(command.toString());
                    } else {
                        logger.warn("Remote control: unsupported command type {} for channel {}", command, channel);
                    }
                    return;

                case SOURCE_APP:
                    if (command instanceof StringType) {
                        remoteController.sendSourceApp(command.toString());
                    } else {
                        logger.warn("Remote control: unsupported command type {} for channel {}", command, channel);
                    }
                    return;

                case POWER:
                    if (command instanceof OnOffType) {
                        if (!isUpnp()) {
                            // websocket uses KEY_POWER
                            // send key only to toggle state
                            if (OnOffType.ON.equals(command) != power) {
                                remoteController.sendKey(KeyCode.KEY_POWER);
                            }
                        } else {
                            // legacy controller uses KEY_POWERON/OFF
                            if (command.equals(OnOffType.ON)) {
                                remoteController.sendKey(KeyCode.KEY_POWERON);
                            } else {
                                remoteController.sendKey(KeyCode.KEY_POWEROFF);
                            }
                        }
                    } else {
                        logger.warn("Remote control: unsupported command type {} for channel {}", command, channel);
                    }
                    return;

                case ART_MODE:
                    if (command instanceof OnOffType) {
                        // websocket uses KEY_POWER
                        // send key only to toggle state when power = off
                        if (!power) {
                            if (OnOffType.ON.equals(command)) {
                                if (!artMode) {
                                    remoteController.sendKey(KeyCode.KEY_POWER);
                                }
                            } else {
                                // really switch off (long press of power)
                                remoteController.sendKeyPress(KeyCode.KEY_POWER, 4000);
                            }
                        } else {
                            // switch TV off
                            remoteController.sendKey(KeyCode.KEY_POWER);
                        }
                    } else {
                        logger.warn("Remote control: unsupported command type {} for channel {}", command, channel);
                    }
                    return;

                case KEY_CODE:
                    if (command instanceof StringType) {
                        String[] cmds = command.toString().strip().toUpperCase().split("\\s*[, ]\\s*");
                        List<KeyCode> commands = new ArrayList<>();
                        for (String cmd : cmds) {
                            try {
                                commands.add(KeyCode.valueOf(cmd.startsWith("KEY_") ? cmd : "KEY_" + cmd));
                            } catch (IllegalArgumentException e) {
                                logger.warn("Remote control: Command '{}' not supported for channel '{}'", cmd,
                                        channel);
                            }
                        }
                        if (commands.isEmpty()) {
                            return;
                        } else if (commands.size() == 1) {
                            remoteController.sendKey(commands.get(0));
                        } else {
                            sendKeys(commands, remoteController);
                        }
                    } else {
                        logger.warn("Remote control: unsupported command type {} for channel {}", command, channel);
                    }
                    return;

                case MUTE:
                    if (command instanceof OnOffType) {
                        remoteController.sendKey(KeyCode.KEY_MUTE);
                    } else {
                        logger.warn("Remote control: unsupported command type {} for channel {}", command, channel);
                    }
                    return;

                case VOLUME:
                    if (command instanceof UpDownType) {
                        if (command.equals(UpDownType.UP)) {
                            remoteController.sendKey(KeyCode.KEY_VOLUP);
                        } else {
                            remoteController.sendKey(KeyCode.KEY_VOLDOWN);
                        }
                    } else {
                        logger.warn("Remote control: unsupported command type {} for channel {}", command, channel);
                    }
                    return;

                case CHANNEL:
                    if (command instanceof DecimalType) {
                        int val = ((DecimalType) command).intValue();
                        int num4 = val / 1000 % 10;
                        int num3 = val / 100 % 10;
                        int num2 = val / 10 % 10;
                        int num1 = val % 10;

                        List<KeyCode> commands = new ArrayList<>();

                        if (num4 > 0) {
                            commands.add(KeyCode.valueOf("KEY_" + num4));
                        }
                        if (num4 > 0 || num3 > 0) {
                            commands.add(KeyCode.valueOf("KEY_" + num3));
                        }
                        if (num4 > 0 || num3 > 0 || num2 > 0) {
                            commands.add(KeyCode.valueOf("KEY_" + num2));
                        }
                        commands.add(KeyCode.valueOf("KEY_" + num1));
                        commands.add(KeyCode.KEY_ENTER);
                        sendKeys(commands, remoteController);
                    } else {
                        logger.warn("Remote control: unsupported command type {} for channel {}", command, channel);
                    }
                    return;
                default:
                    logger.warn("Remote control: unsupported channel: {}", channel);
            }
        }
    }

    /**
     * Send sequence of key codes to Samsung TV.
     *
     * @param keys List of key codes to send.
     * @param sleepInMs Sleep between key code sending in milliseconds.
     */
    public void sendKeys(List<KeyCode> keys, RemoteController remoteController) {
        logger.debug("Try to send sequence of commands: {}", keys);
        int sleepInMs = 300;
        @Nullable
        ScheduledExecutorService scheduler = getScheduler();
        for (int i = 0; i < keys.size(); i++) {
            KeyCode key = keys.get(i);
            if (scheduler != null) {
                scheduler.schedule(() -> {
                    remoteController.sendKey(key);
                }, i * sleepInMs, TimeUnit.MILLISECONDS);
            }
        }

        logger.debug("Command Sequence Queued");
    }

    private void reportError(String message, RemoteControllerException e) {
        reportError(ThingStatusDetail.COMMUNICATION_ERROR, message, e);
    }

    private void reportError(ThingStatusDetail statusDetail, String message, RemoteControllerException e) {
        for (EventListener listener : listeners) {
            listener.reportError(statusDetail, message, e);
        }
    }

    public void appsUpdated(List<String> apps) {
        // do nothing
    }

    public void currentAppUpdated(@Nullable String app) {
        for (EventListener listener : listeners) {
            listener.valueReceived(SOURCE_APP, new StringType(app));
        }
    }

    public void powerUpdated(boolean on, boolean artmode) {
        artModeSupported = true;
        String powerState = fetchPowerState();
        if (checkConnection() && "off".equals(powerState)) {
            // retry if we are connected, but get "off' for powerState
            powerState = fetchPowerState();
        }
        if (!"on".equals(powerState)) {
            on = false;
            artmode = false;
        }
        power = on;
        this.artMode = artmode;
        // order of state updates is important to prevent extraneous transitions in overall state
        for (EventListener listener : listeners) {
            if (on) {
                listener.valueReceived(POWER, on ? OnOffType.ON : OnOffType.OFF);
                listener.valueReceived(ART_MODE, artmode ? OnOffType.ON : OnOffType.OFF);
            } else {
                listener.valueReceived(ART_MODE, artmode ? OnOffType.ON : OnOffType.OFF);
                listener.valueReceived(POWER, on ? OnOffType.ON : OnOffType.OFF);
            }
        }
    }

    public void connectionError(@Nullable Throwable error) {
        logger.debug("Connection error: {}", error != null ? error.getMessage() : "");
        remoteControllers.clear();
    }

    public boolean isArtModeSupported() {
        return artModeSupported;
    }

    public boolean getArtModeIsSupported() {
        for (EventListener listener : listeners) {
            return listener.getArtModeIsSupported();
        }
        return artModeSupported;
    }

    public boolean getPowerState() {
        for (EventListener listener : listeners) {
            return listener.getPowerState();
        }
        return power;
    }

    public String fetchPowerState() {
        for (EventListener listener : listeners) {
            return listener.fetchPowerState();
        }
        return "off";
    }

    public void setOffline() {
        for (EventListener listener : listeners) {
            listener.setOffline();
        }
    }

    public void putConfig(String key, String value) {
        for (EventListener listener : listeners) {
            listener.putConfig(key, value);
        }
    }

    public @Nullable Object getConfig(String key) {
        for (EventListener listener : listeners) {
            return listener.getConfig(key);
        }
        return null;
    }

    public @Nullable ScheduledExecutorService getScheduler() {
        for (EventListener listener : listeners) {
            return listener.getScheduler();
        }
        return null;
    }

    public @Nullable WebSocketFactory getWebSocketFactory() {
        for (EventListener listener : listeners) {
            return listener.getWebSocketFactory();
        }
        return null;
    }
}
