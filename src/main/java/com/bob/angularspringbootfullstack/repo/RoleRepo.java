package com.bob.angularspringbootfullstack.repo;

import com.bob.angularspringbootfullstack.model.Role;
import org.springframework.stereotype.Repository;

import java.util.Collection;

@Repository
public interface RoleRepo<T extends Role> {
    /* here we will add some generic CRUD operations

     */
    T create(T data);

    // This is our 'READ' operation - we will add pagination here, so we can specify the page number and page size
    Collection<T> list(int page, int pageSize);

    T get(Long id);

    T update(Long id, T data);

    void delete(Long id);

    /* TODO: complex operations

     */
    void addRoleToUser(Long userId, String roleName);

}
