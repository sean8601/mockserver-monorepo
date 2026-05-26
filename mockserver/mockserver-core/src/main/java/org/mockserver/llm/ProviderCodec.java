package org.mockserver.llm;

import org.mockserver.model.*;

import java.util.List;

public interface ProviderCodec {

    Provider provider();

    String apiVersion();

    default HttpResponse encode(Completion completion, String model) {
        throw new UnsupportedOperationException("encode not implemented for provider " + provider());
    }

    default List<SseEvent> encodeStreaming(Completion completion, String model, StreamingPhysics physics) {
        throw new UnsupportedOperationException("encodeStreaming not implemented for provider " + provider());
    }

    default HttpResponse encodeEmbedding(EmbeddingResponse embedding, String input) {
        throw new UnsupportedOperationException("encodeEmbedding not implemented for provider " + provider());
    }

    default ParsedConversation decode(HttpRequest request) {
        throw new UnsupportedOperationException("decode not implemented for provider " + provider());
    }
}
