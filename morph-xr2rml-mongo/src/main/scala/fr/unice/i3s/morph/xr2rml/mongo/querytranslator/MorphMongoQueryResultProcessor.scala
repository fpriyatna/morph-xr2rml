package es.upm.fi.dia.oeg.morph.rdb.querytranslator

import java.io.Writer
import org.apache.log4j.Logger
import com.hp.hpl.jena.query.Query
import com.hp.hpl.jena.query.QueryExecution
import com.hp.hpl.jena.query.QueryExecutionFactory
import com.hp.hpl.jena.query.ResultSet
import com.hp.hpl.jena.query.ResultSetFormatter
import es.upm.fi.dia.oeg.morph.base.MorphBaseResultSet
import es.upm.fi.dia.oeg.morph.base.MorphProperties
import es.upm.fi.dia.oeg.morph.base.engine.MorphBaseDataSourceReader
import es.upm.fi.dia.oeg.morph.base.query.MorphAbstractQuery
import es.upm.fi.dia.oeg.morph.base.querytranslator.MorphBaseQueryResultProcessor
import es.upm.fi.dia.oeg.morph.r2rml.model.R2RMLMappingDocument
import fr.unice.i3s.morph.xr2rml.mongo.engine.MorphMongoDataTranslator
import es.upm.fi.dia.oeg.morph.base.engine.IMorphFactory

/**
 * Execute the database query and produce the XML SPARQL result set
 */
class MorphMongoQueryResultProcessor(factory: IMorphFactory) extends MorphBaseQueryResultProcessor(factory) {

    val logger = Logger.getLogger(this.getClass().getName());

    /**
     * Execute the database query, translate the database results into triples,
     * evaluate the SPARQL query on the resulting graph and save the XML output to a file.
     *
     * @param mapSparqlSql map of SPARQL queries and associated MorphAbstractQuery instances.
     * Each MorphAbstractQuery has been translated into executable target queries
     */
    override def translateResult(mapSparqlSql: Map[Query, MorphAbstractQuery]) {

        mapSparqlSql.foreach(mapElement => {
            var start = System.currentTimeMillis();
            val sparqlQuery: Query = mapElement._1
            factory.getDataTranslator.translateData_QueryRewriting(mapElement._2)
            var end = System.currentTimeMillis();
            logger.info("Duration of query execution and generation of triples = " + (end - start) + "ms.");

            // Late SPARQL evaluation: evaluate the SPARQL query on the result graph
            start = System.currentTimeMillis();
            val qexec: QueryExecution = QueryExecutionFactory.create(sparqlQuery, factory.getMaterializer.model)
            val resultSet: ResultSet = qexec.execSelect();
            while (resultSet.hasNext()) {
                val strResultSet = ResultSetFormatter.asXMLString(resultSet)
                factory.getMaterializer.outputStream.write(strResultSet)
            }
            end = System.currentTimeMillis();
            logger.info("Late SPARQL query evaluation time = " + (end - start) + "ms.");

            factory.getMaterializer.outputStream.flush()
            factory.getMaterializer.outputStream.close()
        })
    }

    override def preProcess(sparqlQuery: Query): Unit = {}

    override def process(sparqlQuery: Query, resultSet: MorphBaseResultSet): Unit = {}

    override def postProcess(): Unit = {}

    override def getOutput(): Object = { null }
}