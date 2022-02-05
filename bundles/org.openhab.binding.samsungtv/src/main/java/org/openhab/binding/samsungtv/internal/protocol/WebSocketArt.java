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
package org.openhab.binding.samsungtv.internal.protocol;

import static org.openhab.binding.samsungtv.internal.SamsungTvBindingConstants.*;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.RawType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonSyntaxException;

/**
 * Websocket class to retrieve artmode status (on o.a. the Frame TV's)
 *
 * @author Arjan Mels - Initial contribution
 * @author Nick Waterton - added slideshow handling, upload/download, refactoring
 */
@NonNullByDefault
class WebSocketArt extends WebSocketBase {
    private final Logger logger = LoggerFactory.getLogger(WebSocketArt.class);

    private String host = "Unknown";
    private String slideShowDuration = "off";
    // Favourites is default
    private String categoryId = "MY-C0004";
    private String lastThumbnail = "";
    private boolean slideshow = false;
    public byte[] imageBytes = new byte[0];
    public String fileType = "jpg";
    private static final DateTimeFormatter DATEFORMAT = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private Map<String, String> stateMap = Collections.synchronizedMap(new HashMap<>());

    /**
     * @param remoteControllerWebSocket
     */
    WebSocketArt(RemoteControllerWebSocket remoteControllerWebSocket) {
        super(remoteControllerWebSocket);
        this.host = remoteControllerWebSocket.host;
    }

    @NonNullByDefault({})
    @SuppressWarnings("unused")
    private class JSONMessage {
        String event;

        class Data {
            String event;
            String status;
            String value;
            String current_content_id;
            String content_id;
            String category_id;
            String is_shown;
            String type;
            String file_type;
            String conn_info;

            public String getEvent() {
                return Optional.ofNullable(event).orElse("");
            }

            public String getStatus() {
                return Optional.ofNullable(status).orElse("");
            }

            public String getValue() {
                return Optional.ofNullable(value).orElse(getStatus());
            }

            public int getIntValue() {
                return Optional.of(Integer.valueOf(getValue())).orElse(0);
            }

            public String getCategoryId() {
                return Optional.ofNullable(category_id).orElse("");
            }

            public String getContentId() {
                return Optional.ofNullable(content_id).orElse(getCurrentContentId());
            }

            public String getCurrentContentId() {
                return Optional.ofNullable(current_content_id).orElse("");
            }

            public String getType() {
                return Optional.ofNullable(type).orElse("");
            }

            public String getIsShown() {
                return Optional.ofNullable(is_shown).orElse("No");
            }

            public String getFileType() {
                return Optional.ofNullable(file_type).orElse("");
            }

            public String getConnInfo() {
                return Optional.ofNullable(conn_info).orElse("");
            }
        }

        class Conninfo {
            String d2d_mode;
            long connection_id;
            String request_id;
            String id;
        }

        class Contentinfo {
            String contentInfo;
            String event;
            String ip;
            String port;
            String key;
            String stat;
            String mode;

            public String getContentInfo() {
                return Optional.ofNullable(contentInfo).orElse("");
            }

            public String getIp() {
                return Optional.ofNullable(ip).orElse("");
            }

            public int getPort() {
                return Optional.ofNullable(port).map(Integer::parseInt).orElse(0);
            }

            public String getKey() {
                return Optional.ofNullable(key).orElse("");
            }
        }

        class Header {
            String connection_id;
            String seckey;
            String version;
            String fileID;
            String fileName;
            String fileType;
            String num;
            String total;
            String fileLength;

            public int getFileLength() {
                return Optional.ofNullable(fileLength).map(Integer::parseInt).orElse(0);
            }

            public String getFileType() {
                return Optional.ofNullable(fileType).orElse("");
            }
        }

        // data is sometimes a json object, sometimes a string representation of a json object for d2d_service_message
        @Nullable
        JsonElement data;

        public String getEvent() {
            return Optional.ofNullable(event).orElse("");
        }

        public String getData() {
            return Optional.ofNullable(data).map(a -> a.getAsString()).orElse("");
        }
    }

    @Override
    public void onWebSocketText(@Nullable String msgarg) {
        if (msgarg == null) {
            return;
        }
        String msg = msgarg.replace('\n', ' ');
        super.onWebSocketText(msg);
        try {
            JSONMessage jsonMsg = remoteControllerWebSocket.gson.fromJson(msg, JSONMessage.class);
            if (jsonMsg == null) {
                return;
            }
            switch (jsonMsg.getEvent()) {
                case "ms.channel.connect":
                    logger.debug("{}: Art channel connected", host);
                    break;
                case "ms.channel.ready":
                    logger.debug("{}: Art channel ready", host);
                    stateMap.clear();
                    getArtmodeStatus();
                    getArtmodeStatus("get_auto_rotation_status");
                    getArtmodeStatus("get_current_artwork");
                    getArtmodeStatus("get_color_temperature");
                    break;
                case "ms.channel.clientConnect":
                    logger.debug("{}: Another Art client has connected", host);
                    break;
                case "ms.channel.clientDisconnect":
                    logger.debug("{}: Other Art client has disconnected", host);
                    break;

                case "d2d_service_message":
                    handleD2DServiceMessage(jsonMsg.getData());
                    break;
                default:
                    logger.debug("{}: WebSocketArt Unknown event: {}", host, msg);
            }
        } catch (JsonSyntaxException e) {
            logger.warn("{}: {}: Error ({}) in message: {}", host, this.getClass().getSimpleName(), e.getMessage(),
                    msg);
        }
    }

    /**
     * handle D2DServiceMessages
     *
     * @param msg D2DServiceMessage json as string
     *
     */
    private void handleD2DServiceMessage(String msg) {
        if (msg.isBlank()) {
            logger.debug("{}: Empty d2d_service_message event", host);
            return;
        }
        JSONMessage.Data data = remoteControllerWebSocket.gson.fromJson(msg, JSONMessage.Data.class);
        if (data == null) {
            return;
        }
        // remove returns and white space for ART_JSON channel
        valueReceived(ART_JSON, new StringType(msg.trim().replaceAll("\\n|\\\\n", "").replaceAll("\\s{2,}", " ")));
        switch (data.getEvent()) {
            case "preview_started":
            case "preview_stopped":
            case "favorite_changed":
            case "content_list":
                // do nothing
                break;
            case "brightness_changed":
            case "brightness":
                valueReceived(ART_BRIGHTNESS, new PercentType(data.getIntValue() * 10));
                break;
            case "color_temperature_changed":
            case "color_temperature":
                valueReceived(ART_COLOR_TEMPERATURE, new DecimalType(data.getIntValue()));
                break;
            case "art_mode_changed":
            case "artmode_status":
                logger.debug("{}: {}: {}", host, data.getEvent(), data.getValue());
                if ("off".equals(data.getValue())) {
                    remoteControllerWebSocket.callback.powerUpdated(true, false);
                    remoteControllerWebSocket.callback.currentAppUpdated("");
                } else {
                    remoteControllerWebSocket.callback.powerUpdated(false, true);
                }
                if (!remoteControllerWebSocket.noApps()) {
                    remoteControllerWebSocket.updateCurrentApp();
                }
                break;
            case "auto_rotation_changed":
            case "auto_rotation_image_changed":
            case "auto_rotation_status":
                // value (duration) is "off" or "number" where number is duration in minutes
                // data.type: "shuffleslideshow" or "slideshow"
                // data.current_content_id: Current art displayed eg "MY_F0005"
                // data.category_id: category eg 'MY-C0004' ie favouries or my Photos/shelf
                if (!data.getValue().isBlank()) {
                    slideShowDuration = data.getValue();
                    slideshow = !"off".equals(data.getValue());
                }
                categoryId = (data.getCategoryId().isBlank()) ? categoryId : data.getCategoryId();
                if (!data.getContentId().isBlank() && slideshow) {
                    remoteControllerWebSocket.callback.currentAppUpdated(
                            String.format("%s %s %s", data.getType(), slideShowDuration, categoryId));
                }
                logger.trace("{}: slideshow: {}, {}, {}, {}", host, data.getEvent(), data.getType(), data.getValue(),
                        data.getContentId());
                break;
            case "image_added":
                if (!data.getCategoryId().isBlank()) {
                    logger.info("{}: Image added: {}, category: {}", host, data.getContentId(), data.getCategoryId());
                }
                break;
            case "current_artwork":
            case "image_selected":
                // data.content_id: Current art displayed eg "MY_F0005"
                // data.is_shown: "Yes" or "No"
                if ("Yes".equals(data.getIsShown())) {
                    if (!slideshow) {
                        remoteControllerWebSocket.callback.currentAppUpdated("artMode");
                    }
                }
                valueReceived(ART_LABEL, new StringType(data.getContentId()));
                if (remoteControllerWebSocket.callback.handler.isChLinked(ART_IMAGE)) {
                    getThumbnail(data.getContentId());
                }
                break;
            case "thumbnail":
                logger.trace("{}: thumbnail: Fetching {}.{}", host, data.getContentId(), data.getFileType());
                stateMap.remove(ART_IMAGE);
            case "ready_to_use":
                // upload image (should be 3840x2160 pixels in size)
                if (!data.getConnInfo().isBlank()) {
                    JSONMessage.Contentinfo contentInfo = remoteControllerWebSocket.gson.fromJson(data.getConnInfo(),
                            JSONMessage.Contentinfo.class);
                    if (contentInfo != null) {
                        if ("thumbnail".equals(data.getEvent())) {
                            downloadThumbnail(contentInfo);
                        } else {
                            uploadImage(contentInfo);
                        }
                    }
                }
                break;
            case "go_to_standby":
                logger.debug("{}: go_to_standby", host);
                remoteControllerWebSocket.callback.powerUpdated(false, false);
                remoteControllerWebSocket.callback.setOffline();
                break;
            case "wakeup":
                logger.debug("{}: wakeup from standby", host);
                // check artmode status to know complete status before updating
                getArtmodeStatus();
                getArtmodeStatus("get_auto_rotation_status");
                getArtmodeStatus("get_current_artwork");
                getArtmodeStatus("get_color_temperature");
                break;
            default:
                logger.debug("{}: Unknown d2d_service_message event: {}", host, msg);
        }
    }

    public void valueReceived(String variable, State value) {
        if (!stateMap.getOrDefault(variable, "").equals(value.toString())) {
            remoteControllerWebSocket.callback.handler.valueReceived(variable, value);
            stateMap.put(variable, value.toString());
        } else {
            logger.trace("{}: Value '{}' for {} hasn't changed, ignoring update", host, value, variable);
        }
    }

    /**
     * creates formatted json string for art websocket commands
     *
     * @param request Array of string requests to format
     *
     */
    @NonNullByDefault({})
    class JSONArtModeStatus {
        public JSONArtModeStatus(String[] request) {
            Params.Data data = params.new Data();
            if (request.length == 1) {
                if (request[0].endsWith("}")) {
                    // full json request/command
                    request = request[0].split(",");
                } else {
                    // send simple command in request[0]
                    data.request = request[0];
                    params.data = remoteControllerWebSocket.gson.toJson(data);
                    return;
                }
            }
            switch (request[0]) {
                // predefined requests/commands
                case "set_auto_rotation_status":
                    data.request = request[0];
                    data.type = request[1];
                    data.value = request[2];
                    data.category_id = request[3];
                    params.data = remoteControllerWebSocket.gson.toJson(data);
                    break;
                case "set_brightness":
                case "set_color_temperature":
                    data.request = request[0];
                    data.value = request[1];
                    params.data = remoteControllerWebSocket.gson.toJson(data);
                    break;
                case "get_thumbnail":
                    data.request = request[0];
                    data.content_id = request[1];
                    data.conn_info = new Conninfo();
                    params.data = remoteControllerWebSocket.gson.toJson(data);
                    break;
                case "select_image":
                    data.request = request[0];
                    data.content_id = request[1];
                    data.show = true;
                    params.data = remoteControllerWebSocket.gson.toJson(data);
                    break;
                case "send_image":
                    RawType image = RawType.valueOf(request[1]);
                    fileType = image.getMimeType().split("/")[1];
                    imageBytes = image.getBytes();
                    data.request = request[0];
                    data.file_type = fileType;
                    data.conn_info = new Conninfo();
                    data.image_date = DATEFORMAT.format(Instant.now());
                    // data.matte_id = "flexible_polar";
                    data.file_size = imageBytes.length;
                    params.data = remoteControllerWebSocket.gson.toJson(data);
                    break;
                default:
                    // Just return formatted json (add id if needed)
                    if (Arrays.stream(request).anyMatch(a -> a.contains("\"id\""))) {
                        params.data = String.join(",", request).replace(",}", "}");
                    } else {
                        ArrayList<String> requestList = new ArrayList<>(Arrays.asList(request));
                        requestList.add(requestList.size() - 1,
                                String.format("\"id\":\"%s\"", remoteControllerWebSocket.uuid.toString()));
                        params.data = String.join(",", requestList).replace(",}", "}");
                    }
                    break;
            }
        }

        class Params {
            class Data {
                String request = "get_artmode_status";
                String value;
                String content_id;
                String category_id;
                String type;
                String file_type;
                String image_date;
                String matte_id;
                long file_size;
                @Nullable
                Boolean show = null;
                String id = remoteControllerWebSocket.uuid.toString();
                Conninfo conn_info;
            }

            String event = "art_app_request";
            String to = "host";
            String data;
        }

        class Conninfo {
            String d2d_mode = "socket";
            // this is a random number usually
            long connection_id = 2705890518L;
            String request_id;
            String id = remoteControllerWebSocket.uuid.toString();
        }

        String method = "ms.channel.emit";
        Params params = new Params();
    }

    public void getThumbnail(String content_id) {
        if (!content_id.equals(lastThumbnail)) {
            getArtmodeStatus("get_thumbnail", content_id);
            lastThumbnail = content_id;
        }
    }

    /**
     * Fetch thumbnail of current selected image/jpeg
     *
     * @param contentinfo Contentinfo containing ip address and port to download from
     *
     */
    public void downloadThumbnail(JSONMessage.Contentinfo contentInfo) {
        logger.trace("{}: thumbnail: downloading from: ip:{}, port:{}", host, contentInfo.getIp(),
                contentInfo.getPort());
        try {
            Socket socket = new Socket(contentInfo.getIp(), contentInfo.getPort());
            DataInputStream dataInputStream = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            int headerlen = dataInputStream.readInt();
            byte[] headerByte = new byte[headerlen];
            dataInputStream.readFully(headerByte, 0, headerlen);
            String header = new String(headerByte, StandardCharsets.UTF_8);
            logger.trace("{}: got header: {}", host, header);
            JSONMessage.Header headerData = remoteControllerWebSocket.gson.fromJson(header, JSONMessage.Header.class);
            if (headerData != null && headerData.getFileLength() != 0 && !headerData.getFileType().isBlank()) {
                byte[] image = new byte[headerData.getFileLength()];
                dataInputStream.readFully(image, 0, headerData.getFileLength());
                valueReceived(ART_IMAGE, new RawType(image, "image/" + headerData.getFileType()));
            }
            socket.close();
        } catch (IOException e) {
            logger.warn("{}: Error reading thumbnail {}", host, e.getMessage());
        }
    }

    @NonNullByDefault({})
    @SuppressWarnings("unused")
    private class JSONHeader {
        public JSONHeader(int num, int total, long fileLength, String fileType, String secKey) {
            this.num = num;
            this.total = total;
            this.fileLength = fileLength;
            this.fileType = fileType;
            this.secKey = secKey;
        }

        int num = 0;
        int total = 1;
        long fileLength;
        String fileName = "dummy";
        String fileType;
        String secKey;
        String version = "0.0.1";
    }

    /**
     * Upload Image from ART_IMAGE/ART_LABEL channel
     *
     * @param contentinfo Contentinfo containing ip address, port and key to upload to
     *
     *            imageBytes and fileType are class instance variables obtained from the
     *            getArtmodeStatus() command that triggered the upload.
     *
     */
    public void uploadImage(JSONMessage.Contentinfo contentInfo) {
        logger.trace("{}: Uploading image to ip:{}, port:{}", host, contentInfo.getIp(), contentInfo.getPort());
        try {
            Socket socket = new Socket(contentInfo.getIp(), contentInfo.getPort());
            DataOutputStream dataOutputStream = new DataOutputStream(
                    new BufferedOutputStream(socket.getOutputStream()));
            String header = remoteControllerWebSocket.gson
                    .toJson(new JSONHeader(0, 1, imageBytes.length, fileType, contentInfo.getKey()));
            logger.debug("{}: Image header: {}, {} bytes", host, header, header.length());
            dataOutputStream.writeInt(header.length());
            dataOutputStream.writeBytes(header);
            dataOutputStream.write(imageBytes, 0, imageBytes.length);
            dataOutputStream.flush();
            logger.debug("{}: wrote Image:{} {} bytes to TV", host, fileType, dataOutputStream.size());
            socket.close();
        } catch (IOException e) {
            logger.warn("{}: Error writing image to TV {}", host, e.getMessage());
        }
    }

    /**
     * Set slideshow
     *
     * @param command split on ,space or + where
     *
     *            First parameter is shuffleslideshow or slideshow
     *            Second is duration in minutes or off
     *            Third is category where the value is somethng like MY-C0004 = Favourites or MY-C0002 = My Photos.
     *
     */
    public void setSlideshow(String command) {
        String[] cmd = command.split("[, +]");
        if (cmd.length <= 1) {
            logger.warn("{}: Invalid slideshow command: {}", host, command);
            return;
        }
        String value = ("0".equals(cmd[1])) ? "off" : cmd[1];
        categoryId = (cmd.length >= 3) ? cmd[2] : categoryId;
        getArtmodeStatus("set_auto_rotation_status", cmd[0].toLowerCase(), value, categoryId);
    }

    /**
     * Send commands to Frame TV Art websocket channel
     *
     * @param optionalRequests Array of string requests
     *
     */
    void getArtmodeStatus(String... optionalRequests) {
        if (optionalRequests.length == 0) {
            optionalRequests = new String[] { "get_artmode_status" };
        }
        sendCommand(remoteControllerWebSocket.gson.toJson(new JSONArtModeStatus(optionalRequests)));
    }
}
