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

/**
 * Exercises UI routing and response contracts without connecting to AWS or PostgreSQL.
 *
 * <p>Injected handlers isolate dispatch behavior, while anonymous action implementations
 * exercise the shared redirect/error handling. Production-router rejection tests ensure
 * invalid requests can be handled without initializing database access.
 */
class WebHandlerTest {

    /**
     * Creates an offline routing test instance without contacting AWS or PostgreSQL.
     */
    WebHandlerTest() {
    }

    /**
     * Verifies that an exact route receives the original request once and returns its response unchanged.
     *
     * @param path one of the five supported UI paths supplied by the parameterized test
     */
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

    /**
     * Verifies that unknown paths and path variants return HTTP 404 before database work can begin.
     *
     * @param path unsupported path, including case and trailing-slash variants
     */
    @ParameterizedTest
    @ValueSource(strings = {"/missing", "/create/extra", "/CREATE", "/create/"})
    void unknownPathsNeverReachAnAction(String path) {
        // Construct the actual production router offline: no DB connection or AWS call is needed.
        assertEquals(404, new WebHandler().handleRequest(request(path, "GET"), null).getStatusCode());
    }

    /**
     * Verifies that a known mutation route rejects non-GET requests and advertises GET as allowed.
     *
     * @param method unsupported HTTP method supplied by the parameterized test
     */
    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "DELETE", "HEAD", "OPTIONS"})
    void rejectsOtherMethodsBeforeAnActionCanWrite(String method) {
        var response = new WebHandler().handleRequest(request("/create", method), null);
        assertEquals(405, response.getStatusCode());
        assertEquals("GET", response.getHeaders().get("Allow"));
    }

    /**
     * Verifies HTTP 400 responses for a null event, missing path, and missing HTTP method.
     */
    @Test
    void rejectsIncompleteRequests() {
        var handler = new WebHandler();
        assertEquals(400, handler.handleRequest(null, null).getStatusCode());
        assertEquals(400, handler.handleRequest(request(null, "GET"), null).getStatusCode());
        assertEquals(400, handler.handleRequest(request("/create", null), null).getStatusCode());
    }

    /**
     * Reuses one router across view/action/view requests to check that route selection,
     * response bodies, and redirect headers do not leak from one invocation to the next.
     */
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

    /**
     * Verifies that the shared action handler runs its mutation once and preserves the
     * HTTP 307 redirect, root location, and no-cache header when invoked through the router.
     */
    @Test
    void existingActionStillRedirectsAfterExactlyOneWrite() {
        var calls = new AtomicInteger();
        var action = new AbstractActionFrontEnd() {
            /** Records one simulated database mutation without opening a connection. */
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

    /**
     * Verifies that an exception from an action remains an HTTP 500 response through routing.
     */
    @Test
    void existingActionFailureRemainsAnErrorResponse() {
        var action = new AbstractActionFrontEnd() {
            /**
             * Simulates a failing database mutation for the error-response assertion.
             *
             * @throws IllegalStateException always, to exercise the shared action handler's catch block
             */
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

    /**
     * Builds a minimal payload-format 1.0 request for offline router tests.
     *
     * @param path request path, or {@code null} when testing a missing path
     * @param method HTTP method, or {@code null} when testing a missing method
     * @return a new event containing the supplied path and method
     */
    private static APIGatewayProxyRequestEvent request(String path, String method) {
        return new APIGatewayProxyRequestEvent().withPath(path).withHttpMethod(method);
    }
}
