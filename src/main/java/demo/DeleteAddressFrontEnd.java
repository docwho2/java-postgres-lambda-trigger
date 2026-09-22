package demo;

import org.jooq.impl.DSL;

/**
 * Handles the {@code /delete} UI action by deleting the address with the greatest ID.
 *
 * <p>"Last" refers to the maximum identity value, not the creation timestamp. The inherited
 * handler redirects after the statement completes, including when the table is already empty.
 */
public class DeleteAddressFrontEnd extends AbstractActionFrontEnd {

    /**
     * Creates the address-deletion action without accessing the database.
     */
    public DeleteAddressFrontEnd() {
    }

    /**
     * Deletes the row whose ID matches the maximum address ID in a single SQL statement.
     * An empty table yields no matching row and is treated as a successful no-op.
     *
     * @throws org.jooq.exception.DataAccessException if the delete fails; the inherited
     *         request handler converts this exception to an HTTP 500 response
     */
    @Override
    protected void performAction() {
        // Delete the last row
        var id = DSL.field("id", Integer.class);
        var dsl = PostgresDataSource.getDSL();
        dsl.deleteFrom(ADDRESS_TABLE)
                .where(id.eq(DSL.select(DSL.max(id)).from(ADDRESS_TABLE)))
                .execute();
    }

}
