package org.mockserver.llm.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.mockserver.model.EmbeddingResponse;
import org.mockserver.model.HttpResponse;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class OpenAiEmbeddingResponseTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final OpenAiChatCompletionsCodec codec = new OpenAiChatCompletionsCodec();

    @Test
    public void shouldProduceCorrectStructure() throws Exception {
        // given
        EmbeddingResponse embedding = EmbeddingResponse.embedding()
            .withDimensions(4)
            .withDeterministicFromInput(true)
            .withSeed(42L);

        // when
        HttpResponse response = codec.encodeEmbedding(embedding, "test input");

        // then
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getFirstHeader("content-type"), is("application/json"));

        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        assertThat(root.get("object").asText(), is("list"));
        assertThat(root.get("model").asText(), is("text-embedding-3-small"));

        // data array
        JsonNode data = root.get("data");
        assertThat(data.isArray(), is(true));
        assertThat(data.size(), is(1));

        JsonNode embeddingObj = data.get(0);
        assertThat(embeddingObj.get("object").asText(), is("embedding"));
        assertThat(embeddingObj.get("index").asInt(), is(0));

        // embedding vector
        JsonNode embeddingArray = embeddingObj.get("embedding");
        assertThat(embeddingArray.isArray(), is(true));
        assertThat(embeddingArray.size(), is(4));
    }

    @Test
    public void shouldProduceVectorWithCorrectDimensions() throws Exception {
        // given
        EmbeddingResponse embedding = EmbeddingResponse.embedding()
            .withDimensions(768)
            .withDeterministicFromInput(true);

        // when
        HttpResponse response = codec.encodeEmbedding(embedding, "test input");

        // then
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        JsonNode embeddingArray = root.get("data").get(0).get("embedding");
        assertThat(embeddingArray.size(), is(768));
    }

    @Test
    public void shouldDefaultDimensionsTo1536() throws Exception {
        // given
        EmbeddingResponse embedding = EmbeddingResponse.embedding()
            .withDeterministicFromInput(true);

        // when
        HttpResponse response = codec.encodeEmbedding(embedding, "test input");

        // then
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        JsonNode embeddingArray = root.get("data").get(0).get("embedding");
        assertThat(embeddingArray.size(), is(1536));
    }

    @Test
    public void shouldProduceL2NormalizedVector() throws Exception {
        // given
        EmbeddingResponse embedding = EmbeddingResponse.embedding()
            .withDimensions(128)
            .withDeterministicFromInput(true)
            .withSeed(42L);

        // when
        HttpResponse response = codec.encodeEmbedding(embedding, "test input");

        // then
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        JsonNode embeddingArray = root.get("data").get(0).get("embedding");

        double sumOfSquares = 0;
        for (JsonNode val : embeddingArray) {
            double v = val.asDouble();
            sumOfSquares += v * v;
        }
        double norm = Math.sqrt(sumOfSquares);
        assertThat(norm, is(closeTo(1.0, 1e-6)));
    }

    @Test
    public void shouldProduceDeterministicVectorFromInput() throws Exception {
        // given
        EmbeddingResponse embedding = EmbeddingResponse.embedding()
            .withDimensions(32)
            .withDeterministicFromInput(true)
            .withSeed(42L);

        // when
        HttpResponse response1 = codec.encodeEmbedding(embedding, "same input");
        HttpResponse response2 = codec.encodeEmbedding(embedding, "same input");

        // then
        JsonNode vec1 = OBJECT_MAPPER.readTree(response1.getBodyAsString()).get("data").get(0).get("embedding");
        JsonNode vec2 = OBJECT_MAPPER.readTree(response2.getBodyAsString()).get("data").get(0).get("embedding");
        for (int i = 0; i < 32; i++) {
            assertThat("element " + i, vec1.get(i).asDouble(), is(vec2.get(i).asDouble()));
        }
    }

    @Test
    public void shouldIncludeApproximateUsage() throws Exception {
        // given
        EmbeddingResponse embedding = EmbeddingResponse.embedding()
            .withDimensions(4)
            .withDeterministicFromInput(true);

        // when — input "test input text" has 15 chars, approx 15/4 ≈ 3 tokens
        HttpResponse response = codec.encodeEmbedding(embedding, "test input text");

        // then
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        JsonNode usage = root.get("usage");
        assertThat(usage.get("prompt_tokens").asInt(), is(greaterThan(0)));
        assertThat(usage.get("total_tokens").asInt(), is(usage.get("prompt_tokens").asInt()));
    }

    @Test
    public void shouldHandleNullInput() throws Exception {
        // given
        EmbeddingResponse embedding = EmbeddingResponse.embedding()
            .withDimensions(4);

        // when — deterministicFromInput is not set (default false), so random vector
        HttpResponse response = codec.encodeEmbedding(embedding, null);

        // then
        assertThat(response.getStatusCode(), is(200));
        JsonNode root = OBJECT_MAPPER.readTree(response.getBodyAsString());
        JsonNode embeddingArray = root.get("data").get(0).get("embedding");
        assertThat(embeddingArray.size(), is(4));
    }
}
