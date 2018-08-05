package nl.inl.blacklab.testutil;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.Query;

import nl.inl.blacklab.analysis.BLDutchAnalyzer;
import nl.inl.blacklab.interfaces.struct.MetadataField;
import nl.inl.blacklab.resultproperty.DocPropertyComplexFieldLength;
import nl.inl.blacklab.search.Searcher;
import nl.inl.blacklab.search.indexmetadata.IndexMetadata;
import nl.inl.blacklab.search.indexmetadata.MetadataFieldDesc.ValueListComplete;
import nl.inl.blacklab.search.results.DocResults;
import nl.inl.util.LuceneUtil;

/**
 * Determine the number of tokens in the subcorpus defined by each of the
 * metadatafield values. (Only for those metadata fields that have a limited
 * number of values, all of which were captured in the index metadata file).
 */
public class TokensPerMetaValue {

    public static void main(String[] args) throws IOException, ParseException {

        String indexDir = "/home/jan/blacklab/gysseling/index";
        if (args.length >= 1)
            indexDir = args[0];
        String complexFieldName = "contents";
        if (args.length >= 2)
            complexFieldName = args[1];

        Searcher searcher = Searcher.open(new File(indexDir));
        try {
            // Loop over all metadata fields
            IndexMetadata indexStructure = searcher.getIndexStructure();
            System.out.println("field\tvalue\tnumberOfDocs\tnumberOfTokens");
            for (String metaFieldName : indexStructure.getMetadataFields()) {
                // Check if this field has only a few values
                MetadataField fd = indexStructure.metadataField(metaFieldName);
                if (fd.isValueListComplete().equals(ValueListComplete.YES)) {
                    // Loop over the values
                    for (Map.Entry<String, Integer> entry : fd.valueDistribution().entrySet()) {
                        // Determine token count for this value
                        String fieldName = fd.name();
                        Query filter = LuceneUtil.parseLuceneQuery("\"" + entry.getKey().toLowerCase() + "\"",
                                new BLDutchAnalyzer(), fieldName);
                        DocResults docs = searcher.queryDocuments(filter);
                        int totalNumberOfTokens = docs.intSum(new DocPropertyComplexFieldLength(complexFieldName));
                        System.out.println(fieldName + "\t" + entry.getKey() + "\t" + entry.getValue() + "\t"
                                + totalNumberOfTokens);
                    }
                }
            }
        } finally {
            searcher.close();
        }
    }
}
