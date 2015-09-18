package fr.unice.i3s.morph.xr2rml.mongo.engine

import java.io.Writer

import org.apache.log4j.Logger

import es.upm.fi.dia.oeg.morph.base.Constants
import es.upm.fi.dia.oeg.morph.base.GenericConnection
import es.upm.fi.dia.oeg.morph.base.MorphProperties
import es.upm.fi.dia.oeg.morph.base.engine.MorphBaseDataSourceReader
import es.upm.fi.dia.oeg.morph.base.engine.MorphBaseDataTranslator
import es.upm.fi.dia.oeg.morph.base.engine.MorphBaseRunnerFactory
import es.upm.fi.dia.oeg.morph.base.engine.MorphBaseUnfolder
import es.upm.fi.dia.oeg.morph.base.materializer.MorphBaseMaterializer
import es.upm.fi.dia.oeg.morph.base.querytranslator.IQueryTranslator
import es.upm.fi.dia.oeg.morph.base.querytranslator.MorphBaseQueryResultProcessor
import es.upm.fi.dia.oeg.morph.r2rml.model.R2RMLMappingDocument
import fr.unice.i3s.morph.xr2rml.mongo.MongoUtils

class MorphJsondocRunnerFactory extends MorphBaseRunnerFactory {

    override val logger = Logger.getLogger(this.getClass().getName())

    /**
     * Return a valid connection to the database or raises a run time exception if anything goes wrong
     */
    override def createConnection(props: MorphProperties): GenericConnection = {
        if (props.noOfDatabase == 0)
            throw new Exception("No database connection parameters found in the configuration.")

        val dbType = props.databaseType
        val cnx = dbType match {
            case Constants.DATABASE_MONGODB =>
                MongoUtils.createConnection(props)
            case _ =>
                throw new Exception("Database type not supported: " + dbType)
        }
        cnx
    }

    override def createUnfolder(props: MorphProperties, md: R2RMLMappingDocument): MorphJsondocUnfolder = {
        val dbType = props.databaseType
        val cnx = dbType match {
            case Constants.DATABASE_MONGODB =>
                MongoUtils.createConnection(props)
            case _ =>
                throw new Exception("Database type not supported: " + dbType)
        }
        val unfolder = new MorphJsondocUnfolder(md.asInstanceOf[R2RMLMappingDocument], props)
        unfolder.dbType = dbType
        unfolder
    }

    override def createDataSourceReader(
        properties: MorphProperties, connection: GenericConnection): MorphBaseDataSourceReader = { null }

    override def createDataTranslator(
        mappingDocument: R2RMLMappingDocument,
        materializer: MorphBaseMaterializer,
        unfolder: MorphBaseUnfolder,
        connection: GenericConnection,
        properties: MorphProperties): MorphBaseDataTranslator = {

        new MorphJsondocDataTranslator(
            mappingDocument, materializer, unfolder.asInstanceOf[MorphJsondocUnfolder], connection, properties);
    }

    override def createQueryTranslator(
        properties: MorphProperties, md: R2RMLMappingDocument, connection: GenericConnection): IQueryTranslator = { null }

    override def createQueryResultProcessor(
        properties: MorphProperties,
        md: R2RMLMappingDocument,
        connection: GenericConnection,
        dataSourceReader: MorphBaseDataSourceReader,
        queryTranslator: IQueryTranslator,
        outputStream: Writer): MorphBaseQueryResultProcessor = { null }
}
