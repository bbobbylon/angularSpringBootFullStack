package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.dto.UserDTO;
import com.bob.angularspringbootfullstack.enumeration.EventType;
import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.model.Organization;
import com.bob.angularspringbootfullstack.model.OrganizationInvite;
import com.bob.angularspringbootfullstack.model.OrganizationStats;
import com.bob.angularspringbootfullstack.model.Role;
import com.bob.angularspringbootfullstack.model.Stats;
import com.bob.angularspringbootfullstack.model.User;
import com.bob.angularspringbootfullstack.repo.RoleRepo;
import com.bob.angularspringbootfullstack.rowmapper.OrganizationEventRowMapper;
import com.bob.angularspringbootfullstack.rowmapper.OrganizationInviteRowMapper;
import com.bob.angularspringbootfullstack.rowmapper.OrganizationRowMapper;
import com.bob.angularspringbootfullstack.rowmapper.UserRowMapper;
import com.bob.angularspringbootfullstack.service.CustomerService;
import com.bob.angularspringbootfullstack.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.support.KeyHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.bob.angularspringbootfullstack.query.OrganizationQuery.COUNT_ACTIVE_MEMBERSHIP_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationQuery.COUNT_ACTIVE_MEMBERS_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationQuery.COUNT_ORGANIZATION_EVENTS_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationQuery.DEACTIVATE_MEMBERSHIP_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationQuery.DELETE_INVITE_BY_CODE_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationQuery.DELETE_INVITE_BY_ID_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationQuery.INSERT_MEMBERSHIP_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationQuery.INSERT_ORGANIZATION_EVENT_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationQuery.INSERT_ORGANIZATION_INVITE_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationQuery.INSERT_ORGANIZATION_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationQuery.REACTIVATE_MEMBERSHIP_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationQuery.SELECT_ACTIVE_INVITES_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationQuery.SELECT_ACTIVE_MEMBERS_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationQuery.SELECT_ALL_ORGANIZATIONS_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationQuery.SELECT_INVITE_BY_CODE_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationQuery.SELECT_ORGANIZATION_BY_ID_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationQuery.SELECT_ORGANIZATION_EVENTS_PAGINATED_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationQuery.UPDATE_ORGANIZATION_NAME_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationQuery.UPDATE_ORGANIZATION_PROFILE_QUERY;
import static com.bob.angularspringbootfullstack.query.OrganizationQuery.UPDATE_ORGANIZATION_STATUS_QUERY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavioural guard for the Organization CRUD + membership-management business rules
 * (FUTURE-ENHANCEMENTS.md §3.2) {@link OrganizationServiceImpl} owns: blank-name/invalid-status
 * validation before the database is ever touched, and the empty-scope short-circuit
 * {@link #listOrganizationsWithEmptyScopeNeverQueriesTheDatabase()} exists for — see that
 * method's Javadoc for why it matters. Authorization (who may call these methods at all) is
 * {@link com.bob.angularspringbootfullstack.controller.OrganizationController}'s concern, not
 * this class's — mirrors {@link RoleServiceImplTest}'s split for the analogous Role CRUD.
 *
 * <p>{@link NamedParameterJdbcTemplate} is mocked, matching
 * {@link SecuritySettingsServiceImplTest}'s convention — no database is involved.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrganizationServiceImplTest {

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;
    @Mock
    private RoleRepo<Role> roleRepo;
    @Mock
    private CustomerService customerService;
    @Mock
    private UserService userService;

    @InjectMocks
    private OrganizationServiceImpl service;

    // ── createOrganization ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a blank name is refused before the database is touched")
    void createOrganizationRejectsBlankName() {
        assertThatThrownBy(() -> service.createOrganization("  ")).isInstanceOf(ApiException.class);

        verify(jdbcTemplate, never()).update(eq(INSERT_ORGANIZATION_QUERY), any(SqlParameterSource.class), any(KeyHolder.class));
    }

    @Test
    @DisplayName("a well-formed name is trimmed, inserted, and the created row is re-read")
    void createOrganizationTrimsAndCreates() {
        doAnswer(invocation -> {
            KeyHolder keyHolder = invocation.getArgument(2);
            keyHolder.getKeyList().add(Map.of("id", 42L));
            return 1;
        }).when(jdbcTemplate).update(eq(INSERT_ORGANIZATION_QUERY), any(SqlParameterSource.class), any(KeyHolder.class));
        Organization persisted = Organization.builder().id(42L).name("Acme Partners").status("ACTIVE").build();
        when(jdbcTemplate.queryForObject(eq(SELECT_ORGANIZATION_BY_ID_QUERY), eq(Map.of("id", 42L)), any(OrganizationRowMapper.class)))
                .thenReturn(persisted);

        Organization created = service.createOrganization("  Acme Partners  ");

        assertThat(created).isEqualTo(persisted);
    }

    @Test
    @DisplayName("a duplicate name surfaces as a friendly ApiException, not a raw SQL error")
    void createOrganizationTranslatesDuplicateKey() {
        doThrow(new DuplicateKeyException("dup"))
                .when(jdbcTemplate).update(eq(INSERT_ORGANIZATION_QUERY), any(SqlParameterSource.class), any(KeyHolder.class));

        assertThatThrownBy(() -> service.createOrganization("Acme Partners"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already exists");
    }

    // ── renameOrganization ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("renameOrganization rejects a blank name before the database is touched")
    void renameOrganizationRejectsBlankName() {
        assertThatThrownBy(() -> service.renameOrganization(1L, " ")).isInstanceOf(ApiException.class);

        verify(jdbcTemplate, never()).update(eq(UPDATE_ORGANIZATION_NAME_QUERY), anyMap());
    }

    @Test
    @DisplayName("renameOrganization throws when no organization has that id")
    void renameOrganizationThrowsWhenNotFound() {
        when(jdbcTemplate.update(eq(UPDATE_ORGANIZATION_NAME_QUERY), anyMap())).thenReturn(0);

        assertThatThrownBy(() -> service.renameOrganization(99L, "New Name"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not found");
    }

    // ── setOrganizationStatus ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an unrecognized status is refused before the database is touched")
    void setOrganizationStatusRejectsUnknownValue() {
        assertThatThrownBy(() -> service.setOrganizationStatus(1L, "RETIRED")).isInstanceOf(ApiException.class);

        verify(jdbcTemplate, never()).update(eq(UPDATE_ORGANIZATION_STATUS_QUERY), anyMap());
    }

    @Test
    @DisplayName("a lowercase status is normalized to uppercase and delegated")
    void setOrganizationStatusNormalizesCase() {
        when(jdbcTemplate.update(eq(UPDATE_ORGANIZATION_STATUS_QUERY), eq(Map.of("status", "INACTIVE", "id", 1L)))).thenReturn(1);
        Organization updated = Organization.builder().id(1L).name("Acme").status("INACTIVE").build();
        when(jdbcTemplate.queryForObject(eq(SELECT_ORGANIZATION_BY_ID_QUERY), eq(Map.of("id", 1L)), any(OrganizationRowMapper.class)))
                .thenReturn(updated);

        Organization result = service.setOrganizationStatus(1L, "inactive");

        assertThat(result.getStatus()).isEqualTo("INACTIVE");
    }

    // ── isActiveMemberOfOrganization ─────────────────────────────────────────────────────

    @Test
    @DisplayName("a positive membership count resolves to true")
    void isActiveMemberTrueWhenCountPositive() {
        when(jdbcTemplate.queryForObject(eq(COUNT_ACTIVE_MEMBERSHIP_QUERY), anyMap(), eq(Long.class))).thenReturn(1L);

        assertThat(service.isActiveMemberOfOrganization(5L, 9L)).isTrue();
    }

    @Test
    @DisplayName("a database error fails closed to false, never true")
    void isActiveMemberFailsClosedOnError() {
        when(jdbcTemplate.queryForObject(eq(COUNT_ACTIVE_MEMBERSHIP_QUERY), anyMap(), eq(Long.class)))
                .thenThrow(new RuntimeException("boom"));

        assertThat(service.isActiveMemberOfOrganization(5L, 9L)).isFalse();
    }

    // ── listOrganizations ────────────────────────────────────────────────────────────────

    /**
     * An empty (non-null) scope means "this caller belongs to no organization" and must yield an
     * empty catalog — never the full one. Asserting the database is never even queried guards
     * against a future edit accidentally routing an empty scope into
     * {@link com.bob.angularspringbootfullstack.query.OrganizationQuery#SELECT_ALL_ORGANIZATIONS_QUERY}
     * (which has no {@code WHERE} clause at all) instead of short-circuiting.
     */
    @Test
    @DisplayName("an empty scope short-circuits to an empty result without querying the database")
    void listOrganizationsWithEmptyScopeNeverQueriesTheDatabase() {
        List<Organization> result = (List<Organization>) service.listOrganizations(List.of());

        assertThat(result).isEmpty();
        verify(jdbcTemplate, never()).query(any(String.class), any(OrganizationRowMapper.class));
    }

    @Test
    @DisplayName("a null scope queries the full catalog")
    void listOrganizationsWithNullScopeQueriesEverything() {
        service.listOrganizations(null);

        verify(jdbcTemplate).query(eq(SELECT_ALL_ORGANIZATIONS_QUERY), any(OrganizationRowMapper.class));
    }

    // ── addMember / removeMember ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("addMember inserts a new membership row for a first-time member")
    void addMemberInsertsNewRow() {
        service.addMember(9L, 42L);

        verify(jdbcTemplate).update(eq(INSERT_MEMBERSHIP_QUERY), eq(Map.of("userId", 42L, "organizationId", 9L)));
        verify(jdbcTemplate, never()).update(eq(REACTIVATE_MEMBERSHIP_QUERY), anyMap());
    }

    @Test
    @DisplayName("addMember reactivates an existing (inactive) row instead of failing on the collision")
    void addMemberReactivatesOnCollision() {
        doThrow(new DuplicateKeyException("dup")).when(jdbcTemplate).update(eq(INSERT_MEMBERSHIP_QUERY), anyMap());

        service.addMember(9L, 42L);

        verify(jdbcTemplate).update(eq(REACTIVATE_MEMBERSHIP_QUERY), eq(Map.of("userId", 42L, "organizationId", 9L)));
    }

    @Test
    @DisplayName("addMember translates a foreign-key violation into a friendly ApiException")
    void addMemberTranslatesForeignKeyViolation() {
        doThrow(new DataIntegrityViolationException("fk"))
                .when(jdbcTemplate).update(eq(INSERT_MEMBERSHIP_QUERY), anyMap());

        assertThatThrownBy(() -> service.addMember(9L, 42L)).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("removeMember deactivates the membership row rather than deleting it")
    void removeMemberDeactivates() {
        service.removeMember(9L, 42L);

        verify(jdbcTemplate).update(eq(DEACTIVATE_MEMBERSHIP_QUERY), eq(Map.of("userId", 42L, "organizationId", 9L)));
    }

    // ── listActiveMembers ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("listActiveMembers maps each row to a UserDTO enriched with its role")
    void listActiveMembersMapsRows() {
        User member = User.builder().id(42L).email("member@example.com").build();
        when(jdbcTemplate.query(eq(SELECT_ACTIVE_MEMBERS_QUERY), eq(Map.of("organizationId", 9L)), any(UserRowMapper.class)))
                .thenReturn(List.of(member));
        when(roleRepo.getRoleByUserId(42L)).thenReturn(Role.builder().name("ROLE_USER").build());

        List<UserDTO> members = (List<UserDTO>) service.listActiveMembers(9L);

        assertThat(members).hasSize(1);
        assertThat(members.get(0).getEmail()).isEqualTo("member@example.com");
    }

    // ── updateOrganizationProfile ────────────────────────────────────────────────────────

    @Test
    @DisplayName("updateOrganizationProfile throws when no organization has that id")
    void updateOrganizationProfileThrowsWhenNotFound() {
        when(jdbcTemplate.update(eq(UPDATE_ORGANIZATION_PROFILE_QUERY), any(SqlParameterSource.class))).thenReturn(0);

        assertThatThrownBy(() -> service.updateOrganizationProfile(99L, "desc", "a@b.com", "https://a.com"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("updateOrganizationProfile persists and re-reads the updated organization")
    void updateOrganizationProfileUpdatesAndReReads() {
        when(jdbcTemplate.update(eq(UPDATE_ORGANIZATION_PROFILE_QUERY), any(SqlParameterSource.class))).thenReturn(1);
        Organization updated = Organization.builder().id(1L).name("Acme").description("desc").build();
        when(jdbcTemplate.queryForObject(eq(SELECT_ORGANIZATION_BY_ID_QUERY), eq(Map.of("id", 1L)), any(OrganizationRowMapper.class)))
                .thenReturn(updated);

        Organization result = service.updateOrganizationProfile(1L, "desc", "", "  ");

        assertThat(result.getDescription()).isEqualTo("desc");
    }

    // ── recordOrganizationEvent / listOrganizationEvents / countOrganizationEvents ──────

    @Test
    @DisplayName("recordOrganizationEvent inserts a row carrying the event type and detail")
    void recordOrganizationEventInserts() {
        service.recordOrganizationEvent(9L, 5L, EventType.ORG_CREATED, "Acme");

        verify(jdbcTemplate).update(eq(INSERT_ORGANIZATION_EVENT_QUERY), any(SqlParameterSource.class));
    }

    @Test
    @DisplayName("listOrganizationEvents clamps a negative page to zero and an oversized page size to 100")
    void listOrganizationEventsClampsPaging() {
        service.listOrganizationEvents(9L, -1, 500);

        verify(jdbcTemplate).query(eq(SELECT_ORGANIZATION_EVENTS_PAGINATED_QUERY),
                eq(Map.of("organizationId", 9L, "size", 100, "offset", 0)), any(OrganizationEventRowMapper.class));
    }

    @Test
    @DisplayName("countOrganizationEvents returns zero rather than null")
    void countOrganizationEventsNullSafe() {
        when(jdbcTemplate.queryForObject(eq(COUNT_ORGANIZATION_EVENTS_QUERY), eq(Map.of("organizationId", 9L)), eq(Long.class)))
                .thenReturn(null);

        assertThat(service.countOrganizationEvents(9L)).isZero();
    }

    // ── createInvite / listActiveInvites / revokeInvite ──────────────────────────────────

    @Test
    @DisplayName("createInvite inserts a row, then re-reads it by its generated code")
    void createInviteInsertsAndReReads() {
        OrganizationInvite persisted = OrganizationInvite.builder()
                .id(1L).organizationId(9L).roleName("ROLE_USER").expirationDate(LocalDateTime.now().plusHours(168)).build();
        when(jdbcTemplate.queryForObject(eq(SELECT_INVITE_BY_CODE_QUERY), anyMap(), any(OrganizationInviteRowMapper.class)))
                .thenReturn(persisted);

        OrganizationInvite created = service.createInvite(9L, 5L, "ROLE_USER", 168L);

        assertThat(created).isEqualTo(persisted);
        verify(jdbcTemplate).update(eq(INSERT_ORGANIZATION_INVITE_QUERY), any(SqlParameterSource.class));
    }

    @Test
    @DisplayName("createInvite translates a foreign-key violation into a friendly ApiException")
    void createInviteTranslatesForeignKeyViolation() {
        doThrow(new DataIntegrityViolationException("fk"))
                .when(jdbcTemplate).update(eq(INSERT_ORGANIZATION_INVITE_QUERY), any(SqlParameterSource.class));

        assertThatThrownBy(() -> service.createInvite(9L, 5L, "ROLE_USER", 168L)).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("revokeInvite throws when no invite matches that id within that organization")
    void revokeInviteThrowsWhenNotFound() {
        when(jdbcTemplate.update(eq(DELETE_INVITE_BY_ID_QUERY), eq(Map.of("id", 3L, "organizationId", 9L)))).thenReturn(0);

        assertThatThrownBy(() -> service.revokeInvite(9L, 3L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("listActiveInvites returns whatever the query maps")
    void listActiveInvitesDelegates() {
        OrganizationInvite invite = OrganizationInvite.builder().id(1L).organizationId(9L).build();
        when(jdbcTemplate.query(eq(SELECT_ACTIVE_INVITES_QUERY), eq(Map.of("organizationId", 9L)), any(OrganizationInviteRowMapper.class)))
                .thenReturn(List.of(invite));

        assertThat(service.listActiveInvites(9L)).containsExactly(invite);
    }

    // ── previewInvite ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("previewInvite resolves an unknown code to empty")
    void previewInviteUnknownCodeIsEmpty() {
        when(jdbcTemplate.queryForObject(eq(SELECT_INVITE_BY_CODE_QUERY), eq(Map.of("code", "bogus")), any(OrganizationInviteRowMapper.class)))
                .thenThrow(new EmptyResultDataAccessException(1));

        assertThat(service.previewInvite("bogus")).isEmpty();
    }

    @Test
    @DisplayName("previewInvite resolves an expired code to empty, the same verdict as unknown")
    void previewInviteExpiredCodeIsEmpty() {
        OrganizationInvite expired = OrganizationInvite.builder().organizationId(9L).expirationDate(LocalDateTime.now().minusHours(1)).build();
        when(jdbcTemplate.queryForObject(eq(SELECT_INVITE_BY_CODE_QUERY), eq(Map.of("code", "stale")), any(OrganizationInviteRowMapper.class)))
                .thenReturn(expired);

        assertThat(service.previewInvite("stale")).isEmpty();
    }

    @Test
    @DisplayName("previewInvite resolves a live code to its organization's name")
    void previewInviteLiveCodeResolvesName() {
        OrganizationInvite invite = OrganizationInvite.builder().organizationId(9L).expirationDate(LocalDateTime.now().plusHours(1)).build();
        when(jdbcTemplate.queryForObject(eq(SELECT_INVITE_BY_CODE_QUERY), eq(Map.of("code", "live")), any(OrganizationInviteRowMapper.class)))
                .thenReturn(invite);
        when(jdbcTemplate.queryForObject(eq(SELECT_ORGANIZATION_BY_ID_QUERY), eq(Map.of("id", 9L)), any(OrganizationRowMapper.class)))
                .thenReturn(Organization.builder().id(9L).name("Acme").build());

        assertThat(service.previewInvite("live")).contains("Acme");
    }

    // ── redeemInvite ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("redeemInvite refuses an unknown or expired code with the same message either way")
    void redeemInviteRefusesUnknownOrExpiredCode() {
        when(jdbcTemplate.queryForObject(eq(SELECT_INVITE_BY_CODE_QUERY), eq(Map.of("code", "bogus")), any(OrganizationInviteRowMapper.class)))
                .thenThrow(new EmptyResultDataAccessException(1));

        assertThatThrownBy(() -> service.redeemInvite("bogus", 42L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("invalid or has expired");
    }

    @Test
    @DisplayName("redeemInvite joins the organization, upgrades the role, and deletes the invite")
    void redeemInviteHappyPathUpgradesRole() {
        OrganizationInvite invite = OrganizationInvite.builder()
                .organizationId(9L).roleName("ROLE_ORGANIZATION_ADMIN").expirationDate(LocalDateTime.now().plusHours(1)).build();
        when(jdbcTemplate.queryForObject(eq(SELECT_INVITE_BY_CODE_QUERY), eq(Map.of("code", "live")), any(OrganizationInviteRowMapper.class)))
                .thenReturn(invite);
        when(roleRepo.getRoleByUserId(42L)).thenReturn(Role.builder().name("ROLE_USER").build());
        Organization organization = Organization.builder().id(9L).name("Acme").build();
        when(jdbcTemplate.queryForObject(eq(SELECT_ORGANIZATION_BY_ID_QUERY), eq(Map.of("id", 9L)), any(OrganizationRowMapper.class)))
                .thenReturn(organization);

        Organization joined = service.redeemInvite("live", 42L);

        assertThat(joined).isEqualTo(organization);
        verify(jdbcTemplate).update(eq(INSERT_MEMBERSHIP_QUERY), eq(Map.of("userId", 42L, "organizationId", 9L)));
        verify(userService).updateUserRole(eq(42L), eq("ROLE_ORGANIZATION_ADMIN"), isNull());
        verify(jdbcTemplate).update(eq(DELETE_INVITE_BY_CODE_QUERY), eq(Map.of("code", "live")));
    }

    @Test
    @DisplayName("redeemInvite never demotes a redeemer who already outranks the invite's role")
    void redeemInviteNeverDemotesAHigherRole() {
        OrganizationInvite invite = OrganizationInvite.builder()
                .organizationId(9L).roleName("ROLE_USER").expirationDate(LocalDateTime.now().plusHours(1)).build();
        when(jdbcTemplate.queryForObject(eq(SELECT_INVITE_BY_CODE_QUERY), eq(Map.of("code", "live")), any(OrganizationInviteRowMapper.class)))
                .thenReturn(invite);
        when(roleRepo.getRoleByUserId(42L)).thenReturn(Role.builder().name("ROLE_ADMIN").build());
        when(jdbcTemplate.queryForObject(eq(SELECT_ORGANIZATION_BY_ID_QUERY), eq(Map.of("id", 9L)), any(OrganizationRowMapper.class)))
                .thenReturn(Organization.builder().id(9L).name("Acme").build());

        service.redeemInvite("live", 42L);

        verify(userService, never()).updateUserRole(any(), any(), any());
    }

    // ── getOrganizationStats ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getOrganizationStats combines the member count with the customer/invoice rollups")
    void getOrganizationStatsCombinesRollups() {
        when(jdbcTemplate.queryForObject(eq(COUNT_ACTIVE_MEMBERS_QUERY), eq(Map.of("organizationId", 9L)), eq(Long.class)))
                .thenReturn(4L);
        Stats stats = new Stats();
        when(customerService.getStatsForOrganizations(Set.of(9L))).thenReturn(stats);
        when(customerService.getCustomerStatusBreakdownForOrganizations(Set.of(9L))).thenReturn(Map.of("ACTIVE", 3));

        OrganizationStats result = service.getOrganizationStats(9L);

        assertThat(result).isEqualTo(OrganizationStats.builder().memberCount(4).stats(stats).statusBreakdown(Map.of("ACTIVE", 3)).build());
    }
}
