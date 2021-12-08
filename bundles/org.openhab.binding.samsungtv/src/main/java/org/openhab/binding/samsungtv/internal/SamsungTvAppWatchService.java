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
package org.openhab.binding.samsungtv.internal;

import static java.nio.file.StandardWatchEventKinds.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.WatchEvent;
import java.nio.file.WatchEvent.Kind;
import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.samsungtv.internal.protocol.RemoteControllerWebSocket;
import org.openhab.core.OpenHAB;
import org.openhab.core.service.AbstractWatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link SamsungTvAppWatchService} provides a list of apps for >2020 Samsung TV's
 * File should be in json format
 *
 * @author Nick Waterton - Initial contribution
 */
@NonNullByDefault
public class SamsungTvAppWatchService extends AbstractWatchService {
    private static final String APPS_PATH = OpenHAB.getConfigFolder() + File.separator + "misc";
    private static final String APPS_FILE = "samsungtv.applist";

    private final Logger logger = LoggerFactory.getLogger(SamsungTvAppWatchService.class);
    private final RemoteControllerWebSocket remoteControllerWebSocket;
    private String host = "Unknown";
    private boolean started = false;

    public SamsungTvAppWatchService(String host, RemoteControllerWebSocket remoteControllerWebSocket) {
        super(APPS_PATH);
        this.host = host;
        this.remoteControllerWebSocket = remoteControllerWebSocket;
    }

    public void start() {
        logger.info("{}: Starting Apps File monitoring service", host);
        started = true;
        makeFileDir();
        readFileApps();
        activate();
    }

    public boolean getStarted() {
        return started;
    }

    /**
     * Create directory and file if they don't exist
     *
     */
    public void makeFileDir() {
        try {
            File directory = new File(APPS_PATH);
            if (!directory.exists()) {
                directory.mkdir();
            }
            File file = new File(APPS_PATH, APPS_FILE);
            file.createNewFile();
        } catch (IOException e) {
            logger.debug("{}: Cannot create apps file: {}", host, e.getMessage());
        }
    }

    public void readFileApps() {
        processWatchEvent(null, null, Paths.get(APPS_PATH, APPS_FILE));
    }

    @Override
    protected boolean watchSubDirectories() {
        return false;
    }

    @Override
    protected Kind<?>[] getWatchEventKinds(@Nullable Path directory) {
        return new Kind<?>[] { ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY };
    }

    @Override
    protected void processWatchEvent(@Nullable WatchEvent<?> event, @Nullable Kind<?> kind, @Nullable Path path) {
        if (path != null && path.endsWith(APPS_FILE)) {
            logger.debug("{}: Updating Apps list from FILE {}", host, path);
            try {
                @SuppressWarnings("null")
                List<String> allLines = Files.lines(path).filter(line -> !line.trim().startsWith("#"))
                        .collect(Collectors.toList());
                logger.debug("{}: Updated Apps list, {} apps in list", host, allLines.size());
                remoteControllerWebSocket.updateAppList(allLines);
            } catch (IOException e) {
                logger.debug("{}: Cannot read apps file: {}", host, e.getMessage());
            }
        }
    }
}
