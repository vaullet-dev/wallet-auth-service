package dev.vaullet.auth.account.service;

/**
 * What {@code PUT /v1/accounts/me} answered with: the caller's account, and whether this call is the
 * one that created it. The controller turns the second into 201 or 200; the service does not know
 * about status codes.
 */
public record OwnAccount(Account account, boolean created) {}
