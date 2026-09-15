package com.hodi.modules.sellers.identity;

import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.enums.ConfigKey;
import com.hodi.modules.sellers.SellerState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Co-op account validation, until there is a core-banking endpoint to ask.
 *
 * <p>It returns {@code PENDING_INTEGRATION} and pulls no details, which is the honest answer and also the
 * one that keeps the screen truthful: the applicant is told the account could not be checked and types
 * their details themselves, exactly as somebody without an account would.
 *
 * <p>What it does <em>not</em> do is invent a person. A stub that returned plausible names would put
 * fabricated identity data into an application the bank then approves on the strength of it.
 */
@Component
@RequiredArgsConstructor
public class SimulatedCoopAccountProvider implements IdentityCheckProvider {

    private final ConfigurationService configs;

    @Override
    public String code() {
        return SellerState.CHECK_COOP;
    }

    /** True the day somebody sets the base URL, which is the signal to write the real provider. */
    @Override
    public boolean configured() {
        String url = configs.getString(ConfigKey.COOP_ACCOUNT_BASE_URL);
        return url != null && !url.isBlank();
    }

    @Override
    public CheckResult run(CheckRequest request) {
        return CheckResult.pendingIntegration("Co-op account validation");
    }
}
