package demo;

import org.jooq.impl.DSL;

/**
 * Handles the {@code /multiple} UI action by inserting five fixed sample addresses.
 *
 * <p>All inserts share one transaction to demonstrate how asynchronous database-trigger
 * invocations can begin before their source rows become visible to another connection.
 * The inherited handler redirects only after the transaction call returns.
 */
public class CreateMultipleAddressFrontEnd extends AbstractActionFrontEnd {

    /**
     * Creates the batch-address action; the transaction starts only when the action is invoked.
     */
    public CreateMultipleAddressFrontEnd() {
    }

    /**
     * Inserts the Microsoft, Tesla, SpaceX, State Farm, and Delta sample addresses in
     * one transaction, with numbered notes identifying the five rows in the UI.
     *
     * <p>A failed insert rolls back the transaction. Lambda invocations initiated by the
     * database triggers are external effects and are not undone by that database rollback.
     *
     * @throws org.jooq.exception.DataAccessException if database access or the transaction
     *         fails; the inherited handler converts the failure to an HTTP 500 response
     */
    @Override
    protected void performAction() {
        // Insert 5 addresses in one transaction so they all hit at once
        var dsl = PostgresDataSource.getDSL();
        dsl.transaction((configuration) -> {
            var dslT = configuration.dsl();
            dslT.insertInto(ADDRESS_TABLE)
                .set(DSL.field("address_1"), "One Microsoft Way")
                .set(DSL.field("city"), "Redmond")
                .set(DSL.field("district"), "WA")
                .set(DSL.field("address_notes"), "Batch Address #1")
                .execute();
            dslT.insertInto(ADDRESS_TABLE)
                .set(DSL.field("address_1"), "1 Tesla Road")
                .set(DSL.field("city"), "Austin")
                .set(DSL.field("district"), "TX")
                .set(DSL.field("address_notes"), "Batch Address #2")
                .execute();
            dslT.insertInto(ADDRESS_TABLE)
                .set(DSL.field("address_1"), "1 Rocket Road")
                .set(DSL.field("city"), "Hawthorne")
                .set(DSL.field("district"), "CA")
                .set(DSL.field("address_notes"), "Batch Address #3")
                .execute();
            dslT.insertInto(ADDRESS_TABLE)
                .set(DSL.field("address_1"), "1 State Farm Plaza")
                .set(DSL.field("city"), "Bloomington")
                .set(DSL.field("district"), "IL")
                .set(DSL.field("address_notes"), "Batch Address #4")
                .execute();
            dslT.insertInto(ADDRESS_TABLE)
                .set(DSL.field("address_1"), "1030 Delta Blvd")
                .set(DSL.field("city"), "Atlanta")
                .set(DSL.field("district"), "GA")
                .set(DSL.field("address_notes"), "Batch Address #5")
                .execute();
        });
    }

}
