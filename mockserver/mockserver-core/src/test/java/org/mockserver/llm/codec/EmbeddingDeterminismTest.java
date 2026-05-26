package org.mockserver.llm.codec;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class EmbeddingDeterminismTest {

    @Test
    public void shouldProduceIdenticalVectorForSameInputAndSeed() {
        // given
        String input = "test input text";
        int dimensions = 128;
        long seed = 42;

        // when
        double[] vector1 = OpenAiChatCompletionsCodec.generateDeterministicVector(input, dimensions, seed);
        double[] vector2 = OpenAiChatCompletionsCodec.generateDeterministicVector(input, dimensions, seed);

        // then
        assertThat(vector1.length, is(dimensions));
        assertThat(vector2.length, is(dimensions));
        for (int i = 0; i < dimensions; i++) {
            assertThat("element " + i + " should be identical",
                vector1[i], is(vector2[i]));
        }
    }

    @Test
    public void shouldProduceL2NormalizedVector() {
        // given
        String input = "test input text";
        int dimensions = 256;
        long seed = 42;

        // when
        double[] vector = OpenAiChatCompletionsCodec.generateDeterministicVector(input, dimensions, seed);
        OpenAiChatCompletionsCodec.normalizeL2(vector);

        // then — L2 norm should be approximately 1.0
        double sumOfSquares = 0;
        for (double v : vector) {
            sumOfSquares += v * v;
        }
        double norm = Math.sqrt(sumOfSquares);
        assertThat(norm, is(closeTo(1.0, 1e-10)));
    }

    @Test
    public void shouldProduceIndependentVectorsForDifferentDimensions() {
        // given
        String input = "test input text";
        long seed = 42;

        // when
        double[] vector128 = OpenAiChatCompletionsCodec.generateDeterministicVector(input, 128, seed);
        double[] vector256 = OpenAiChatCompletionsCodec.generateDeterministicVector(input, 256, seed);

        // then — vectors have different lengths
        assertThat(vector128.length, is(128));
        assertThat(vector256.length, is(256));

        // The 128-dim vector must NOT be a prefix of the 256-dim vector — different
        // dimensions values seed the PRNG independently so the two vectors are
        // unrelated. At least one element in the shared prefix range must differ.
        boolean atLeastOneElementDiffers = false;
        for (int i = 0; i < 128; i++) {
            if (vector128[i] != vector256[i]) {
                atLeastOneElementDiffers = true;
                break;
            }
        }
        assertThat("128-dim vector must be independent of the 256-dim prefix",
            atLeastOneElementDiffers, is(true));
    }

    @Test
    public void shouldProduceDifferentVectorsForDifferentInputs() {
        // given
        int dimensions = 128;
        long seed = 42;

        // when
        double[] vector1 = OpenAiChatCompletionsCodec.generateDeterministicVector("input A", dimensions, seed);
        double[] vector2 = OpenAiChatCompletionsCodec.generateDeterministicVector("input B", dimensions, seed);

        // then — at least some elements should differ
        boolean anyDifferent = false;
        for (int i = 0; i < dimensions; i++) {
            if (vector1[i] != vector2[i]) {
                anyDifferent = true;
                break;
            }
        }
        assertThat("different inputs should produce different vectors", anyDifferent, is(true));
    }

    @Test
    public void shouldProduceDifferentVectorsForDifferentSeeds() {
        // given
        String input = "same input";
        int dimensions = 128;

        // when
        double[] vector1 = OpenAiChatCompletionsCodec.generateDeterministicVector(input, dimensions, 1);
        double[] vector2 = OpenAiChatCompletionsCodec.generateDeterministicVector(input, dimensions, 2);

        // then — at least some elements should differ
        boolean anyDifferent = false;
        for (int i = 0; i < dimensions; i++) {
            if (vector1[i] != vector2[i]) {
                anyDifferent = true;
                break;
            }
        }
        assertThat("different seeds should produce different vectors", anyDifferent, is(true));
    }

    @Test
    public void shouldProduceValuesInExpectedRange() {
        // given — pre-normalization values should be in [-1, 1]
        String input = "test";
        int dimensions = 1000;
        long seed = 0;

        // when
        double[] vector = OpenAiChatCompletionsCodec.generateDeterministicVector(input, dimensions, seed);

        // then — all values should be in [-1, 1] before normalization
        for (int i = 0; i < dimensions; i++) {
            assertThat("value at " + i + " should be >= -1", vector[i], is(greaterThanOrEqualTo(-1.0)));
            assertThat("value at " + i + " should be <= 1", vector[i], is(lessThanOrEqualTo(1.0)));
        }
    }
}
