package com.hodi.modules.sellers.identity;

import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.enums.ConfigKey;
import com.hodi.modules.sellers.SellerState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** The IPRS lookup, until there is one to make. See {@link SimulatedCoopAccountProvider}. */
@Component
@RequiredArgsConstructor
public class SimulatedIprsProvider implements IdentityCheckProvider {

    private final ConfigurationService configs;

    @Override
    public String code() {
        return SellerState.CHECK_IPRS;
    }

    @Override
    public boolean configured() {
        String url = configs.getString(ConfigKey.IPRS_BASE_URL);
        return url != null && !url.isBlank();
    }

    @Override
    public CheckResult run(CheckRequest request) {
        return CheckResult.pendingIntegration("The IPRS lookup");
    }
}
