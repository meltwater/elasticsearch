/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.index.mapper.core;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.ElasticSearchIllegalArgumentException;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.analysis.NamedAnalyzer;
import org.elasticsearch.index.codec.postingsformat.PostingsFormatProvider;
import org.elasticsearch.index.fielddata.FieldDataType;
import org.elasticsearch.index.mapper.*;
import org.elasticsearch.index.similarity.SimilarityProvider;
import org.elasticsearch.search.suggest.completion.AnalyzingCompletionLookupProvider;
import org.elasticsearch.search.suggest.completion.CompletionPostingsFormatProvider;
import org.elasticsearch.search.suggest.completion.CompletionTokenStream;

import java.io.IOException;
import java.io.Reader;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.elasticsearch.common.xcontent.XContentParser.NumberType;

/**
 *
 */
public class CompletionFieldMapper extends AbstractFieldMapper<String> {

    public static final String CONTENT_TYPE = "completion";

    public static class Defaults extends AbstractFieldMapper.Defaults {
        public static final FieldType FIELD_TYPE = new FieldType(AbstractFieldMapper.Defaults.FIELD_TYPE);

        static {
            FIELD_TYPE.setOmitNorms(true);
            FIELD_TYPE.freeze();
        }

        public static final boolean DEFAULT_PRESERVE_SEPARATORS = true;
        public static final boolean DEFAULT_POSITION_INCREMENTS = true;
        public static final boolean DEFAULT_HAS_PAYLOADS = false;
        public static final int DEFAULT_MAX_INPUT_LENGTH = 50;
    }

    public static class Fields {
        // Mapping field names
        public static final String ANALYZER = "analyzer";
        public static final String INDEX_ANALYZER = "index_analyzer";
        public static final String SEARCH_ANALYZER = "search_analyzer";
        public static final String PRESERVE_SEPARATORS = "preserve_separators";
        public static final String PRESERVE_POSITION_INCREMENTS = "preserve_position_increments";
        public static final String PAYLOADS = "payloads";
        public static final String TYPE = "type";
        public static final String MAX_INPUT_LENGTH = "max_input_len";
        // Content field names
        public static final String CONTENT_FIELD_NAME_INPUT = "input";
        public static final String CONTENT_FIELD_NAME_OUTPUT = "output";
        public static final String CONTENT_FIELD_NAME_PAYLOAD = "payload";
        public static final String CONTENT_FIELD_NAME_WEIGHT = "weight";
    }

    public static Set<String> ALLOWED_CONTENT_FIELD_NAMES = Sets.newHashSet(Fields.CONTENT_FIELD_NAME_INPUT,
            Fields.CONTENT_FIELD_NAME_OUTPUT, Fields.CONTENT_FIELD_NAME_PAYLOAD, Fields.CONTENT_FIELD_NAME_WEIGHT);

    public static class Builder extends AbstractFieldMapper.Builder<Builder, CompletionFieldMapper> {

        private boolean preserveSeparators = Defaults.DEFAULT_PRESERVE_SEPARATORS;
        private boolean payloads = Defaults.DEFAULT_HAS_PAYLOADS;
        private boolean preservePositionIncrements = Defaults.DEFAULT_POSITION_INCREMENTS;
        private int maxInputLength = Defaults.DEFAULT_MAX_INPUT_LENGTH;

        public Builder(String name) {
            super(name, Defaults.FIELD_TYPE);
        }

        public Builder payloads(boolean payloads) {
            this.payloads = payloads;
            return this;
        }

        public Builder preserveSeparators(boolean preserveSeparators) {
            this.preserveSeparators = preserveSeparators;
            return this;
        }

        public Builder preservePositionIncrements(boolean preservePositionIncrements) {
            this.preservePositionIncrements = preservePositionIncrements;
            return this;
        }

        public Builder maxInputLength(int maxInputLength) {
            if (maxInputLength <= 0) {
                throw new ElasticSearchIllegalArgumentException(Fields.MAX_INPUT_LENGTH + " must be > 0 but was [" + maxInputLength + "]");
            }
            this.maxInputLength = maxInputLength;
            return this;
        }

        @Override
        public CompletionFieldMapper build(Mapper.BuilderContext context) {
            return new CompletionFieldMapper(buildNames(context), indexAnalyzer, searchAnalyzer, postingsProvider, similarity, payloads,
                    preserveSeparators, preservePositionIncrements, maxInputLength);
        }
    }

    public static class TypeParser implements Mapper.TypeParser {

        @Override
        public Mapper.Builder<?, ?> parse(String name, Map<String, Object> node, ParserContext parserContext) throws MapperParsingException {
            CompletionFieldMapper.Builder builder = new CompletionFieldMapper.Builder(name);
            for (Map.Entry<String, Object> entry : node.entrySet()) {
                String fieldName = entry.getKey();
                Object fieldNode = entry.getValue();
                if (fieldName.equals("type")) {
                    continue;
                }
                if (fieldName.equals("analyzer")) {
                    NamedAnalyzer analyzer = getNamedAnalyzer(parserContext, fieldNode.toString());
                    builder.indexAnalyzer(analyzer);
                    builder.searchAnalyzer(analyzer);
                } else if (fieldName.equals(Fields.INDEX_ANALYZER) || fieldName.equals("indexAnalyzer")) {
                    builder.indexAnalyzer(getNamedAnalyzer(parserContext, fieldNode.toString()));
                } else if (fieldName.equals(Fields.SEARCH_ANALYZER) || fieldName.equals("searchAnalyzer")) {
                    builder.searchAnalyzer(getNamedAnalyzer(parserContext, fieldNode.toString()));
                } else if (fieldName.equals(Fields.PAYLOADS)) {
                    builder.payloads(Boolean.parseBoolean(fieldNode.toString()));
                } else if (fieldName.equals(Fields.PRESERVE_SEPARATORS) || fieldName.equals("preserveSeparators")) {
                    builder.preserveSeparators(Boolean.parseBoolean(fieldNode.toString()));
                } else if (fieldName.equals(Fields.PRESERVE_POSITION_INCREMENTS) || fieldName.equals("preservePositionIncrements")) {
                    builder.preservePositionIncrements(Boolean.parseBoolean(fieldNode.toString()));
                } else if (fieldName.equals(Fields.MAX_INPUT_LENGTH) || fieldName.equals("maxInputLen")) {
                    builder.maxInputLength(Integer.parseInt(fieldNode.toString()));
                } else {
                    throw new MapperParsingException("Unknown field [" + fieldName + "]");
                }
            }

            if (builder.searchAnalyzer == null) {
                builder.searchAnalyzer(parserContext.analysisService().analyzer("simple"));
            }

            if (builder.indexAnalyzer == null) {
                builder.indexAnalyzer(parserContext.analysisService().analyzer("simple"));
            }
            // we are just using this as the default to be wrapped by the CompletionPostingsFormatProvider in the SuggesteFieldMapper ctor
            builder.postingsFormat(parserContext.postingFormatService().get("default"));
            return builder;
        }

        private NamedAnalyzer getNamedAnalyzer(ParserContext parserContext, String name) {
            NamedAnalyzer analyzer = parserContext.analysisService().analyzer(name);
            if (analyzer == null) {
                throw new ElasticSearchIllegalArgumentException("Can't find default or mapped analyzer with name [" + name + "]");
            }
            return analyzer;
        }
    }

    private static final BytesRef EMPTY = new BytesRef();

    private final CompletionPostingsFormatProvider completionPostingsFormatProvider;
    private final AnalyzingCompletionLookupProvider analyzingSuggestLookupProvider;
    private final boolean payloads;
    private final boolean preservePositionIncrements;
    private final boolean preserveSeparators;
    private int maxInputLength;

    public CompletionFieldMapper(Names names, NamedAnalyzer indexAnalyzer, NamedAnalyzer searchAnalyzer, PostingsFormatProvider postingsProvider, SimilarityProvider similarity, boolean payloads,
                                 boolean preserveSeparators, boolean preservePositionIncrements, int maxInputLength) {
        super(names, 1.0f, Defaults.FIELD_TYPE, indexAnalyzer, searchAnalyzer, postingsProvider, null, similarity, null, null);
        analyzingSuggestLookupProvider = new AnalyzingCompletionLookupProvider(preserveSeparators, false, preservePositionIncrements, payloads);
        this.completionPostingsFormatProvider = new CompletionPostingsFormatProvider("completion", postingsProvider, analyzingSuggestLookupProvider);
        this.preserveSeparators = preserveSeparators;
        this.payloads = payloads;
        this.preservePositionIncrements = preservePositionIncrements;
        this.maxInputLength = maxInputLength;
    }


    @Override
    public PostingsFormatProvider postingsFormatProvider() {
        return this.completionPostingsFormatProvider;
    }

    @Override
    public void parse(ParseContext context) throws IOException {
        XContentParser parser = context.parser();
        XContentParser.Token token = parser.currentToken();

        String surfaceForm = null;
        BytesRef payload = null;
        long weight = -1;
        List<String> inputs = Lists.newArrayListWithExpectedSize(4);

        if (token == XContentParser.Token.VALUE_STRING) {
            inputs.add(parser.text());
        } else {
            String currentFieldName = null;
            while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                if (token == XContentParser.Token.FIELD_NAME) {
                    currentFieldName = parser.currentName();
                    if (!ALLOWED_CONTENT_FIELD_NAMES.contains(currentFieldName)) {
                        throw new ElasticSearchIllegalArgumentException("Unknown field name[" + currentFieldName + "], must be one of " + ALLOWED_CONTENT_FIELD_NAMES);
                    }
                } else if (Fields.CONTENT_FIELD_NAME_PAYLOAD.equals(currentFieldName)) {
                    if (!isStoringPayloads()) {
                        throw new MapperException("Payloads disabled in mapping");
                    }
                    if (token == XContentParser.Token.START_OBJECT) {
                        XContentBuilder payloadBuilder = XContentFactory.contentBuilder(parser.contentType()).copyCurrentStructure(parser);
                        payload = payloadBuilder.bytes().toBytesRef();
                        payloadBuilder.close();
                    } else if (token.isValue()) {
                        payload = parser.bytesOrNull();
                    } else {
                        throw new MapperException("payload doesn't support type " + token);
                    }
                } else if (token == XContentParser.Token.VALUE_STRING) {
                    if (Fields.CONTENT_FIELD_NAME_OUTPUT.equals(currentFieldName)) {
                        surfaceForm = parser.text();
                    }
                    if (Fields.CONTENT_FIELD_NAME_INPUT.equals(currentFieldName)) {
                        inputs.add(parser.text());
                    }
                } else if (token == XContentParser.Token.VALUE_NUMBER) {
                    if (Fields.CONTENT_FIELD_NAME_WEIGHT.equals(currentFieldName)) {
                        NumberType numberType = parser.numberType();
                        if (NumberType.LONG != numberType && NumberType.INT != numberType) {
                            throw new ElasticSearchIllegalArgumentException("Weight must be an integer, but was [" + parser.numberValue() + "]");
                        }
                        weight = parser.longValue(); // always parse a long to make sure we don't get the overflow value
                        if (weight < 0 || weight > Integer.MAX_VALUE) {
                            throw new ElasticSearchIllegalArgumentException("Weight must be in the interval [0..2147483647], but was [" + weight + "]");
                        }
                    }
                } else if (token == XContentParser.Token.START_ARRAY) {
                    if (Fields.CONTENT_FIELD_NAME_INPUT.equals(currentFieldName)) {
                        while ((token = parser.nextToken()) != XContentParser.Token.END_ARRAY) {
                            inputs.add(parser.text());
                        }
                    }
                }
            }
        }
        payload = payload == null ? EMPTY : payload;
        if (surfaceForm == null) { // no surface form use the input
            for (String input : inputs) {
                BytesRef suggestPayload = analyzingSuggestLookupProvider.buildPayload(new BytesRef(
                        input), weight, payload);
                context.doc().add(getCompletionField(input, suggestPayload));
            }
        } else {
            BytesRef suggestPayload = analyzingSuggestLookupProvider.buildPayload(new BytesRef(
                    surfaceForm), weight, payload);
            for (String input : inputs) {
                context.doc().add(getCompletionField(input, suggestPayload));
            }
        }
    }

    public Field getCompletionField(String input, BytesRef payload) {
        final String originalInput = input;
        if (input.length() > maxInputLength) {
            final int len = correctSubStringLen(input, Math.min(maxInputLength, input.length()));
            input = input.substring(0, len);
        }
        for (int i = 0; i < input.length(); i++) {
            if (isReservedChar(input.charAt(i))) {
                throw new ElasticSearchIllegalArgumentException("Illegal input [" + originalInput + "] UTF-16 codepoint  [0x"
                        + Integer.toHexString((int) input.charAt(i)).toUpperCase(Locale.ROOT)
                        + "] at position " + i + " is a reserved character");
            }
        }
        return new SuggestField(names.indexName(), input, this.fieldType, payload, analyzingSuggestLookupProvider);
    }

    public static int correctSubStringLen(String input, int len) {
        if (Character.isHighSurrogate(input.charAt(len - 1))) {
            assert input.length() >= len + 1 && Character.isLowSurrogate(input.charAt(len));
            return len + 1;
        }
        return len;
    }

    public BytesRef buildPayload(BytesRef surfaceForm, long weight, BytesRef payload) throws IOException {
        return analyzingSuggestLookupProvider.buildPayload(
                surfaceForm, weight, payload);
    }

    private static final class SuggestField extends Field {
        private final BytesRef payload;
        private final CompletionTokenStream.ToFiniteStrings toFiniteStrings;

        public SuggestField(String name, Reader value, FieldType type, BytesRef payload, CompletionTokenStream.ToFiniteStrings toFiniteStrings) {
            super(name, value, type);
            this.payload = payload;
            this.toFiniteStrings = toFiniteStrings;
        }

        public SuggestField(String name, String value, FieldType type, BytesRef payload, CompletionTokenStream.ToFiniteStrings toFiniteStrings) {
            super(name, value, type);
            this.payload = payload;
            this.toFiniteStrings = toFiniteStrings;
        }

        @Override
        public TokenStream tokenStream(Analyzer analyzer) throws IOException {
            TokenStream ts = super.tokenStream(analyzer);
            return new CompletionTokenStream(ts, payload, toFiniteStrings);
        }
    }


    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(name())
                .field(Fields.TYPE, CONTENT_TYPE);
        if (indexAnalyzer.name().equals(searchAnalyzer.name())) {
            builder.field(Fields.ANALYZER, indexAnalyzer.name());
        } else {
            builder.field(Fields.INDEX_ANALYZER, indexAnalyzer.name())
                    .field(Fields.SEARCH_ANALYZER, searchAnalyzer.name());
        }
        builder.field(Fields.PAYLOADS, this.payloads);
        builder.field(Fields.PRESERVE_SEPARATORS, this.preserveSeparators);
        builder.field(Fields.PRESERVE_POSITION_INCREMENTS, this.preservePositionIncrements);
        builder.field(Fields.MAX_INPUT_LENGTH, this.maxInputLength);
        return builder.endObject();
    }

    @Override
    protected void parseCreateField(ParseContext context, List<Field> fields) throws IOException {
    }


    @Override
    protected String contentType() {
        return CONTENT_TYPE;
    }

    @Override
    public boolean isSortable() {
        return false;
    }

    @Override
    public boolean hasDocValues() {
        return false;
    }

    @Override
    public FieldType defaultFieldType() {
        return Defaults.FIELD_TYPE;
    }

    @Override
    public FieldDataType defaultFieldDataType() {
        return null;
    }

    @Override
    public String value(Object value) {
        if (value == null) {
            return null;
        }
        return value.toString();
    }

    public boolean isStoringPayloads() {
        return payloads;
    }

    @Override
    public void merge(Mapper mergeWith, MergeContext mergeContext) throws MergeMappingException {
        super.merge(mergeWith, mergeContext);
        CompletionFieldMapper fieldMergeWith = (CompletionFieldMapper) mergeWith;
        if (payloads != fieldMergeWith.payloads) {
            mergeContext.addConflict("mapper [" + names.fullName() + "] has different payload values");
        }
        if (preservePositionIncrements != fieldMergeWith.preservePositionIncrements) {
            mergeContext.addConflict("mapper [" + names.fullName() + "] has different 'preserve_position_increments' values");
        }
        if (preserveSeparators != fieldMergeWith.preserveSeparators) {
            mergeContext.addConflict("mapper [" + names.fullName() + "] has different 'preserve_separators' values");
        }
        if (!mergeContext.mergeFlags().simulate()) {
            this.maxInputLength = fieldMergeWith.maxInputLength;
        }
    }

    private static final char END_LABEL = 0x00;

    // this should be package private but our tests don't allow it.
    public static boolean isReservedChar(char character) {
        /* we also use 0xFF as a SEP_LABEL in the suggester but it's not valid UTF-8 so no need to check.
         *  we also don't need to convert to UTF-8 here to check for the 0x00 end label since all multi-byte
         *  UTF-8 chars start with 0x10 binary so if the UTF-16 CP is == 0x00 it's the single byte UTF-8 CP */
        return character == END_LABEL;
    }
}
