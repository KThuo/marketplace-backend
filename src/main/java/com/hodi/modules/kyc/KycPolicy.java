package com.hodi.modules.kyc;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.enums.ConfigKey;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.profiles.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * When clearance becomes a precondition to listing (M8 slice 2).
 *
 * <h2>The policy, and where it is applied</h2>
 *
 * <p>§13 built the gate and left this open: the machinery drops a seller's listing permissions unless their
 * profile's {@code kyc_status} clears, but nothing said <em>when</em> that should apply. It now does, as a
 * configuration row naming the seller types it covers.
 *
 * <p>Applied at the moment a seller type is set rather than re-derived on every permission resolution. The
 * resolver runs on every login and every token refresh; making it consult a configuration string and a
 * tenant row would put two more reads on the hottest path in the application to answer a question that
 * changes when somebody edits an organisation. So the answer is written down instead: a tenant given a
 * covered type has its people moved from {@code NOT_REQUIRED} — never asked — to {@code PENDING}, and
 * PENDING is what fails the gate.
 *
 * <h2>Why the two states are not the same</h2>
 *
 * <p>{@code NOT_REQUIRED} means nobody has asked this organisation for anything, which is the honest state
 * for a platform administrator and for a seller onboarded before the policy existed. {@code PENDING} means
 * the platform has asked and is waiting. Collapsing them would make "we changed the rules" indistinguishable
 * from "you have not answered", and only one of those is the seller's problem.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KycPolicy {

    private final ConfigurationService configs;
    private final UserProfileRepository profiles;

    /** Whether this seller type must be cleared before it may list. */
    public boolean isRequiredFor(String sellerType) {
        if (sellerType == null || sellerType.isBlank()) return false;
        return requiredTypes().contains(sellerType.trim().toUpperCase());
    }

    /** The configured set, parsed. Empty means nothing is mandatory. */
    public Set<String> requiredTypes() {
        String raw = configs.getString(ConfigKey.KYC_REQUIRED_SELLER_TYPES);
        if (raw == null || raw.isBlank()) return Set.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toUpperCase)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Applies the policy to one organisation's people, after its seller type is set or changed.
     *
     * <p>Only ever moves a profile <em>into</em> {@code PENDING} from {@code NOT_REQUIRED}. An organisation
     * already approved stays approved when its type is edited — re-asking a cleared seller for their pack
     * because somebody fixed a typo on their profile would be a decision the platform did not make. And a
     * type that stops being covered does not silently clear anybody either: withdrawing a requirement is not
     * the same as passing it, and that reversal should be a Compliance decision with a name on it.
     *
     * @return how many profiles moved
     */
    @Transactional
    public int applyTo(Long tenantId, String sellerType) {
        if (tenantId == null || !isRequiredFor(sellerType)) return 0;

        List<UserProfile> people = profiles.findLiveByTenant(tenantId);
        int moved = 0;
        for (UserProfile profile : people) {
            if (!AppConstant.KYC_NOT_REQUIRED.equals(profile.getKycStatus())) continue;
            profile.setKycStatus(AppConstant.KYC_PENDING);
            profile.setKycDecidedAt(null);
            profiles.save(profile);
            moved++;
        }
        if (moved > 0) {
            log.info("KYC is required for {} — {} profile(s) of tenant {} moved to PENDING",
                    sellerType, moved, tenantId);
        }
        return moved;
    }

    /**
     * Refuses a seller type that is not one of the six.
     *
     * <p>The table's own CHECK says the same. This is what turns it into a sentence, and it is also what
     * stops a typo becoming a seller nobody has a KYC checklist for — an organisation of type "Compnay"
     * would pass a free-text column and then be unable to assemble a pack at all.
     */
    public String normalise(String sellerType) {
        if (sellerType == null || sellerType.isBlank()) return null;
        String value = sellerType.trim().toUpperCase();
        if (!ALL.contains(value)) {
            throw new HodiException(
                    "Choose one of: " + String.join(", ", ALL) + ".", HttpStatus.BAD_REQUEST);
        }
        return value;
    }

    /** The six. Also the keys of the KYC requirement catalogue — one list, or a seller cannot be cleared. */
    public static final Set<String> ALL = new LinkedHashSet<>(List.of(
            AppConstant.SELLER_INDIVIDUAL, AppConstant.SELLER_COMPANY, AppConstant.SELLER_SACCO,
            AppConstant.SELLER_DEVELOPER, AppConstant.SELLER_AGENCY, AppConstant.SELLER_GOVERNMENT));
}
