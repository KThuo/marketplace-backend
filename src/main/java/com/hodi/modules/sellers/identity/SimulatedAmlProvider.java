package com.hodi.modules.sellers.identity;

import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.enums.ConfigKey;
import com.hodi.modules.sellers.SellerState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** AML screening, until there is a screening service to ask. See {@link SimulatedCoopAccountProvider}. */
@Component
@RequiredArgsConstructor
public class SimulatedAmlProvider implements IdentityCheckProvider {

    private final ConfigurationService configs;

    @Override
    public String code() {
        return SellerState.CHECK_AML;
    }

    @Override
    public boolean configured() {
        String url = configs.getString(ConfigKey.AML_BASE_URL);
        return url != null && !url.isBlank();
    }

    @Override
    public CheckResult run(CheckRequest request) {
        return CheckResult.pendingIntegration("AML screening");
    }
}
