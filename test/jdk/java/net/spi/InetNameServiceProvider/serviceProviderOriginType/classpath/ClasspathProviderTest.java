/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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
import org.testng.annotations.Test;
//import ClasspathNameServiceProviderImpl;

import static org.testng.Assert.assertThrows;

/*
 * @test
 * @summary Test that InetNameServiceProvider can be installed in class path.
 * @library ../../lib
 * @build nspi.testlib/testlib.ResolutionRegistry ClasspathNameServiceProviderImpl
 * @run testng/othervm ClasspathProviderTest
 */


public class ClasspathProviderTest {

    @Test
    public void testResolution() throws Exception {
        InetAddress inetAddress = InetAddress.getByName("classpath-provider-test.org");
        System.err.println("Resolved address:" + inetAddress);

        if (!ClasspathNameServiceProviderImpl.registry.containsAddressMapping(inetAddress)) {
            throw new RuntimeException("Test NSPI was not properly set");
        }
    }
}
