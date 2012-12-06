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

package org.elasticsearch.index.mapper.ip;

import org.apache.lucene.analysis.NumericTokenStream;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.search.*;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.NumericUtils;
import org.elasticsearch.ElasticSearchIllegalArgumentException;
import org.elasticsearch.common.Explicit;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.Numbers;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.analysis.NamedAnalyzer;
import org.elasticsearch.index.analysis.NumericAnalyzer;
import org.elasticsearch.index.analysis.NumericTokenizer;
import org.elasticsearch.index.cache.field.data.FieldDataCache;
import org.elasticsearch.index.codec.postingsformat.PostingsFormatProvider;
import org.elasticsearch.index.field.data.FieldDataType;
import org.elasticsearch.index.mapper.*;
import org.elasticsearch.index.mapper.core.LongFieldMapper;
import org.elasticsearch.index.mapper.core.NumberFieldMapper;
import org.elasticsearch.index.query.QueryParseContext;
import org.elasticsearch.index.search.NumericRangeFieldDataFilter;
import org.elasticsearch.index.similarity.SimilarityProvider;

import java.io.IOException;
import java.io.Reader;
import java.util.Map;
import java.util.regex.Pattern;

import static org.elasticsearch.index.mapper.MapperBuilders.ipField;
import static org.elasticsearch.index.mapper.core.TypeParsers.parseNumberField;

/**
 *
 */
public class IpFieldMapper extends NumberFieldMapper<Long> {

    public static final String CONTENT_TYPE = "ip";

    public static String longToIp(long longIp) {
        int octet3 = (int) ((longIp >> 24) % 256);
        int octet2 = (int) ((longIp >> 16) % 256);
        int octet1 = (int) ((longIp >> 8) % 256);
        int octet0 = (int) ((longIp) % 256);
        return octet3 + "." + octet2 + "." + octet1 + "." + octet0;
    }

    private static final Pattern pattern = Pattern.compile("\\.");

    public static long ipToLong(String ip) throws ElasticSearchIllegalArgumentException {
        try {
            String[] octets = pattern.split(ip);
            if (octets.length != 4) {
                throw new ElasticSearchIllegalArgumentException("failed to parse ip [" + ip + "], not full ip address (4 dots)");
            }
            return (Long.parseLong(octets[0]) << 24) + (Integer.parseInt(octets[1]) << 16) +
                    (Integer.parseInt(octets[2]) << 8) + Integer.parseInt(octets[3]);
        } catch (Exception e) {
            if (e instanceof ElasticSearchIllegalArgumentException) {
                throw (ElasticSearchIllegalArgumentException) e;
            }
            throw new ElasticSearchIllegalArgumentException("failed to parse ip [" + ip + "]", e);
        }
    }

    public static class Defaults extends NumberFieldMapper.Defaults {
        public static final String NULL_VALUE = null;

        public static final FieldType IP_FIELD_TYPE = new FieldType(NumberFieldMapper.Defaults.NUMBER_FIELD_TYPE);

        static {
            IP_FIELD_TYPE.freeze();
        }
    }

    public static class Builder extends NumberFieldMapper.Builder<Builder, IpFieldMapper> {

        protected String nullValue = Defaults.NULL_VALUE;

        public Builder(String name) {
            super(name, new FieldType(Defaults.IP_FIELD_TYPE));
            builder = this;
        }

        public Builder nullValue(String nullValue) {
            this.nullValue = nullValue;
            return this;
        }

        @Override
        public IpFieldMapper build(BuilderContext context) {
            fieldType.setOmitNorms(fieldType.omitNorms() && boost == 1.0f);
            IpFieldMapper fieldMapper = new IpFieldMapper(buildNames(context),
                    precisionStep, boost, fieldType, nullValue, ignoreMalformed(context), provider, similarity);
            fieldMapper.includeInAll(includeInAll);
            return fieldMapper;
        }
    }

    public static class TypeParser implements Mapper.TypeParser {
        @Override
        public Mapper.Builder parse(String name, Map<String, Object> node, ParserContext parserContext) throws MapperParsingException {
            IpFieldMapper.Builder builder = ipField(name);
            parseNumberField(builder, name, node, parserContext);
            for (Map.Entry<String, Object> entry : node.entrySet()) {
                String propName = Strings.toUnderscoreCase(entry.getKey());
                Object propNode = entry.getValue();
                if (propName.equals("null_value")) {
                    builder.nullValue(propNode.toString());
                }
            }
            return builder;
        }
    }

    private String nullValue;

    protected IpFieldMapper(Names names, int precisionStep,
                            float boost, FieldType fieldType,
                            String nullValue, Explicit<Boolean> ignoreMalformed,
                            PostingsFormatProvider provider, SimilarityProvider similarity) {
        super(names, precisionStep, null, boost, fieldType,
                ignoreMalformed, new NamedAnalyzer("_ip/" + precisionStep, new NumericIpAnalyzer(precisionStep)),
                new NamedAnalyzer("_ip/max", new NumericIpAnalyzer(Integer.MAX_VALUE)), provider, similarity);
        this.nullValue = nullValue;
    }

    @Override
    protected int maxPrecisionStep() {
        return 64;
    }

    @Override
    public Long value(Object value) {
        if (value == null) {
            return null;
        }
        return Numbers.bytesToLong((byte[]) value);
    }

    @Override
    public Long valueFromString(String value) {
        return ipToLong(value);
    }

    /**
     * IPs should return as a string.
     *
     * @param value
     */
    @Override
    public Object valueForSearch(Object value) {
        return valueAsString(value);
    }

    @Override
    public String valueAsString(Object value) {
        Long val = value(value);
        if (val == null) {
            return null;
        }
        return longToIp(val);
    }

    @Override
    public String indexedValue(String value) {
        BytesRef bytesRef = new BytesRef();
        NumericUtils.longToPrefixCoded(ipToLong(value), precisionStep(), bytesRef);
        return bytesRef.utf8ToString();
    }

    @Override
    public Query fuzzyQuery(String value, String minSim, int prefixLength, int maxExpansions, boolean transpositions) {
        long iValue = ipToLong(value);
        long iSim;
        try {
            iSim = ipToLong(minSim);
        } catch (ElasticSearchIllegalArgumentException e) {
            try {
                iSim = Long.parseLong(minSim);
            } catch (NumberFormatException e1) {
                iSim = (long) Double.parseDouble(minSim);
            }
        }
        return NumericRangeQuery.newLongRange(names.indexName(), precisionStep,
                iValue - iSim,
                iValue + iSim,
                true, true);
    }

    @Override
    public Query fuzzyQuery(String value, double minSim, int prefixLength, int maxExpansions, boolean transpositions) {
        // Lucene 4 Upgrade: It's surprising this uses FuzzyQuery instead of NumericRangeQuery
        int edits = FuzzyQuery.floatToEdits((float) minSim, value.codePointCount(0, value.length()));
        return new FuzzyQuery(names.createIndexNameTerm(indexedValue(value)), edits, prefixLength, maxExpansions, transpositions);
    }

    @Override
    public Query rangeQuery(String lowerTerm, String upperTerm, boolean includeLower, boolean includeUpper, @Nullable QueryParseContext context) {
        return NumericRangeQuery.newLongRange(names.indexName(), precisionStep,
                lowerTerm == null ? null : ipToLong(lowerTerm),
                upperTerm == null ? null : ipToLong(upperTerm),
                includeLower, includeUpper);
    }

    @Override
    public Filter rangeFilter(String lowerTerm, String upperTerm, boolean includeLower, boolean includeUpper, @Nullable QueryParseContext context) {
        return NumericRangeFilter.newLongRange(names.indexName(), precisionStep,
                lowerTerm == null ? null : ipToLong(lowerTerm),
                upperTerm == null ? null : ipToLong(upperTerm),
                includeLower, includeUpper);
    }

    @Override
    public Filter rangeFilter(FieldDataCache fieldDataCache, String lowerTerm, String upperTerm, boolean includeLower, boolean includeUpper, @Nullable QueryParseContext context) {
        return NumericRangeFieldDataFilter.newLongRange(fieldDataCache, names.indexName(),
                lowerTerm == null ? null : ipToLong(lowerTerm),
                upperTerm == null ? null : ipToLong(upperTerm),
                includeLower, includeUpper);
    }

    @Override
    public Filter nullValueFilter() {
        if (nullValue == null) {
            return null;
        }
        final long value = ipToLong(nullValue);
        return NumericRangeFilter.newLongRange(names.indexName(), precisionStep,
                value,
                value,
                true, true);
    }

    @Override
    protected Field innerParseCreateField(ParseContext context) throws IOException {
        String ipAsString;
        if (context.externalValueSet()) {
            ipAsString = (String) context.externalValue();
            if (ipAsString == null) {
                ipAsString = nullValue;
            }
        } else {
            if (context.parser().currentToken() == XContentParser.Token.VALUE_NULL) {
                ipAsString = nullValue;
            } else {
                ipAsString = context.parser().text();
            }
        }

        if (ipAsString == null) {
            return null;
        }
        if (context.includeInAll(includeInAll, this)) {
            context.allEntries().addText(names.fullName(), ipAsString, boost);
        }

        final long value = ipToLong(ipAsString);
        return new LongFieldMapper.CustomLongNumericField(this, value, fieldType);
    }

    @Override
    public FieldDataType fieldDataType() {
        return FieldDataType.DefaultTypes.LONG;
    }

    @Override
    protected String contentType() {
        return CONTENT_TYPE;
    }

    @Override
    public void merge(Mapper mergeWith, MergeContext mergeContext) throws MergeMappingException {
        super.merge(mergeWith, mergeContext);
        if (!this.getClass().equals(mergeWith.getClass())) {
            return;
        }
        if (!mergeContext.mergeFlags().simulate()) {
            this.nullValue = ((IpFieldMapper) mergeWith).nullValue;
        }
    }

    @Override
    protected void doXContentBody(XContentBuilder builder) throws IOException {
        super.doXContentBody(builder);
        if (indexed() != Defaults.IP_FIELD_TYPE.indexed() ||
                analyzed() != Defaults.IP_FIELD_TYPE.tokenized()) {
            builder.field("index", indexTokenizeOptionToString(indexed(), analyzed()));
        }
        if (stored() != Defaults.IP_FIELD_TYPE.stored()) {
            builder.field("store", stored());
        }
        if (storeTermVectors() != Defaults.IP_FIELD_TYPE.storeTermVectors()) {
            builder.field("store_term_vector", storeTermVectors());
        }
        if (storeTermVectorOffsets() != Defaults.IP_FIELD_TYPE.storeTermVectorOffsets()) {
            builder.field("store_term_vector_offsets", storeTermVectorOffsets());
        }
        if (storeTermVectorPositions() != Defaults.IP_FIELD_TYPE.storeTermVectorPositions()) {
            builder.field("store_term_vector_positions", storeTermVectorPositions());
        }
        if (storeTermVectorPayloads() != Defaults.IP_FIELD_TYPE.storeTermVectorPayloads()) {
            builder.field("store_term_vector_payloads", storeTermVectorPayloads());
        }
        if (omitNorms() != Defaults.IP_FIELD_TYPE.omitNorms()) {
            builder.field("omit_norms", omitNorms());
        }
        if (indexOptions() != Defaults.IP_FIELD_TYPE.indexOptions()) {
            builder.field("index_options", indexOptionToString(indexOptions()));
        }
        if (precisionStep != Defaults.PRECISION_STEP) {
            builder.field("precision_step", precisionStep);
        }
        if (similarity() != null) {
            builder.field("similarity", similarity().name());
        }
        if (nullValue != null) {
            builder.field("null_value", nullValue);
        }
        if (includeInAll != null) {
            builder.field("include_in_all", includeInAll);
        }
    }

    public static class NumericIpAnalyzer extends NumericAnalyzer<NumericIpTokenizer> {

        private final int precisionStep;

        public NumericIpAnalyzer() {
            this(NumericUtils.PRECISION_STEP_DEFAULT);
        }

        public NumericIpAnalyzer(int precisionStep) {
            this.precisionStep = precisionStep;
        }

        @Override
        protected NumericIpTokenizer createNumericTokenizer(Reader reader, char[] buffer) throws IOException {
            return new NumericIpTokenizer(reader, precisionStep, buffer);
        }
    }

    public static class NumericIpTokenizer extends NumericTokenizer {

        public NumericIpTokenizer(Reader reader, int precisionStep) throws IOException {
            super(reader, new NumericTokenStream(precisionStep), null);
        }

        public NumericIpTokenizer(Reader reader, int precisionStep, char[] buffer) throws IOException {
            super(reader, new NumericTokenStream(precisionStep), buffer, null);
        }

        @Override
        protected void setValue(NumericTokenStream tokenStream, String value) {
            tokenStream.setLongValue(ipToLong(value));
        }
    }
}
