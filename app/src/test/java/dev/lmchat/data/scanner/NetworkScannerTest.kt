package dev.lmchat.data.scanner

import org.junit.Assert.*
import org.junit.Test

class NetworkScannerTest {
    @Test fun detectsNarrowSubnetsInsteadOfFallingBackToUnrelatedRanges() {
        assertEquals("192.168.1.128/25", NetworkScanner.subnetPrefix("192.168.1.200", 25))
        assertEquals("192.168.1.192/27", NetworkScanner.subnetPrefix("192.168.1.200", 27))
        assertEquals("192.168.1.200/32", NetworkScanner.subnetPrefix("192.168.1.200", 32))
    }

    @Test fun preservesFullWideSubnetAndDotted24Format() {
        assertEquals("192.168.68.0/22", NetworkScanner.subnetPrefix("192.168.70.42", 22))
        assertEquals("192.168.70.", NetworkScanner.subnetPrefix("192.168.70.42", 24))
        assertEquals("10.42.0.0/16", NetworkScanner.subnetPrefix("10.42.70.42", 16))
    }

    @Test fun rejectsInvalidOrExcessivelyLargeRanges() {
        assertNull(NetworkScanner.subnetPrefix("192.168.1.200", 0))
        assertNull(NetworkScanner.subnetPrefix("192.168.1.200", 15))
        assertNull(NetworkScanner.subnetPrefix("192.168.1.200", 33))
        assertNull(NetworkScanner.subnetPrefix("192.168.999.200", 24))
        assertNull(NetworkScanner.subnetPrefix("not-an-ip", 24))
    }
}
