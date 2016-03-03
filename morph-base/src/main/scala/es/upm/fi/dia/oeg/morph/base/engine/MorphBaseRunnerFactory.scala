package es.upm.fi.dia.oeg.morph.base.engine

import java.io.PrintWriter
import java.io.StringWriter
import java.io.Writer

import org.apache.log4j.Logger

import com.hp.hpl.jena.query.QueryFactory

import es.upm.fi.dia.oeg.morph.base.GenericConnection
import es.upm.fi.dia.oeg.morph.base.MorphProperties
import es.upm.fi.dia.oeg.morph.base.materializer.MaterializerFactory
import es.upm.fi.dia.oeg.morph.base.materializer.MorphBaseMaterializer
import es.upm.fi.dia.oeg.morph.base.querytranslator.MorphBaseQueryResultProcessor
import es.upm.fi.dia.oeg.morph.base.querytranslator.MorphBaseQueryTranslator
import es.upm.fi.dia.oeg.morph.r2rml.model.R2RMLMappingDocument

abstract class MorphBaseRunnerFactory {
    val logger = Logger.getLogger(this.getClass());

    def createRunner(configurationDirectory: String, configurationFile: String): MorphBaseRunner = {
        val configurationProperties = MorphProperties.apply(configurationDirectory, configurationFile);
        this.createRunner(configurationProperties);
    }

    def createRunner(properties: MorphProperties): MorphBaseRunner = {

        if (logger.isDebugEnabled) logger.debug("Creating MorphBaseRunner")

        // Building CONNECTION
        val connection = this.createConnection(properties);

        // Building MAPPING DOCUMENT
        val mappingDocument = R2RMLMappingDocument(properties.mappingDocumentFilePath, properties, connection);

        // Building UNFOLDER
        val unfolder = this.createUnfolder(properties, mappingDocument);

        // Building MATERIALIZER
        val outputStream: Writer =
            if (properties.outputFilePath.isDefined)
                new PrintWriter(properties.outputFilePath.get, "UTF-8")
            else new StringWriter
        val materializer = this.buildMaterializer(properties, mappingDocument, outputStream);

        // Building DATA SOURCE READER
        val dataSourceReader = this.createDataSourceReader(mappingDocument, properties, connection);

        // Building DATA TRANSLATOR
        val dataTranslator = this.createDataTranslator(mappingDocument, materializer, unfolder, dataSourceReader, properties)

        // ---------------------------------------------------------------------------------
        // The Data Source Reader, Query Translator, Query Result Writer, Result Processor, are only applicable
        // in the case of query rewriting access mode, i.e. not in data materialization.
        // ---------------------------------------------------------------------------------

        // Building QUERY TRANSLATOR
        val queryTranslator = this.createQueryTranslator(properties, mappingDocument, dataSourceReader)

        // Building RESULT PROCESSOR to translate the SQL result set into a SPARQL result set and serialize it into a file
        val resultProcessor = this.createQueryResultProcessor(properties, mappingDocument, dataSourceReader, dataTranslator, queryTranslator, outputStream)

        // ---------------------------------------------------------------------------------
        // Creation of final runner object
        // ---------------------------------------------------------------------------------

        val sparqlQuery =
            if (properties.queryFilePath.isDefined)
                Some(QueryFactory.read(properties.queryFilePath.get))
            else None
        val runner = new MorphBaseRunner(mappingDocument, unfolder, dataTranslator, queryTranslator, resultProcessor, outputStream, sparqlQuery)
        runner;
    }

    def createConnection(configurationProperties: MorphProperties): GenericConnection

    def createUnfolder(properties: MorphProperties, md: R2RMLMappingDocument): MorphBaseUnfolder

    def createDataSourceReader(md: R2RMLMappingDocument, properties: MorphProperties, connection: GenericConnection): MorphBaseDataSourceReader

    def createDataTranslator(
        md: R2RMLMappingDocument,
        materializer: MorphBaseMaterializer,
        unfolder: MorphBaseUnfolder,
        dataSourceReader: MorphBaseDataSourceReader,
        properties: MorphProperties): MorphBaseDataTranslator

    def createQueryTranslator(properties: MorphProperties, md: R2RMLMappingDocument, dataSourceReader: MorphBaseDataSourceReader): MorphBaseQueryTranslator

    def createQueryResultProcessor(
        properties: MorphProperties,
        md: R2RMLMappingDocument,
        dataSourceReader: MorphBaseDataSourceReader,
        dataTranslator: MorphBaseDataTranslator,
        queryTranslator: MorphBaseQueryTranslator,
        outputStream: Writer): MorphBaseQueryResultProcessor;

    private def buildMaterializer(configurationProperties: MorphProperties, mappingDocument: R2RMLMappingDocument, outputStream: Writer): MorphBaseMaterializer = {
        val jenaMode = configurationProperties.jenaMode;
        val materializer = MaterializerFactory.create(outputStream, jenaMode);
        val mappingDocumentPrefixMap = mappingDocument.mappingDocumentPrefixMap;
        if (mappingDocumentPrefixMap != null) {
            materializer.setModelPrefixMap(mappingDocumentPrefixMap);
        }
        materializer
    }
}
