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
package org.openhab.binding.samsungtv.internal.handler;

import static org.openhab.binding.samsungtv.internal.SamsungTvBindingConstants.*;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.jupnp.UpnpService;
import org.jupnp.model.meta.Device;
import org.jupnp.model.meta.LocalDevice;
import org.jupnp.model.meta.RemoteDevice;
import org.jupnp.registry.Registry;
import org.jupnp.registry.RegistryListener;
import org.openhab.binding.samsungtv.internal.WakeOnLanUtility;
import org.openhab.binding.samsungtv.internal.config.SamsungTvConfiguration;
import org.openhab.binding.samsungtv.internal.protocol.RemoteControllerException;
import org.openhab.binding.samsungtv.internal.protocol.RemoteControllerLegacy;
import org.openhab.binding.samsungtv.internal.service.MainTVServerService;
import org.openhab.binding.samsungtv.internal.service.MediaRendererService;
import org.openhab.binding.samsungtv.internal.service.RemoteControllerService;
import org.openhab.binding.samsungtv.internal.service.api.EventListener;
import org.openhab.binding.samsungtv.internal.service.api.SamsungTvService;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.io.net.http.HttpUtil;
import org.openhab.core.io.net.http.WebSocketFactory;
import org.openhab.core.io.transport.upnp.UpnpIOService;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

/**
 * The {@link SamsungTvHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Pauli Anttila - Initial contribution
 * @author Martin van Wingerden - Some changes for non-UPnP configured devices
 * @author Arjan Mels - Remove RegistryListener, manually create RemoteService in all circumstances, add sending of WOL
 *         package to power on TV
 * @author Nick Waterton - Improve Frame TV handling and some refactoring
 */
@NonNullByDefault
public class SamsungTvHandler extends BaseThingHandler implements RegistryListener, EventListener {

    private static final int WOL_PACKET_RETRY_COUNT = 10;
    private static final int WOL_SERVICE_CHECK_COUNT = 30;

    private final Logger logger = LoggerFactory.getLogger(SamsungTvHandler.class);

    private final UpnpIOService upnpIOService;
    private final UpnpService upnpService;
    private final WebSocketFactory webSocketFactory;

    private SamsungTvConfiguration configuration;

    private @Nullable String upnpUDN = null;

    /* Samsung TV services */
    private final Set<SamsungTvService> services = new CopyOnWriteArraySet<>();

    /* Store powerState to be able to restore upon new link */
    private boolean powerState = false;

    /* Store if art mode is supported to be able to skip switching power state to ON during initialization */
    public boolean artModeIsSupported = false;

    private @Nullable ScheduledFuture<?> pollingJob;

    /** Path for the information endpoint (note the final slash!) */
    private static final String WS_ENDPOINT_V2 = "/api/v2/";

    /** Description of the json returned for the information endpoint */
    @NonNullByDefault({})
    public class TVProperties {
        class Device {
            boolean FrameTVSupport;
            boolean GamePadSupport;
            boolean ImeSyncedSupport;
            String OS;
            String PowerState;
            boolean TokenAuthSupport;
            boolean VoiceSupport;
            String countryCode;
            String description;
            String firmwareVersion;
            String modelName;
            String name;
            String networkType;
            String resolution;
        }

        Device device;
        String isSupport;
    }

    public SamsungTvHandler(Thing thing, UpnpIOService upnpIOService, UpnpService upnpService,
            WebSocketFactory webSocketFactory) {
        super(thing);

        logger.debug("Create a Samsung TV Handler for thing '{}'", getThing().getUID());

        this.upnpIOService = upnpIOService;
        this.upnpService = upnpService;
        this.webSocketFactory = webSocketFactory;
        this.configuration = getConfigAs(SamsungTvConfiguration.class);
    }

    /**
     * For Modern TVs get configuration
     *
     * @return TVProperties
     */
    @Nullable
    public synchronized TVProperties fetchTVProperties() {
        @Nullable
        TVProperties properties = null;
        try {
            URI uri = new URI("http", null, configuration.hostName, SamsungTvConfiguration.PORT_DEFAULT_WEBSOCKET,
                    WS_ENDPOINT_V2, null, null);
            @Nullable
            String response = HttpUtil.executeUrl("GET", uri.toURL().toString(), 2000);
            properties = new Gson().fromJson(response, TVProperties.class);
            if (properties == null) {
                throw new IOException("No Data");
            }
        } catch (URISyntaxException | IOException e) {
            logger.debug("Cannot connect to TV: {}", e.getMessage());
        }
        return properties;
    }

    /**
     * Update WOL MAC address
     * Discover the type of remote control service the TV supports.
     * update artModeIsSupported and PowerState
     * Update the configuration with results
     *
     */
    public void discoverConfiguration() {
        /* Check if configuration should be updated */
        if (configuration.macAddress == null || configuration.macAddress.trim().isEmpty()) {
            String macAddress = WakeOnLanUtility.getMACAddress(configuration.hostName);
            if (macAddress != null) {
                putConfig(SamsungTvConfiguration.MAC_ADDRESS, macAddress);
                logger.debug("updated macAddress: {}", macAddress);
            }
        }
        if (SamsungTvConfiguration.PROTOCOL_NONE.equals(configuration.protocol)) {
            try {
                RemoteControllerLegacy remoteController = new RemoteControllerLegacy(configuration.hostName,
                        SamsungTvConfiguration.PORT_DEFAULT_LEGACY, "openHAB", "openHAB");
                remoteController.openConnection();
                remoteController.close();
                putConfig(SamsungTvConfiguration.PROTOCOL, SamsungTvConfiguration.PROTOCOL_LEGACY);
                putConfig(SamsungTvConfiguration.PORT, SamsungTvConfiguration.PORT_DEFAULT_LEGACY);
                return;
            } catch (RemoteControllerException e) {
                // ignore error
            }
        }

        @Nullable
        TVProperties properties = fetchTVProperties();
        if (properties != null) {
            if (SamsungTvConfiguration.PROTOCOL_NONE.equals(configuration.protocol)) {
                if (properties.device.TokenAuthSupport) {
                    putConfig(SamsungTvConfiguration.PROTOCOL, SamsungTvConfiguration.PROTOCOL_SECUREWEBSOCKET);
                    putConfig(SamsungTvConfiguration.PORT, SamsungTvConfiguration.PORT_DEFAULT_SECUREWEBSOCKET);
                } else {
                    putConfig(SamsungTvConfiguration.PROTOCOL, SamsungTvConfiguration.PROTOCOL_WEBSOCKET);
                    putConfig(SamsungTvConfiguration.PORT, SamsungTvConfiguration.PORT_DEFAULT_WEBSOCKET);
                }
            }
            artModeIsSupported = properties.device.FrameTVSupport;
            setPowerState("on".equals(properties.device.PowerState));
            logger.debug("Updated artModeIsSupported: {} and PowerState: {}", artModeIsSupported, getPowerState());
        }
    }

    /**
     * For TV with artMode, get PowerState from TVProperties
     *
     * @return String giving power state (Frame TV can be on or standby, off if unreachable)
     */
    public String fetchPowerState() {
        @Nullable
        TVProperties properties = fetchTVProperties();
        String PowerState = (properties == null) ? "off" : properties.device.PowerState;
        setPowerState("on".equals(PowerState));
        logger.debug("PowerState is: {}", PowerState);
        return PowerState;
    }

    public boolean wakeup(String channel, Command command) {
        // Try to wake up TV if command is power on using WOL
        if ((channel.equals(POWER) || channel.equals(ART_MODE)) && OnOffType.ON.equals(command)) {
            sendWOLandResendCommand(channel, command);
            return true;
        }
        return false;
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        logger.debug("Received channel: {}, command: {}", channelUID, command);

        String channel = channelUID.getId();

        // Delegate command to correct service
        for (SamsungTvService service : services) {
            for (String s : service.getSupportedChannelNames()) {
                if (channel.equals(s)) {
                    if (!service.checkConnection()) {
                        wakeup(channel, command);
                        return;
                    }
                    service.handleCommand(channel, command);
                    return;
                }
            }
        }
        // if power on command try WOL for good measure:
        if (!wakeup(channel, command)) {
            logger.warn("Channel '{}' not connected", channelUID);
        }
    }

    @Override
    public void channelLinked(ChannelUID channelUID) {
        logger.trace("channelLinked: {}", channelUID);
        if (!artModeIsSupported) {
            updateState(POWER, getPowerState() ? OnOffType.ON : OnOffType.OFF);
        }

        for (SamsungTvService service : services) {
            service.clearCache();
        }
    }

    private synchronized void setPowerState(boolean state) {
        powerState = state;
    }

    public synchronized boolean getPowerState() {
        return powerState;
    }

    public boolean getArtModeIsSupported() {
        return artModeIsSupported;
    }

    @Override
    public void initialize() {
        updateStatus(ThingStatus.UNKNOWN);

        logger.debug("Initializing Samsung TV handler for uid '{}'", getThing().getUID());

        configuration = getConfigAs(SamsungTvConfiguration.class);

        upnpService.getRegistry().addListener(this);

        // note this can take up to 2 seconds to return if TV is off
        discoverConfiguration();

        checkAndCreateServices();

        logger.debug("Start refresh task, interval={}", configuration.refreshInterval);
        pollingJob = scheduler.scheduleWithFixedDelay(this::poll, 0, configuration.refreshInterval,
                TimeUnit.MILLISECONDS);
    }

    @Override
    @SuppressWarnings("null")
    public void dispose() {
        logger.debug("Disposing SamsungTvHandler");

        if (pollingJob != null) {
            if (!pollingJob.isCancelled()) {
                pollingJob.cancel(true);
            }
            pollingJob = null;
        }

        upnpService.getRegistry().removeListener(this);
        shutdown();
        putOffline();
    }

    private void shutdown() {
        logger.debug("Shutdown all Samsung services");
        for (SamsungTvService service : services) {
            stopService(service);
        }
        services.clear();
    }

    private synchronized void putOnline() {
        updateStatus(ThingStatus.ONLINE);

        if (!artModeIsSupported) {
            setPowerState(true);
            updateState(POWER, OnOffType.ON);
        }
    }

    private synchronized void putOffline() {
        setPowerState(false);
        updateStatus(ThingStatus.OFFLINE);
        updateState(ART_MODE, OnOffType.OFF);
        updateState(POWER, OnOffType.OFF);
        updateState(SOURCE_APP, new StringType(""));
    }

    private void poll() {
        for (SamsungTvService service : services) {
            // Skip channels if service is not connected/started
            if (!service.checkConnection()) {
                continue;
            }
            for (String channel : service.getSupportedChannelNames()) {
                if (isLinked(channel)) {
                    // Avoid redundant REFRESH commands when 2 channels are linked to the same UPnP action request
                    if ((channel.equals(SOURCE_ID) && isLinked(SOURCE_NAME))
                            || (channel.equals(CHANNEL_NAME) && isLinked(PROGRAM_TITLE))) {
                        continue;
                    }
                    service.handleCommand(channel, RefreshType.REFRESH);
                }
            }
        }
    }

    @Override
    public synchronized void valueReceived(String variable, State value) {
        logger.debug("Received value '{}':'{}' for thing '{}'", variable, value, this.getThing().getUID());

        if (POWER.equals(variable)) {
            setPowerState(OnOffType.ON.equals(value));
        } else if (ART_MODE.equals(variable)) {
            artModeIsSupported = true;
        }
        updateState(variable, value);
    }

    @Override
    public void reportError(ThingStatusDetail statusDetail, @Nullable String message, @Nullable Throwable e) {
        if (logger.isTraceEnabled()) {
            logger.trace("Error was reported: {}", message, e);
        } else {
            logger.debug("Error was reported: {}, {}", message, e.getMessage());
        }
        updateStatus(ThingStatus.OFFLINE, statusDetail, message);
    }

    /**
     * One Samsung TV contains several UPnP devices. Samsung TV is discovered by
     * Media Renderer UPnP device. This function tries to find another UPnP
     * devices related to same Samsung TV and create handler for those.
     */
    private void checkAndCreateServices() {
        logger.debug("Check and create missing UPnP services");

        boolean isOnline = false;

        for (Device<?, ?, ?> device : upnpService.getRegistry().getDevices()) {
            if (createService((RemoteDevice) device) == true) {
                isOnline = true;
            }
        }

        if (isOnline == true) {
            logger.debug("Device was online");
            putOnline();
        } else {
            logger.debug("Device was NOT online");
            putOffline();
        }

        checkCreateManualConnection();
    }

    private synchronized boolean createService(RemoteDevice device) {
        if (configuration.hostName != null
                && configuration.hostName.equals(device.getIdentity().getDescriptorURL().getHost())) {
            String modelName = device.getDetails().getModelDetails().getModelName();
            String udn = device.getIdentity().getUdn().getIdentifierString();
            String type = device.getType().getType();

            SamsungTvService existingService = findServiceInstance(type);

            if (existingService == null || !existingService.isUpnp()) {
                SamsungTvService newService = createService(type, upnpIOService, udn, configuration.hostName,
                        configuration.port);

                if (newService != null) {
                    if (existingService != null) {
                        stopService(existingService);
                        startService(newService);
                        logger.debug("Restarting service in UPnP mode for: {}, {} ({})", modelName, type, udn);
                    } else {
                        startService(newService);
                        logger.debug("Started service for: {}, {} ({})", modelName, type, udn);
                    }
                } else {
                    logger.trace("Skipping unknown UPnP service: {}, {} ({})", modelName, type, udn);
                }
            } else {
                logger.debug("Service rediscovered, clearing caches: {}, {} ({})", modelName, type, udn);
                existingService.clearCache();
            }
            return true;
        }
        return false;
    }

    /**
     * Create Samsung TV service.
     *
     * @param type
     * @param upnpIOService
     * @param udn
     * @param host
     * @param port
     * @return
     */
    private synchronized @Nullable SamsungTvService createService(String type, UpnpIOService upnpIOService, String udn,
            String host, int port) {
        SamsungTvService service = null;

        switch (type) {
            case MainTVServerService.SERVICE_NAME:
                service = new MainTVServerService(upnpIOService, udn, this);
                break;
            case MediaRendererService.SERVICE_NAME:
                service = new MediaRendererService(upnpIOService, udn, this);
                break;
            // will not be created automatically
            case RemoteControllerService.SERVICE_NAME:
                service = new RemoteControllerService(host, port, true, this);
                break;
        }
        return service;
    }

    private synchronized @Nullable SamsungTvService findServiceInstance(String serviceName) {
        for (SamsungTvService service : services) {
            if (serviceName.equals(service.getServiceName())) {
                return service;
            }
        }
        return null;
    }

    private synchronized void checkCreateManualConnection() {
        try {
            // create remote service manually if it does not yet exist

            RemoteControllerService service = (RemoteControllerService) findServiceInstance(
                    RemoteControllerService.SERVICE_NAME);
            if (service == null) {
                service = new RemoteControllerService(configuration.hostName, configuration.port, false, this);
                startService(service);
            } else {
                // open connection again if needed
                if (!service.checkConnection()) {
                    service.start();
                }
            }
        } catch (RuntimeException e) {
            logger.warn("Catching all exceptions because otherwise the thread would silently fail", e);
        }
    }

    private synchronized void startService(SamsungTvService service) {
        service.addEventListener(this);
        service.start();
        services.add(service);
    }

    private synchronized void stopService(SamsungTvService service) {
        service.stop();
        service.removeEventListener(this);
        services.remove(service);
    }

    @Override
    public void remoteDeviceAdded(@Nullable Registry registry, @Nullable RemoteDevice device) {
        if (configuration.hostName != null && device != null && device.getIdentity() != null
                && device.getIdentity().getDescriptorURL() != null
                && configuration.hostName.equals(device.getIdentity().getDescriptorURL().getHost())
                && device.getType() != null) {
            logger.debug("remoteDeviceAdded: {}, {}", device.getType().getType(),
                    device.getIdentity().getDescriptorURL());

            upnpUDN = device.getIdentity().getUdn().getIdentifierString().replace("-", "_");
            logger.debug("remoteDeviceAdded, upnpUDN={}", upnpUDN);
            checkAndCreateServices();
        }
    }

    @Override
    public void remoteDeviceRemoved(@Nullable Registry registry, @Nullable RemoteDevice device) {
        if (device == null) {
            return;
        }
        String udn = device.getIdentity().getUdn().getIdentifierString().replace("-", "_");
        if (udn.equals(upnpUDN)) {
            logger.debug("Device removed: udn={}", upnpUDN);
            shutdown();
            putOffline();
            checkCreateManualConnection();
        }
    }

    @Override
    public void remoteDeviceUpdated(@Nullable Registry registry, @Nullable RemoteDevice device) {
    }

    @Override
    public void remoteDeviceDiscoveryStarted(@Nullable Registry registry, @Nullable RemoteDevice device) {
    }

    @Override
    public void remoteDeviceDiscoveryFailed(@Nullable Registry registry, @Nullable RemoteDevice device,
            @Nullable Exception ex) {
    }

    @Override
    public void localDeviceAdded(@Nullable Registry registry, @Nullable LocalDevice device) {
    }

    @Override
    public void localDeviceRemoved(@Nullable Registry registry, @Nullable LocalDevice device) {
    }

    @Override
    public void beforeShutdown(@Nullable Registry registry) {
    }

    @Override
    public void afterShutdown() {
    }

    public void sendWOL() {
        logger.info("Send WOL packet to {} ({})", configuration.hostName, configuration.macAddress);

        // send max 10 WOL packets with 100ms intervals
        scheduler.schedule(new Runnable() {
            int count = 0;

            @Override
            public void run() {
                count++;
                if (count < WOL_PACKET_RETRY_COUNT) {
                    WakeOnLanUtility.sendWOLPacket(configuration.macAddress);
                    scheduler.schedule(this, 100, TimeUnit.MILLISECONDS);
                }
            }
        }, 1, TimeUnit.MILLISECONDS);
    }

    /**
     * Send multiple WOL packets spaced with 100ms intervals and resend command
     *
     * @param channel Channel to resend command on
     * @param command Command to resend
     */
    private void sendWOLandResendCommand(String channel, Command command) {
        if (configuration.macAddress == null || configuration.macAddress.isEmpty()) {
            logger.warn("Cannot send WOL packet to {} MAC address unknown", configuration.hostName);
            return;
        } else {
            // takes 1 second to send
            sendWOL();

            // after RemoteService up again to ensure state is properly set
            scheduler.schedule(new Runnable() {
                int count = 0;
                boolean send = false;

                @Override
                public void run() {
                    count++;
                    if (count <= WOL_SERVICE_CHECK_COUNT) {
                        RemoteControllerService service = (RemoteControllerService) findServiceInstance(
                                RemoteControllerService.SERVICE_NAME);
                        if (service != null) {
                            // do not resend command if artMode as TV wakes up in artMode
                            if (send && !"artMode".equals(channel)) {
                                logger.info("Service found after {} attempts: resend command {} to channel {}", count,
                                        command, channel);
                                service.handleCommand(channel, command);
                            }
                            if (!send) {
                                send = true;
                                // NOTE: resend command delay by 2 seconds to allow time for connection
                                scheduler.schedule(this, 2000, TimeUnit.MILLISECONDS);
                            }
                        } else {
                            if (count % 10 == 0) {
                                // resend WOL every 10 seconds
                                sendWOL();
                            }
                            scheduler.schedule(this, 1000, TimeUnit.MILLISECONDS);
                        }
                    } else {
                        logger.info("Service NOT found after {} attempts", count);
                    }
                }
            }, 1000, TimeUnit.MILLISECONDS);
        }
    }

    public void setOffline() {
        // schedule this in the future to allow remoteControllerService to return immediately
        scheduler.schedule(() -> {
            shutdown();
            putOffline();
        }, 100, TimeUnit.MILLISECONDS);
    }

    public void putConfig(@Nullable String key, @Nullable Object value) {
        getConfig().put(key, value);
        Configuration config = editConfiguration();
        config.put(key, value);
        updateConfiguration(config);
        logger.debug("Updated Configuration {}:{}", key, value);
        configuration = getConfigAs(SamsungTvConfiguration.class);
    }

    public Object getConfig(@Nullable String key) {
        return getConfig().get(key);
    }

    public ScheduledExecutorService getScheduler() {
        return scheduler;
    }

    public WebSocketFactory getWebSocketFactory() {
        return webSocketFactory;
    }
}
