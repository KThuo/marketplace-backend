package com.hodi.modules.usertypes;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface UserTypeRepository
        extends JpaRepository<UserType, Long>, JpaSpecificationExecutor<UserType> {

    Optional<UserType> findByCode(String code);

    boolean existsByCodeIgnoreCase(String code);

    /** Ordered for the pickers, which read as a hierarchy rather than an alphabetical list. */
    List<UserType> findByStatusNotOrderBySortOrderAsc(Integer status);

    /**
     * The types a given population may hold.
     *
     * <p>Used by the user form: a seller owner creating staff is offered SELLER types and nothing else, so
     * the form cannot express a user who belongs to an organisation their type does not match.
     */
    List<UserType> findByActorClassAndStatusNotOrderBySortOrderAsc(String actorClass, Integer status);
}
