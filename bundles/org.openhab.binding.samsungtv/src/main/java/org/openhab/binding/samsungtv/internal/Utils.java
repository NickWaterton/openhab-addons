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

import java.util.Base64;
import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.jupnp.model.meta.RemoteDevice;
import org.openhab.core.types.Command;

/**
 * The {@link Utils} is a collection of static utilities
 *
 * @author Nick Waterton - Initial contribution
 */
@NonNullByDefault
public class Utils {

    public static String b64encode(String str) {
        return Base64.getUrlEncoder().encodeToString(str.getBytes());
    }

    public static String truncCmd(Command command) {
        String cmd = command.toString();
        return (cmd.length() <= 80) ? cmd : cmd.substring(0, 80) + "...";
    }

    public static String getModelName(@Nullable RemoteDevice device) {
        return Optional.ofNullable(device).map(a -> a.getDetails()).map(a -> a.getModelDetails())
                .map(a -> a.getModelName()).orElse("");
    }

    public static String getManufacturer(@Nullable RemoteDevice device) {
        return Optional.ofNullable(device).map(a -> a.getDetails()).map(a -> a.getManufacturerDetails())
                .map(a -> a.getManufacturer()).orElse("");
    }

    public static String getFriendlyName(@Nullable RemoteDevice device) {
        return Optional.ofNullable(device).map(a -> a.getDetails()).map(a -> a.getFriendlyName()).orElse("");
    }

    public static String getUdn(@Nullable RemoteDevice device) {
        return Optional.ofNullable(device).map(a -> a.getIdentity()).map(a -> a.getUdn())
                .map(a -> a.getIdentifierString()).orElse("");
    }

    public static String getHost(@Nullable RemoteDevice device) {
        return Optional.ofNullable(device).map(a -> a.getIdentity()).map(a -> a.getDescriptorURL())
                .map(a -> a.getHost()).orElse("");
    }

    public static String getType(@Nullable RemoteDevice device) {
        return Optional.ofNullable(device).map(a -> a.getType()).map(a -> a.getType()).orElse("");
    }
}
