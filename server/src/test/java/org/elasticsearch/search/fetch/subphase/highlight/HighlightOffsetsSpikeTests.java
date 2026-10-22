/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.search.fetch.subphase.highlight;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.charfilter.HTMLStripCharFilter;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.search.highlight.Encoder;
import org.apache.lucene.search.uhighlight.Passage;
import org.apache.lucene.search.uhighlight.PassageFormatter;
import org.elasticsearch.common.Strings;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.analysis.AnalyzerScope;
import org.elasticsearch.index.analysis.IndexAnalyzers;
import org.elasticsearch.index.analysis.NamedAnalyzer;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.ParsedDocument;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.lucene.search.uhighlight.CustomPassageFormatter;
import org.elasticsearch.lucene.search.uhighlight.Snippet;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.HighlighterTestCase;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.json.JsonXContent;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Spike for the design in #5736, not meant to merge: checks that offsets computed from the unified highlighter's passages
 * describe each fragment exactly, by re-rendering the fragment from the field content and the computed offsets. It
 * counts the pre-existing {@code BoundedBreakIteratorScanner} assertion separately and logs samples of it.
 * Run more documents with {@code -Dtests.iters=N}.
 */
public class HighlightOffsetsSpikeTests extends HighlighterTestCase {

    private static final String PRE = "[[";
    private static final String POST = "]]";
    private static final String[] WORDS = {
        "fox",
        "quick",
        "brown",
        "the",
        "jumps",
        "over",
        "lazy",
        "dog",
        "foxes",
        "jumping",
        "héllo",
        "naïve",
        "résumé",
        "日本語",
        "😀",
        "a😀b",
        "𝔘𝔫𝔦𝔠𝔬𝔡𝔢",
        "co-op",
        "e-mail",
        "foo.bar",
        "x/y",
        "<b>bold</b>",
        "&amp;",
        "\"quoted\"",
        "Ünïcödé" };
    private static final String[] SEPARATORS = { " ", " ", " ", "  ", ". ", ", ", "\n", "\t", "! ", "? ", " \u2029", "\u2029", " - " };

    private final Stats stats = new Stats();
    private String expectedContent;

    static final class Stats {
        int docs;
        int errors;
        int passages;
        int crossValuePassages;
        int leadingTrimmed;
        int trailingTrimmed;
        int paragraphStripped;
        int overlappingMatchesMerged;
        int clippedMatches;
        int contentMismatches;
        int boundedScannerAsserts;
        final List<String> failures = new ArrayList<>();
        final List<String> errorSamples = new ArrayList<>();
        final List<String> boundedScannerSamples = new ArrayList<>();

        @Override
        public String toString() {
            return String.format(
                Locale.ROOT,
                "docs=%d errors=%d passages=%d crossValue=%d leadingTrimmed=%d trailingTrimmed=%d paragraphStripped=%d "
                    + "mergedOverlaps=%d clippedMatches=%d contentMismatches=%d boundedScannerAsserts=%d failures=%d",
                docs,
                errors,
                passages,
                crossValuePassages,
                leadingTrimmed,
                trailingTrimmed,
                paragraphStripped,
                overlappingMatchesMerged,
                clippedMatches,
                contentMismatches,
                boundedScannerAsserts,
                failures.size()
            );
        }
    }

    @Override
    protected IndexAnalyzers createIndexAnalyzers(IndexSettings indexSettings) {
        Analyzer htmlStrip = new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                Tokenizer tokenizer = new StandardTokenizer();
                return new TokenStreamComponents(tokenizer, new LowerCaseFilter(tokenizer));
            }

            @Override
            protected Reader initReader(String fieldName, Reader reader) {
                return new HTMLStripCharFilter(reader);
            }
        };
        return IndexAnalyzers.of(
            Map.of(
                "default",
                new NamedAnalyzer("default", AnalyzerScope.INDEX, new StandardAnalyzer()),
                "whitespace",
                new NamedAnalyzer("whitespace", AnalyzerScope.INDEX, new WhitespaceAnalyzer()),
                "english",
                new NamedAnalyzer("english", AnalyzerScope.INDEX, new EnglishAnalyzer()),
                "html",
                new NamedAnalyzer("html", AnalyzerScope.INDEX, htmlStrip)
            )
        );
    }

    @Override
    protected Map<String, Highlighter> getHighlighters() {
        return Map.of("unified", new DefaultHighlighter() {
            @Override
            protected PassageFormatter getPassageFormatter(SearchHighlightContext.Field field, Encoder encoder) {
                return new CheckingFormatter(encoder, field.fieldOptions().numberOfFragments());
            }
        });
    }

    public void testOffsetsReproduceFragments() throws IOException {
        int iterations = scaledRandomIntBetween(300, 1500);
        for (int i = 0; i < iterations; i++) {
            runOne();
        }
        logger.info("spike stats: {}", stats);
        for (String failure : stats.failures.subList(0, Math.min(5, stats.failures.size()))) {
            logger.info("failure sample: {}", failure);
        }
        for (String error : stats.errorSamples.subList(0, Math.min(5, stats.errorSamples.size()))) {
            logger.info("error sample: {}", error);
        }
        for (String sample : stats.boundedScannerSamples) {
            logger.info("bounded scanner assert sample: {}", sample);
        }
        assertEquals("content mismatches", 0, stats.contentMismatches);
        assertTrue("render mismatches: " + stats.failures.size(), stats.failures.isEmpty());
    }

    private void runOne() throws IOException {
        boolean keyword = rarely();
        String analyzer = randomFrom("default", "whitespace", "english", "html");
        XContentBuilder mapping = JsonXContent.contentBuilder().startObject().startObject("_doc").startObject("properties");
        mapping.startObject("field");
        if (keyword) {
            mapping.field("type", "keyword");
        } else {
            mapping.field("type", "text").field("analyzer", analyzer);
            switch (randomIntBetween(0, 2)) {
                case 0 -> mapping.field("index_options", "offsets");
                case 1 -> mapping.field("term_vector", "with_positions_offsets");
                default -> {
                }
            }
            if (randomBoolean()) {
                mapping.field("position_increment_gap", 0);
            }
        }
        mapping.endObject().endObject().endObject().endObject();
        MapperService mapperService = createMapperService(mapping);

        int valueCount = randomBoolean() ? 1 : randomIntBetween(2, 4);
        List<String> values = new ArrayList<>();
        List<String> allWords = new ArrayList<>();
        for (int v = 0; v < valueCount; v++) {
            StringBuilder sb = new StringBuilder();
            if (rarely()) {
                sb.append(randomFrom(" ", "  ", "\n", "\t "));
            }
            int words = keyword ? randomIntBetween(1, 3) : randomIntBetween(1, 30);
            for (int w = 0; w < words; w++) {
                String word = randomFrom(WORDS);
                allWords.add(word);
                sb.append(word);
                if (w < words - 1) {
                    sb.append(randomFrom(SEPARATORS));
                }
            }
            if (rarely()) {
                sb.append(randomFrom(" ", "  ", "\n", " \u2029", "."));
            }
            values.add(sb.toString());
        }
        XContentBuilder source = JsonXContent.contentBuilder().startObject();
        if (valueCount == 1 && randomBoolean()) {
            source.field("field", values.get(0));
        } else {
            source.array("field", values.toArray(String[]::new));
        }
        source.endObject();
        ParsedDocument doc = mapperService.documentMapper().parse(source(Strings.toString(source)));

        QueryBuilder query = randomQuery(keyword, values, allWords);
        HighlightBuilder.Field hf = new HighlightBuilder.Field("field");
        hf.numOfFragments(randomFrom(0, 1, 2, 5));
        hf.fragmentSize(randomFrom(5, 18, 40, 100));
        hf.order(randomFrom(HighlightBuilder.Order.NONE, HighlightBuilder.Order.SCORE));
        hf.encoder(randomFrom("default", "html"));
        hf.noMatchSize(randomFrom(0, 0, 12));
        hf.boundaryScannerType(randomFrom(HighlightBuilder.BoundaryScannerType.SENTENCE, HighlightBuilder.BoundaryScannerType.WORD));
        if (rarely()) {
            hf.maxAnalyzedOffset(randomIntBetween(5, 60));
        }
        HighlightBuilder hb = new HighlightBuilder().field(hf).preTags(PRE).postTags(POST);
        SearchSourceBuilder search = new SearchSourceBuilder().query(query).highlighter(hb);

        expectedContent = String.join("\u0000", values);
        stats.docs++;
        try {
            highlight(mapperService, doc, search);
        } catch (IllegalArgumentException e) {
            stats.errors++;
            if (stats.errorSamples.size() < 20) {
                stats.errorSamples.add(e.getMessage());
            }
        } catch (AssertionError e) {
            if (e.getStackTrace().length > 0 && e.getStackTrace()[0].getClassName().endsWith("BoundedBreakIteratorScanner")) {
                stats.boundedScannerAsserts++;
                if (stats.boundedScannerSamples.size() < 5) {
                    stats.boundedScannerSamples.add(
                        "fragment_size="
                            + hf.fragmentSize()
                            + " boundary="
                            + hf.boundaryScannerType()
                            + " query="
                            + Strings.toString(query).replaceAll("\\s+", "")
                            + " values="
                            + escape(String.join(" | ", values))
                    );
                }
            } else {
                throw e;
            }
        }
    }

    private QueryBuilder randomQuery(boolean keyword, List<String> values, List<String> allWords) {
        if (keyword) {
            return randomBoolean()
                ? QueryBuilders.termQuery("field", randomFrom(values))
                : QueryBuilders.prefixQuery("field", randomFrom(values).substring(0, 1));
        }
        String word = randomFrom(allWords);
        return switch (randomIntBetween(0, 5)) {
            case 0 -> QueryBuilders.matchQuery("field", word);
            case 1 -> QueryBuilders.matchQuery("field", randomFrom(allWords) + " " + randomFrom(allWords) + " " + randomFrom(WORDS));
            case 2 -> {
                int start = randomIntBetween(0, allWords.size() - 1);
                int end = Math.min(allWords.size(), start + randomIntBetween(2, 3));
                yield QueryBuilders.matchPhraseQuery("field", String.join(" ", allWords.subList(start, end)));
            }
            case 3 -> QueryBuilders.prefixQuery("field", word.toLowerCase(Locale.ROOT).substring(0, Math.min(2, word.length())));
            case 4 -> QueryBuilders.fuzzyQuery("field", word.toLowerCase(Locale.ROOT));
            default -> {
                BoolQueryBuilder bool = QueryBuilders.boolQuery();
                bool.should(QueryBuilders.matchQuery("field", word));
                bool.should(QueryBuilders.matchPhraseQuery("field", randomFrom(allWords) + " " + randomFrom(allWords)));
                yield bool;
            }
        };
    }

    /**
     * Delegates to the production formatter, then computes the offsets this design would return for each passage and
     * checks that rendering the content at those offsets reproduces the production fragment exactly.
     */
    private final class CheckingFormatter extends PassageFormatter {
        private final CustomPassageFormatter delegate;
        private final Encoder encoder;
        private final int numberOfFragments;

        CheckingFormatter(Encoder encoder, int numberOfFragments) {
            this.delegate = new CustomPassageFormatter(PRE, POST, encoder, numberOfFragments);
            this.encoder = encoder;
            this.numberOfFragments = numberOfFragments;
        }

        @Override
        public Snippet[] format(Passage[] passages, String content) {
            Snippet[] snippets = delegate.format(passages, content);
            if (content.equals(expectedContent) == false) {
                stats.contentMismatches++;
            }
            for (int j = 0; j < passages.length; j++) {
                check(passages[j], content, snippets[j]);
            }
            return snippets;
        }

        private void check(Passage passage, String content, Snippet snippet) {
            stats.passages++;
            int passageStart = passage.getStartOffset();
            int passageEnd = passage.getEndOffset();
            List<int[]> matches = new ArrayList<>();
            int n = passage.getNumMatches();
            for (int i = 0; i < n; i++) {
                int start = passage.getMatchStarts()[i];
                int end = passage.getMatchEnds()[i];
                while (i + 1 < n && passage.getMatchStarts()[i + 1] < end) {
                    end = Math.max(passage.getMatchEnds()[++i], end);
                    stats.overlappingMatchesMerged++;
                }
                if (end > passageEnd) {
                    stats.clippedMatches++;
                    end = passageEnd;
                }
                matches.add(new int[] { start, end });
            }
            int lastMatchEnd = matches.isEmpty() ? passageStart : matches.get(matches.size() - 1)[1];
            int start = passageStart;
            int end = Math.max(lastMatchEnd, passageEnd);
            if (end > lastMatchEnd
                && content.charAt(end - 1) == HighlightUtils.PARAGRAPH_SEPARATOR
                && encoder.encodeText(String.valueOf(HighlightUtils.PARAGRAPH_SEPARATOR))
                    .equals(String.valueOf(HighlightUtils.PARAGRAPH_SEPARATOR))) {
                end--;
                stats.paragraphStripped++;
            }
            if (numberOfFragments > 0) {
                int leadingLimit = matches.isEmpty() ? end : matches.get(0)[0];
                int before = start;
                while (start < leadingLimit && content.charAt(start) <= ' ') {
                    start++;
                }
                if (start != before) {
                    stats.leadingTrimmed++;
                }
                int trailingLimit = matches.isEmpty() ? start : lastMatchEnd;
                before = end;
                while (end > trailingLimit && content.charAt(end - 1) <= ' ') {
                    end--;
                }
                if (end != before) {
                    stats.trailingTrimmed++;
                }
            }
            if (content.substring(start, end).indexOf('\u0000') >= 0) {
                stats.crossValuePassages++;
            }
            String rendered = render(content, start, end, matches);
            if (rendered.equals(snippet.getText()) == false && stats.failures.size() < 50) {
                stats.failures.add(
                    "content="
                        + escape(content)
                        + " passage=["
                        + passageStart
                        + ","
                        + passageEnd
                        + ") offsets=["
                        + start
                        + ","
                        + end
                        + ") nof="
                        + numberOfFragments
                        + "\n  expected="
                        + escape(snippet.getText())
                        + "\n  rendered="
                        + escape(rendered)
                );
            }
        }

        private String render(String content, int start, int end, List<int[]> matches) {
            StringBuilder sb = new StringBuilder();
            int pos = start;
            for (int[] match : matches) {
                sb.append(encoder.encodeText(content.substring(pos, match[0])));
                sb.append(PRE).append(encoder.encodeText(content.substring(match[0], match[1]))).append(POST);
                pos = match[1];
            }
            sb.append(encoder.encodeText(content.substring(pos, Math.max(pos, end))));
            return sb.toString().replace(HighlightUtils.NULL_SEPARATOR, ' ');
        }
    }

    private static String escape(String s) {
        return s.replace("\u0000", "\\0").replace("\u2029", "\\PS").replace("\n", "\\n").replace("\t", "\\t");
    }
}
