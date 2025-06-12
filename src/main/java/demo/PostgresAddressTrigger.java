/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
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
 * Geo Code Addresses that are added to the address table
 *
 * @author sjensen
 */
public class PostgresAddressTrigger extends PostgresAbstractTrigger {

    static final LocationClient locationClient;
    static final String PLACE_INDEX;

    static {
        locationClient = LocationClient.builder()
                .httpClient(AwsCrtHttpClient.builder().build())
                .build();

        PLACE_INDEX = System.getenv("PLACE_INDEX");
    }

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
