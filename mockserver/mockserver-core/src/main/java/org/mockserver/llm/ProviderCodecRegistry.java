package org.mockserver.llm;

import org.mockserver.model.Provider;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class ProviderCodecRegistry {

    private static final ProviderCodecRegistry INSTANCE = new ProviderCodecRegistry();

    private final ConcurrentHashMap<Provider, ProviderCodec> codecs = new ConcurrentHashMap<>();

    public static ProviderCodecRegistry getInstance() {
        return INSTANCE;
    }

    public void register(ProviderCodec codec) {
        codecs.put(codec.provider(), codec);
    }

    public Optional<ProviderCodec> lookup(Provider provider) {
        return Optional.ofNullable(codecs.get(provider));
    }
}
