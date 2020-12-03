/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

import java.net.InetAddress;
import java.net.SocketPermission;
import java.net.UnknownHostException;
import java.security.Permission;
import java.util.logging.Logger;

import org.testng.Assert;
import org.testng.annotations.Test;


/*
 * @test
 * @summary Test that resolution of host name requires SocketPermission("resolve", <host name>)
 * permission when running with security manager and custom name service provider installed.
 * @library ../lib
 * @build nspi.testlib/testlib.ResolutionRegistry simple.insp/insp.SimpleNameServiceProviderImpl
 *        ResolvePermissionTest
 * @run testng/othervm -Dtest.allowConnectToJavaTestOrg=true -Dtest.insp.dataFileName=nonExistentFile ResolvePermissionTest
 * @run testng/othervm -Dtest.allowConnectToJavaTestOrg=false -Dtest.insp.dataFileName=nonExistentFile ResolvePermissionTest
 */

public class ResolvePermissionTest {

    @Test
    public void testRuntimePermission() throws Exception {
        boolean allowJavaTestOrgResolve = Boolean.getBoolean(ALLOW_SYSTEM_PROPERTY);
        // Set security manager which grants all permissions + RuntimePermission("inetNameService")
        var securityManager = new ResolvePermissionTest.TestSecurityManager(allowJavaTestOrgResolve);
        try {
            System.setSecurityManager(securityManager);
            Class expectedExceptionClass = allowJavaTestOrgResolve ?
                    UnknownHostException.class : SecurityException.class;
            var exception = Assert.expectThrows(expectedExceptionClass, () -> InetAddress.getByName("javaTest.org"));
            LOGGER.info("Got expected exception: " + exception);
        } finally {
            System.setSecurityManager(null);
        }
    }

    static class TestSecurityManager extends SecurityManager {
        final boolean allowJavaTestOrgResolve;

        public TestSecurityManager(boolean allowJavaTestOrgResolve) {
            this.allowJavaTestOrgResolve = allowJavaTestOrgResolve;
        }

        @Override
        public void checkPermission(Permission permission) {
            if (permission instanceof java.net.SocketPermission) {
                SocketPermission sockPerm = (SocketPermission) permission;
                if ("resolve".equals(sockPerm.getActions())) {
                    String host = sockPerm.getName();
                    LOGGER.info("Checking 'resolve' SocketPermission: " + permission);
                    if ("javaTest.org".equals(host) && !allowJavaTestOrgResolve) {
                        LOGGER.info("Denying 'resolve' permission for 'javaTest.org'");
                        throw new SecurityException("Access Denied");
                    }
                }
            }
        }
    }
    private static final Logger LOGGER = Logger.getLogger(ResolvePermissionTest.class.getName());
    private static final String ALLOW_SYSTEM_PROPERTY = "test.allowJavaTestOrgResolve";


}
