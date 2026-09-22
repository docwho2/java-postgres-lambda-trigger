package demo;

import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class WebHandlerTest {

    @ParameterizedTest
    @ValueSource(strings = {"/", "/create", "/multiple", "/delete", "/audit"})
    void dispatchesOnlyTheMatchingRouteAndPreservesTheResponse(String path) {
        var input = request(path, "GET").withQueryStringParameters(Map.of("example", "value"));
        var expected = new APIGatewayProxyResponseEvent()
                .withStatusCode(path.equals("/") ? 200 : 307)
                .withHeaders(Map.of("Location", "/", "Cache-Control", "no-cache"))
                .withBody("response");
        var calls = new AtomicInteger();
        Map<String, RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent>> routes = new HashMap<>();
        for (String route : new String[]{"/", "/create", "/multiple", "/delete", "/audit"}) {
            routes.put(route, (event, context) -> {
                assertEquals(path, route, "Must not invoke a different action");
                assertSame(input, event, "Forward the complete request without rebuilding it");
                calls.incrementAndGet();
                return expected;
            });
        }

        assertSame(expected, new WebHandler(routes).handleRequest(input, null));
        assertEquals(1, calls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/missing", "/create/extra", "/CREATE", "/create/"})
    void unknownPathsNeverReachAnAction(String path) {
        // Construct the actual production router offline: no DB connection or AWS call is needed.
        assertEquals(404, new WebHandler().handleRequest(request(path, "GET"), null).getStatusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "DELETE", "HEAD", "OPTIONS"})
    void rejectsOtherMethodsBeforeAnActionCanWrite(String method) {
        var response = new WebHandler().handleRequest(request("/create", method), null);
        assertEquals(405, response.getStatusCode());
        assertEquals("GET", response.getHeaders().get("Allow"));
    }

    @Test
    void rejectsIncompleteRequests() {
        var handler = new WebHandler();
        assertEquals(400, handler.handleRequest(null, null).getStatusCode());
        assertEquals(400, handler.handleRequest(request(null, "GET"), null).getStatusCode());
        assertEquals(400, handler.handleRequest(request("/create", null), null).getStatusCode());
    }

    @Test
    void warmRequestsDoNotReuseThePreviousRouteOrResponse() {
        var handler = new WebHandler(Map.of(
                "/", (event, context) -> new APIGatewayProxyResponseEvent().withStatusCode(200).withBody("home"),
                "/create", (event, context) -> new APIGatewayProxyResponseEvent().withStatusCode(307).withHeaders(Map.of("Location", "/"))));
        assertEquals("home", handler.handleRequest(request("/", "GET"), null).getBody());
        var redirect = handler.handleRequest(request("/create", "GET"), null);
        assertEquals(307, redirect.getStatusCode());
        assertNull(redirect.getBody());
        var homeAgain = handler.handleRequest(request("/", "GET"), null);
        assertEquals(200, homeAgain.getStatusCode());
        assertNull(homeAgain.getHeaders());
    }

    @Test
    void existingActionStillRedirectsAfterExactlyOneWrite() {
        var calls = new AtomicInteger();
        var action = new AbstractActionFrontEnd() {
            @Override
            protected void performAction() {
                calls.incrementAndGet();
            }
        };
        var handler = new WebHandler(Map.of("/create", action));
        var response = handler.handleRequest(request("/create", "GET"), null);
        assertEquals(1, calls.get());
        assertEquals(307, response.getStatusCode());
        assertEquals("/", response.getHeaders().get("Location"));
        assertEquals("no-cache", response.getHeaders().get("Cache-Control"));
    }

    @Test
    void existingActionFailureRemainsAnErrorResponse() {
        var action = new AbstractActionFrontEnd() {
            @Override
            protected void performAction() {
                throw new IllegalStateException("test database failure");
            }
        };
        var response = new WebHandler(Map.of("/create", action))
                .handleRequest(request("/create", "GET"), null);
        assertEquals(500, response.getStatusCode());
        assertTrue(response.getBody().contains("test database failure"));
    }

    private static APIGatewayProxyRequestEvent request(String path, String method) {
        return new APIGatewayProxyRequestEvent().withPath(path).withHttpMethod(method);
    }
}
