package com.hodi.modules.profiles;

import com.hodi.common.AppConstant;
import com.hodi.modules.usergroups.UserGroup;
import com.hodi.modules.usertypes.UserType;
import com.hodi.security.principal.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creating and retiring profiles.
 *
 * <p>Exists so the five places that mint a user — staff administration, seller onboarding, institution
 * onboarding, buyer self-registration and the seeder — do not each assemble a profile row from a user type
 * and an organisation. Five copies of that assembly is five chances to leave {@code profile_type} out of step
 * with the user type's actor class, which is the one field here that decides structure rather than
 * presentation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserProfileService {

    private final UserProfileRepository repository;

    /**
     * The first profile for a newly created person.
     *
     * <p>{@code profileType} is copied from the user type rather than passed in: the caller already chose a
     * user type, and letting them also name the actor class is letting them contradict themselves. A
     * {@code SELLER} type in a profile marked {@code PLATFORM} would be an account whose permissions resolve
     * against one axis and whose visibility resolves against another.
     *
     * <p>Marked default, because it is the only one. Later profiles for the same person go through
     * {@link #addProfile}, which never moves the default — where a session lands is the holder's choice, not
     * a side effect of somebody adding them to an organisation.
     */
    @Transactional
    public UserProfile provisionFirst(Long userId, UserType type, UserGroup group,
                                      Long tenantId, String tenantName,
                                      Long institutionId, String institutionName) {
        return save(userId, type, group, tenantId, tenantName, institutionId, institutionName, true);
    }

    @Transactional
    public UserProfile addProfile(Long userId, UserType type, UserGroup group,
                                  Long tenantId, String tenantName,
                                  Long institutionId, String institutionName) {
        return save(userId, type, group, tenantId, tenantName, institutionId, institutionName, false);
    }

    private UserProfile save(Long userId, UserType type, UserGroup group,
                             Long tenantId, String tenantName,
                             Long institutionId, String institutionName, boolean isDefault) {
        UserProfile profile = UserProfile.builder()
                .userId(userId)
                .profileType(type.getActorClass())
                .userTypeId(type.getId())
                .userTypeCode(type.getCode())
                .userTypeName(type.getName())
                .userGroupId(group == null ? null : group.getId())
                .userGroupName(group == null ? null : group.getName())
                .tenantId(tenantId)
                .tenantName(tenantName)
                .institutionId(institutionId)
                .institutionName(institutionName)
                .kycStatus(AppConstant.KYC_NOT_REQUIRED)
                .defaultProfile(isDefault)
                .status(AppConstant.STATUS_ACTIVE)
                .statusFlag(AppConstant.FLAG_ACTIVE)
                .createdBy(AuthContext.username())
                .build();
        return repository.save(profile);
    }
}
