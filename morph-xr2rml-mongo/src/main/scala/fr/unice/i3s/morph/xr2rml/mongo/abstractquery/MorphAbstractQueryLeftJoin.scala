package fr.unice.i3s.morph.xr2rml.mongo.abstractquery

import org.apache.log4j.Logger
import es.upm.fi.dia.oeg.morph.base.MorphBaseResultRdfTerms
import es.upm.fi.dia.oeg.morph.base.engine.MorphBaseDataSourceReader
import es.upm.fi.dia.oeg.morph.base.engine.MorphBaseDataTranslator
import es.upm.fi.dia.oeg.morph.base.query.MorphAbstractQuery
import es.upm.fi.dia.oeg.morph.r2rml.model.R2RMLTriplesMap
import fr.unice.i3s.morph.xr2rml.mongo.engine.MorphMongoDataTranslator
import fr.unice.i3s.morph.xr2rml.mongo.querytranslator.MorphMongoQueryTranslator
import es.upm.fi.dia.oeg.morph.base.querytranslator.MorphBaseQueryTranslator

/**
 * Representation of the LEFT JOIN abstract query generated from two basic graph patterns.
 *
 * @param left the query representing the left basic graph pattern of the join
 * @param right the query representing the right basic graph pattern of the join
 */
class MorphAbstractQueryLeftJoin(

    val left: MorphAbstractQuery,
    val right: MorphAbstractQuery)

        extends MorphAbstractQuery(None) {

    val logger = Logger.getLogger(this.getClass().getName());

    override def toString = {
        "[" + left.toString + "]\n" +
            "LEFt JOIN\n[" +
            right.toString + "]\n" +
            "ON " + getSharedVariables
    }

    override def toStringConcrete: String = {
        "[" + left.toStringConcrete + "]\n" +
            "LEFT JOIN\n[" +
            right.toStringConcrete + "]\n" +
            "ON " + getSharedVariables
    }

    /**
     * Translate all atomic abstract queries of this abstract query into concrete queries.
     * @param translator the query translator
     */
    override def translateAtomicAbstactQueriesToConcrete(translator: MorphBaseQueryTranslator): Unit = {
        left.translateAtomicAbstactQueriesToConcrete(translator)
        right.translateAtomicAbstactQueriesToConcrete(translator)
    }

    /**
     * Check if atomic abstract queries within this query have a target query properly initialized
     * i.e. targetQuery is not empty
     */
    override def isTargetQuerySet: Boolean = {
        left.isTargetQuerySet && right.isTargetQuerySet
    }

    /**
     * Return the list of SPARQL variables projected in this abstract query
     */
    override def getVariables: List[String] = {
        (left.getVariables ++ right.getVariables).sortWith(_ < _).distinct
    }

    /**
     * Execute the left and right queries, generate the RDF terms for each of the result documents,
     * then make a LEFT JOIN of all the results
     *
     * @param dataSourceReader the data source reader to query the database
     * @param dataTrans the data translator to create RDF terms
     * @return a list of MorphBaseResultRdfTerms instances, one for each result document
     * May return an empty result but NOT null.
     * @throws MorphException if the triples map bound to the query has no referencing object map
     */
    override def generateRdfTerms(
        dataSourceReader: MorphBaseDataSourceReader,
        dataTranslator: MorphBaseDataTranslator): List[MorphBaseResultRdfTerms] = {

        logger.info("Generating RDF terms from the inner join query:\n" + this.toStringConcrete)
        val joinResult: scala.collection.mutable.Map[String, MorphBaseResultRdfTerms] = new scala.collection.mutable.HashMap

        // First, generate the triples for both left and right graph patterns of the join
        val leftTriples = left.generateRdfTerms(dataSourceReader, dataTranslator)
        val rightTriples = right.generateRdfTerms(dataSourceReader, dataTranslator)

        if (logger.isDebugEnabled)
            logger.debug("Inner joining " + leftTriples.size + " left triples with " + rightTriples.size + " right triples.")
        if (logger.isTraceEnabled) {
            logger.trace("Left triples:\n" + leftTriples.mkString("\n"))
            logger.trace("Right triples:\n" + rightTriples.mkString("\n"))
        }

        // For each variable x shared by both graph patterns, select the left and right triples
        // in which at least one term is bound to x, then join the documents on these terms.
        for (x <- this.getSharedVariables) {

            val leftTripleX = leftTriples.filter(_.hasVariable(x))
            val rightTripleX = rightTriples.filter(_.hasVariable(x))

            for (leftTriple <- leftTripleX) {
                val leftTerm = leftTriple.getTermsForVariable(x)
                for (rightTriple <- rightTripleX) {
                    val rightTerm = rightTriple.getTermsForVariable(x)
                    if (leftTerm.intersect(rightTerm).isEmpty) {
                        // If there is no match, keep only the left MorphBaseResultRdfTerms instances 
                        if (!joinResult.contains(leftTriple.getId))
                            joinResult += (leftTriple.getId -> leftTriple)
                    } else {
                        // If there is a match, keep the left and right MorphBaseResultRdfTerms instances 
                        if (!joinResult.contains(leftTriple.getId))
                            joinResult += (leftTriple.getId -> leftTriple)
                        if (!joinResult.contains(rightTriple.getId))
                            joinResult += (rightTriple.getId -> rightTriple)
                    }
                }
            }
        }
        if (logger.isDebugEnabled)
            logger.debug("Inner join computed " + joinResult.size + " results.")
        joinResult.values.toList
    }

    private def getSharedVariables = {
        left.getVariables.intersect(right.getVariables)
    }
}