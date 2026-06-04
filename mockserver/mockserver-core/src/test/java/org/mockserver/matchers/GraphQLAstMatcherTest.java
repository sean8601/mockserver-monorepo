package org.mockserver.matchers;

import org.junit.Test;
import org.mockserver.model.GraphQLBody;
import org.mockserver.model.SelectionSetMatchType;

import java.util.Set;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class GraphQLAstMatcherTest {

    // --- extractOperationType ---

    @Test
    public void shouldExtractQueryOperationType() {
        assertThat(GraphQLAstMatcher.extractOperationType("query { hero { name } }"), is("query"));
    }

    @Test
    public void shouldExtractMutationOperationType() {
        assertThat(GraphQLAstMatcher.extractOperationType("mutation CreateUser { createUser { id } }"), is("mutation"));
    }

    @Test
    public void shouldExtractSubscriptionOperationType() {
        assertThat(GraphQLAstMatcher.extractOperationType("subscription OnUpdate { userUpdated { id } }"), is("subscription"));
    }

    @Test
    public void shouldDefaultToQueryForShorthandSyntax() {
        assertThat(GraphQLAstMatcher.extractOperationType("{ hero { name } }"), is("query"));
    }

    @Test
    public void shouldDefaultToQueryForNull() {
        assertThat(GraphQLAstMatcher.extractOperationType(null), is("query"));
    }

    @Test
    public void shouldIgnoreCommentsBeforeOperationType() {
        assertThat(GraphQLAstMatcher.extractOperationType("# a comment\nmutation Foo { bar }"), is("mutation"));
    }

    @Test
    public void shouldHandleCaseInsensitiveOperationType() {
        assertThat(GraphQLAstMatcher.extractOperationType("QUERY { hero { name } }"), is("query"));
        assertThat(GraphQLAstMatcher.extractOperationType("Mutation CreateUser { createUser { id } }"), is("mutation"));
    }

    // --- extractOperationName ---

    @Test
    public void shouldExtractOperationName() {
        assertThat(GraphQLAstMatcher.extractOperationName("query GetHero { hero { name } }"), is("GetHero"));
    }

    @Test
    public void shouldReturnEmptyStringWhenNoOperationName() {
        assertThat(GraphQLAstMatcher.extractOperationName("query { hero { name } }"), is(""));
    }

    @Test
    public void shouldReturnEmptyStringForShorthandQuery() {
        assertThat(GraphQLAstMatcher.extractOperationName("{ hero { name } }"), is(""));
    }

    @Test
    public void shouldReturnEmptyStringForNull() {
        assertThat(GraphQLAstMatcher.extractOperationName(null), is(""));
    }

    @Test
    public void shouldExtractMutationOperationName() {
        assertThat(GraphQLAstMatcher.extractOperationName("mutation CreateUser($input: Input!) { createUser(input: $input) { id } }"), is("CreateUser"));
    }

    // --- extractTopLevelFields ---

    @Test
    public void shouldExtractSingleField() {
        Set<String> fields = GraphQLAstMatcher.extractTopLevelFields("query { hero { name } }");
        assertThat(fields.size(), is(1));
        assertThat(fields.contains("hero"), is(true));
    }

    @Test
    public void shouldExtractMultipleFields() {
        Set<String> fields = GraphQLAstMatcher.extractTopLevelFields("query { hero { name } starships { id } }");
        assertThat(fields.size(), is(2));
        assertThat(fields.contains("hero"), is(true));
        assertThat(fields.contains("starships"), is(true));
    }

    @Test
    public void shouldExtractFieldsFromShorthandQuery() {
        Set<String> fields = GraphQLAstMatcher.extractTopLevelFields("{ user { name } posts { title } }");
        assertThat(fields.size(), is(2));
        assertThat(fields.contains("user"), is(true));
        assertThat(fields.contains("posts"), is(true));
    }

    @Test
    public void shouldNotIncludeNestedFieldNames() {
        Set<String> fields = GraphQLAstMatcher.extractTopLevelFields("query { hero { name age address { city } } }");
        assertThat(fields.size(), is(1));
        assertThat(fields.contains("hero"), is(true));
        assertThat(fields.contains("name"), is(false));
        assertThat(fields.contains("age"), is(false));
        assertThat(fields.contains("address"), is(false));
        assertThat(fields.contains("city"), is(false));
    }

    @Test
    public void shouldHandleFieldsWithArguments() {
        Set<String> fields = GraphQLAstMatcher.extractTopLevelFields("query { user(id: 1) { name } }");
        assertThat(fields.contains("user"), is(true));
    }

    @Test
    public void shouldReturnEmptySetForNull() {
        assertThat(GraphQLAstMatcher.extractTopLevelFields(null).isEmpty(), is(true));
    }

    @Test
    public void shouldReturnEmptySetForNoSelectionSet() {
        assertThat(GraphQLAstMatcher.extractTopLevelFields("query GetHero").isEmpty(), is(true));
    }

    @Test
    public void shouldSkipOnKeyword() {
        Set<String> fields = GraphQLAstMatcher.extractTopLevelFields("query { hero { name } ... on Droid { primaryFunction } }");
        assertThat(fields.contains("hero"), is(true));
        assertThat(fields.contains("on"), is(false));
    }

    @Test
    public void shouldHandleFieldsWithVariableDefinitions() {
        Set<String> fields = GraphQLAstMatcher.extractTopLevelFields("query GetUser($id: ID!) { user(id: $id) { name } }");
        assertThat(fields.contains("user"), is(true));
        assertThat(fields.size(), is(1));
    }

    // --- extractQueryFromBody ---

    @Test
    public void shouldExtractQueryFromJsonWrapper() {
        String body = "{\"query\": \"{ users { id name } }\", \"variables\": {}}";
        assertThat(GraphQLAstMatcher.extractQueryFromBody(body), is("{ users { id name } }"));
    }

    @Test
    public void shouldReturnRawGraphQLQueryAsIs() {
        String body = "query { users { id } }";
        assertThat(GraphQLAstMatcher.extractQueryFromBody(body), is("query { users { id } }"));
    }

    @Test
    public void shouldHandleEscapedNewlinesInJsonQuery() {
        String body = "{\"query\": \"{\\n  users {\\n    id\\n  }\\n}\"}";
        assertThat(GraphQLAstMatcher.extractQueryFromBody(body), is("{\n  users {\n    id\n  }\n}"));
    }

    // --- AST_SUBSET matching ---

    @Test
    public void astSubsetShouldMatchWhenExpectedFieldsPresent() {
        GraphQLBody body = GraphQLBody.graphQL("query { hero { name } }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_SUBSET)
            .withFields("hero");
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        // Actual has more fields — subset matches
        assertThat(matcher.matches("query { hero { name } starships { id } }"), is(true));
    }

    @Test
    public void astSubsetShouldNotMatchWhenExpectedFieldMissing() {
        GraphQLBody body = GraphQLBody.graphQL("query { hero { name } }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_SUBSET)
            .withFields("hero");
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        // Actual is missing the expected field
        assertThat(matcher.matches("query { starships { id } }"), is(false));
    }

    @Test
    public void astSubsetShouldMatchMultipleExpectedFields() {
        GraphQLBody body = GraphQLBody.graphQL("query { hero starships }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_SUBSET)
            .withFields("hero", "starships");
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertThat(matcher.matches("query { hero { name } starships { id } extra { data } }"), is(true));
    }

    @Test
    public void astSubsetShouldUseFieldsFromQueryWhenNoExplicitFields() {
        GraphQLBody body = GraphQLBody.graphQL("query { hero { name } }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_SUBSET);
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertThat(matcher.matches("query { hero { name age } starships { id } }"), is(true));
    }

    @Test
    public void astSubsetShouldCheckOperationType() {
        GraphQLBody body = GraphQLBody.graphQL("query { hero { name } }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_SUBSET)
            .withFields("hero");
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        // Actual is a mutation, not a query
        assertThat(matcher.matches("mutation { hero { name } }"), is(false));
    }

    @Test
    public void astSubsetShouldCheckOperationName() {
        GraphQLBody body = GraphQLBody.graphQL("query GetHero { hero { name } }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_SUBSET)
            .withFields("hero");
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertThat(matcher.matches("query GetHero { hero { name } starships { id } }"), is(true));
        assertThat(matcher.matches("query GetVillain { hero { name } }"), is(false));
    }

    @Test
    public void astSubsetShouldNotMatchNullBody() {
        GraphQLBody body = GraphQLBody.graphQL("query { hero { name } }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_SUBSET)
            .withFields("hero");
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertThat(matcher.matches(null), is(false));
    }

    @Test
    public void astSubsetShouldNotMatchEmptyBody() {
        GraphQLBody body = GraphQLBody.graphQL("query { hero { name } }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_SUBSET)
            .withFields("hero");
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertThat(matcher.matches(""), is(false));
        assertThat(matcher.matches("   "), is(false));
    }

    // --- AST_EXACT matching ---

    @Test
    public void astExactShouldMatchIdenticalFieldSet() {
        GraphQLBody body = GraphQLBody.graphQL("query { hero starships }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_EXACT);
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertThat(matcher.matches("query { hero { name }   starships { id } }"), is(true));
    }

    @Test
    public void astExactShouldNotMatchMissingField() {
        GraphQLBody body = GraphQLBody.graphQL("query { hero starships }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_EXACT);
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        // Missing starships
        assertThat(matcher.matches("query { hero { name } }"), is(false));
    }

    @Test
    public void astExactShouldNotMatchExtraField() {
        GraphQLBody body = GraphQLBody.graphQL("query { hero starships }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_EXACT);
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        // Extra field
        assertThat(matcher.matches("query { hero starships extra }"), is(false));
    }

    @Test
    public void astExactShouldMatchRegardlessOfWhitespace() {
        GraphQLBody body = GraphQLBody.graphQL("query { hero starships }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_EXACT);
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertThat(matcher.matches("query {\n  hero {\n    name\n  }\n  starships {\n    id\n  }\n}"), is(true));
    }

    @Test
    public void astExactShouldCheckOperationType() {
        GraphQLBody body = GraphQLBody.graphQL("mutation { createUser }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_EXACT);
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertThat(matcher.matches("mutation { createUser { id } }"), is(true));
        assertThat(matcher.matches("query { createUser { id } }"), is(false));
    }

    @Test
    public void astExactShouldCheckOperationName() {
        GraphQLBody body = GraphQLBody.graphQL("query GetHero { hero }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_EXACT);
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertThat(matcher.matches("query GetHero { hero { name } }"), is(true));
        assertThat(matcher.matches("query GetVillain { hero { name } }"), is(false));
    }

    @Test
    public void astExactShouldUseExplicitFieldsWhenProvided() {
        GraphQLBody body = GraphQLBody.graphQL("query { hero starships }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_EXACT)
            .withFields("hero", "starships");
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertThat(matcher.matches("query { hero { name } starships { id } }"), is(true));
        assertThat(matcher.matches("query { hero { name } }"), is(false));
    }

    // --- JSON-wrapped body matching ---

    @Test
    public void astSubsetShouldHandleJsonWrappedGraphQL() {
        GraphQLBody body = GraphQLBody.graphQL("query { users }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_SUBSET)
            .withFields("users");
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertThat(matcher.matches("{\"query\":\"{ users { id name } }\",\"variables\":{}}"), is(true));
    }

    @Test
    public void astExactShouldHandleJsonWrappedGraphQL() {
        GraphQLBody body = GraphQLBody.graphQL("query { users }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_EXACT)
            .withFields("users");
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertThat(matcher.matches("{\"query\":\"{ users { id name } }\",\"variables\":{}}"), is(true));
    }

    // --- NORMALISED_STRING mode (should not match — delegates to GraphQLMatcher) ---

    @Test
    public void normalisedStringModeShouldNotMatchInAstMatcher() {
        GraphQLBody body = GraphQLBody.graphQL("query { hero { name } }");
        // selectionSetMatchType defaults to null which maps to NORMALISED_STRING
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertThat(matcher.matches("query { hero { name } }"), is(false));
    }

    // --- Edge cases ---

    @Test
    public void shouldHandleQueryWithComments() {
        GraphQLBody body = GraphQLBody.graphQL("query { hero { name } }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_SUBSET)
            .withFields("hero");
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertThat(matcher.matches("# fetch hero\nquery { hero { name } }"), is(true));
    }

    @Test
    public void shouldHandleQueryWithDirectives() {
        GraphQLBody body = GraphQLBody.graphQL("query GetHero @cached { hero { name } }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_SUBSET);
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertThat(matcher.matches("query GetHero @cached { hero { name } extra { data } }"), is(true));
    }

    @Test
    public void shouldHandleSubscription() {
        GraphQLBody body = GraphQLBody.graphQL("subscription OnUpdate { userUpdated }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_EXACT);
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertThat(matcher.matches("subscription OnUpdate { userUpdated { id name } }"), is(true));
        assertThat(matcher.matches("query OnUpdate { userUpdated { id } }"), is(false));
    }

    @Test
    public void shouldHandleFieldsWithAliases() {
        // Aliases appear before the colon: "smallPic: profilePic(size: 64)"
        // The parser picks up both the alias and the field name since it splits on non-alphanumeric
        Set<String> fields = GraphQLAstMatcher.extractTopLevelFields("query { smallPic: profilePic(size: 64) { url } }");
        // "smallPic" and "profilePic" are both extracted since the parser reads identifiers
        assertThat(fields.contains("smallPic"), is(true));
    }

    @Test
    public void shouldHandleFieldsWithStringArguments() {
        Set<String> fields = GraphQLAstMatcher.extractTopLevelFields("query { search(query: \"hello world\") { results } }");
        assertThat(fields.contains("search"), is(true));
        assertThat(fields.contains("hello"), is(false));
        assertThat(fields.contains("world"), is(false));
    }
}
