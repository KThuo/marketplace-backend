package com.hodi.modules.payments;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.logging.RequestAction;
import com.hodi.modules.payments.PaymentTypeDtos.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The channels, and the accounts an organisation collects into.
 *
 * <p>Two subjects under one path because they are read together and configured in one sitting: the catalogue
 * says what is possible, the accounts say what is happening.
 *
 * <p><strong>Every write on an account takes a one-time code</strong>, sent to the organisation's own contact
 * number. The permission decides who may ask; the code decides whether the organisation knows. Withdrawing an
 * account is the exception, and deliberately so — it sends money nowhere, and it is what somebody does the
 * moment they suspect an account has been tampered with.
 */
@RestController
@RequestMapping("/api/v1/payment-types")
@RequiredArgsConstructor
public class PaymentTypeController {

    private final PaymentTypeService channels;
    private final PaymentAccountService accounts;

    // ── the accounts ──────────────────────────────────────────────────────────

    @GetMapping("/list")
    @PreAuthorize("hasAuthority('PAYMENT_TYPES_VIEW')")
    public ApiResponse<PagedResponse<AccountResponse>> list(@ModelAttribute AccountListRequest request) {
        return ApiResponse.success(accounts.list(request));
    }

    @GetMapping("/find/{hashId}")
    @PreAuthorize("hasAuthority('PAYMENT_TYPES_VIEW')")
    public ApiResponse<AccountResponse> find(@PathVariable String hashId) {
        return ApiResponse.success(accounts.find(hashId));
    }

    /**
     * A channel's configuration — where it points and what it authenticates with.
     *
     * <p>Behind {@code PAYMENT_TYPES_MANAGE}, the same permission the rest of the catalogue's writes use
     * and one the platform holds: the catalogue is shared by every organisation, so where a channel points
     * is not one organisation's decision. Secrets read back masked, never in clear.
     */
    @GetMapping("/{hashId}/configuration")
    @PreAuthorize("hasAuthority('PAYMENT_TYPES_MANAGE')")
    public ApiResponse<PaymentTypeDtos.ChannelConfiguration> configuration(@PathVariable String hashId) {
        return ApiResponse.success(channels.configurationOf(hashId));
    }

    @PostMapping("/{hashId}/configuration")
    @PreAuthorize("hasAuthority('PAYMENT_TYPES_MANAGE')")
    public ApiResponse<PaymentTypeDtos.ChannelConfiguration> configure(
            @PathVariable String hashId,
            @RequestBody PaymentTypeDtos.SaveChannelConfiguration request) {
        return ApiResponse.success(channels.configure(hashId, request));
    }

    /**
     * What this owner may be given.
     *
     * <p>Platform staff name the organisation; everybody else gets their own. The list carries nothing about
     * what is absent from it.
     */
    @GetMapping("/accounts/setup")
    @PreAuthorize("hasAuthority('PAYMENT_TYPES_MANAGE')")
    public ApiResponse<PaymentTypeDtos.AccountSetupContext> setupContext() {
        return ApiResponse.success(accounts.setupContext());
    }

    @GetMapping("/assignable")
    @PreAuthorize("hasAuthority('PAYMENT_TYPES_MANAGE')")
    public ApiResponse<List<AssignableChannel>> assignable(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String institutionId) {
        return ApiResponse.success(accounts.assignable(tenantId, institutionId));
    }

    /** The developments an account may be narrowed to, for the form. */
    @GetMapping("/developments")
    @PreAuthorize("hasAuthority('PAYMENT_TYPES_MANAGE')")
    public ApiResponse<List<DevelopmentOption>> developments(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String institutionId) {
        return ApiResponse.success(accounts.developmentOptions(tenantId, institutionId));
    }

    /**
     * The accounts money for a booking may be recorded through.
     *
     * <p>Gated on receiving payments rather than on payment types, because this is the shape the receive
     * form loads and the person at the counter need not be the person who configures accounts.
     */
    @GetMapping("/offered/{bookingId}")
    @PreAuthorize("hasAuthority('PAYMENTS_RECEIVE')")
    public ApiResponse<List<OfferedAccount>> offered(@PathVariable String bookingId) {
        return ApiResponse.success(accounts.offered(bookingId));
    }

    /** The duplicate check the form runs before a code is spent. */
    @GetMapping("/check-account")
    @PreAuthorize("hasAuthority('PAYMENT_TYPES_MANAGE')")
    public ApiResponse<AccountCheck> checkAccount(@RequestParam String accountNo,
                                                  @RequestParam(required = false) String excluding) {
        return ApiResponse.success(accounts.checkAccount(accountNo, excluding));
    }

    /**
     * Texts the organisation a code.
     *
     * <p>Its own endpoint rather than a side effect of opening the form, so the wizard asks for it when the
     * person reaches the last step — a code issued three steps earlier would have half expired by the time it
     * was typed.
     */
    @PostMapping("/otp")
    @PreAuthorize("hasAuthority('PAYMENT_TYPES_MANAGE')")
    @RequestAction("REQUEST_PAYMENT_ACCOUNT_CODE")
    public ApiResponse<OtpIssued> requestCode(@RequestBody(required = false) OtpRequest request) {
        OtpIssued issued = accounts.requestCode(request == null ? new OtpRequest(null, null) : request);
        return ApiResponse.success("Code sent to " + issued.sentTo(), issued);
    }

    @PostMapping("/create")
    @PreAuthorize("hasAuthority('PAYMENT_TYPES_MANAGE')")
    @RequestAction("SET_UP_PAYMENT_ACCOUNT")
    public ApiResponse<AccountResponse> assign(@Valid @RequestBody SaveAccountRequest request) {
        AccountResponse saved = accounts.assign(request);
        return ApiResponse.success(saved.name() + " set up for " + saved.ownerName(), saved);
    }

    @PostMapping("/update/{hashId}")
    @PreAuthorize("hasAuthority('PAYMENT_TYPES_MANAGE')")
    @RequestAction("UPDATE_PAYMENT_ACCOUNT")
    public ApiResponse<AccountResponse> update(@PathVariable String hashId,
                                               @Valid @RequestBody SaveAccountRequest request) {
        return ApiResponse.success("Saved", accounts.update(hashId, request));
    }

    @PostMapping("/{hashId}/status")
    @PreAuthorize("hasAuthority('PAYMENT_TYPES_MANAGE')")
    @RequestAction("SET_PAYMENT_ACCOUNT_STATUS")
    public ApiResponse<Void> setStatus(@PathVariable String hashId, @RequestParam boolean active) {
        return ApiResponse.success(accounts.setStatus(hashId, active), null);
    }

    // ── the catalogue ─────────────────────────────────────────────────────────

    @GetMapping("/catalogue")
    @PreAuthorize("hasAuthority('PAYMENT_TYPES_VIEW')")
    public ApiResponse<PagedResponse<ChannelResponse>> catalogue(@ModelAttribute ChannelListRequest request) {
        return ApiResponse.success(channels.list(request));
    }

    @GetMapping("/catalogue/find/{hashId}")
    @PreAuthorize("hasAuthority('PAYMENT_TYPES_VIEW')")
    public ApiResponse<ChannelResponse> channel(@PathVariable String hashId) {
        return ApiResponse.success(channels.find(hashId));
    }

    /**
     * Rename or reorder a channel.
     *
     * <p>Its behaviour is not editable and there is no create: whether a channel is a phone prompt or an
     * inbound credit is the gateway's fact, and a row with no gateway behind it is a payment method that
     * cannot collect. Channels arrive by migration.
     */
    @PostMapping("/catalogue/update/{hashId}")
    @PreAuthorize("hasAuthority('PAYMENT_CATALOGUE_MANAGE')")
    @RequestAction("UPDATE_PAYMENT_METHOD")
    public ApiResponse<ChannelResponse> updateChannel(@PathVariable String hashId,
                                                      @Valid @RequestBody UpdateChannelRequest request) {
        return ApiResponse.success("Saved", channels.update(hashId, request));
    }

    @PostMapping("/catalogue/{hashId}/status")
    @PreAuthorize("hasAuthority('PAYMENT_CATALOGUE_MANAGE')")
    @RequestAction("SET_PAYMENT_METHOD_STATUS")
    public ApiResponse<Void> setChannelStatus(@PathVariable String hashId, @RequestParam boolean active) {
        return ApiResponse.success(channels.setStatus(hashId, active), null);
    }
}
