package fr.unice.i3s.morph.xr2rml.mongo.querytranslator

import scala.collection.JavaConversions.asScalaBuffer
import scala.collection.JavaConversions.seqAsJavaList

import org.apache.log4j.Logger

import com.hp.hpl.jena.graph.NodeFactory
import com.hp.hpl.jena.graph.Triple
import com.hp.hpl.jena.sparql.algebra.Op
import com.hp.hpl.jena.sparql.algebra.TableFactory
import com.hp.hpl.jena.sparql.algebra.op.OpBGP
import com.hp.hpl.jena.sparql.algebra.op.OpDistinct
import com.hp.hpl.jena.sparql.algebra.op.OpFilter
import com.hp.hpl.jena.sparql.algebra.op.OpGroup
import com.hp.hpl.jena.sparql.algebra.op.OpJoin
import com.hp.hpl.jena.sparql.algebra.op.OpLeftJoin
import com.hp.hpl.jena.sparql.algebra.op.OpOrder
import com.hp.hpl.jena.sparql.algebra.op.OpProject
import com.hp.hpl.jena.sparql.algebra.op.OpSlice
import com.hp.hpl.jena.sparql.algebra.op.OpTable
import com.hp.hpl.jena.sparql.algebra.op.OpUnion
import com.hp.hpl.jena.sparql.core.BasicPattern
import com.hp.hpl.jena.sparql.engine.binding.BindingFactory

import es.upm.fi.dia.oeg.morph.base.Constants
import es.upm.fi.dia.oeg.morph.base.engine.IMorphFactory
import es.upm.fi.dia.oeg.morph.base.exception.MorphException
import es.upm.fi.dia.oeg.morph.base.query.AbstractQuery
import es.upm.fi.dia.oeg.morph.base.query.AbstractQueryConditionEquals
import es.upm.fi.dia.oeg.morph.base.query.AbstractQueryProjection
import es.upm.fi.dia.oeg.morph.base.query.ConditionType
import es.upm.fi.dia.oeg.morph.base.query.IReference
import es.upm.fi.dia.oeg.morph.base.querytranslator.MorphBaseQueryTranslator
import es.upm.fi.dia.oeg.morph.base.querytranslator.MorphBaseTriplePatternBinder
import es.upm.fi.dia.oeg.morph.base.querytranslator.TPBinding
import es.upm.fi.dia.oeg.morph.base.querytranslator.TPBindings
import fr.unice.i3s.morph.xr2rml.mongo.MongoDBQuery
import fr.unice.i3s.morph.xr2rml.mongo.abstractquery.AbstractAtomicQuery
import fr.unice.i3s.morph.xr2rml.mongo.abstractquery.AbstractQueryInnerJoin
import fr.unice.i3s.morph.xr2rml.mongo.abstractquery.AbstractQueryInnerJoinRef
import fr.unice.i3s.morph.xr2rml.mongo.abstractquery.AbstractQueryLeftJoin
import fr.unice.i3s.morph.xr2rml.mongo.abstractquery.AbstractQueryUnion
import fr.unice.i3s.morph.xr2rml.mongo.query.MongoQueryNode
import fr.unice.i3s.morph.xr2rml.mongo.query.MongoQueryNodeAnd
import fr.unice.i3s.morph.xr2rml.mongo.query.MongoQueryNodeUnion

/**
 * Translation of a SPARQL query into a set of MongoDB queries.
 *
 * This class assumes that the xR2RML mapping is normalized, that is, a triples map
 * has exactly one predicate-object map, and each predicate-object map has
 * exactly one predicate and one object map.
 * In the code this assumption is mentioned by the annotation @NORMALIZED_ASSUMPTION
 *
 * @author Franck Michel (franck.michel@cnrs.fr)
 */
class MorphMongoQueryTranslator(factory: IMorphFactory) extends MorphBaseQueryTranslator(factory) {

    val triplePatternBinder: MorphBaseTriplePatternBinder = new MorphBaseTriplePatternBinder(factory)

    override val logger = Logger.getLogger(this.getClass());

    /**
     * High level entry point to the query translation process.
     *
     * @todo The project part is calculated but not managed: all fields of MongoDB documents are retrieved.
     * Besides, only the references are listed, but they must be bound to the variable they represent
     * (the AS of the project part) so that the INNER JOIN can be computed, and from the reference
     * we must figure out which field exactly is to be projected and translate this into a MongoDB
     * collection.find() projection parameter. E.g.: \$.field.* =&gt; {'field':true}<br>
     *
     * @return a AbstractQuery instance in which the targetQuery parameter has been set with
     * a list containing a set of concrete queries. May return None if no bindings are found.
     */
    override def translate(op: Op): Option[AbstractQuery] = {
        if (logger.isDebugEnabled) logger.debug("opSparqlQuery = " + op)
        val start = System.currentTimeMillis()

        //--- Calculate the Triple Pattern Bindings
        var opMod = excludeTriplesAboutCollecOrContainer(op)
        if (!opMod.isDefined) {
            logger.warn("Query cannot be processed due to collection/container related triples.")
            return None
        }
        val bindings = triplePatternBinder.bindm(opMod.get)
        logger.info("Triple pattern bindings computation time = " + (System.currentTimeMillis() - start) + "ms.")
        logger.info("Triple pattern bindings:\n" + bindings.values.mkString("\n"))

        //--- Translate the SPARQL query into an abstract query
        val emptyBindings = bindings.filter(b => b._2.bound.isEmpty)
        if (bindings.isEmpty) {
            logger.warn("No bindings found for any triple pattern of the query")
            None
        } else if (!emptyBindings.isEmpty) {
            logger.warn("Could not find bindings for triple patterns:\n" + emptyBindings.keys)
            None
        } else {
            var abstractQuery = translateSparqlQueryToAbstract(bindings, opMod.get)
            if (abstractQuery.isDefined) {

                // Optimize the abstract query
                val absq = abstractQuery.get.optimizeQuery(optimizer)
                if (absq != abstractQuery.get) {
                    logger.info("\n-------------------------------------------------------------------------\n" +
                        "------------------ Abstract query BEFORE optimization: ------------------\n" + abstractQuery.get)
                    logger.info("\n------------------ Abstract query AFTER optimization: -------------------\n" + absq +
                        "\n-------------------------------------------------------------------------")
                }
                //--- Translate the atomic abstract queries into concrete MongoDB queries
                absq.translateAtomicAbstactQueriesToConcrete(this)
                Some(absq)
            } else
                throw new MorphException("Error: cannot translate SPARQL query to an abstract query")
        }
    }

    /**
     * Recursive translation of a SPARQL query into an abstract query
     *
     * @param bindings bindings of the the SPARQL query triple patterns
     * @param op SPARQL query or SPARQL query element
     * @return a AbstractQuery instance or None if the query element is not supported in the translation
     * @todo manage SPARQL filters
     */
    private def translateSparqlQueryToAbstract(bindings: Map[String, TPBindings], op: Op): Option[AbstractQuery] = {
        op match {
            case opProject: OpProject => { // SELECT clause
                this.translateSparqlQueryToAbstract(bindings, opProject.getSubOp)
            }
            case bgp: OpBGP => { // Basic Graph Pattern
                val triples: List[Triple] = bgp.getPattern.getList.toList
                if (triples.size == 0)
                    None
                else if (triples.size == 1) {
                    val tpBindings = this.getTpBindings(bindings, triples.head)
                    if (!tpBindings.isDefined) {
                        logger.warn("No binding defined for triple pattern " + triples.head.toString)
                        None
                    } else
                        Some(this.transTPm(tpBindings.get))
                } else {
                    // Make an INER JOIN between the first triple pattern and the rest of the triple patterns
                    val tpBindingsLeft = this.getTpBindings(bindings, triples.head)
                    if (!tpBindingsLeft.isDefined) {
                        logger.warn("No binding defined for triple pattern " + triples.head.toString)
                        None
                    } else {
                        val left = this.transTPm(tpBindingsLeft.get)
                        val right = this.translateSparqlQueryToAbstract(bindings, new OpBGP(BasicPattern.wrap(triples.tail)))
                        if (right.isDefined)
                            Some(new AbstractQueryInnerJoin(List(left, right.get)))
                        else
                            Some(left)
                    }
                }
            }
            case opJoin: OpJoin => { // AND pattern
                val left = translateSparqlQueryToAbstract(bindings, opJoin.getLeft)
                val right = translateSparqlQueryToAbstract(bindings, opJoin.getRight)
                if (left.isDefined && right.isDefined)
                    Some(new AbstractQueryInnerJoin(List(left.get, right.get)))
                else if (left.isDefined)
                    left
                else if (right.isDefined)
                    right
                else None
            }
            case opLeftJoin: OpLeftJoin => { //OPT pattern
                val left = translateSparqlQueryToAbstract(bindings, opLeftJoin.getLeft)
                val right = translateSparqlQueryToAbstract(bindings, opLeftJoin.getRight)
                if (left.isDefined && right.isDefined)
                    Some(new AbstractQueryLeftJoin(left.get, right.get))
                else if (left.isDefined)
                    left
                else if (right.isDefined)
                    right
                else None
            }
            case opUnion: OpUnion => { //UNION pattern
                val left = translateSparqlQueryToAbstract(bindings, opUnion.getLeft)
                val right = translateSparqlQueryToAbstract(bindings, opUnion.getRight)

                if (left.isDefined && right.isDefined)
                    Some(new AbstractQueryUnion(List(left.get, right.get)))
                else if (left.isDefined)
                    left
                else if (right.isDefined)
                    right
                else None
            }
            case opFilter: OpFilter => { //FILTER pattern
                logger.warn("SPARQL Filter no supported. Ignoring it.")
                this.translateSparqlQueryToAbstract(bindings, opFilter.getSubOp)
            }
            case opSlice: OpSlice => {
                logger.warn("SPARQL LIMIT no supported in query translation.")
                this.translateSparqlQueryToAbstract(bindings, opSlice.getSubOp)
            }
            case opDistinct: OpDistinct => {
                logger.warn("SPARQL DISTINCT no supported in query translation.")
                this.translateSparqlQueryToAbstract(bindings, opDistinct.getSubOp)
            }
            case opOrder: OpOrder => {
                logger.warn("SPARQL ORDER no supported in query translation.")
                this.translateSparqlQueryToAbstract(bindings, opOrder.getSubOp)
            }
            case opGroup: OpGroup => {
                logger.warn("SPARQL GROUP BY no supported in query translation.")
                this.translateSparqlQueryToAbstract(bindings, opGroup.getSubOp)
            }
            case _ => {
                logger.warn("SPARQL feature no supported in query translation.")
                None
            }
        }
    }

    private final val REGEX_RDF_CONTAINER_PREDICATE = ("^" + Constants.RDF_NS + """_\p{Alnum}+""").r
    private final val REGEX_RDF_LIST_PREDICATE = ("^" + Constants.RDF_NS + """(first|rest|nil)""").r
    private final val REGEX_RDF_LIST_CONT_CLASSES = ("^" + Constants.RDF_NS + """(List|Bag|Seq|Alt)""").r

    /**
     * Check if a triple deals with collections or containers, i.e. either its predicate is one<br>
     * - rdf:_1, rdf:_2, ... for containers, or<br>
     * - rdf:first, rdf:rest or rdf:nil for collections,<br>
     * or its predicate is rdf:type and the object is one of rdf:List, rdf:Bag, rdf:Seq, rdf:Alt.
     */
    private def isTripleAboutCollecOrContainer(tp: Triple): Boolean = {

        // Is the predicate rdf:_1, ...,rdf:_n?
        val pred = tp.getPredicate.toString
        val obj = tp.getObject.toString

        return REGEX_RDF_CONTAINER_PREDICATE.findFirstMatchIn(pred).isDefined ||
            REGEX_RDF_LIST_PREDICATE.findFirstMatchIn(pred).isDefined ||
            // rdf:type [rdf:List|rdf:Bag|rdf:Seq|rdf:Alt]
            (pred == (Constants.RDF_NS + "type") && tp.getObject.isURI && REGEX_RDF_LIST_CONT_CLASSES.findFirstMatchIn(obj).isDefined)
    }
    
    /**
     * Remove from a SPARQL query all triples that pertain to the management of RDF collections and containers:
     * i.e. with a predicate rdf:first, rdf:rest, rdf:nil, rdf:_1, rdf:_2 etc.
     * or rdf:type and the object is one of rdf:List, rdf:Bag, rdf:Seq, rdf:Alt.
     *
     * @param op a SPARQL query
     * @return the same query minus triples involving predicates about RDF collections and containers,
     * or None if there is no more triples after removal
     */
    def excludeTriplesAboutCollecOrContainer(op: Op): Option[Op] = {
        val result = op match {
            case opProject: OpProject => {
                val subOp = excludeTriplesAboutCollecOrContainer(opProject.getSubOp)
                if (subOp.isDefined)
                    Some(new OpProject(subOp.get, opProject.getVars))
                else None
            }
            case bgp: OpBGP => { // Basic Graph Pattern
                val triples: List[Triple] = bgp.getPattern.getList.toList

                if (triples.size == 0)
                    None
                else if (triples.size == 1) {
                    if (isTripleAboutCollecOrContainer(triples.head)) {
                        if (logger.isDebugEnabled)
                            logger.debug("Ignoring triple " + triples.head)
                        None
                    } else
                        Some(bgp)
                } else {
                    // There are several triples in the basic graph pattern
                    if (isTripleAboutCollecOrContainer(triples.head)) {
                        if (logger.isDebugEnabled)
                            logger.debug("Ignoring triple " + triples.head)
                        // Forget the first triple and process the subsequent triples of the basic pattern
                        excludeTriplesAboutCollecOrContainer(new OpBGP(BasicPattern.wrap(triples.tail)))

                    } else {
                        // Keep the first triple and process the subsequent triples of the basic pattern
                        val newBgp = excludeTriplesAboutCollecOrContainer(new OpBGP(BasicPattern.wrap(triples.tail)))
                        if (newBgp.isDefined) {
                            // Create the new list of selected triples and make a basic pattern out of it
                            val newTriplesList = List(triples.head) ++ newBgp.get.asInstanceOf[OpBGP].getPattern.getList.toList
                            Some(new OpBGP(BasicPattern.wrap(newTriplesList)))
                        } else
                            Some(new OpBGP(BasicPattern.wrap(List(triples.head))))
                    }
                }
            }
            case opJoin: OpJoin => { // AND pattern
                val left = excludeTriplesAboutCollecOrContainer(opJoin.getLeft)
                val right = excludeTriplesAboutCollecOrContainer(opJoin.getRight)
                if (left.isDefined && right.isDefined)
                    Some(OpJoin.create(left.get, right.get))
                else if (left.isDefined)
                    left
                else
                    right
            }
            case opLeftJoin: OpLeftJoin => { //OPT pattern
                val left = excludeTriplesAboutCollecOrContainer(opLeftJoin.getLeft)
                val right = excludeTriplesAboutCollecOrContainer(opLeftJoin.getRight)
                if (left.isDefined && right.isDefined)
                    Some(OpLeftJoin.create(left.get, right.get, opLeftJoin.getExprs))
                else if (left.isDefined)
                    left
                else
                    right
            }
            case opUnion: OpUnion => { //UNION pattern
                val left = excludeTriplesAboutCollecOrContainer(opUnion.getLeft)
                val right = excludeTriplesAboutCollecOrContainer(opUnion.getRight)
                if (left.isDefined && right.isDefined)
                    Some(OpUnion.create(left.get, right.get))
                else if (left.isDefined)
                    left
                else
                    right
            }
            case opFilter: OpFilter => {
                val sub = excludeTriplesAboutCollecOrContainer(opFilter.getSubOp)
                if (sub.isDefined)
                    Some(OpFilter.filter(opFilter.getExprs, sub.get))
                else
                    None
            }
            case opSlice: OpSlice => {
                val sub = excludeTriplesAboutCollecOrContainer(opSlice.getSubOp)
                if (sub.isDefined)
                    Some(new OpSlice(sub.get, opSlice.getStart, opSlice.getLength))
                else
                    None
            }
            case opDistinct: OpDistinct => {
                val sub = excludeTriplesAboutCollecOrContainer(opDistinct.getSubOp)
                if (sub.isDefined)
                    Some(OpDistinct.create(sub.get))
                else
                    None
            }
            case opOrder: OpOrder => {
                val sub = excludeTriplesAboutCollecOrContainer(opOrder.getSubOp)
                if (sub.isDefined)
                    Some(new OpOrder(sub.get, opOrder.getConditions))
                else
                    None
            }
            case opGroup: OpGroup => {
                val sub = excludeTriplesAboutCollecOrContainer(opGroup.getSubOp)
                if (sub.isDefined)
                    Some(new OpGroup(sub.get, opGroup.getGroupVars, opGroup.getAggregators))
                else
                    None
            }
            case _ => { Some(op) }
        }
        result
    }

    /**
     * Retrieve the triples maps bound to a triple pattern
     */
    private def getTpBindings(bindings: Map[String, TPBindings], triple: Triple): Option[TPBindings] = {

        if (bindings.contains(triple.toString))
            Some(bindings(triple.toString))
        else
            None
    }

    /**
     * Translation of a triple pattern into an abstract query under a set of xR2RML triples maps
     *
     * @param tpBindings a SPARQL triple pattern and the triples maps bound to it
     * @return abstract query. This may be an UNION if there are multiple triples maps,
     * and this may contain INNER JOINs for triples maps that have a referencing object map (parent triples map).
     * If there is only one triples map and no parent triples map, the result is an atomic abstract query.
     * @throws es.upm.fi.dia.oeg.morph.base.exception.MorphException
     */
    override def transTPm(tpBindings: TPBindings): AbstractQuery = {
        val tp = tpBindings.tp
        val unionOf = for (tm <- tpBindings.bound) yield {

            // Sanity checks about the @NORMALIZED_ASSUMPTION
            val poms = tm.getPropertyMappings
            if (poms.isEmpty || poms.size > 1)
                throw new MorphException("The candidate triples map " + tm.toString + " must have exactly one predicate-object map.")
            val pom = poms.head
            if (pom.predicateMaps.size != 1 ||
                !((!pom.hasObjectMap && pom.hasRefObjectMap) || (pom.hasObjectMap && !pom.hasRefObjectMap)))
                throw new MorphException("The candidate triples map " + tm.toString + " must have exactly one predicate map and one object map.")

            // Start translation
            val from = tm.logicalSource
            val project = genProjection(tp, tm)
            val where = genCond(tp, tm)
            val Q =
                if (!pom.hasRefObjectMap)
                    // If there is no parent triples map, simply return this atomic abstract query
                    new AbstractAtomicQuery(Set(new TPBinding(tp, tm)), from, project, where)
                else {
                    // If there is a parent triples map, create an INNER JOIN ON childRef = parentRef
                    val q1 = new AbstractAtomicQuery(Set.empty, from, project, where) // no tp nor TM in case of a RefObjectMap

                    val rom = pom.getRefObjectMap(0)
                    val Pfrom = factory.getMappingDocument.getParentTriplesMap(rom).logicalSource
                    val Pproject = genProjectionParent(tp, tm)
                    var Pwhere = genCondParent(tp, tm)

                    if (rom.joinConditions.size != 1)
                        logger.warn("Multiple join conditions not supported in a ReferencingObjectMap. Considering only the first one.")
                    val jc = rom.joinConditions.toIterable.head // assume only one join condition. @TODO

                    // Optimization: If there is an equality condition, in the child query, about the child reference of the join condition, 
                    // then we can set the same equality condition on the parent reference in the parent query since child/childRef == parent/parentRef
                    val eqChild = where.filter(w => (w.condType == ConditionType.Equals) && (w.asInstanceOf[IReference].reference == jc.childRef))
                    if (!eqChild.isEmpty) {
                        val eqParent = new AbstractQueryConditionEquals(jc.parentRef, eqChild.head.asInstanceOf[AbstractQueryConditionEquals].eqValue.toString)
                        if (logger.isDebugEnabled) logger.debug("Copying equality condition on child ref to parent ref: " + eqParent)
                        Pwhere = Pwhere + eqParent
                    }
                    val q2 = new AbstractAtomicQuery(Set.empty, Pfrom, Pproject, Pwhere) // no tp nor TM in case of a RefObjectMap 
                    new AbstractQueryInnerJoinRef(Set(new TPBinding(tp, tm)), q1, jc.childRef, q2, jc.parentRef)
                }
            Q // yield query Q for triples map tm
        }

        // If only one triples map then we return the abstract query for that TM
        val resultQ =
            if (unionOf.size == 1)
                unionOf.head
            else
                // If several triples map, then we return a UNION of the abstract queries for each TM
                new AbstractQueryUnion(unionOf)

        if (logger.isDebugEnabled())
            logger.debug("transTPm: Translation of triple pattern: [" + tp + "] with triples maps " + tpBindings.bound + ":\n" + resultQ.toString)
        resultQ
    }

    /**
     * Translate a non-optimized MongoQueryNode instance into one or more optimized concrete MongoDB queries
     * whose results must be UNIONed.
     *
     * Firstly, the query is optimized, which may generate a top-level UNION.
     * Then, a query string is generated from the optimized MongoQueryNode, to which the initial query string
     * (from the logical source) is appended.
     * A UNION is translated into several concrete queries. For any other type of query only one query string is returned.
     *
     * @todo the project part is not managed: must transform JSONPath references into actually projectable
     * fields in a MongoDB query
     *
     * @param from From part of the atomic abstract query (query from the logical source)
     * @param project Project part of the atomic abstract query (xR2RML references to project, NOT MANAGED FOR NOW).
     * @param absQuery the abstract MongoDB query to translate into concrete MongoDB queries.
     * @return list of MongoDBQuery instances. If there are several instances, their results must be UNIONed.
     */
    def mongoAbstractQuerytoConcrete(
        from: MongoDBQuery,
        project: Set[AbstractQueryProjection],
        absQuery: MongoQueryNode): List[MongoDBQuery] = {

        var Q = absQuery.optimize
        if (logger.isTraceEnabled())
            logger.trace("Condtions optimized to: " + Q)

        // If the query is an AND, merge its FIELD nodes that have the same root path
        // Example 1. AND('a':{$gt:10}, 'a':{$lt:20}) => 'a':{$gt:10, $lt:20}
        // Example 2. AND('a.b':{$elemMatch:{Q1}}, 'a.b':{$elemMatch:{Q2}}) => 'a.b': {$elemMatch:{$and:[{Q1},{Q2}]}}.
        if (Q.isAnd)
            Q = new MongoQueryNodeAnd(MongoQueryNode.fusionQueries(Q.asInstanceOf[MongoQueryNodeAnd].members))

        val Qstr: List[MongoDBQuery] =
            if (Q.isUnion)
                // For each member of the UNION, convert it to a query string, add the query from the logical source,
                // and create a MongoDBQuery instance
                Q.asInstanceOf[MongoQueryNodeUnion].members.map(
                    q => new MongoDBQuery(
                        from.collection,
                        q.toTopLevelQuery(from.query),
                        q.toTopLevelProjection))
            else
                // If no UNION, convert to a query string, add the query from the logical source, and create a MongoDBQuery instance
                List(new MongoDBQuery(
                    from.collection,
                    Q.toTopLevelQuery(from.query),
                    Q.toTopLevelProjection))

        if (logger.isTraceEnabled())
            logger.trace("Final set of concrete queries:\n [" + Qstr + "]")
        Qstr
    }
}
