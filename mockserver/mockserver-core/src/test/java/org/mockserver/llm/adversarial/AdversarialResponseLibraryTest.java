package org.mockserver.llm.adversarial;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.core.Is.is;

public class AdversarialResponseLibraryTest {

    @Test
    public void catalogIsNonEmptyAndWellFormed() {
        assertThat(AdversarialResponseLibrary.list().size(), greaterThanOrEqualTo(5));
        for (AdversarialResponseLibrary.Payload p : AdversarialResponseLibrary.list()) {
            assertThat(p.getId(), is(notNullValue()));
            assertThat(p.getCategory(), is(notNullValue()));
            assertThat(p.getDescription(), is(notNullValue()));
            assertThat(p.getText(), is(notNullValue())); // may be empty (empty_response payload)
        }
    }

    @Test
    public void getReturnsDeterministicPayloadText() {
        String first = AdversarialResponseLibrary.get("prompt_injection_ignore_instructions").get().getText();
        String second = AdversarialResponseLibrary.get("prompt_injection_ignore_instructions").get().getText();
        assertThat(first, is(second));
        assertThat(first.toLowerCase().contains("ignore"), is(true));
    }

    @Test
    public void idsIncludeKnownCategories() {
        assertThat(AdversarialResponseLibrary.ids(), hasItem("jailbreak_persona"));
        assertThat(AdversarialResponseLibrary.ids(), hasItem("malformed_json_payload"));
    }

    @Test
    public void unknownIdReturnsEmpty() {
        assertThat(AdversarialResponseLibrary.get("does_not_exist").isPresent(), is(false));
        assertThat(AdversarialResponseLibrary.get(null).isPresent(), is(false));
    }

    @Test
    public void overlongPayloadIsLarge() {
        assertThat(AdversarialResponseLibrary.get("overlong_repetition").get().getText().length(), greaterThan(1000));
    }
}
