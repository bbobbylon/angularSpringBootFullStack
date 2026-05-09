package com.bob.angularspringbootfullstack.repo;

import com.bob.angularspringbootfullstack.model.Invoice;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;

public interface InvoiceRepo extends PagingAndSortingRepository<Invoice, Long>, ListCrudRepository<Invoice, Long> {
}
