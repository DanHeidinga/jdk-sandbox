/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package testlib;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ResolutionRegistry {

    // Map to store hostName -> InetAddress mappings
    private final Map<String, List<byte[]>> registry;

    private static final Logger LOGGER = Logger.getLogger(ResolutionRegistry.class.getName());

    public ResolutionRegistry() {

        // Populate registry from test data file
        String fileName = System.getProperty("test.insp.dataFileName", "addresses.txt");
        Path addressesFile = Paths.get(System.getProperty("test.src", ".")).resolve(fileName);
        LOGGER.info("Creating ResolutionRegistry instance from file:" + addressesFile);
        registry = parseDataFile(addressesFile);
    }

    private Map<String, List<byte[]>> parseDataFile(Path addressesFile) {
        try {
            if (addressesFile.toFile().isFile()) {
                Map<String, List<byte[]>> resReg = new ConcurrentHashMap<>();
                // Prepare list of hostname/address entries
                List<String[]> entriesList = Files.readAllLines(addressesFile).stream()
                        .map(String::trim)
                        .filter(Predicate.not(String::isBlank))
                        .filter(s -> !s.startsWith("#"))
                        .map(s -> s.split("\\s+"))
                        .filter(sarray -> sarray.length == 2)
                        .filter(ResolutionRegistry::hasLiteralAddress)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
                // Convert list of entries into registry Map
                for (var entry : entriesList) {
                    String ipAddress = entry[0].trim();
                    String hostName = entry[1].trim();
                    byte[] addrBytes = toByteArray(ipAddress);
                    if (addrBytes != null) {
                        var list = resReg.containsKey(hostName) ? resReg.get(hostName) : new ArrayList();
                        list.add(addrBytes);
                        if (!resReg.containsKey(hostName)) {
                            resReg.put(hostName, list);
                        }
                    }
                }
                resReg.replaceAll((k, v) -> Collections.unmodifiableList(v));
                // Print constructed registry
                StringBuilder sb = new StringBuilder("Constructed addresses registry:" + System.lineSeparator());
                for (var entry : resReg.entrySet()) {
                    sb.append("\t" + entry.getKey() + ": ");
                    for (byte[] addr : entry.getValue()) {
                        sb.append(addressBytesToString(addr) + " ");
                    }
                    sb.append(System.lineSeparator());
                }
                LOGGER.info(sb.toString());
                return resReg;
            } else {
                // If file doesn't exist - return empty map
                return Collections.emptyMap();
            }
        } catch (IOException ioException) {
            // If any problems parsing the file - log a warning and return an empty map
            LOGGER.log(Level.WARNING, "Error reading data file", ioException);
            return Collections.emptyMap();
        }
    }

    // Line is not a blank and not a comment
    private static boolean hasLiteralAddress(String[] lineFields) {
        String addressString = lineFields[0].trim();
        return addressString.charAt(0) == '[' ||
                Character.digit(addressString.charAt(0), 16) != -1 ||
                (addressString.charAt(0) == ':');
    }

    // Line is not blank and not comment
    private static byte[] toByteArray(String addressString) {
        InetAddress address;
        // Will reuse InetAddress functionality to parse literal IP address
        // strings. This call is guarded by 'hasLiteralAddress' method.
        try {
            address = InetAddress.getByName(addressString);
        } catch (UnknownHostException unknownHostException) {
            LOGGER.warning("Can't parse address string:'" + addressString + "'");
            return null;
        }
        return address.getAddress();
    }

    public Stream<InetAddress> lookupHost(String host) throws UnknownHostException {
        LOGGER.info("Looking-up '" + host + "' address");
        if (!registry.containsKey(host)) {
            throw new UnknownHostException(host);
        }
        return registry.get(host)
                .stream()
                .map(ba -> constructInetAddress(host, ba))
                .filter(Objects::nonNull);
    }

    private static InetAddress constructInetAddress(String host, byte[] address) {
        try {
            return InetAddress.getByAddress(host, address);
        } catch (UnknownHostException unknownHostException) {
            return null;
        }
    }

    public String lookupAddress(byte[] addressBytes) {
        for (var entry : registry.entrySet()) {
            if (entry.getValue()
                    .stream()
                    .filter(ba -> Arrays.equals(ba, addressBytes))
                    .findAny()
                    .isPresent()) {
                return entry.getKey();
            }
        }
        try {
            return InetAddress.getByAddress(addressBytes).getHostAddress();
        } catch (UnknownHostException unknownHostException) {
            throw new IllegalArgumentException();
        }
    }

    public boolean containsAddressMapping(InetAddress address) {
        String hostName = address.getHostName();
        if (registry.containsKey(hostName)) {
            var mappedBytes = registry.get(address.getHostName());
            for (byte[] mappedAddr : mappedBytes) {
                if (Arrays.equals(mappedAddr, address.getAddress())) {
                    return true;
                }
            }
        }
        return false;
    }

    public static String addressBytesToString(byte[] bytes) {
        try {
            return InetAddress.getByAddress(bytes).toString();
        } catch (UnknownHostException unknownHostException) {
            return Arrays.toString(bytes);
        }
    }
}
