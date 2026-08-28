package com.fansea.ai.openapi.credential;

public sealed interface CachedCredentialResult {

    record Hit(ApiCredentialResolver.CachedCredential credential) implements CachedCredentialResult {
    }

    record Missing() implements CachedCredentialResult {
    }

    record NotCached() implements CachedCredentialResult {
    }
}
