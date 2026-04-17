package com.bob.angularspringbootfullstack.repo;

import com.bob.angularspringbootfullstack.model.User;

import java.util.Collection;


public interface UserRepo<T extends User> {

    /* here we will add some generic CRUD operations

     */
    T create(T data);

    // This is our 'READ' operation - we will add pagination here, so we can specify the page number and page size
    Collection<T> list(int page, int pageSize);

    T get(Long id);

    T update(Long id, T data);

    void delete(Long id);

    User getUserByEmail(String email);
}
/* TODO: complex operations

 */