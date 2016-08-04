package nl.javadude.gradle.plugins.license.maven;

import static com.mycila.maven.plugin.license.document.DocumentType.defaultMapping;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.*;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.gradle.api.GradleException;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.file.FileCollection;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

import com.mycila.maven.plugin.license.Callback;
import com.mycila.maven.plugin.license.HeaderSection;
import com.mycila.maven.plugin.license.document.Document;
import com.mycila.maven.plugin.license.document.DocumentPropertiesLoader;
import com.mycila.maven.plugin.license.document.DocumentType;
import com.mycila.maven.plugin.license.header.AdditionalHeaderDefinition;
import com.mycila.maven.plugin.license.header.Header;
import com.mycila.maven.plugin.license.header.HeaderDefinition;
import com.mycila.maven.plugin.license.header.HeaderType;

import com.mycila.xmltool.XMLDoc;

public class AbstractLicenseMojo {
    static Logger logger = Logging.getLogger(AbstractLicenseMojo.class);

    // Backing AbstraceLicenseMojo
    Collection<File> validHeaders; // Convert to FileCollection
    File rootDir;
    Map<String, String> initial;

    protected String[] keywords = new String[] { "copyright" };
    protected String[] headerDefinitions = new String[0]; // TODO Not sure how a user would specify
    protected HeaderSection[] headerSections = new HeaderSection[0];
    protected String encoding;
    protected float concurrencyFactor = 1.5f;
    protected Map<String, String> mapping;
    
    boolean dryRun;
    boolean skipExistingHeaders;
    boolean useDefaultMappings;
    boolean strictCheck;
    URI header;
    FileCollection source;
    DocumentPropertiesLoader documentPropertiesLoader;

    public AbstractLicenseMojo(Collection<File> validHeaders, File rootDir, Map<String, String> initial,
                    boolean dryRun, boolean skipExistingHeaders, boolean useDefaultMappings, boolean strictCheck,
                    URI header, FileCollection source, Map<String, String> mapping, String encoding, DocumentPropertiesLoader documentPropertiesLoader) {
        super();
        this.validHeaders = validHeaders;
        this.rootDir = rootDir;
        this.initial = initial;
        this.dryRun = dryRun;
        this.skipExistingHeaders = skipExistingHeaders;
        this.useDefaultMappings = useDefaultMappings;
        this.strictCheck = strictCheck;
        this.header = header;
        this.source = source;
        this.mapping = mapping;
        this.encoding = encoding;
        this.documentPropertiesLoader = documentPropertiesLoader;
    }

    protected void execute(final Callback callback) throws MalformedURLException {

        final Header h = new Header(header.toURL(), encoding, headerSections);
        logger.debug("Header {}:\n{}", h.getLocation(), h);

        if (this.validHeaders == null)
            this.validHeaders = new ArrayList<File>();
        final List<Header> validHeaders = new ArrayList<Header>(this.validHeaders.size());
        for (File validHeader : this.validHeaders) {
            validHeaders.add(new Header(validHeader.toURI().toURL(), encoding, headerSections));
        }

        final DocumentFactory documentFactory = new DocumentFactory(rootDir, buildMapping(), buildHeaderDefinitions(),
                        encoding, keywords, documentPropertiesLoader);

        int nThreads = (int) (Runtime.getRuntime().availableProcessors() * concurrencyFactor);
        ExecutorService executorService = Executors.newFixedThreadPool(nThreads);
        CompletionService<Boolean> completionService = new ExecutorCompletionService<Boolean>(executorService);
        int count = 0;
        logger.debug("Number of execution threads: {}", nThreads);

        try {
            for (final File file : source) {
                completionService.submit(new Runnable() {
                    public void run() {
                        Document document = documentFactory.createDocuments(file);
                        logger.debug("Selected file: {} [header style: {}]", DocumentFactory.getRelativeFile(rootDir, document),
                                        document.getHeaderDefinition());
                        if (document.isNotSupported()) {
                            logger.warn("Unknown file extension: {}", DocumentFactory.getRelativeFile(rootDir, document));
                        } else if (document.is(h)) {
                            logger.debug("Skipping header file: {}", DocumentFactory.getRelativeFile(rootDir, document));
                        } else if (document.hasHeader(h, strictCheck)) {
                            callback.onExistingHeader(document, h);
                        } else {
                            boolean headerFound = false;
                            for (Header validHeader : validHeaders) {
                                headerFound = document.hasHeader(validHeader, strictCheck);
                                if (headerFound) {
                                    callback.onExistingHeader(document, h);
                                    break;
                                }
                            }
                            if (!headerFound)
                                callback.onHeaderNotFound(document, h);
                        }
                    }
                }, null);
                count++;
            }

            while (count-- > 0) {
                try {
                    completionService.take().get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof Error)
                        throw (Error) cause;
                    if (cause instanceof RuntimeException)
                        throw (RuntimeException) cause;
                    throw new GradleException(cause.getMessage(), cause);
                }
            }

        } finally {
            executorService.shutdownNow();
        }

    }

    private Map<String, String> buildMapping() {
        Map<String, String> extensionMapping = useDefaultMappings ? new HashMap<String, String>(defaultMapping())
                        : new HashMap<String, String>();
                        
        List<HeaderType> headerTypes = Arrays.asList(HeaderType.values());
        Set<String> validHeaderTypes = new HashSet<String>();
        for (HeaderType headerType : headerTypes) {
            validHeaderTypes.add(headerType.name());
        }

        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            String headerType = entry.getValue().toUpperCase();
            String fileType = entry.getKey().toLowerCase();
            if (!validHeaderTypes.contains(headerType)) {
                throw new InvalidUserDataException(String.format("The provided header type (%s) for %s is invalid", headerType, fileType));
            }
            extensionMapping.put(fileType, headerType);
        }
        // force inclusion of unknown item to manage unknown files
        extensionMapping.put(DocumentType.UNKNOWN.getExtension(), DocumentType.UNKNOWN.getDefaultHeaderTypeName());
        return extensionMapping;
    }

    private Map<String, HeaderDefinition> buildHeaderDefinitions() {
        // like mappings, first get default definitions
        final Map<String, HeaderDefinition> headers = new HashMap<String, HeaderDefinition>(
                        HeaderType.defaultDefinitions());
        // and then override them with those provided in properties file
        for (String resource : headerDefinitions) {
            final AdditionalHeaderDefinition fileDefinitions = new AdditionalHeaderDefinition(XMLDoc.from(findResource(resource), true));
            final Map<String, HeaderDefinition> map = fileDefinitions.getDefinitions();
            logger.debug("{} header definitions loaded from '{}'", map.size(), resource);
            headers.putAll(map);
        }
        // force inclusion of unknown item to manage unknown files
        headers.put(HeaderType.UNKNOWN.getDefinition().getType(), HeaderType.UNKNOWN.getDefinition());
        return headers;
    }

    // TODO reimplement with Gradle classloaders
    private File findResource(String resource) {
        return null;
    }

}
