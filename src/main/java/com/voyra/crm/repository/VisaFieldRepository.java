package com.voyra.crm.repository;

import com.voyra.crm.entity.VisaField;
import com.voyra.crm.enums.VisaFieldScope;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface VisaFieldRepository extends JpaRepository<VisaField, String> {

    /** Active common fields, then the active fields of that country (ignoring case), each by sort order. */
    @Query("SELECT f FROM VisaField f WHERE f.active = true AND (f.scope = com.voyra.crm.enums.VisaFieldScope.COMMON"
            + " OR (f.scope = com.voyra.crm.enums.VisaFieldScope.COUNTRY AND lower(f.country) = lower(:country)))"
            + " ORDER BY f.scope, f.sortOrder, f.createdAt")
    List<VisaField> findActiveFor(@Param("country") String country);

    List<VisaField> findByActiveTrueOrderByScopeAscCountryAscSortOrderAsc();

    /** Every field of one scope and country, active or removed (removed ones can be restored by label). */
    @Query("SELECT f FROM VisaField f WHERE f.scope = :scope AND lower(coalesce(f.country, '')) = lower(coalesce(:country, ''))"
            + " ORDER BY f.sortOrder")
    List<VisaField> findInGroup(@Param("scope") VisaFieldScope scope, @Param("country") String country);

    @Query("SELECT DISTINCT f.country FROM VisaField f WHERE f.active = true AND f.scope = com.voyra.crm.enums.VisaFieldScope.COUNTRY"
            + " ORDER BY f.country")
    List<String> findActiveCountries();
}
