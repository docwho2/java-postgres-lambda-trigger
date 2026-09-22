package demo;

import com.fasterxml.jackson.databind.JsonNode;
import static demo.PostgresAbstractTrigger.TG_OP.INSERT;
import java.util.Objects;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import software.amazon.awssdk.http.crt.AwsCrtHttpClient;
import software.amazon.awssdk.services.location.LocationClient;
import software.amazon.awssdk.services.location.model.SearchPlaceIndexForTextResponse;

/**
 * Geocodes address row changes with Amazon Location Service and persists the best match.
 *
 * <p>The database trigger selects INSERT/UPDATE rows whose {@code requires_geo_coding} flag
 * is true. This handler geocodes inserts and updates that change {@code address_1}; changing
 * only the city, district, or postal code does not cause a lookup here. A successful lookup
 * clears the flag to prevent the geocoding update from invoking this handler recursively.
 *
 * @author sjensen
 */
public class PostgresAddressTrigger extends PostgresAbstractTrigger {

    /**
     * Creates the geocoding handler after class initialization has prepared the shared Location client.
     */
    public PostgresAddressTrigger() {
    }

    /** Amazon Location client initialized once per execution environment and reused for lookups. */
    static final LocationClient locationClient;
    /** Place-index name read from the SAM-provided {@code PLACE_INDEX} environment variable. */
    static final String PLACE_INDEX;

    static {
        locationClient = LocationClient.builder()
                .httpClient(AwsCrtHttpClient.builder().build())
                .build();

        PLACE_INDEX = System.getenv("PLACE_INDEX");
    }

    /**
     * Determines whether an address event requires a lookup and geocodes it when needed.
     *
     * <p>INSERT always triggers a lookup. Other operations compare {@code address_1} in the
     * two images; callers are expected to send UPDATE in that branch. DELETE is not supported
     * by this address handler and is excluded by its database trigger. The geocoding flag is
     * checked by the SQL trigger, not by this method.
     *
     * @param operation expected INSERT or UPDATE operation from the address trigger
     * @param table_name source table name; unused because this handler always updates {@code address}
     * @param old_addr previous address image for UPDATE; unused for INSERT
     * @param new_addr current address image containing the ID and address fields to geocode
     * @throws org.jooq.exception.DataAccessException if persisting a geocoding result fails
     * @throws software.amazon.awssdk.core.exception.SdkException if the Location lookup fails
     */
    @Override
    protected void processEvent(TG_OP operation, String table_name, JsonNode old_addr, JsonNode new_addr) {

        boolean needs_geocode = false;

        if (operation.equals(INSERT)) {
            // New Address, so always encode
            needs_geocode = true;
        } else {
            // UPDATE, we need to determine if geo coding is required
            // for now just look for address_1 changes
            var old_address_1 = old_addr.findValue("address_1") != null ? old_addr.findValue("address_1").asText() : null;
            var new_address_1 = new_addr.findValue("address_1") != null ? new_addr.findValue("address_1").asText() : null;

            if (!Objects.equals(old_address_1, new_address_1)) {
                needs_geocode = true;
            }

        }

        if (needs_geocode) {
            geoCodeAddress(new_addr);
        } else {
            log.debug("No Geo Coding is required on this address");
        }

    }

    /**
     * Looks up one address and writes the first Location result back to its source row.
     *
     * <p>The search uses street, city, district, and an optional postal code. A match stores
     * the formatted address, timestamp, longitude/latitude, and response JSON, and clears
     * {@code requires_geo_coding}. With no match, the row and flag remain unchanged.
     *
     * <p>An update affecting zero rows is retried with linearly increasing sleeps of
     * 100 milliseconds, allowing time for the originating transaction to commit. This loop
     * has no explicit attempt/deadline limit and ignores interruption; a missing or rolled-back
     * row can keep it running until the Lambda timeout or another failure terminates execution.
     *
     * @param addr address row image with {@code id}, {@code address_1}, {@code city},
     *             {@code district}, and a {@code postal_code} field that may contain JSON null
     * @throws org.jooq.exception.DataAccessException if the database update fails
     * @throws software.amazon.awssdk.core.exception.SdkException if the Location lookup fails
     * @throws NullPointerException if a required JSON field is absent
     */
    private void geoCodeAddress(JsonNode addr) {
        // Build address String
        final var address = new StringBuilder(addr.findValue("address_1").asText());

        // Required
        address.append(", ").append(addr.findValue("city").asText());
        address.append(", ").append(addr.findValue("district").asText());

        if (!addr.findValue("postal_code").isNull()) {
            address.append("  ").append(addr.findValue("postal_code").asText());
        }

        var response = locationClient.searchPlaceIndexForText((t) -> {
            t.indexName(PLACE_INDEX)
                    .maxResults(1)
                    .text(address.toString());
        });

        log.debug(response);

        if (response.hasResults()) {
            final var place = response.results().get(0).place();

            // Take the response and create a serializable version that we can later convert to JSON
            var last_coding = mapper.convertValue(response.toBuilder(), SearchPlaceIndexForTextResponse.serializableBuilderClass());
            var dsl = PostgresDataSource.getDSL();
            var statement = dsl.update(DSL.table("address"))
                    // Make sure we set this to false so we don't trigger ourselves again
                    .set(DSL.field("requires_geo_coding", Boolean.class), false)
                    .set(DSL.field("address_formatted"), place.label())
                    .set(DSL.field("geo_coded"), DSL.now())
                    .set(DSL.field("geo_longitude", Double.class), place.geometry().point().get(0))
                    .set(DSL.field("geo_latitude", Double.class), place.geometry().point().get(1))
                    .set(DSL.field("geo_last_coding", JSONB.class), JSONB.jsonb(mapper.valueToTree(last_coding).toString()))
                    .where(DSL.field("id", Integer.class).eq(addr.findValue("id").asInt()));

            int updated = 0;
            int counter = 0;
            while (updated == 0) {
                updated = statement.execute();
                if (updated == 0) {
                    // Sleep a little since row did not update
                    try {
                        var sleep = ++counter * 100;
                        log.debug("Sleeping " + sleep + " ms due to insert not finding row yet");
                        Thread.sleep(sleep);
                    } catch (InterruptedException ie) {

                    }
                }
            }

        } else {
            log.info("Places query returned no results");
        }
    }

}
