/**
 * Copyright (c) 2010-2023 Contributors to the openHAB project
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

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.URLConnection;
import java.nio.ByteBuffer;
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

    private String host = "";
    private String className = "";
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
        this.className = this.getClass().getSimpleName();
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

        class BinaryData {
            byte[] data;
            int off;
            int len;

            BinaryData(byte[] data, int off, int len) {
                this.data = data;
                this.off = off;
                this.len = len;
            }

            public byte[] getBinaryData() {
                return Optional.ofNullable(data).orElse(new byte[0]);
            }

            public int getOff() {
                return Optional.ofNullable(off).orElse(0);
            }

            public int getLen() {
                return Optional.ofNullable(len).orElse(0);
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

            Header(int fileLength) {
                this.fileLength = String.valueOf(fileLength);
            }

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

        BinaryData binData;

        public String getEvent() {
            return Optional.ofNullable(event).orElse("");
        }

        public String getData() {
            return Optional.ofNullable(data).map(a -> a.getAsString()).orElse("");
        }

        public void putBinaryData(byte[] arr, int off, int len) {
            this.binData = new BinaryData(arr, off, len);
        }

        public BinaryData getBinaryData() {
            return Optional.ofNullable(binData).orElse(new BinaryData(new byte[0], 0, 0));
        }
    }

    @Override
    public void onWebSocketBinary(byte @Nullable [] arr, int off, int len) {
        if (arr == null) {
            return;
        }
        super.onWebSocketBinary(arr, off, len);
        String msg = extractMsg(arr, off, len, true);
        // offset is start of binary data
        int offset = ByteBuffer.wrap(arr, off, len).getShort() + off + 2; // 2 = length of Short
        try {
            JSONMessage jsonMsg = remoteControllerWebSocket.gson.fromJson(msg, JSONMessage.class);
            if (jsonMsg == null) {
                return;
            }
            switch (jsonMsg.getEvent()) {
                case "d2d_service_message":
                    jsonMsg.putBinaryData(arr, offset, len);
                    handleD2DServiceMessage(jsonMsg);
                    break;
                default:
                    logger.debug("{}: WebSocketArt(binary) Unknown event: {}", host, msg);
            }
        } catch (JsonSyntaxException e) {
            logger.warn("{}: {}: Error ({}) in message: {}", host, className, e.getMessage(), msg);
        }
    }

    @Override
    public synchronized void onWebSocketText(@Nullable String msgarg) {
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
                    if (remoteControllerWebSocket.callback.getArtMode2022()) {
                        remoteControllerWebSocket.callback.setArtMode2022(false);
                        logger.info("{}: Art Mode has been renabled on Frame TV's >= 2022", host);
                    }
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
                    handleD2DServiceMessage(jsonMsg);
                    break;
                default:
                    logger.debug("{}: WebSocketArt Unknown event: {}", host, msg);
            }
        } catch (JsonSyntaxException e) {
            logger.warn("{}: {}: Error ({}) in message: {}", host, className, e.getMessage(), msg);
        }
    }

    /**
     * handle D2DServiceMessages
     *
     * @param jsonMsg JSONMessage
     *
     */
    private synchronized void handleD2DServiceMessage(JSONMessage jsonMsg) {
        String msg = jsonMsg.getData();
        try {
            JSONMessage.Data data = remoteControllerWebSocket.gson.fromJson(msg, JSONMessage.Data.class);
            if (data == null) {
                logger.debug("{}: Empty d2d_service_message event", host);
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
                    logger.trace("{}: slideshow: {}, {}, {}, {}", host, data.getEvent(), data.getType(),
                            data.getValue(), data.getContentId());
                    break;
                case "image_added":
                    if (!data.getCategoryId().isBlank()) {
                        logger.info("{}: Image added: {}, category: {}", host, data.getContentId(),
                                data.getCategoryId());
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
                    if (remoteControllerWebSocket.callback.handler.isChannelLinked(ART_IMAGE)) {
                        getThumbnail(data.getContentId());
                    }
                    break;
                case "thumbnail":
                    logger.trace("{}: thumbnail: Fetching {}", host, data.getContentId());
                    stateMap.remove(ART_IMAGE);
                case "ready_to_use":
                    // upload image (should be 3840x2160 pixels in size)
                    msg = data.getConnInfo();
                    if (!msg.isBlank()) {
                        JSONMessage.Contentinfo contentInfo = remoteControllerWebSocket.gson.fromJson(msg,
                                JSONMessage.Contentinfo.class);
                        if (contentInfo != null) {
                            if ("thumbnail".equals(data.getEvent())) {
                                downloadThumbnail(contentInfo);
                            } else {
                                uploadImage(contentInfo);
                            }
                        }
                    } else {
                        // <2019 (ish) Frame TV's return thumbnails as binary data
                        receiveThumbnail(jsonMsg.getBinaryData());
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
        } catch (JsonSyntaxException e) {
            logger.warn("{}: {}: Error ({}) in message: {}", host, className, e.getMessage(), msg);
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
                    data.file_size = Long.valueOf(imageBytes.length);
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
                Long file_size;
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
     * Extract header message from binary data
     * <2019 (ish) Frame TV's return some messages as binary data
     * First two bytes are a short giving the header length
     * header is a D2DServiceMessages followed by the binary image data.
     *
     * Also Extract header information from image downloaded via socket
     * in which case first four bytes are the header length followed by the binary image data.
     *
     * @param byte[] payload
     * @param int offset (usually 0)
     * @param int len
     * @param boolean fromBinMsg true if this was received as a binary message (header length is a Short)
     *
     */
    public String extractMsg(byte[] payload, int offset, int len, boolean fromBinMsg) {
        ByteBuffer buf = ByteBuffer.wrap(payload, offset, len);
        int headerlen = fromBinMsg ? buf.getShort() : buf.getInt();
        offset += fromBinMsg ? Short.BYTES : Integer.BYTES;
        String type = fromBinMsg ? "D2DServiceMessages(from binary)" : "image header";
        String header = new String(payload, offset, headerlen, StandardCharsets.UTF_8);
        logger.trace("{}: Got {}: {}", host, type, header);
        return header;
    }

    /**
     * Receive thumbnail from binary data returned by TV in response to get_thumbnail command
     * <2019 (ish) Frame TV's return thumbnails as binary data.
     *
     * @param JSONMessage.BinaryData
     *
     */
    public void receiveThumbnail(JSONMessage.BinaryData binaryData) {
        extractThumbnail(binaryData.getBinaryData(), binaryData.getLen() - binaryData.getOff());
    }

    /**
     * Download thumbnail of current selected image/jpeg from ip+port
     *
     * @param contentinfo Contentinfo containing ip address and port to download from
     *
     */
    public void downloadThumbnail(JSONMessage.Contentinfo contentInfo) {
        logger.trace("{}: thumbnail: downloading from: ip:{}, port:{}", host, contentInfo.getIp(),
                contentInfo.getPort());
        try {
            Socket socket = new Socket(contentInfo.getIp(), contentInfo.getPort());
            byte[] payload = socket.getInputStream().readAllBytes();
            socket.close();
            String header = extractMsg(payload, 0, payload.length, false);
            JSONMessage.Header headerData = Optional
                    .ofNullable(remoteControllerWebSocket.gson.fromJson(header, JSONMessage.Header.class))
                    .orElse(new JSONMessage().new Header(0));
            extractThumbnail(payload, headerData.getFileLength());
        } catch (IOException e) {
            logger.warn("{}: Error downloading thumbnail {}", host, e.getMessage());
        }
    }

    /**
     * Extract thumbnail from payload
     *
     * @param payload byte[] containing binary data and possibly header info
     * @param fileLength int with image file size
     *
     */
    public void extractThumbnail(byte[] payload, int fileLength) {
        try {
            byte[] image = new byte[fileLength];
            ByteBuffer.wrap(image).put(payload, payload.length - fileLength, fileLength);
            String ftype = Optional
                    .ofNullable(URLConnection.guessContentTypeFromStream(new ByteArrayInputStream(image)))
                    .orElseThrow(() -> new Exception("Unable to determine image type"));
            valueReceived(ART_IMAGE, new RawType(image, ftype));
        } catch (Exception e) {
            if (logger.isTraceEnabled()) {
                logger.warn("{}: Error extracting thumbnail: ", host, e);
            } else {
                logger.warn("{}: Error extracting thumbnail {}", host, e.getMessage());
            }
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
