// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.mapillary.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.mapillary.spi.preferences.IMapillaryUrls;
import org.openstreetmap.josm.plugins.mapillary.spi.preferences.MapillaryConfig;
import org.openstreetmap.josm.plugins.mapillary.testutils.annotations.IntegrationTest;
import org.openstreetmap.josm.plugins.mapillary.testutils.annotations.MapillaryURLWireMock;

@MapillaryURLWireMock(MapillaryURLWireMock.Type.INTEGRATION)
@IntegrationTest
class MapillaryURLTest {

    private static final String CLIENT_ID_QUERY_PART = "client_id=" + MapillaryConfig.getUrls().getClientId();

    // TODO Test APIv4 when actually available

    @Test
    void testBrowseImageURL() {
        assertEquals(URI.create("https://www.mapillary.com/app/?pKey=1234567890123456789012&focus=photo"),
            MapillaryConfig.getUrls().browseImage("1234567890123456789012"));
    }

    @Test
    void testIllegalBrowseImageURL() {
        assertThrows(IllegalArgumentException.class, () -> MapillaryConfig.getUrls().browseImage(null));
    }

    @Test
    void testConnectURL() {
        assertUrlEquals(MapillaryConfig.getUrls().connect("http://redirect-host/ä"),
            "https://www.mapillary.com/connect", CLIENT_ID_QUERY_PART, "scope=read", "response_type=code",
            "redirect_uri=http%3A%2F%2Fredirect-host%2F%C3%A4");

        assertUrlEquals(MapillaryConfig.getUrls().connect(null), "https://www.mapillary.com/connect",
            CLIENT_ID_QUERY_PART, "scope=read", "response_type=code");

        assertUrlEquals(MapillaryConfig.getUrls().connect(""), "https://www.mapillary.com/connect",
            CLIENT_ID_QUERY_PART, "scope=read", "response_type=code");
    }

    @Test
    void testString2MalformedURL() throws IllegalAccessException, IllegalArgumentException, InvocationTargetException,
        NoSuchMethodException, SecurityException {
        Method method = IMapillaryUrls.class.getDeclaredMethod("string2URL", String[].class);
        method.setAccessible(true);
        assertNull(method.invoke(null, new Object[] { new String[] { "malformed URL" } })); // this simply invokes
                                                                                            // string2URL("malformed
                                                                                            // URL")
        assertNull(method.invoke(null, new Object[] { null })); // invokes string2URL(null)
    }

    @Test
    void testUtilityClass() {
        TestUtil.testUtilityClass(MapillaryConfig.class);
    }

    protected static void assertUrlEquals(URI actualUrl, String expectedBaseUrl, String... expectedParams) {
        final String actualUrlString = actualUrl.toString();
        assertEquals(expectedBaseUrl,
            actualUrlString.contains("?") ? actualUrlString.substring(0, actualUrlString.indexOf('?'))
                : actualUrlString);
        String[] actualParams = actualUrl.getRawQuery() == null ? new String[0] : actualUrl.getRawQuery().split("&");
        assertEquals(expectedParams.length, actualParams.length);
        for (String expectedParam : expectedParams) {
            boolean parameterIsPresent = false;
            for (int acIndex = 0; acIndex < actualParams.length; acIndex++) {
                parameterIsPresent = actualParams[acIndex].equals(expectedParam);
                if (parameterIsPresent)
                    break;
            }
            assertTrue(parameterIsPresent,
                expectedParam + " was expected in the query string of " + actualUrl + " but wasn't there.");
        }
    }
}
