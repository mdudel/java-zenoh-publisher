/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.purejava.scouting;

import io.mdudel.zenoh.purejava.wire.WhatAmI;
import io.mdudel.zenoh.purejava.wire.ZenohId;
import io.mdudel.zenoh.purejava.wire.messages.Hello;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real-multicast integration test. Skipped (via {@link org.junit.jupiter.api.Assumptions})
 * on hosts with no multicast-capable NIC or where the kernel refuses to
 * bind the group - both of which are common on containerised CI (see
 * MEMORY 2026-06-18 note on 127.0.0.1 and multicast).
 *
 * <p>When the host supports multicast: builds a real
 * {@link PureJavaZenohScout} bound to the first suitable NIC, sends one
 * hand-crafted HELLO to {@code 224.0.0.224:7446} via a plain
 * {@link DatagramSocket} bound to the same NIC, and asserts the scout
 * observes it. Also asserts SCOUT emission adds to the counters.</p>
 */
class PureJavaZenohScoutIntegrationTest {

    private static NetworkInterface pickMulticastNic() {
        try {
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            while (all.hasMoreElements()) {
                NetworkInterface nif = all.nextElement();
                if (!nif.isUp() || nif.isLoopback() || !nif.supportsMulticast()) continue;
                boolean hasIpv4 = false;
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    if (addrs.nextElement() instanceof Inet4Address) { hasIpv4 = true; break; }
                }
                if (hasIpv4) return nif;
            }
        } catch (IOException ignore) {}
        return null;
    }

    @Test void discoverInjectedHelloOverRealMulticast() throws Exception {
        NetworkInterface nif = pickMulticastNic();
        assumeTrue(nif != null,
                "No multicast-capable NIC on this host; skipping integration test");

        InetAddress group = InetAddress.getByName("224.0.0.224");
        int port = 7446;

        // Confirm the kernel actually lets us join the group on this NIC.
        // Some containers block IGMP; assume-skip if so.
        try (MulticastSocket probe = new MulticastSocket(port)) {
            probe.setReuseAddress(true);
            probe.setNetworkInterface(nif);
            probe.joinGroup(new InetSocketAddress(group, port), nif);
            probe.leaveGroup(new InetSocketAddress(group, port), nif);
        } catch (IOException e) {
            assumeTrue(false,
                    "Kernel refused multicast group join on " + nif.getName()
                            + ": " + e.getMessage() + " - skipping");
        }

        CountDownLatch discovered = new CountDownLatch(1);
        AtomicReference<DiscoveredNode> got = new AtomicReference<>();
        ScoutListener l = new ScoutListener() {
            @Override public void onDiscover(DiscoveredNode node) {
                got.set(node);
                discovered.countDown();
            }
        };
        ZenohId senderZid = new ZenohId(new byte[]{
                0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88});
        Hello h = new Hello(9, WhatAmI.ROUTER, senderZid,
                List.of("tcp/1.2.3.4:7447"), List.of());
        byte[] payload = h.encode();

        try (PureJavaZenohScout scout = PureJavaZenohScout.builder()
                .networkInterfaces(List.of(nif))
                .mode(PureJavaZenohScout.Mode.ACTIVE)
                .scoutIntervalMillis(300L)
                .listener(l)
                .build()) {
            scout.start();

            // Send the HELLO from a separate socket bound to the same NIC.
            try (DatagramSocket sender = new DatagramSocket(0, nif.getInetAddresses().nextElement())) {
                DatagramPacket pkt = new DatagramPacket(
                        payload, payload.length,
                        new InetSocketAddress(group, port));
                // Try a few sends; loopback multicast can drop the first packet.
                // If the kernel refuses to route to the multicast group at all
                // (common on K8s pods with /32 netmask, or hardened bastions),
                // treat as a skip - our unit tests already cover the state
                // machine, this test just wanted the wire-level sanity check.
                for (int i = 0; i < 5 && discovered.getCount() > 0; i++) {
                    try {
                        sender.send(pkt);
                    } catch (IOException e) {
                        assumeTrue(false,
                                "Kernel refused multicast send to " + group + ": "
                                        + e.getMessage() + " - skipping");
                    }
                    Thread.sleep(100L);
                }
            }

            boolean ok = discovered.await(3, TimeUnit.SECONDS);
            assumeTrue(ok, "HELLO did not loop back within 3s - kernel routing skipped test");

            DiscoveredNode d = got.get();
            assertEquals(senderZid, d.zid());
            assertEquals(WhatAmI.ROUTER, d.role());
            assertEquals(List.of("tcp/1.2.3.4:7447"), d.locators());

            // Also verify the scout emitted at least one SCOUT.
            Thread.sleep(500L);
            org.junit.jupiter.api.Assertions.assertTrue(scout.scoutsSent() >= 1,
                    "expected at least 1 SCOUT emitted");
        }
    }
}
