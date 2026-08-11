package com.takesome.springsuite.module;

import java.security.Provider;
import java.security.Security;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

final class SuiteCryptoProviderBootstrap {
    private SuiteCryptoProviderBootstrap() {
    }

    static boolean installBouncyCastleProvider() {
        Provider existing = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME);
        if (existing != null) {
            return false;
        }
        Security.addProvider(new BouncyCastleProvider());
        return true;
    }
}
