package com.bob.angularspringbootfullstack.repo;

import com.bob.angularspringbootfullstack.model.Services;
import org.springframework.data.repository.ListCrudRepository;

/**
 * ServicesRepo is the Spring Data JPA repository for {@link Services} catalog entries.
 * <p>
 * Extends {@code ListCrudRepository} for standard CRUD operations. The catalog is
 * read-only from the invoice creation flow — services are managed separately and
 * returned to the UI so the user can select from predefined offerings.
 */
public interface ServicesRepo extends ListCrudRepository<Services, Long> {
}
