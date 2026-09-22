package demo;

import org.jooq.impl.DSL;

/**
 * Handles the {@code /create} UI action by inserting the fixed Apple headquarters address.
 *
 * <p>The inherited request handler redirects to the root page after insertion. Database
 * defaults initialize the geocoding flag, and the database's triggers initiate geocoding
 * and audit processing asynchronously.
 */
public class CreateAddressFrontEnd extends AbstractActionFrontEnd {

    /**
     * Creates the single-address action without inserting data or opening a database connection.
     */
    public CreateAddressFrontEnd() {
    }

    /**
     * Inserts one Apple headquarters row into {@code address} using the table defaults
     * for unspecified fields, including identity and geocoding state.
     *
     * @throws org.jooq.exception.DataAccessException if the insert fails; the inherited
     *         request handler converts this exception to an HTTP 500 response
     */
    @Override
    protected void performAction() {
        // Just Insert One address and return
        var dsl = PostgresDataSource.getDSL();
        dsl.insertInto(ADDRESS_TABLE)
                .set(DSL.field("address_1"), "1 Apple Park Way")
                .set(DSL.field("city"), "Cupertino")
                .set(DSL.field("district"), "CA")
                .set(DSL.field("address_notes"), "Apple HQ")
                .execute();
    }

}
