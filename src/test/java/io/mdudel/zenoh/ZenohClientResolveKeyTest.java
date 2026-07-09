package io.mdudel.zenoh;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the org-prefix key resolver contract. Any change to the slash-
 * normalisation logic in {@link ZenohClient#resolveKey(String, String)}
 * must update these expectations deliberately.
 */
class ZenohClientResolveKeyTest {

    private static String resolve(String org, String key) throws Exception {
        Method m = ZenohClient.class.getDeclaredMethod("resolveKey", String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, org, key);
    }

    @Test void nullOrgReturnsKeyVerbatim() throws Exception {
        assertEquals("tracks", resolve(null, "tracks"));
    }

    @Test void emptyOrgReturnsKeyVerbatim() throws Exception {
        assertEquals("tracks", resolve("", "tracks"));
    }

    @Test void simpleJoin() throws Exception {
        assertEquals("acme/tracks", resolve("acme", "tracks"));
    }

    @Test void trailingSlashOnOrgTrimmed() throws Exception {
        assertEquals("acme/tracks", resolve("acme/", "tracks"));
    }

    @Test void leadingSlashOnKeyTrimmed() throws Exception {
        assertEquals("acme/tracks", resolve("acme", "/tracks"));
    }

    @Test void bothSlashesTrimmed() throws Exception {
        assertEquals("acme/tracks", resolve("acme/", "/tracks"));
    }

    @Test void multiSegmentKey() throws Exception {
        assertEquals("acme/radar/cat062", resolve("acme", "radar/cat062"));
    }

    @Test void emptyKeyReturnsOrg() throws Exception {
        assertEquals("acme", resolve("acme", ""));
    }
}
