/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.search.fetch.subphase.highlight;

import org.apache.lucene.search.join.ScoreMode;
import org.elasticsearch.action.search.SearchPhaseExecutionException;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.common.document.DocumentField;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.query.InnerHitBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.subphase.FieldAndFormat;
import org.elasticsearch.test.ESSingleNodeTestCase;
import org.elasticsearch.xcontent.Text;
import org.elasticsearch.xcontent.XContentType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Spike for the design in #5736, not meant to merge: checks whether the values the unified highlighter sees, in order,
 * are the values the {@code fields} option returns, and how a search reports a highlighting failure on some of its
 * shards. Both tests log a report instead of asserting; run with {@code -Dtests.output=always} and read the
 * {@code SAME ORDER AS fields} lines and the partial-failure report.
 */
public class HighlightValueOrderSpikeTests extends ESSingleNodeTestCase {

    private static final String PRE = "[[";
    private static final String POST = "]]";

    public void testValueOrderMatchesFieldsOption() {
        StringBuilder report = new StringBuilder("\nVALUE-ORDER REPORT\n");

        report.append(runCase("text with null in array", Settings.EMPTY, """
            {"properties":{"f":{"type":"text"}}}""", """
            {"f":["b fox one", null, "a fox two"]}""", "f", QueryBuilders.matchQuery("f", "fox")));
        report.append(runCase("keyword lowercase normalizer + ignore_above 12", Settings.EMPTY, """
            {"properties":{"f":{"type":"keyword","normalizer":"lowercase","ignore_above":12}}}""", """
            {"f":["B FOX", "this fox is far too long", "A FOX"]}""", "f", QueryBuilders.wildcardQuery("f", "*fox*")));
        report.append(runCase("text stored, store:true", Settings.EMPTY, """
            {"properties":{"f":{"type":"text","store":true}}}""", """
            {"f":["b fox one", "a fox two"]}""", "f", QueryBuilders.matchQuery("f", "fox")));
        report.append(runCase("copy_to target", Settings.EMPTY, """
            {"properties":{"a":{"type":"text","copy_to":"all"},"b":{"type":"text","copy_to":"all"},"all":{"type":"text"}}}""", """
            {"b":"b fox one", "a":["a fox two", "a fox three"]}""", "all", QueryBuilders.matchQuery("all", "fox")));
        report.append(runCase("multi-field keyword subfield", Settings.EMPTY, """
            {"properties":{"f":{"type":"text","fields":{"raw":{"type":"keyword"}}}}}""", """
            {"f":["b fox one", "a fox two"]}""", "f.raw", QueryBuilders.wildcardQuery("f.raw", "*fox*")));
        report.append(runCase("array of objects obj.f", Settings.EMPTY, """
            {"properties":{"obj":{"properties":{"f":{"type":"text"}}}}}""", """
            {"obj":[{"f":"b fox one"},{"f":["a fox two","c fox three"]}]}""", "obj.f", QueryBuilders.matchQuery("obj.f", "fox")));
        report.append(runCase("synthetic source, text", Settings.builder().put("index.mapping.source.mode", "synthetic").build(), """
            {"properties":{"f":{"type":"text"}}}""", """
            {"f":["b fox one", "a fox two", "b fox one"]}""", "f", QueryBuilders.matchQuery("f", "fox")));
        report.append(runCase("synthetic source, keyword", Settings.builder().put("index.mapping.source.mode", "synthetic").build(), """
            {"properties":{"f":{"type":"keyword"}}}""", """
            {"f":["b fox", "a fox", "b fox"]}""", "f", QueryBuilders.wildcardQuery("f", "*fox*")));
        report.append(
            runCase(
                "synthetic source, text with keyword subfield",
                Settings.builder().put("index.mapping.source.mode", "synthetic").build(),
                """
                    {"properties":{"f":{"type":"text","fields":{"raw":{"type":"keyword"}}}}}""",
                """
                    {"f":["b fox one", "a fox two", "b fox one"]}""",
                "f",
                QueryBuilders.matchQuery("f", "fox")
            )
        );
        report.append(nestedInnerHitsCase());
        logger.info(report.toString());
    }

    private String runCase(String name, Settings settings, String mapping, String doc, String field, QueryBuilder query) {
        String index = "idx-" + randomAlphaOfLength(8).toLowerCase(java.util.Locale.ROOT);
        StringBuilder out = new StringBuilder("CASE ").append(name).append('\n');
        try {
            client().admin().indices().prepareCreate(index).setSettings(settings).setMapping(mapping).get();
            client().prepareIndex(index)
                .setId("1")
                .setSource(doc, XContentType.JSON)
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .get();
            SearchResponse response = client().prepareSearch(index)
                .setQuery(query)
                .highlighter(new HighlightBuilder().field(field).numOfFragments(0).preTags(PRE).postTags(POST))
                .addFetchField(new FieldAndFormat(field, null))
                .get();
            try {
                SearchHit hit = response.getHits().getHits()[0];
                out.append("  _source   : ").append(hit.getSourceAsString()).append('\n');
                DocumentField df = hit.field(field);
                out.append("  fields    : ").append(df == null ? "null" : df.getValues()).append('\n');
                HighlightField hf = hit.getHighlightFields().get(field);
                List<String> fragments = new ArrayList<>();
                if (hf != null && hf.fragments() != null) {
                    for (Text t : hf.fragments()) {
                        fragments.add(t.string().replace(PRE, "").replace(POST, ""));
                    }
                }
                out.append("  highlight : ").append(fragments).append('\n');
                List<Object> fieldValues = df == null ? List.of() : df.getValues();
                out.append("  SAME ORDER AS fields: ")
                    .append(fragments.equals(fieldValues.stream().map(Object::toString).toList()))
                    .append('\n');
            } finally {
                response.decRef();
            }
        } catch (Exception e) {
            out.append("  ERROR: ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append('\n');
        }
        return out.toString();
    }

    private String nestedInnerHitsCase() {
        StringBuilder out = new StringBuilder("CASE nested inner_hits\n");
        try {
            client().admin().indices().prepareCreate("nested-idx").setMapping("""
                {"properties":{"comments":{"type":"nested","properties":{"text":{"type":"text"}}}}}""").get();
            client().prepareIndex("nested-idx")
                .setId("1")
                .setSource("""
                    {"comments":[{"text":["first fox", "second fox"]},{"text":"third fox"}]}""", XContentType.JSON)
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .get();
            InnerHitBuilder ih = new InnerHitBuilder().setHighlightBuilder(
                new HighlightBuilder().field("comments.text").numOfFragments(0).preTags(PRE).postTags(POST)
            ).addFetchField("comments.text");
            SearchResponse response = client().prepareSearch("nested-idx")
                .setQuery(
                    QueryBuilders.nestedQuery("comments", QueryBuilders.matchQuery("comments.text", "fox"), ScoreMode.Avg).innerHit(ih)
                )
                .get();
            try {
                SearchHit hit = response.getHits().getHits()[0];
                for (SearchHit inner : hit.getInnerHits().get("comments").getHits()) {
                    DocumentField df = inner.field("comments.text");
                    HighlightField hf = inner.getHighlightFields().get("comments.text");
                    out.append("  nested offset=")
                        .append(inner.getNestedIdentity().getOffset())
                        .append(" fields=")
                        .append(df == null ? "null" : df.getValues())
                        .append(" highlight=")
                        .append(hf == null ? "null" : Arrays.toString(hf.fragments()))
                        .append('\n');
                }
            } finally {
                response.decRef();
            }
        } catch (Exception e) {
            out.append("  ERROR: ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append('\n');
        }
        return out.toString();
    }

    public void testPartialHighlightFailure() {
        StringBuilder out = new StringBuilder("\nPARTIAL-FAILURE REPORT\n");
        client().admin().indices().prepareCreate("tv").setSettings(Settings.builder().put("index.number_of_shards", 1)).setMapping("""
            {"properties":{"f":{"type":"text","term_vector":"with_positions_offsets"}}}""").get();
        client().admin().indices().prepareCreate("notv").setSettings(Settings.builder().put("index.number_of_shards", 1)).setMapping("""
            {"properties":{"f":{"type":"text"}}}""").get();
        for (String index : List.of("tv", "notv")) {
            for (int i = 0; i < 3; i++) {
                client().prepareIndex(index).setId(index + i).setSource(Map.of("f", "the quick fox " + i)).get();
            }
            client().admin().indices().prepareRefresh(index).get();
        }
        SearchSourceBuilder source = new SearchSourceBuilder().query(QueryBuilders.matchQuery("f", "fox"))
            .highlighter(new HighlightBuilder().field(new HighlightBuilder.Field("f").highlighterType("fvh")));
        try {
            SearchResponse response = client().prepareSearch("tv", "notv").setSource(source).get();
            try {
                out.append("  status=")
                    .append(response.status())
                    .append(" totalHits=")
                    .append(response.getHits().getTotalHits())
                    .append(" returnedHits=")
                    .append(response.getHits().getHits().length)
                    .append(" failedShards=")
                    .append(response.getFailedShards())
                    .append('\n');
                for (SearchHit hit : response.getHits().getHits()) {
                    out.append("    hit index=")
                        .append(hit.getIndex())
                        .append(" id=")
                        .append(hit.getId())
                        .append(" highlight=")
                        .append(hit.getHighlightFields().keySet())
                        .append('\n');
                }
                for (var failure : response.getShardFailures()) {
                    out.append("    failure index=")
                        .append(failure.index())
                        .append(" status=")
                        .append(failure.status())
                        .append(" reason=")
                        .append(failure.reason(), 0, Math.min(160, failure.reason().length()))
                        .append('\n');
                }
            } finally {
                response.decRef();
            }
        } catch (SearchPhaseExecutionException e) {
            out.append("  THROWN status=").append(e.status()).append(" msg=").append(e.getMessage()).append('\n');
        }
        try {
            SearchResponse response = client().prepareSearch("notv").setSource(source).get();
            response.decRef();
            out.append("  single failing index: no exception\n");
        } catch (SearchPhaseExecutionException e) {
            out.append("  single failing index: THROWN status=").append(e.status()).append('\n');
        }
        logger.info(out.toString());
    }
}
