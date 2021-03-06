package fr.unice.i3s.morph.xr2rml.mongo.abstractquery

import org.apache.log4j.Logger

import es.upm.fi.dia.oeg.morph.base.MorphBaseResultRdfTerms
import es.upm.fi.dia.oeg.morph.base.engine.MorphBaseDataSourceReader
import es.upm.fi.dia.oeg.morph.base.engine.MorphBaseDataTranslator
import es.upm.fi.dia.oeg.morph.base.query.AbstractQuery
import es.upm.fi.dia.oeg.morph.base.querytranslator.MorphBaseQueryOptimizer
import es.upm.fi.dia.oeg.morph.base.querytranslator.MorphBaseQueryTranslator
import es.upm.fi.dia.oeg.morph.base.query.AbstractQueryProjection
import es.upm.fi.dia.oeg.morph.base.querytranslator.TpBindings

/**
 * Representation of the LEFT JOIN abstract query generated from two basic graph patterns.
 *
 * @param left the query representing the left basic graph pattern of the join
 * @param right the query representing the right basic graph pattern of the join
 * @param limit the value of the optional LIMIT keyword in the SPARQL graph pattern
 *
 * @author Franck Michel, I3S laboratory
 */
class AbstractQueryLeftJoin(

    val left: AbstractQuery,
    val right: AbstractQuery,
    lim: Option[Long])

        extends AbstractQuery(new TpBindings, lim) {

    val logger = Logger.getLogger(this.getClass().getName());

    override def equals(a: Any): Boolean = {
        a.isInstanceOf[AbstractQueryLeftJoin] && {
            val q = a.asInstanceOf[AbstractQueryLeftJoin]
            this.left == q.left && this.right == q.right
        }
    }

    override def toString = {
        "[" + left.toString + "\n" +
            "] LEFT JOIN [\n" +
            right.toString + "\n" +
            "] ON " + getSharedVariables + limitStr
    }

    override def toStringConcrete: String = {
        "[" + left.toStringConcrete + "\n" +
            "] LEFT JOIN [\n" +
            right.toStringConcrete + "\n" +
            "] ON " + getSharedVariables + limitStr
    }

    /**
     * Translate all atomic abstract queries of this abstract query into concrete queries.
     * @param translator the query translator
     */
    override def translateAbstactQueriesToConcrete(translator: MorphBaseQueryTranslator): Unit = {
        left.translateAbstactQueriesToConcrete(translator)
        right.translateAbstactQueriesToConcrete(translator)
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
    override def getVariables: Set[String] = {
        (left.getVariables ++ right.getVariables)
    }

    /**
     * Get the xR2RML projection of variable ?x in this query.
     *
     * @param varName the variable name
     * @return a set of projections in which the 'as' field is defined and equals 'varName'
     */
    override def getProjectionsForVariable(varName: String): Set[AbstractQueryProjection] = {
        left.getProjectionsForVariable(varName) ++ right.getProjectionsForVariable(varName)
    }

    /**
     * Optimize left and right members and, if they are atomic queries,
     * try to propagate conditions of the left query to the right query.
     */
    override def optimizeQuery(optimizer: MorphBaseQueryOptimizer): AbstractQuery = {

        if (logger.isDebugEnabled)
            logger.debug("\n------------------ Optimizing query ------------------\n" + this)

        val leftOpt = left.optimizeQuery(optimizer)
        val rightOpt = right.optimizeQuery(optimizer)
        if (leftOpt.isInstanceOf[AbstractQueryAtomicMongo] && rightOpt.isInstanceOf[AbstractQueryAtomicMongo]) {

            // Try to narrow down right atomic query by propagating conditions from the left query
            if (optimizer.propagateConditionFromJoin) {
                var rightAtom = rightOpt.asInstanceOf[AbstractQueryAtomicMongo]
                rightAtom = rightAtom.propagateConditionFromJoinedQuery(leftOpt)
                if (logger.isDebugEnabled)
                    if (rightAtom != right)
                        logger.debug("Propagated condition of from left to right query")
                    else
                        logger.debug("No condition propagation from left to right query")

                val res = new AbstractQueryLeftJoin(leftOpt, rightAtom, limit)
                if (logger.isDebugEnabled) logger.debug("\n------------------ Query optimized into ------------------\n" + res)
                res
            }
        }
        this
    }

    /**
     * Execute the left and right queries, generate the RDF terms for each of the result documents,
     * then make a LEFT JOIN of all the results
     *
     * @param dataSourceReader the data source reader to query the database
     * @param dataTrans the data translator to create RDF terms
     * @return a list of MorphBaseResultRdfTerms instances, one for each result document
     * May return an empty result but NOT null.
     * @throws es.upm.fi.dia.oeg.morph.base.exception.MorphException if the triples map bound to the query has no referencing object map
     */
    override def generateRdfTerms(
        dataSourceReader: MorphBaseDataSourceReader,
        dataTranslator: MorphBaseDataTranslator): List[MorphBaseResultRdfTerms] = {

        val start = System.currentTimeMillis
        if (logger.isInfoEnabled) {
            logger.info("===============================================================================");
            logger.info("Generating RDF terms from the left join query:\n" + this.toStringConcrete)
        }
        var joinResult = Map[Int, MorphBaseResultRdfTerms]()

        // First, generate the triples for both left and right graph patterns of the join
        val leftTriples = left.generateRdfTerms(dataSourceReader, dataTranslator)
        val rightTriples = right.generateRdfTerms(dataSourceReader, dataTranslator)
        var nonJoinedLeft = leftTriples
        var nonJoinedRight = rightTriples

        if (logger.isDebugEnabled)
            logger.debug("Left joining " + leftTriples.size + " left triple(s) with " + rightTriples.size + " right triple(s).")
        if (logger.isTraceEnabled) {
            logger.trace("Left triples:\n" + leftTriples.mkString("\n"))
            logger.trace("Right triples:\n" + rightTriples.mkString("\n"))
        }

        val sharedVars = this.getSharedVariables
        if (sharedVars.isEmpty) {
            val res =
                if (limit.isDefined) {
                    val lim = limit.get
                    val fromLeft = leftTriples.take(lim.toInt)
                    val fromRight = if (fromLeft.size < lim.toInt) rightTriples.take(lim.toInt - fromLeft.size) else List.empty
                    fromLeft ++ fromRight
                } else {
                    leftTriples ++ rightTriples
                }
            logger.info("Left join on empty set of variables computed " + res.size + " triples.")
            res
        } else {
            // For each variable x shared by both graph patterns, select the left and right triples
            // in which at least one term is bound to x, then join the documents on these terms.
            for (x <- sharedVars if (!limit.isDefined || (limit.isDefined && joinResult.size < limit.get))) {

                val leftTripleX = leftTriples.filter(_.hasVariable(x))
                val rightTripleX = rightTriples.filter(_.hasVariable(x))

                for (leftTriple <- leftTripleX if (!limit.isDefined || (limit.isDefined && joinResult.size < limit.get))) {
                    for (rightTriple <- rightTripleX if (!limit.isDefined || (limit.isDefined && joinResult.size < limit.get))) {
                        val leftTerm = leftTriple.getTermForVariable(x)
                        val rightTerm = rightTriple.getTermForVariable(x)
                        if (leftTerm.get != rightTerm.get) {
                            // If there is no match, keep only the left MorphBaseResultRdfTerms instances 
                            if (!joinResult.contains(leftTriple.id))
                                joinResult += (leftTriple.id -> leftTriple)
                        } else {
                            // If there is a match, keep the left and right MorphBaseResultRdfTerms instances 
                            if (!joinResult.contains(leftTriple.id))
                                joinResult += (leftTriple.id -> leftTriple)
                            if (!joinResult.contains(rightTriple.id))
                                joinResult += (rightTriple.id -> rightTriple)
                        }
                    }
                }

                // All left and right triples that do not contain any of the shared variables are kept
                nonJoinedLeft = nonJoinedLeft.filter(!_.hasVariable(x))
                nonJoinedRight = nonJoinedRight.filter(!_.hasVariable(x))
            }

            val res = joinResult.values.toList
            val resNonJoined =
                if (limit.isDefined) {
                    if (joinResult.size < limit.get) {
                        val lim = limit.get - joinResult.size
                        val fromLeft = nonJoinedLeft.take(lim.toInt)
                        val fromRight = if (fromLeft.size < lim.toInt) nonJoinedRight.take(lim.toInt - fromLeft.size) else List.empty
                        fromLeft ++ fromRight
                    } else
                        List.empty
                } else
                    nonJoinedLeft ++ nonJoinedRight

            logger.info("Left join computed " + res.size + " triples + " + resNonJoined.size + " triples with no shared variable, in " + (System.currentTimeMillis - start) + " ms.")
            res ++ resNonJoined
        }
    }

    private def getSharedVariables: Set[String] = {
        left.getVariables.intersect(right.getVariables)
    }
}