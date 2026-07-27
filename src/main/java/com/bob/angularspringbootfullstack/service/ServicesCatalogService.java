package com.bob.angularspringbootfullstack.service;

import com.bob.angularspringbootfullstack.model.Services;

import java.util.List;

/**
 * Administrative management of the {@link Services} catalog.
 *
 * <h3>Why this is its own service</h3>
 * {@link CustomerService} already exposes {@code getServices()} because the invoice form needs the
 * pick-list, and it would have been easy to hang four more methods off it. That service is,
 * however, already carrying customers, invoices, statistics and reporting; adding catalog
 * administration to it would make a class that is hard to read harder still, for no reason beyond
 * the two features sharing one table. Catalog management has a different audience (administrators
 * rather than every user), a different authorization rule, and a different lifecycle, so it gets
 * its own seam.
 *
 * <p>The division of labour between the two is deliberate and worth stating: {@code CustomerService}
 * answers "what may I put on an invoice?" and therefore returns <em>active</em> services only,
 * while this service answers "what does our catalog contain?" and returns everything, retired
 * entries included. Neither is a superset of the other by accident.
 */
public interface ServicesCatalogService {

    /**
     * Returns every catalog entry, including retired ones.
     *
     * @return the whole catalog, newest last (insertion order)
     */
    List<Services> getAllServices();

    /**
     * Retrieves one catalog entry by id.
     *
     * @param serviceId the id to look up
     * @return the matching service
     * @throws com.bob.angularspringbootfullstack.exception.ApiException if no such service exists
     */
    Services getService(Long serviceId);

    /**
     * Adds a new service to the catalog.
     *
     * <p>The new entry is active unless the caller explicitly says otherwise — creating something
     * already retired is a legitimate but unusual intent, and it should have to be stated.
     *
     * @param service the service to create; its id is ignored
     * @return the persisted service, with its generated id
     */
    Services createService(Services service);

    /**
     * Applies edits to an existing catalog entry.
     *
     * <p><b>Editing a service does not rewrite history.</b> Invoices copy a service's name and
     * price into their own line items when they are raised, so a price change here affects only
     * invoices raised from now on. That is the correct behaviour and the reason the copy exists —
     * without it, correcting a typo in a service name would silently restate every invoice ever
     * issued against it.
     *
     * @param serviceId the id of the entry to edit
     * @param edits     the submitted values
     * @return the updated service
     * @throws com.bob.angularspringbootfullstack.exception.ApiException if no such service exists
     */
    Services updateService(Long serviceId, Services edits);

    /**
     * Retires or reinstates a catalog entry.
     *
     * <p>A single method rather than separate {@code deactivate}/{@code reactivate} operations,
     * because the two are the same state change in opposite directions and splitting them would
     * duplicate the lookup and the not-found handling for no gain. It is deliberately not a
     * delete: see {@link Services#getActive()} for why the row must survive its retirement.
     *
     * @param serviceId the id of the entry to change
     * @param active    true to offer the service, false to retire it
     * @return the updated service
     * @throws com.bob.angularspringbootfullstack.exception.ApiException if no such service exists
     */
    Services setServiceActive(Long serviceId, boolean active);
}
