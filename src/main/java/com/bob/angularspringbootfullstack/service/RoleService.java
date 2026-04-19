package com.bob.angularspringbootfullstack.service;

import com.bob.angularspringbootfullstack.model.Role;

public interface RoleService {
    Role getRoleByUserId(Long id);
}
