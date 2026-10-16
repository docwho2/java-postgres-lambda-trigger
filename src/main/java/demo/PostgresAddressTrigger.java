package demo;

import com.fasterxml.jackson.databind.JsonNode;
import static demo.PostgresAbstractTrigger.TG_OP.INSERT;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.jooq.CloseableDSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import software.amazon.awssdk.http.crt.AwsCrtHttpClient;
import software.amazon.awssdk.services.location.LocationClient;
import software.amazon.awssdk.services.location.model.SearchPlaceIndexForTextResponse;

/**
 * Geocodes address row changes with Amazon Location Service and persists the best match.
 *
 * <p>The database trigger selects INSERT/UPDATE rows whose {@code requires_geo_coding} flag
 * is true. This handler geocodes inserts and changes to any address component, including
 * city, district, or postal code. A successful lookup clears the flag to prevent the geocoding update from invoking this handler recursively.
 *
 * @author sjensen
 */
public class PostgresAddressTrigger extends PostgresAbstractTrigger {

    /** Maximum attempts to update a row that may not yet be visible after insertion. */
    private static final int MAX_UPDATE_ATTEMPTS = 10;
    /** Geocoding service boundary, initialized lazily by the production lookup. */
    private final Function<String, SearchPlaceIndexForTextResponse> lookup;
    /** Connection factory; each geocoding result is written within one closeable scope. */
    private final Supplier<CloseableDSLContext> database;
    /** Interruptible delay used between attempts, replaceable for deterministic tests. */
    private final Sleeper sleeper;

    /** Creates the handler without initializing AWS clients or opening database connections. */
    public PostgresAddressTrigger() {
        this(PostgresAddressTrigger::lookupAddress, PostgresDataSource::getDSL, Thread::sleep);
    }

    /**
     * Supplies external-operation boundaries for offline geocoding tests.
     *
     * @param lookup address search operation
     * @param database request-scoped connection factory
     * @param sleeper interruptible backoff operation
     * @throws NullPointerException if any dependency is null
     */
    PostgresAddressTrigger(Function<String, SearchPlaceIndexForTextResponse> lookup,
            Supplier<CloseableDSLContext> database, Sleeper sleeper) {
        this.lookup = Objects.requireNonNull(lookup);
        this.database = Objects.requireNonNull(database);
        this.sleeper = Objects.requireNonNull(sleeper);
    }

    /**
     * Geocodes relevant address changes; DELETE and unchanged address components are no-ops.
     *
     * @param operation validated PostgreSQL row operation
     * @param table_name source table; must be {@code address}
     * @param old_addr previous row object for UPDATE, JSON null for INSERT
     * @param new_addr current row object for INSERT/UPDATE, JSON null for DELETE
     * @throws IllegalArgumentException if the source table or required address fields are invalid
     * @throws IllegalStateException if the row never becomes visible or waiting is interrupted
     * @throws org.jooq.exception.DataAccessException if the result cannot be persisted
     * @throws software.amazon.awssdk.core.exception.SdkException if the Location lookup fails
     */
    @Override
    protected void processEvent(TG_OP operation, String table_name, JsonNode old_addr, JsonNode new_addr) {
        if (!"address".equals(table_name)) {
            throw new IllegalArgumentException("Geocoding only supports the address table");
        }
        if (operation == TG_OP.DELETE) {
            return;
        }
        boolean changed = operation == INSERT;
        for (var field : new String[]{"address_1", "city", "district", "postal_code"}) {
            changed |= !Objects.equals(old_addr.get(field), new_addr.get(field));
        }
        if (changed) {
            geoCodeAddress(new_addr);
        }
    }

    /**
     * Looks up one address and writes the first Location result back to its source row.
     *
     * <p>The search uses street, city, district, and an optional postal code. A match stores
     * the formatted address, timestamp, longitude/latitude, and response JSON, and clears
     * {@code requires_geo_coding}. With no match, the row and flag remain unchanged.
     *
     * <p>Zero-row updates are retried at most ten times with delays of 100 through 900 ms,
     * allowing a briefly uncommitted source row to become visible. Exhaustion or interruption
     * fails the invocation for Lambda's asynchronous retry policy. The database connection
     * is closed on every exit path, and each update has a two-second query timeout.
     *
     * @param addr current row with an integer ID, nonblank street/city/district, and optional postal code
     * @throws IllegalArgumentException if required row fields are missing or invalid
     * @throws IllegalStateException if retries are exhausted or interrupted
     * @throws org.jooq.exception.DataAccessException if the database update fails
     * @throws software.amazon.awssdk.core.exception.SdkException if the lookup fails
     */
    private void geoCodeAddress(JsonNode addr) {
        if (!addr.path("id").isIntegralNumber() || !addr.path("id").canConvertToInt()) {
            throw new IllegalArgumentException("Address requires an integer id");
        }
        var address = new StringBuilder(requiredText(addr, "address_1"))
                .append(", ").append(requiredText(addr, "city"))
                .append(", ").append(requiredText(addr, "district"));
        var postalCode = addr.path("postal_code");
        if (!postalCode.isNull() && !postalCode.isMissingNode()) {
            if (!postalCode.isTextual()) {
                throw new IllegalArgumentException("Address postal_code must be text or null");
            }
            address.append("  ").append(postalCode.asText());
        }
        var response = lookup.apply(address.toString());

        if (response.hasResults()) {
            final var place = response.results().get(0).place();

            // Take the response and create a serializable version that we can later convert to JSON
            var last_coding = mapper.convertValue(response.toBuilder(), SearchPlaceIndexForTextResponse.serializableBuilderClass());
            try (var dsl = database.get()) {
                var statement = dsl.update(DSL.table("address"))
                        // Make sure we set this to false so we don't trigger ourselves again
                        .set(DSL.field("requires_geo_coding", Boolean.class), false)
                        .set(DSL.field("address_formatted"), place.label())
                        .set(DSL.field("geo_coded"), DSL.now())
                        .set(DSL.field("geo_longitude", Double.class), place.geometry().point().get(0))
                        .set(DSL.field("geo_latitude", Double.class), place.geometry().point().get(1))
                        .set(DSL.field("geo_last_coding", JSONB.class), JSONB.jsonb(mapper.valueToTree(last_coding).toString()))
                        .where(DSL.field("id", Integer.class).eq(addr.path("id").asInt()));

                statement.queryTimeout(2);
                retryUpdate(statement::execute, sleeper);
            }

        } else {
            log.info("Places query returned no results");
        }
    }

    /**
     * Repeats a zero-row update with bounded backoff while preserving interruption.
     *
     * @param update database statement execution returning its affected-row count
     * @param sleeper delay operation; tests may supply a no-wait implementation
     * @throws IllegalStateException if all attempts miss the row or the thread is interrupted
     */
    static void retryUpdate(IntSupplier update, Sleeper sleeper) {
        for (int attempt = 1; attempt <= MAX_UPDATE_ATTEMPTS; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Geocoding update interrupted");
            }
            if (update.getAsInt() > 0) {
                return;
            }
            if (attempt < MAX_UPDATE_ATTEMPTS) {
                try {
                    sleeper.sleep(attempt * 100L);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Geocoding update interrupted", failure);
                }
            }
        }
        throw new IllegalStateException("Address row was not visible after ten update attempts");
    }

    /**
     * Validates a required textual address component before calling external services.
     *
     * @param row address row image
     * @param field address component name
     * @return nonblank component value
     * @throws IllegalArgumentException if the field is missing, blank, or not textual
     */
    private static String requiredText(JsonNode row, String field) {
        var value = row.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Address requires nonblank " + field);
        }
        return value.asText();
    }

    /**
     * Queries the configured place index for its best address match.
     *
     * @param address formatted address search string
     * @return Location response containing zero or one result
     * @throws IllegalStateException if the place-index setting is absent
     * @throws software.amazon.awssdk.core.exception.SdkException if the service call fails
     */
    private static SearchPlaceIndexForTextResponse lookupAddress(String address) {
        var index = System.getenv("PLACE_INDEX");
        if (index == null || index.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: PLACE_INDEX");
        }
        return LocationHolder.CLIENT.searchPlaceIndexForText(request -> request
                .indexName(index).maxResults(1).text(address));
    }

    /** Lazily initializes the reusable SDK client only for invocations that need geocoding. */
    private static final class LocationHolder {
        /** Prevents construction of the static client holder. */
        private LocationHolder() {
        }
        /** Shared Location client; SDK calls are bounded separately from database retries. */
        private static final LocationClient CLIENT = LocationClient.builder()
                .httpClient(AwsCrtHttpClient.builder().build())
                .overrideConfiguration(config -> config.apiCallTimeout(java.time.Duration.ofSeconds(5)))
                .build();
    }

    /** Interruptible backoff operation used between database update attempts. */
    @FunctionalInterface
    interface Sleeper {
        /**
         * Delays the next attempt for the requested duration.
         *
         * @param milliseconds delay duration
         * @throws InterruptedException if the waiting thread is interrupted
         */
        void sleep(long milliseconds) throws InterruptedException;
    }
}
