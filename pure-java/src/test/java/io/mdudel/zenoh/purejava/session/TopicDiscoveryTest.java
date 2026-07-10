/*
 * Copyright 2026 the java-zenoh-publisher-pure contributors.
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 */
package io.mdudel.zenoh.purejava.session;

import io.mdudel.zenoh.purejava.PureJavaZenohSubscriber;
import io.mdudel.zenoh.purejava.wire.messages.Interest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the INTEREST-based topic discovery API. Exercises
 * ZenohSession.declareInterest + finalInterest and the
 * PureJavaZenohSubscriber.discoverTopics wrapper.
 */
class TopicDiscoveryTest {

    private LoopbackZenohRouter router;

    @BeforeEach void bind() throws IOException {
        router = LoopbackZenohRouter.bind();
    }

    @AfterEach void unbind() {
        if (router != null) router.close();
    }

    @Test void discoverTopicsSendsInterestAndRoutesRepliedDeclares() throws Exception {
        try (PureJavaZenohSubscriber sub = PureJavaZenohSubscriber.builder()
                .connectEndpoint("tcp/127.0.0.1:" + router.port())
                .leaseMs(2_000)
                .build()) {
            sub.start();

            CopyOnWriteArrayList<String> discovered = new CopyOnWriteArrayList<>();
            AtomicBoolean completed = new AtomicBoolean(false);
            CountDownLatch got3 = new CountDownLatch(3);

            PureJavaZenohSubscriber.TopicDiscovery td = sub.discoverTopics("**",
                    new PureJavaZenohSubscriber.TopicListener() {
                        @Override public void onTopicDeclared(String keyExpr, long id) {
                            discovered.add(keyExpr + "#" + id);
                            got3.countDown();
                        }
                        @Override public void onDiscoveryComplete() {
                            completed.set(true);
                        }
                    });

            // Router receives the INTEREST.
            List<Interest> interests = waitForInterests(1);
            assertEquals(1, interests.size());
            Interest interest = interests.get(0);
            assertEquals(Interest.Mode.CURRENT_FUTURE, interest.mode());
            assertTrue(interest.wantsSubscribers());
            assertEquals("**", interest.keySuffix());
            long interestId = interest.id();

            // Router replies with three DeclareSubscribers + a FINAL.
            LoopbackZenohRouter.ClientSession peer = router.sessions().get(0);
            peer.sendDeclareSubscriber(1L, interestId, 101L, "sensors/room1/temp");
            peer.sendDeclareSubscriber(2L, interestId, 102L, "sensors/room2/temp");
            peer.sendDeclareSubscriber(3L, interestId, 103L, "logs/system/**");
            peer.sendDeclareFinal(4L, interestId);

            assertTrue(got3.await(2, TimeUnit.SECONDS),
                    "listener saw only " + discovered.size() + "/3 topics");
            assertTrue(discovered.contains("sensors/room1/temp#101"));
            assertTrue(discovered.contains("sensors/room2/temp#102"));
            assertTrue(discovered.contains("logs/system/**#103"));

            // The FINAL sentinel should have fired onDiscoveryComplete.
            long deadline = System.currentTimeMillis() + 500;
            while (!completed.get() && System.currentTimeMillis() < deadline) Thread.sleep(20);
            assertTrue(completed.get(), "onDiscoveryComplete never fired");

            td.close();
        }
    }

    @Test void discoverTopicsHandlesFutureUndeclare() throws Exception {
        try (PureJavaZenohSubscriber sub = PureJavaZenohSubscriber.builder()
                .connectEndpoint("tcp/127.0.0.1:" + router.port())
                .leaseMs(2_000)
                .build()) {
            sub.start();

            CountDownLatch declared   = new CountDownLatch(1);
            CountDownLatch undeclared = new CountDownLatch(1);
            long[] seenSubId = { -1 };

            sub.discoverTopics("**",
                    new PureJavaZenohSubscriber.TopicListener() {
                        @Override public void onTopicDeclared(String keyExpr, long id) {
                            seenSubId[0] = id;
                            declared.countDown();
                        }
                        @Override public void onTopicUndeclared(long id) {
                            if (id == seenSubId[0]) undeclared.countDown();
                        }
                    });

            List<Interest> interests = waitForInterests(1);
            long interestId = interests.get(0).id();

            LoopbackZenohRouter.ClientSession peer = router.sessions().get(0);
            peer.sendDeclareSubscriber(1L, interestId, 999L, "peers/live/**");
            assertTrue(declared.await(2, TimeUnit.SECONDS));

            peer.sendUndeclareSubscriber(2L, interestId, 999L);
            assertTrue(undeclared.await(2, TimeUnit.SECONDS),
                    "listener never saw the undeclare for sub #999");
        }
    }

    @Test void topicDiscoveryCloseSendsFinalInterest() throws Exception {
        try (PureJavaZenohSubscriber sub = PureJavaZenohSubscriber.builder()
                .connectEndpoint("tcp/127.0.0.1:" + router.port())
                .leaseMs(2_000)
                .build()) {
            sub.start();

            PureJavaZenohSubscriber.TopicDiscovery td = sub.discoverTopics("**",
                    (keyExpr, id) -> { /* ignore */ });
            long interestId = td.interestId();
            assertTrue(td.isOpen());

            waitForInterests(1);
            td.close();
            assertFalse(td.isOpen());

            // Now the router should also see a FINAL-mode Interest with the same id.
            long deadline = System.currentTimeMillis() + 1_000;
            Interest finalMsg = null;
            while (System.currentTimeMillis() < deadline && finalMsg == null) {
                for (Interest i : router.sessions().get(0).receivedInterests()) {
                    if (i.id() == interestId && i.mode() == Interest.Mode.FINAL) {
                        finalMsg = i;
                        break;
                    }
                }
                if (finalMsg == null) Thread.sleep(20);
            }
            assertNotNull(finalMsg, "router never received a FINAL Interest for id " + interestId);
        }
    }

    @Test void discoverAllTopicsIsSugarForWildcard() throws Exception {
        try (PureJavaZenohSubscriber sub = PureJavaZenohSubscriber.builder()
                .connectEndpoint("tcp/127.0.0.1:" + router.port())
                .leaseMs(2_000)
                .build()) {
            sub.start();
            sub.discoverAllTopics((k, id) -> { /* ignore */ });
            List<Interest> interests = waitForInterests(1);
            assertEquals("**", interests.get(0).keySuffix());
        }
    }

    @Test void multipleConcurrentInterestsAreIndependentlyRouted() throws Exception {
        try (PureJavaZenohSubscriber sub = PureJavaZenohSubscriber.builder()
                .connectEndpoint("tcp/127.0.0.1:" + router.port())
                .leaseMs(2_000)
                .build()) {
            sub.start();

            CopyOnWriteArrayList<String> listA = new CopyOnWriteArrayList<>();
            CopyOnWriteArrayList<String> listB = new CopyOnWriteArrayList<>();
            CountDownLatch aDone = new CountDownLatch(1);
            CountDownLatch bDone = new CountDownLatch(1);

            sub.discoverTopics("sensors/**", (k, id) -> {
                listA.add(k);
                aDone.countDown();
            });
            sub.discoverTopics("logs/**", (k, id) -> {
                listB.add(k);
                bDone.countDown();
            });

            List<Interest> interests = waitForInterests(2);
            long idA = interests.stream()
                    .filter(i -> "sensors/**".equals(i.keySuffix()))
                    .findFirst().orElseThrow().id();
            long idB = interests.stream()
                    .filter(i -> "logs/**".equals(i.keySuffix()))
                    .findFirst().orElseThrow().id();

            LoopbackZenohRouter.ClientSession peer = router.sessions().get(0);
            peer.sendDeclareSubscriber(1L, idA, 1L, "sensors/room1/temp");
            peer.sendDeclareSubscriber(2L, idB, 2L, "logs/system/boot");

            assertTrue(aDone.await(2, TimeUnit.SECONDS), "listenerA missed its declare");
            assertTrue(bDone.await(2, TimeUnit.SECONDS), "listenerB missed its declare");
            assertEquals(1, listA.size());
            assertEquals(1, listB.size());
            assertEquals("sensors/room1/temp", listA.get(0));
            assertEquals("logs/system/boot",   listB.get(0));
        }
    }

    @Test void discoverBeforeStartRejected() throws Exception {
        try (PureJavaZenohSubscriber sub = PureJavaZenohSubscriber.builder()
                .connectEndpoint("tcp/127.0.0.1:" + router.port())
                .leaseMs(2_000)
                .build()) {
            org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                    () -> sub.discoverTopics("**", (k, id) -> {}));
        }
    }

    @Test void listenerExceptionDoesNotBreakDelivery() throws Exception {
        try (PureJavaZenohSubscriber sub = PureJavaZenohSubscriber.builder()
                .connectEndpoint("tcp/127.0.0.1:" + router.port())
                .leaseMs(2_000)
                .build()) {
            sub.start();

            java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();
            CountDownLatch all3 = new CountDownLatch(3);
            sub.discoverTopics("**", (keyExpr, id) -> {
                int n = count.incrementAndGet();
                all3.countDown();
                if (n == 2) throw new RuntimeException("intentional test throw");
            });

            List<Interest> interests = waitForInterests(1);
            long interestId = interests.get(0).id();

            LoopbackZenohRouter.ClientSession peer = router.sessions().get(0);
            peer.sendDeclareSubscriber(1L, interestId, 1L, "a");
            peer.sendDeclareSubscriber(2L, interestId, 2L, "b");
            peer.sendDeclareSubscriber(3L, interestId, 3L, "c");

            assertTrue(all3.await(2, TimeUnit.SECONDS),
                    "delivery halted after listener threw; count=" + count.get());
            assertEquals(3, count.get());
        }
    }

    // ---- helpers ------------------------------------------------------

    private List<Interest> waitForInterests(int n) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < deadline) {
            List<Interest> is = router.sessions().isEmpty()
                    ? List.of()
                    : router.sessions().get(0).receivedInterests();
            if (is.size() >= n) return is;
            Thread.sleep(20);
        }
        return router.sessions().isEmpty()
                ? List.of()
                : router.sessions().get(0).receivedInterests();
    }
}
