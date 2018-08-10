/*******************************************************************************
 * Copyright (c) 2010, 2012 Institute for Dutch Lexicology
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package nl.inl.blacklab.index;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;

import net.jcip.annotations.NotThreadSafe;
import nl.inl.blacklab.contentstore.ContentStore;
import nl.inl.blacklab.exceptions.BlackLabRuntimeException;
import nl.inl.blacklab.exceptions.DocumentFormatException;
import nl.inl.blacklab.exceptions.MalformedInputFile;
import nl.inl.blacklab.forwardindex.ForwardIndex;
import nl.inl.blacklab.index.DocIndexerFactory.Format;
import nl.inl.blacklab.index.annotated.AnnotationWriter;
import nl.inl.blacklab.indexers.config.ConfigInputFormat;
import nl.inl.blacklab.search.BlackLabIndexWriter;
import nl.inl.blacklab.search.ContentAccessor;
import nl.inl.blacklab.search.indexmetadata.AnnotatedFieldNameUtil;
import nl.inl.blacklab.search.indexmetadata.Annotation;
import nl.inl.util.FileProcessor;
import nl.inl.util.FileUtil;
import nl.inl.util.UnicodeStream;

/**
 * Tool for indexing. Reports its progress to an IndexListener.
 * 
 * Not thread-safe, although indexing itself can use thread in certain cases
 * (only when using configuration file based indexing right now)
 */
@NotThreadSafe // in index mode
class IndexerImpl implements DocWriter, Indexer {

    static final Logger logger = LogManager.getLogger(IndexerImpl.class);

    /**
     * FileProcessor FileHandler that creates a DocIndexer for every file and
     * performs some reporting.
     */
    private class DocIndexerWrapper implements FileProcessor.FileHandler {

        @Override
        public void file(String path, InputStream is, File file) throws Exception {
            // Attempt to detect the encoding of our inputStream, falling back to DEFAULT_INPUT_ENCODING if the stream 
            // doesn't contain a a BOM This doesn't do any character parsing/decoding itself, it just detects and skips
            // the BOM (if present) and exposes the correct character set for this stream (if present)
            // This way we can later use the charset to decode the input
            // There is one gotcha however, and that is that if the inputstream contains non-textual data, we pass the 
            // default encoding to our DocIndexer
            // This usually isn't an issue, since docIndexers work exclusively with either binary data or text.
            // In the case of binary data docIndexers, they should always ignore the encoding anyway
            // and for text docIndexers, passing a binary file is an error in itself already.
            try (
                    UnicodeStream inputStream = new UnicodeStream(is, DEFAULT_INPUT_ENCODING);
                    DocIndexer docIndexer = DocumentFormats.get(IndexerImpl.this.formatIdentifier, IndexerImpl.this, path,
                            inputStream, inputStream.getEncoding());) {
                impl(docIndexer, path);
            }
        }

        public void file(String path, Reader reader) throws Exception {
            try (DocIndexer docIndexer = DocumentFormats.get(IndexerImpl.this.formatIdentifier, IndexerImpl.this, path,
                    reader)) {
                impl(docIndexer, path);
            }
        }

        private void impl(DocIndexer indexer, String documentName) throws Exception {
            // FIXME progress reporting is broken in multithreaded indexing, as the listener is shared between threads
            // So a docIndexer that didn't index anything can slip through if another thread did index some data in the 
            // meantime
            listener().fileStarted(documentName);
            int docsDoneBefore = searcher.writer().numDocs();
            long tokensDoneBefore = listener().getTokensProcessed();

            indexer.index();
            listener().fileDone(documentName);
            int docsDoneAfter = searcher.writer().numDocs();
            if (docsDoneAfter == docsDoneBefore) {
                logger.warn("No docs found in " + documentName + "; wrong format?");
            }
            long tokensDoneAfter = listener().getTokensProcessed();
            if (tokensDoneAfter == tokensDoneBefore) {
                logger.warn("No words indexed in " + documentName + "; wrong format?");
            }
        }

        @Override
        public void directory(File dir) throws Exception {
            // ignore
        }
    }

    protected DocIndexerWrapper docIndexerWrapper = new DocIndexerWrapper();

    /** Our index */
    protected BlackLabIndexWriter searcher;

    /** Stop after indexing this number of docs. -1 if we shouldn't stop. */
    protected int maxNumberOfDocsToIndex = -1;

    /** Should we terminate indexing? (e.g. because of an error) */
    boolean terminateIndexing = false;

    /**
     * Where to report indexing progress.
     */
    protected IndexListener listener = null;

    /**
     * Have we reported our creation and the start of indexing to the listener yet?
     */
    protected boolean createAndIndexStartReported = false;

    /**
     * When we encounter a zip or tgz file, do we descend into it like it was a
     * directory?
     */
    boolean processArchivesAsDirectories = true;

    /**
     * Recursively index files inside a directory? (or archive file, if
     * processArchivesAsDirectories == true)
     */
    protected boolean defaultRecurseSubdirs = true;

    /**
     * Format of the documents we're going to be indexing, used to create the
     * correct type of DocIndexer.
     */
    protected String formatIdentifier;

    /**
     * Parameters we should pass to our DocIndexers upon instantiation.
     */
    protected Map<String, String> indexerParam;

    /** How to index metadata fields (tokenized) */
    protected FieldType metadataFieldTypeTokenized;

    /** How to index metadata fields (untokenized) */
    protected FieldType metadataFieldTypeUntokenized;

    /** Where to look for files linked from the input files */
    protected List<File> linkedFileDirs = new ArrayList<>();

    /**
     * If a file cannot be found in the linkedFileDirs, use this to retrieve it (if
     * present)
     */
    protected Function<String, File> linkedFileResolver;

    /** Index using multiple threads? */
    protected boolean useThreads = false;

    // TODO this is a workaround for a bug where indexMetadata is always written, even when an indexing task was 
    // rollbacked on an empty index result of this is that the index can never be opened again (the forwardindex 
    // is missing files that the indexMetadata.yaml says must exist?) so record rollbacks and then don't write 
    // the updated indexMetadata
    boolean hasRollback = false;

    /** Was this Indexer closed? */
    private boolean closed = false;

    /**
     * Construct Indexer
     *
     * @param directory the main BlackLab index directory
     * @param create if true, creates a new index; otherwise, appends to existing
     *            index
     * @throws IOException
     * @throws DocumentFormatException if autodetection of the document format
     *             failed
     */
    IndexerImpl(File directory, boolean create)
            throws IOException, DocumentFormatException {
        this(directory, create, (String) null, null);
    }

    /**
     * Construct Indexer
     *
     * @param directory the main BlackLab index directory
     * @param create if true, creates a new index; otherwise, appends to existing
     *            index. When creating a new index, a formatIdentifier or an
     *            indexTemplateFile containing a valid "documentFormat" value should
     *            also be supplied. Otherwise adding new data to the index isn't
     *            possible, as we can't construct a DocIndexer to do the actual
     *            indexing without a valid formatIdentifier.
     * @param formatIdentifier (optional) determines how this Indexer will index any
     *            new data added to it. If omitted, when opening an existing index,
     *            the formatIdentifier in its metadata (as "documentFormat") is used
     *            instead. When creating a new index, this format will be stored as
     *            the default for that index, unless another default is already set
     *            by the indexTemplateFile (as "documentFormat"), it will still be
     *            used by this Indexer however.
     * @param indexTemplateFile JSON file to use as template for index structure /
     *            metadata (if creating new index)
     * @throws DocumentFormatException if no formatIdentifier was specified and
     *             autodetection failed
     * @throws IOException
     */
    IndexerImpl(File directory, boolean create, String formatIdentifier, File indexTemplateFile)
            throws DocumentFormatException, IOException {
        init(directory, create, formatIdentifier, indexTemplateFile);
    }

    protected void init(File directory, boolean create, String formatIdentifier, File indexTemplateFile)
            throws IOException, DocumentFormatException {

        if (create) {
            if (indexTemplateFile != null) {
                searcher = BlackLabIndexWriter.openForWriting(directory, true, indexTemplateFile);

                // Read back the formatIdentifier that was provided through the indexTemplateFile now that the index 
                // has written it might be null
                final String defaultFormatIdentifier = searcher.metadataWriter().documentFormat();

                if (DocumentFormats.isSupported(formatIdentifier)) {
                    this.formatIdentifier = formatIdentifier;
                    if (defaultFormatIdentifier == null || defaultFormatIdentifier.isEmpty()) {
                        // indexTemplateFile didn't provide a default formatIdentifier,
                        // overwrite it with our provided formatIdentifier
                        searcher.metadataWriter().setDocumentFormat(formatIdentifier);
                        searcher.metadataWriter().save();
                    }
                } else if (DocumentFormats.isSupported(defaultFormatIdentifier)) {
                    this.formatIdentifier = defaultFormatIdentifier;
                } else {
                    // TODO we should delete the newly created index here as it failed, how do we clean up files properly?
                    searcher.close();
                    throw new DocumentFormatException("Input format config '" + formatIdentifier
                            + "' not found (or format config contains an error) when creating new index in "
                            + directory);
                }
            } else if (DocumentFormats.isSupported(formatIdentifier)) {
                this.formatIdentifier = formatIdentifier;

                // No indexTemplateFile, but maybe the formatIdentifier is backed by a ConfigInputFormat (instead of some other DocIndexer implementation)
                // this ConfigInputFormat could then still be used as a minimal template to setup the index
                // (if there's no ConfigInputFormat, that's okay too, a default index template will be used instead)
                ConfigInputFormat format = null;
                for (Format desc : DocumentFormats.getFormats()) {
                    if (desc.getId().equals(formatIdentifier) && desc.getConfig() != null) {
                        format = desc.getConfig();
                        break;
                    }
                }

                // template might still be null, in that case a default will be created
                searcher = BlackLabIndexWriter.openForWriting(directory, true, format);

                String defaultFormatIdentifier = searcher.metadata().documentFormat();
                if (defaultFormatIdentifier == null || defaultFormatIdentifier.isEmpty()) {
                    // ConfigInputFormat didn't provide a default formatIdentifier,
                    // overwrite it with our provided formatIdentifier
                    searcher.metadataWriter().setDocumentFormat(formatIdentifier);
                    searcher.metadataWriter().save();
                }
            } else {
                throw new DocumentFormatException("Input format config '" + formatIdentifier
                        + "' not found (or format config contains an error) when creating new index in " + directory);
            }
        } else { // opening an existing index

            this.searcher = BlackLabIndexWriter.openForWriting(directory, false);
            String defaultFormatIdentifier = this.searcher.metadata().documentFormat();

            if (DocumentFormats.isSupported(formatIdentifier))
                this.formatIdentifier = formatIdentifier;
            else if (DocumentFormats.isSupported(defaultFormatIdentifier))
                this.formatIdentifier = defaultFormatIdentifier;
            else {
                searcher.close();
                String message = formatIdentifier == null ? "No formatIdentifier"
                        : "Unknown formatIdentifier '" + formatIdentifier + "'";
                throw new DocumentFormatException(
                        message + ", and could not determine the default documentFormat for index " + directory);
            }
        }

        metadataFieldTypeTokenized = new FieldType();
        metadataFieldTypeTokenized.setStored(true);
        //metadataFieldTypeTokenized.setIndexed(true);
        metadataFieldTypeTokenized.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
        metadataFieldTypeTokenized.setTokenized(true);
        metadataFieldTypeTokenized.setOmitNorms(true); // @@@ <-- depending on setting?
        metadataFieldTypeTokenized.setStoreTermVectors(true);
        metadataFieldTypeTokenized.setStoreTermVectorPositions(true);
        metadataFieldTypeTokenized.setStoreTermVectorOffsets(true);
        metadataFieldTypeTokenized.freeze();

        metadataFieldTypeUntokenized = new FieldType(metadataFieldTypeTokenized);
        metadataFieldTypeUntokenized.setIndexOptions(IndexOptions.DOCS_AND_FREQS);
        //metadataFieldTypeUntokenized.setTokenized(false);  // <-- this should be done with KeywordAnalyzer, otherwise untokenized fields aren't lowercased
        metadataFieldTypeUntokenized.setStoreTermVectors(false);
        metadataFieldTypeUntokenized.setStoreTermVectorPositions(false);
        metadataFieldTypeUntokenized.setStoreTermVectorOffsets(false);
        metadataFieldTypeUntokenized.freeze();
    }

    @Override
    public FieldType metadataFieldType(boolean tokenized) {
        return tokenized ? metadataFieldTypeTokenized : metadataFieldTypeUntokenized;
    }

    /* (non-Javadoc)
     * @see nl.inl.blacklab.index.IndexerInterface#setProcessArchivesAsDirectories(boolean)
     */
    @Override
    public void setProcessArchivesAsDirectories(boolean b) {
        processArchivesAsDirectories = b;
    }

    /* (non-Javadoc)
     * @see nl.inl.blacklab.index.IndexerInterface#setRecurseSubdirs(boolean)
     */
    @Override
    public void setRecurseSubdirs(boolean recurseSubdirs) {
        this.defaultRecurseSubdirs = recurseSubdirs;
    }

    /* (non-Javadoc)
     * @see nl.inl.blacklab.index.IndexerInterface#setFormatIdentifier(java.lang.String)
     */
    @Override
    public void setFormatIdentifier(String formatIdentifier) throws DocumentFormatException {
        if (!DocumentFormats.isSupported(formatIdentifier))
            throw new DocumentFormatException("Cannot set formatIdentifier '" + formatIdentifier + "' for index "
                    + this.searcher.name() + "; unknown identifier");

        this.formatIdentifier = formatIdentifier;
    }

    /* (non-Javadoc)
     * @see nl.inl.blacklab.index.IndexerInterface#setListener(nl.inl.blacklab.index.IndexListener)
     */
    @Override
    public void setListener(IndexListener listener) {
        this.listener = listener;
        listener(); // report creation and start of indexing, if it hadn't been reported yet
    }

    /* (non-Javadoc)
     * @see nl.inl.blacklab.index.IndexerInterface#listener()
     */
    @Override
    public IndexListener listener() {
        if (listener == null) {
            listener = new IndexListenerReportConsole();
        }
        if (!createAndIndexStartReported) {
            createAndIndexStartReported = true;
            listener.indexerCreated(this);
            listener.indexStart();
        }
        return listener;
    }

    /**
     * Log an exception that occurred during indexing
     * 
     * @param msg log message
     * @param e the exception
     */
    protected void log(String msg, Exception e) {
        logger.error(msg, e);
    }

    /* (non-Javadoc)
     * @see nl.inl.blacklab.index.IndexerInterface#setMaxNumberOfDocsToIndex(int)
     */
    @Override
    public void setMaxNumberOfDocsToIndex(int n) {
        this.maxNumberOfDocsToIndex = n;
    }

    /* (non-Javadoc)
     * @see nl.inl.blacklab.index.IndexerInterface#rollback()
     */
    @Override
    public void rollback() {
        listener().rollbackStart();
        searcher.rollback();
        listener().rollbackEnd();
        hasRollback = true;
    }

    /* (non-Javadoc)
     * @see nl.inl.blacklab.index.IndexerInterface#close()
     */
    // TODO this should call close() on running FileProcessors
    @Override
    public synchronized void close() {

        // Signal to the listener that we're done indexing and closing the index (which might take a
        // while)
        listener().indexEnd();
        listener().closeStart();

        if (!hasRollback) {
            searcher.metadataWriter().addToTokenCount(listener().getTokensProcessed());
            searcher.metadataWriter().save();
        }
        searcher.close();

        // Signal that we're completely done now
        listener().closeEnd();
        listener().indexerClosed();
        
        closed = true;
    }
    
    /* (non-Javadoc)
     * @see nl.inl.blacklab.index.IndexerInterface#isClosed()
     */
    @Override
    public boolean isClosed() {
        return closed;
    }

    /**
     * Add a Lucene document to the index
     *
     * @param document the document to add
     * @throws CorruptIndexException
     * @throws IOException
     */
    @Override
    public void add(Document document) throws CorruptIndexException, IOException {
        searcher.writer().addDocument(document);
        listener().luceneDocumentAdded();
    }

    /* (non-Javadoc)
     * @see nl.inl.blacklab.index.IndexerInterface#update(org.apache.lucene.index.Term, org.apache.lucene.document.Document)
     */
    @Override
    public void update(Term term, Document document) throws CorruptIndexException, IOException {
        searcher.writer().updateDocument(term, document);
        listener().luceneDocumentAdded();
    }

    /**
     * Add a list of tokens to a forward index
     *
     * @param prop the annotation to get values and position increments from
     * @return the id assigned to the content
     */
    @Override
    public int addToForwardIndex(AnnotationWriter prop) {
        Annotation annotation = searcher.getOrCreateAnnotation(prop.field(), prop.getName());
        ForwardIndex forwardIndex = searcher.forwardIndex(annotation);
        if (forwardIndex == null)
            throw new IllegalArgumentException("No forward index for field " + AnnotatedFieldNameUtil.annotationField(prop.field().name(), prop.getName()));
        return forwardIndex.addDocument(prop.getValues(), prop.getPositionIncrements());
    }

    /* (non-Javadoc)
     * @see nl.inl.blacklab.index.IndexerInterface#index(java.lang.String, java.io.InputStream)
     */
    @Override
    public void index(String documentName, InputStream input) {
        index(documentName, input, "*");
    }

    /* (non-Javadoc)
     * @see nl.inl.blacklab.index.IndexerInterface#index(java.lang.String, java.io.Reader)
     */
    @Override
    public void index(String documentName, Reader reader) throws Exception {
        try {
            docIndexerWrapper.file(documentName, reader);
        } catch (MalformedInputFile e) {
            listener.errorOccurred(e, documentName, null);
            logger.error("Parsing " + documentName + " failed:");
            e.printStackTrace();
            logger.error("(continuing indexing)");
        } catch (Exception e) {
            listener.errorOccurred(e, documentName, null);
            logger.error("Parsing " + documentName + " failed:");
            e.printStackTrace();
            logger.error("(continuing indexing)");
        }
    }

    /* (non-Javadoc)
     * @see nl.inl.blacklab.index.IndexerInterface#index(java.lang.String, java.io.InputStream, java.lang.String)
     */
    @Override
    public void index(String fileName, InputStream input, String fileNameGlob) {
        try (FileProcessor proc = new FileProcessor(this.useThreads, this.defaultRecurseSubdirs,
                this.processArchivesAsDirectories)) {
            proc.setFileNameGlob(fileNameGlob);
            proc.setFileHandler(docIndexerWrapper);
            proc.setErrorHandler(listener);
            proc.processInputStream(fileName, input, null);
        }
    }

    /* (non-Javadoc)
     * @see nl.inl.blacklab.index.IndexerInterface#index(java.io.File)
     */
    @Override
    public void index(File file) {
        index(file, "*");
    }

    /* (non-Javadoc)
     * @see nl.inl.blacklab.index.IndexerInterface#index(java.io.File, java.lang.String)
     */
    // TODO this is nearly a literal copy of index for a stream, unify them somehow (take care that file might be a directory)
    @Override
    public void index(File file, String fileNameGlob) {
        try (FileProcessor proc = new FileProcessor(useThreads, this.defaultRecurseSubdirs,
                this.processArchivesAsDirectories)) {
            proc.setFileNameGlob(fileNameGlob);
            proc.setFileHandler(docIndexerWrapper);
            proc.setErrorHandler(listener);
            proc.processFile(file);
        } catch (FileNotFoundException e) {
            throw BlackLabRuntimeException.wrap(e);
        }
    }

    /**
     * Should we continue indexing or stop?
     *
     * We stop if we've reached the maximum that was set (if any), or if a fatal
     * error has occurred (indicated by terminateIndexing).
     *
     * @return true if we should continue, false if not
     */
    @Override
    public synchronized boolean continueIndexing() {
        if (terminateIndexing)
            return false;
        if (maxNumberOfDocsToIndex >= 0) {
            return docsToDoLeft() > 0;
        }
        return true;
    }

    /**
     * How many more documents should we process?
     *
     * @return the number of documents
     */
    @Override
    public synchronized int docsToDoLeft() {
        if (maxNumberOfDocsToIndex < 0)
            return maxNumberOfDocsToIndex;
        int docsDone = searcher.writer().numDocs();
        return Math.max(0, maxNumberOfDocsToIndex - docsDone);
    }

    /*
     * BlackLab index version history:
     * 1. Initial version
     * 2. Sort index added to forward index; multiple forward indexes possible
     */

    @Override
    public ContentStore contentStore(String fieldName) {
        ContentAccessor contentAccessor = searcher.contentAccessor(searcher.field(fieldName));
        return contentAccessor == null ? null : contentAccessor.getContentStore();
    }

    /* (non-Javadoc)
     * @see nl.inl.blacklab.index.IndexerInterface#indexLocation()
     */
    @Override
    public File indexLocation() {
        return searcher.indexDirectory();
    }

    /* (non-Javadoc)
     * @see nl.inl.blacklab.index.IndexerInterface#setIndexerParam(java.util.Map)
     */
    @Override
    public void setIndexerParam(Map<String, String> indexerParam) {
        this.indexerParam = indexerParam;
    }

    /**
     * Get the parameters we would like to be passed to the DocIndexer class.
     *
     * Used by DocIndexer classes to get their parameters.
     * 
     * @return the parameters
     */
    @Override
    public Map<String, String> indexerParameters() {
        return indexerParam;
    }

    /**
     * Get the IndexWriter we're using.
     *
     * Useful if e.g. you want to access FSDirectory.
     *
     * @return the IndexWriter
     */
    protected IndexWriter writer() {
        return searcher.writer();
    }

    @Override
    public BlackLabIndexWriter indexWriter() {
        return searcher;
    }

    /* (non-Javadoc)
     * @see nl.inl.blacklab.index.IndexerInterface#setLinkedFileDirs(java.util.List)
     */
    @Override
    public void setLinkedFileDirs(List<File> linkedFileDirs) {
        this.linkedFileDirs.clear();
        this.linkedFileDirs.addAll(linkedFileDirs);
    }

    /**
     * Add a directory to search for linked files.
     *
     * DocIndexerXPath allows us to index a second file into the same Lucene
     * document, which is useful for external metadata, etc. This determines how
     * linked files are located.
     *
     * @param linkedFileDir directory to search
     */
    public void addLinkedFileDir(File linkedFileDir) {
        this.linkedFileDirs.add(linkedFileDir);
    }

    /* (non-Javadoc)
     * @see nl.inl.blacklab.index.IndexerInterface#setLinkedFileResolver(java.util.function.Function)
     */
    @Override
    public void setLinkedFileResolver(Function<String, File> resolver) {
        this.linkedFileResolver = resolver;
    }

    @Override
    public Optional<Function<String, File>> linkedFileResolver() {
        return Optional.of(this.linkedFileResolver);
    }

    @Override
    public File linkedFile(String inputFile) {
        File f = new File(inputFile);
        if (f.exists())
            return f; // either absolute or relative to current dir
        if (f.isAbsolute())
            return null; // we tried absolute, but didn't find it

        // Look in the configured directories for the relative path
        f = FileUtil.findFile(linkedFileDirs, inputFile, null);
        if (f == null && this.linkedFileResolver != null)
            f = this.linkedFileResolver.apply(inputFile);

        return f;
    }

    /* (non-Javadoc)
     * @see nl.inl.blacklab.index.IndexerInterface#setUseThreads(boolean)
     */
    @Override
    public void setUseThreads(boolean useThreads) {
        this.useThreads = useThreads;

        // TODO some of the class-based docIndexers don't support theaded indexing
        if (!DocumentFormats.getFormat(formatIdentifier).isConfigurationBased()) {
            logger.info("Threaded indexing is disabled for format " + formatIdentifier);
            this.useThreads = false;
        }
    }
}
