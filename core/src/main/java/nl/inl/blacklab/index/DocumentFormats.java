package nl.inl.blacklab.index;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import nl.inl.blacklab.index.xpath.ConfigInputFormat;
import nl.inl.blacklab.index.xpath.DocIndexerXPathOpenSonarCmdi;
import nl.inl.blacklab.index.xpath.DocIndexerXPathOpenSonarFoLiA;
import nl.inl.blacklab.indexers.DocIndexerAlto;
import nl.inl.blacklab.indexers.DocIndexerFolia;
import nl.inl.blacklab.indexers.DocIndexerPageXml;
import nl.inl.blacklab.indexers.DocIndexerTei;
import nl.inl.blacklab.indexers.DocIndexerTeiPosInFunctionAttr;
import nl.inl.blacklab.indexers.DocIndexerTeiText;
import nl.inl.blacklab.indexers.DocIndexerWhiteLab2;
import nl.inl.blacklab.indexers.DocIndexerXmlSketch;

/**
 * Document format registry, for resolving a DocIndexer class given a
 * format identifier (common abbreviation or (qualified) class name).
 */
public class DocumentFormats {

	/** Document formats */
    @Deprecated
	static Map<String, Class<? extends DocIndexer>> formats = new TreeMap<>();

	/** Factories for different document formats */
    static Map<String, DocIndexerFactory> factories = new TreeMap<>();

	static {
		// Some abbreviations for commonly used builtin DocIndexers.
		// You can also specify the classname for builtin DocIndexers,
		// or a fully-qualified name for your custom DocIndexer (must
		// be on the classpath)
		register("alto", DocIndexerAlto.class);
		register("folia", DocIndexerFolia.class);
		register("whitelab2", DocIndexerWhiteLab2.class);
		register("pagexml", DocIndexerPageXml.class);
		register("sketchxml", DocIndexerXmlSketch.class);

		// TEI has a number of variants
		// By default, the contents of the "body" element are indexed, but alternatively you can index the contents of "text".
		// By default, the "type" attribute is assumed to contain PoS, but alternatively you can use the "function" attribute.
		register("tei", DocIndexerTei.class);
		register("tei-element-body", DocIndexerTei.class);
		register("tei-element-text", DocIndexerTeiText.class);
		register("tei-pos-type", DocIndexerTei.class);
		register("tei-pos-function", DocIndexerTeiPosInFunctionAttr.class);

        register(DocIndexerXPathOpenSonarFoLiA.getConfig());
        register(DocIndexerXPathOpenSonarCmdi.getConfig());
	}

	/**
	 * Register a DocIndexer class under an abbreviated document format identifier.
	 *
	 * @param formatAbbreviation the format abbreviation, e.g. "tei"
	 *   (NOTE: format abbreviations are case-insensitive, and are lowercased internally)
	 * @param docIndexerClass the DocIndexer class for this format
	 */
    @Deprecated
	public static void register(String formatAbbreviation, final Class<? extends DocIndexer> docIndexerClass) {
		formats.put(formatAbbreviation.toLowerCase(), docIndexerClass);
		factories.put(formatAbbreviation.toLowerCase(), new DocIndexerFactoryClass(docIndexerClass));
	}

	/**
	 * Register an input format configuration under its name.
	 *
	 * @param config input format configuration to register
	 */
	public static void register(final ConfigInputFormat config) {
	    factories.put(config.getName().toLowerCase(), new DocIndexerFactoryConfig(config));
	}

	/**
	 * Get the DocIndexer class associated with the format identifier.
	 *
	 * @param formatIdentifier format identifier, e.g. "tei" or "com.example.MyIndexer"
	 * @return the DocIndexer class, or null if not found
	 */
    @Deprecated
	public static Class<? extends DocIndexer> getIndexerClass(String formatIdentifier) {
		// Check if it's a known abbreviation.
		Class<?> docIndexerClass = formats.get(formatIdentifier.toLowerCase());
		if (docIndexerClass == null) {
			// No; is it a fully qualified class name?
			try {
				docIndexerClass = Class.forName(formatIdentifier);
			} catch (Exception e1) {
				try {
					// No. Is it a class in the BlackLab indexers package?
					docIndexerClass = Class.forName("nl.inl.blacklab.indexers." + formatIdentifier);
				} catch (Exception e) {
					// Couldn't be resolved. That's okay, we'll just return null and let
					// the application deal with it.
				}
			}
		}
		return docIndexerClass.asSubclass(DocIndexer.class);
	}

    @SuppressWarnings("unchecked")
    public static DocIndexerFactory getIndexerFactory(String formatIdentifier) {
	    DocIndexerFactory factory = factories.get(formatIdentifier.toLowerCase());
	    if (factory != null)
	        return factory;
        // No; is it a fully qualified class name?
        Class<? extends DocIndexer> docIndexerClass = null;
        try {
            docIndexerClass = (Class<? extends DocIndexer>) Class.forName(formatIdentifier);
        } catch (Exception e1) {
            try {
                // No. Is it a class in the BlackLab indexers package?
                docIndexerClass = (Class<? extends DocIndexer>) Class.forName("nl.inl.blacklab.indexers." + formatIdentifier);
            } catch (Exception e) {
                // Couldn't be resolved. That's okay, we'll just return null and let
                // the application deal with it.
            }
        }
        if (docIndexerClass != null) {
            return new DocIndexerFactoryClass(docIndexerClass);
        }
        return null;
	}

	/**
	 * Check if a particular string denotes a valid document format.
	 *
	 * @param formatIdentifier format identifier, e.g. "tei" or "com.example.MyIndexer"
	 * @return true iff it corresponds to a format
	 */
	public static boolean exists(String formatIdentifier) {
		return getIndexerFactory(formatIdentifier) != null;
	}

	/**
	 * Returns a sorted list of registered document format abbreviations.
	 *
	 * @return the list of registered abbreviations
	 */
	public static List<String> list() {
		List<String> l = new ArrayList<>(formats.keySet());
		Collections.sort(l);
		return Collections.unmodifiableList(l);
	}

}
