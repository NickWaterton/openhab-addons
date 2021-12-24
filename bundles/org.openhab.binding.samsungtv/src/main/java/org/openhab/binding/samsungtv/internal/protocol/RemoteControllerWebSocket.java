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
package org.openhab.binding.samsungtv.internal.protocol;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.component.LifeCycle.Listener;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.openhab.binding.samsungtv.internal.SamsungTvAppWatchService;
import org.openhab.binding.samsungtv.internal.config.SamsungTvConfiguration;
import org.openhab.binding.samsungtv.internal.service.RemoteControllerService;
import org.openhab.core.io.net.http.WebSocketFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

/**
 * The {@link RemoteControllerWebSocket} is responsible for sending key codes to the
 * Samsung TV via the websocket protocol (for newer TV's).
 *
 * @author Arjan Mels - Initial contribution
 * @author Arjan Mels - Moved websocket inner classes to standalone classes
 * @author Nick Waterton - added Action enum and some refactoring
 */
@NonNullByDefault
public class RemoteControllerWebSocket extends RemoteController implements Listener {

    private final Logger logger = LoggerFactory.getLogger(RemoteControllerWebSocket.class);

    private static final String WS_ENDPOINT_REMOTE_CONTROL = "/api/v2/channels/samsung.remote.control";
    private static final String WS_ENDPOINT_ART = "/api/v2/channels/com.samsung.art-app";
    private static final String WS_ENDPOINT_V2 = "/api/v2";

    // WebSocket helper classes
    private final WebSocketRemote webSocketRemote;
    private final WebSocketArt webSocketArt;
    private final WebSocketV2 webSocketV2;

    // refresh limit for current app update
    private static final long UPDATE_CURRENT_APP_REFRESH = 10000;
    public long previousUpdateCurrentApp = 0;

    // JSON parser class. Also used by WebSocket handlers.
    public final Gson gson = new Gson();

    // Callback class. Also used by WebSocket handlers.
    final RemoteControllerService callback;

    // Websocket client class shared by WebSocket handlers.
    final WebSocketClient client;

    // App File servicce
    private final SamsungTvAppWatchService samsungTvAppWatchService;

    // list instaled apps after 2 updates
    public int updateCount = 0;

    // UUID used for data exchange via websockets
    final UUID uuid = UUID.randomUUID();

    // Description of Apps
    public class App {
        public String appId;
        public String name;
        public int type;

        App(String appId, String name, int type) {
            this.appId = appId;
            this.name = name;
            this.type = type;
        }

        @Override
        public String toString() {
            return this.name;
        }

        public String getAppId() {
            return Optional.ofNullable(appId).orElse("");
        }

        public String getName() {
            return Optional.ofNullable(name).orElse("");
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getType() {
            return Optional.ofNullable(type).orElse(2);
        }
    }

    // Map of all available apps
    public Map<String, App> apps = new ConcurrentHashMap<>();
    // manually added apps (from File)
    public Map<String, App> manApps = new ConcurrentHashMap<>();

    /**
     * The {@link Action} presents available actions for keys with Samsung TV.
     *
     */
    public static enum Action {

        CLICK("Click"),
        PRESS("Press"),
        RELEASE("Release"),
        MOVE("Move"),
        END("End"),
        TEXT("Text"),
        MOUSECLICK("MouseClick");

        private final String value;

        Action() {
            value = "Click";
        }

        Action(String newvalue) {
            this.value = newvalue;
        }

        @Override
        public String toString() {
            return value;
        }
    }

    /**
     * Create and initialize remote controller instance.
     *
     * @param host Host name of the Samsung TV.
     * @param port TCP port of the remote controller protocol.
     * @param appName Application name used to send key codes.
     * @param uniqueId Unique Id used to send key codes.
     * @param RemoteControllerService callback
     * @throws RemoteControllerException
     */
    public RemoteControllerWebSocket(String host, int port, String appName, String uniqueId,
            RemoteControllerService callback) throws RemoteControllerException {
        super(host, port, appName, uniqueId);
        this.callback = callback;

        WebSocketFactory webSocketFactory = callback.getWebSocketFactory();
        if (webSocketFactory == null) {
            throw new RemoteControllerException("No WebSocketFactory available");
        }

        this.samsungTvAppWatchService = new SamsungTvAppWatchService(host, this);

        client = webSocketFactory.createWebSocketClient("samsungtv");
        client.addLifeCycleListener(this);

        webSocketRemote = new WebSocketRemote(this);
        webSocketArt = new WebSocketArt(this);
        webSocketV2 = new WebSocketV2(this);
    }

    public boolean isConnected() {
        return webSocketRemote.isConnected();
    }

    public void openConnection() throws RemoteControllerException {
        logger.trace("{}: openConnection()", host);

        if (!(client.isStarted() || client.isStarting())) {
            logger.debug("{}: RemoteControllerWebSocket start Client", host);
            try {
                client.start();
                client.setMaxBinaryMessageBufferSize(1000000);
                // websocket connect will be done in lifetime handler
                return;
            } catch (Exception e) {
                logger.warn("{}: Cannot connect to websocket remote control interface: {}", host, e.getMessage());
                throw new RemoteControllerException(e);
            }
        }
        connectWebSockets();
    }

    private void logResult(String msg, Throwable cause) {
        if (logger.isTraceEnabled()) {
            logger.trace("{}: {}: ", host, msg, cause);
        } else {
            logger.warn("{}: {}: {}", host, msg, cause.getMessage());
        }
    }

    public static String b64encode(String str) {
        return Base64.getUrlEncoder().encodeToString(str.getBytes());
    }

    private void connectWebSockets() {
        logger.trace("{}: connectWebSockets()", host);

        String encodedAppName = b64encode(appName);

        String protocol = (SamsungTvConfiguration.PROTOCOL_SECUREWEBSOCKET
                .equals(callback.getConfig(SamsungTvConfiguration.PROTOCOL))) ? "wss" : "ws";

        try {
            String token = (String) callback.getConfig(SamsungTvConfiguration.WEBSOCKET_TOKEN);
            if ("wss".equals(protocol) && StringUtil.isBlank(token)) {
                logger.warn(
                        "{}: webSocketRemote connecting without Token, please accept the connection on the TV within 30 seconds",
                        host);
            }
            webSocketRemote.connect(new URI(protocol, null, host, port, WS_ENDPOINT_REMOTE_CONTROL,
                    "name=" + encodedAppName + (StringUtil.isNotBlank(token) ? "&token=" + token : ""), null));
        } catch (RemoteControllerException | URISyntaxException e) {
            logResult("Problem connecting to remote websocket", e);
        }

        try {
            webSocketArt.connect(new URI(protocol, null, host, port, WS_ENDPOINT_ART, "name=" + encodedAppName, null));
        } catch (RemoteControllerException | URISyntaxException e) {
            logResult("Problem connecting to artmode websocket", e);
        }

        try {
            webSocketV2.connect(new URI(protocol, null, host, port, WS_ENDPOINT_V2, "name=" + encodedAppName, null));
        } catch (RemoteControllerException | URISyntaxException e) {
            logResult("Problem connecting to V2 websocket", e);
        }
    }

    private void closeConnection() throws RemoteControllerException {
        logger.debug("{}: RemoteControllerWebSocket closeConnection", host);

        try {
            webSocketRemote.close();
            webSocketArt.close();
            webSocketV2.close();
            client.stop();
        } catch (Exception e) {
            throw new RemoteControllerException(e);
        }
    }

    @Override
    public void close() throws RemoteControllerException {
        logger.debug("{}: RemoteControllerWebSocket close", host);
        closeConnection();
    }

    public boolean noApps() {
        return apps.isEmpty();
    }

    public void listApps() {
        Stream<Map.Entry<String, App>> st = (noApps()) ? manApps.entrySet().stream() : apps.entrySet().stream();
        logger.debug("{}: Installed Apps: {}", host,
                st.map(entry -> entry.getValue().appId + " = " + entry.getKey()).collect(Collectors.joining(", ")));
    }

    /**
     * Retrieve app status for all apps. In the WebSocketv2 handler the currently running app will be determined
     */
    public synchronized void updateCurrentApp() {
        // limit noApp refresh rate
        if (noApps() && System.currentTimeMillis() < previousUpdateCurrentApp + UPDATE_CURRENT_APP_REFRESH) {
            return;
        }
        previousUpdateCurrentApp = System.currentTimeMillis();
        if (webSocketV2.isNotConnected()) {
            logger.warn("{}: Cannot retrieve current app webSocketV2 is not connected", host);
            return;
        }
        // if noapps by this point, start file app service
        if (updateCount == 1 && noApps() && !samsungTvAppWatchService.getStarted()) {
            samsungTvAppWatchService.start();
            updateCount = 0;
        }
        // list apps
        if (updateCount++ == 2) {
            listApps();
        }
        for (App app : (noApps()) ? manApps.values() : apps.values()) {
            webSocketV2.getAppStatus(app.getAppId());
        }
    }

    /**
     * Update manual App list from file (called from SamsungTvAppWatchService)
     */
    public void updateAppList(List<String> fileApps) {
        previousUpdateCurrentApp = System.currentTimeMillis();
        manApps.clear();
        fileApps.forEach(line -> {
            try {
                App app = gson.fromJson(line, App.class);
                if (app != null) {
                    manApps.put(app.getName(), new App(app.getAppId(), app.getName(), app.getType()));
                    logger.debug("{}: Added app: {}/{}", host, app.getName(), app.getAppId());
                }
            } catch (JsonSyntaxException e) {
                logger.warn("{}: cannot add app, wrong format {}: {}", host, line, e.getMessage());
            }
        });
        updateCount = 0;
        // updateCurrentApp();
    }

    /**
     * Send key code to Samsung TV.
     *
     * @param key Key code to send.
     */
    public void sendKey(Object key) {
        if (key instanceof KeyCode) {
            sendKey((KeyCode) key, Action.CLICK);
        } else if (key instanceof String) {
            sendKey((String) key);
        }
    }

    public void sendKey(String value) {
        try {
            if (value.startsWith("{")) {
                logger.debug("{}: Try to send Mouse move: {}", host, value);
                sendKeyData(value, Action.MOVE);
            } else if ("LeftClick".equals(value) || "RightClick".equals(value)) {
                logger.debug("{}: Try to send Mouse Click: {}", host, value);
                sendKeyData(value, Action.MOUSECLICK);
            } else if (value.isEmpty()) {
                logger.debug("{}: Try to send InputEnd", host);
                sendKeyData("", Action.END);
            } else {
                logger.debug("{}: Try to send Text: {}", host, value);
                sendKeyData(value, Action.TEXT);
            }
        } catch (RemoteControllerException e) {
            logger.debug("{}: Couldn't send Text/Mouse move {}", host, e.getMessage());
        }
    }

    public void sendKey(KeyCode key, Action action) {
        logger.debug("{}: Try to send command: {}, {}", host, key, action);
        try {
            sendKeyData(key, action);
        } catch (RemoteControllerException e) {
            logger.debug("{}: Couldn't send command {}", host, e.getMessage());
        }
    }

    public void sendKeyPress(KeyCode key, int duration) {
        sendKey(key, Action.PRESS);
        // send key release in duration milliseconds
        @Nullable
        ScheduledExecutorService scheduler = callback.getScheduler();
        if (scheduler != null) {
            scheduler.schedule(() -> {
                if (isConnected()) {
                    sendKey(key, Action.RELEASE);
                }
            }, duration, TimeUnit.MILLISECONDS);
        }
    }

    private void sendKeyData(Object key, Action action) throws RemoteControllerException {
        webSocketRemote.sendKeyData(action.toString(), key.toString());
    }

    public void sendSourceApp(String appName) {
        if (appName.toLowerCase().contains("slideshow")) {
            webSocketArt.setSlideshow(appName);
        } else {
            sendSourceApp(appName, null);
        }
    }

    public void sendSourceApp(String appName, @Nullable String url) {
        Stream<Map.Entry<String, App>> st = (noApps()) ? manApps.entrySet().stream() : apps.entrySet().stream();
        boolean found = st.filter(a -> a.getKey().equals(appName) || a.getValue().name.equals(appName))
                .map(a -> sendSourceApp(a.getValue().appId, a.getValue().type == 2, url)).findFirst().orElse(false);
        if (!found) {
            // treat appName as appId with optional type number eg "3201907018807, 2"
            String[] appArray = appName.trim().split(",");
            sendSourceApp(appArray[0], (appArray.length > 1) ? "2".equals(appArray[1]) : true, null);
        }
    }

    public boolean sendSourceApp(String appId, boolean type, @Nullable String url) {
        if (noApps()) {
            // 2020 TV's and later use webSocketV2 for app launch
            webSocketV2.sendSourceApp(appId, type, url);
        } else {
            webSocketRemote.sendSourceApp(appId, type, url);
        }
        return true;
    }

    public void sendUrl(String url) {
        String processedUrl = url.replace("/", "\\/");
        if (noApps()) {
            // 2020 TV's and later don't return apps list
            String browserId = manApps.values().stream().filter(a -> "Internet".equals(a.name)).map(a -> a.name)
                    .findFirst().orElse("org.tizen.browser");
            sendSourceApp(browserId, processedUrl);
        } else {
            webSocketRemote.sendSourceApp("org.tizen.browser", false, processedUrl);
        }
    }

    public boolean closeApp() {
        return webSocketV2.closeApp();
    }

    /**
     * Get app status after 3 second delay (apps take 3s to launch)
     */
    public void getAppStatus(String id) {
        if (!id.isBlank()) {
            @Nullable
            ScheduledExecutorService scheduler = callback.getScheduler();
            if (scheduler != null) {
                scheduler.schedule(() -> {
                    if (!webSocketV2.isNotConnected()) {
                        webSocketV2.getAppStatus(id);
                    }
                }, 3000, TimeUnit.MILLISECONDS);
            }
        }
    }

    public void getArtmodeStatus(String... optionalRequests) {
        if (callback.getArtModeSupported()) {
            webSocketArt.getArtmodeStatus(optionalRequests);
        }
    }

    @Override
    public void lifeCycleStarted(@Nullable LifeCycle arg0) {
        logger.trace("{}: WebSocketClient started", host);
        connectWebSockets();
    }

    @Override
    public void lifeCycleFailure(@Nullable LifeCycle arg0, @Nullable Throwable throwable) {
        logger.warn("{}: WebSocketClient failure: {}", host, throwable != null ? throwable.toString() : null);
    }

    @Override
    public void lifeCycleStarting(@Nullable LifeCycle arg0) {
        logger.trace("{}: WebSocketClient starting", host);
    }

    @Override
    public void lifeCycleStopped(@Nullable LifeCycle arg0) {
        logger.trace("{}: WebSocketClient stopped", host);
    }

    @Override
    public void lifeCycleStopping(@Nullable LifeCycle arg0) {
        logger.trace("{}: WebSocketClient stopping", host);
    }
}
