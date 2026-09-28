package com.hodi.modules.developments;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.enums.ConfigKey;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import com.hodi.security.principal.UserPrincipal;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Set;

/**
 * Who collects a development's money, and who manages its spending — the two decisions that are the bank's.
 *
 * <p>Both live on the development rather than platform-wide. The bank sells on an owner's behalf and holds the
 * buyers' money until every party is satisfied, which is why collection defaults to the bank; and it may
 * finance or run a project, which is why it may take over the spending. A different arrangement for one
 * project, or a change of model later, is then a setting on that project rather than a code change.
 *
 * <p>Only the bank's staff may change either. Changing them moves no money and touches no account that already
 * exists: it decides who may do what from now on — see {@code PaymentAccountService} for collection and
 * {@link DevelopmentVisibility#assertMayManageSpending} for spending.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DevelopmentMoneySettingsService {

    private static final Set<String> COLLECTION_MODES =
            Set.of(Development.COLLECTED_BY_BANK, Development.COLLECTED_BY_OWNER);
    private static final Set<String> SPENDING_MANAGERS =
            Set.of(Development.MANAGED_BY_OWNER, Development.MANAGED_BY_BANK);

    private final DevelopmentRepository developments;
    private final DevelopmentVisibility visibility;
    private final ConfigurationService configs;
    private final AuditService audit;

    /**
     * The settings as this caller sees them, with what they may do about them.
     *
     * @param mayChange          the caller is the bank's staff and may change both
     * @param mayManageSpending  the caller may record and pay costs here — for the finance tab's buttons
     */
    public record MoneySettings(
            String developmentId,
            String collectionMode,
            String spendingManagedBy,
            boolean mayChange,
            boolean mayManageSpending) {}

    public record SaveMoneySettingsRequest(
            @NotBlank(message = "Say who collects the money") String collectionMode,
            @NotBlank(message = "Say who manages the spending") String spendingManagedBy) {}

    @Transactional(readOnly = true)
    public MoneySettings find(String developmentHashId) {
        return toResponse(requireVisible(developmentHashId), AuthContext.require());
    }

    @Transactional
    public MoneySettings save(String developmentHashId, SaveMoneySettingsRequest request) {
        UserPrincipal caller = AuthContext.require();
        Development development = requireVisible(developmentHashId);
        if (!caller.isPlatformStaff()) {
            throw new HodiException("Only the bank decides who collects and who manages spending on a "
                    + "development.", HttpStatus.FORBIDDEN);
        }
        String collection = oneOf(request.collectionMode(), COLLECTION_MODES, "Who collects");
        String spending = oneOf(request.spendingManagedBy(), SPENDING_MANAGERS, "Who manages spending");

        String before = snapshot(development);
        development.setCollectionMode(collection);
        development.setSpendingManagedBy(spending);
        development.setUpdatedBy(AuthContext.username());
        developments.save(development);
        /*
         * Audited with both values before and after. The collection mode decides whose accounts a buyer's
         * money may land in, so a change to it is one of the questions an auditor asks first.
         */
        audit.record(AppConstant.ACTION_UPDATE, "DevelopmentMoneySettings", development.getId(), before,
                snapshot(development));
        log.info("{} set {} to collection={} spending={}", AuthContext.username(), development.getReference(),
                collection, spending);
        return toResponse(development, caller);
    }

    /**
     * What a new development starts with: whatever the platform-wide setting said until now.
     *
     * <p>{@code payments.collection.scope} answered this for every development at once. It still does for a
     * development nobody has configured yet, so a deployment where organisations collect keeps behaving that
     * way, and one where the platform collects — the default — starts every new project with the bank.
     */
    public String defaultCollectionMode() {
        return collectionModeFor(configs.getString(ConfigKey.PAYMENT_COLLECTION_SCOPE));
    }

    /** The platform-wide setting's answer, as a development's. Anything but ORGANISATION means the bank. */
    static String collectionModeFor(String scope) {
        return "ORGANISATION".equalsIgnoreCase(scope) ? Development.COLLECTED_BY_OWNER : Development.COLLECTED_BY_BANK;
    }

    private MoneySettings toResponse(Development d, UserPrincipal caller) {
        return new MoneySettings(HashIdUtil.encodeId(d.getId()), d.getCollectionMode(), d.getSpendingManagedBy(),
                caller.isPlatformStaff(), visibility.mayManageSpending(d, caller));
    }

    private Development requireVisible(String hashId) {
        Development development = developments.findById(HashIdUtil.decodeId(hashId))
                .orElseThrow(() -> new ResourceNotFoundException("Development", hashId));
        if (!visibility.mayRead(development, AuthContext.require())) {
            throw new ResourceNotFoundException("Development", hashId);
        }
        return development;
    }

    private static String oneOf(String value, Set<String> allowed, String what) {
        String v = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(v)) {
            throw new HodiException(what + " must be BANK or OWNER.", HttpStatus.BAD_REQUEST);
        }
        return v;
    }

    private static String snapshot(Development d) {
        return "collection=" + d.getCollectionMode() + " spending=" + d.getSpendingManagedBy();
    }
}
