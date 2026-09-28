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

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

/**
 * Who collects a development's money, who manages its spending, and what a sale here pays the bank and the
 * agent who brought the buyer — the decisions that are the bank's.
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
    private static final Set<String> AGENT_FEE_BEARERS =
            Set.of(Development.AGENT_PAID_BY_SELLER, Development.AGENT_PAID_BY_BANK);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final DevelopmentRepository developments;
    private final DevelopmentVisibility visibility;
    private final ConfigurationService configs;
    private final AuditService audit;

    /**
     * The settings as this caller sees them, with what they may do about them.
     *
     * @param mayChange                    the caller is the bank's staff and may change everything here
     * @param mayManageSpending            the caller may record and pay costs here — for the finance tab's buttons
     * @param bankCommissionPercent        this development's rate, or null for the platform default
     * @param agentCommissionPercent       likewise for the agent who brought the buyer
     * @param agentCommissionPaidBy        SELLER or BANK: whose money the agent's fee comes out of
     * @param defaultBankCommissionPercent what null means today, so the card can say "1.5% (platform default)"
     */
    public record MoneySettings(
            String developmentId,
            String collectionMode,
            String spendingManagedBy,
            boolean mayChange,
            boolean mayManageSpending,
            BigDecimal bankCommissionPercent,
            BigDecimal agentCommissionPercent,
            String agentCommissionPaidBy,
            BigDecimal defaultBankCommissionPercent,
            BigDecimal defaultAgentCommissionPercent) {}

    /**
     * The three commission fields are optional: absent or null means "the platform default", which is what a
     * development starts with. A rate is a percentage between 0 and 100 with at most three decimals.
     */
    public record SaveMoneySettingsRequest(
            @NotBlank(message = "Say who collects the money") String collectionMode,
            @NotBlank(message = "Say who manages the spending") String spendingManagedBy,
            BigDecimal bankCommissionPercent,
            BigDecimal agentCommissionPercent,
            String agentCommissionPaidBy) {
        public SaveMoneySettingsRequest(String collectionMode, String spendingManagedBy) {
            this(collectionMode, spendingManagedBy, null, null, null);
        }
    }

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
        BigDecimal bankRate = rate(request.bankCommissionPercent(), "The bank's commission");
        BigDecimal agentRate = rate(request.agentCommissionPercent(), "The agent's commission");
        String agentPaidBy = request.agentCommissionPaidBy() == null || request.agentCommissionPaidBy().isBlank()
                ? null : oneOf(request.agentCommissionPaidBy(), AGENT_FEE_BEARERS, "Who pays the agent");

        String before = snapshot(development);
        development.setCollectionMode(collection);
        development.setSpendingManagedBy(spending);
        development.setBankCommissionPercent(bankRate);
        development.setAgentCommissionPercent(agentRate);
        development.setAgentCommissionPaidBy(agentPaidBy);
        development.setUpdatedBy(AuthContext.username());
        developments.save(development);
        /*
         * Audited with both values before and after. The collection mode decides whose accounts a buyer's
         * money may land in, so a change to it is one of the questions an auditor asks first.
         */
        audit.record(AppConstant.ACTION_UPDATE, "DevelopmentMoneySettings", development.getId(), before,
                snapshot(development));
        log.info("{} set {} to {}", AuthContext.username(), development.getReference(), snapshot(development));
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
                caller.isPlatformStaff(), visibility.mayManageSpending(d, caller),
                d.getBankCommissionPercent(), d.getAgentCommissionPercent(), d.getAgentCommissionPaidBy(),
                percent(ConfigKey.COMMISSION_RATE_PERCENT), percent(ConfigKey.AGENT_COMMISSION_RATE_PERCENT));
    }

    /** The platform default as a number, or zero when what is configured is not one. */
    private BigDecimal percent(ConfigKey key) {
        String raw = configs.getString(key);
        try {
            return new BigDecimal(raw == null || raw.isBlank() ? "0" : raw.trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /** Null stays null — the default. Anything else is a percentage, and says so if it is not. */
    private static BigDecimal rate(BigDecimal value, String what) {
        if (value == null) return null;
        if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(HUNDRED) > 0) {
            throw new HodiException(what + " is a percentage between 0 and 100.", HttpStatus.BAD_REQUEST);
        }
        if (value.stripTrailingZeros().scale() > 3) {
            throw new HodiException(what + " has at most three decimal places.", HttpStatus.BAD_REQUEST);
        }
        return value.setScale(3, java.math.RoundingMode.UNNECESSARY);
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
        return "collection=" + d.getCollectionMode() + " spending=" + d.getSpendingManagedBy()
                + " bankRate=" + (d.getBankCommissionPercent() == null ? "default" : d.getBankCommissionPercent())
                + " agentRate=" + (d.getAgentCommissionPercent() == null ? "default" : d.getAgentCommissionPercent())
                + " agentPaidBy=" + d.agentFeeBorneBy();
    }
}
