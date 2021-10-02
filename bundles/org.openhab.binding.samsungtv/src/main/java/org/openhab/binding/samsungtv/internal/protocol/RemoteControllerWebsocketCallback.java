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

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.io.net.http.WebSocketFactory;

/**
 * Callback from the websocket remote controller
 *
 * @author Arjan Mels - Initial contribution
 */
@NonNullByDefault
public interface RemoteControllerWebsocketCallback {

    void appsUpdated(List<String> apps);

    void currentAppUpdated(@Nullable String app);

    void powerUpdated(boolean on, boolean artmode);

    void connectionError(@Nullable Throwable error);

    void putConfig(String token, Object object);

    void setOffline();

    @Nullable
    Object getConfig(String token);

    @Nullable
    ScheduledExecutorService getScheduler();

    @Nullable
    WebSocketFactory getWebSocketFactory();
}
