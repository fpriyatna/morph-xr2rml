package fr.unice.i3s.morph.xr2rml.mongo.engine

import org.apache.log4j.Logger
import com.hp.hpl.jena.datatypes.xsd.XSDDatatype
import com.hp.hpl.jena.rdf.model.Literal
import com.hp.hpl.jena.vocabulary.RDF
import com.jayway.jsonpath.{Configuration, JsonPath, ParseContext, ReadContext}
import es.upm.fi.dia.oeg.morph.base.Constants
import es.upm.fi.dia.oeg.morph.base.GeneralUtility
import es.upm.fi.dia.oeg.morph.base.RDFTerm
import es.upm.fi.dia.oeg.morph.base.RDFTermLiteral
import es.upm.fi.dia.oeg.morph.base.TemplateUtility
import es.upm.fi.dia.oeg.morph.base.engine.IMorphFactory
import es.upm.fi.dia.oeg.morph.base.engine.MorphBaseDataTranslator
import es.upm.fi.dia.oeg.morph.base.exception.MorphException
import es.upm.fi.dia.oeg.morph.base.path.MixedSyntaxPath
import es.upm.fi.dia.oeg.morph.base.query.AbstractQuery
import es.upm.fi.dia.oeg.morph.r2rml.model.{AbstractTermMap, R2RMLTriplesMap, xR2RMLPushDown}

import scala.util.parsing.json.{JSON, JSONObject}
import collection.JavaConverters._
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.databind.node.{ArrayNode, ObjectNode}

/**
  * Utility class to transform a triples map or a MongoDB query into RDF triples
  *
  * @author Franck Michel, I3S laboratory
  */
class MorphMongoDataTranslator(val fact: IMorphFactory)
  extends MorphBaseDataTranslator(fact) with java.io.Serializable {

  if (!factory.getConnection.isMongoDB)
    throw new MorphException("Database connection type does not match MongoDB")

  override val logger = Logger.getLogger(this.getClass().getName());

  val mapper = new ObjectMapper

  /**
    * Query the database using the triples map logical source, and build triples from the result.
    * Triples are stored in the Jena model of the data materializer.
    * This method applies to the data materialization approach, not to the query reweriting.
    *
    * For each document of the result set:
    * <ol>
    * <li>create a subject resource and an optional graph resource if the subject map contains a rr:graph/rr:graphMap property,</li>
    * <li>loop on each predicate-object map: create a list of resources for the predicates, a list of resources for the objects,
    * a list of resources from the subject map of a parent object map in case there are referencing object maps,
    * and a list of resources representing target graphs mentioned in the predicate-object map.</li>
    * <li>Finally combine all subject, graph, predicate and object resources to generate triples.</li>
    * </ol>
    *
    * @param tm the triples map for which to generate the triples
    */
  override def generateRDFTriples(tm: R2RMLTriplesMap): Unit = {

    val ls = tm.logicalSource;
    val lsPushDown = ls.listPushDown;
    val sm = tm.subjectMap;
    val poms = tm.predicateObjectMaps;
    val query = factory.getUnfolder.unfoldTriplesMap(tm)

    // Execute the query against the database and apply the iterator
    val childWithoutIteratorResultSet:List[String] = factory.getDataSourceReader.execute(query, None)
      .asInstanceOf[MorphMongoResultSet].resultSet.toList


    // Execute the query against the database and apply the iterator
    val childWithIteratorResultSet = factory.getDataSourceReader.executeQueryAndIterator(query, ls.docIterator, None)
      .asInstanceOf[MorphMongoResultSet].resultSet.toList

    val childResultSetWithPushDown = for(i <- 0 to childWithIteratorResultSet.size - 1) yield {
      val childWithoutIteratorString = childWithoutIteratorResultSet(i);
      val childWithIteratorString = childWithIteratorResultSet(i);
      val pushDownFields = this.generatePushDownFields(lsPushDown, childWithoutIteratorString);
      val childWithIteratorWithPushDown = this.insertPushedDownFieldsIntoJsonString(childWithIteratorString, pushDownFields);
      childWithIteratorWithPushDown;
    }


    // Execute the queries of all the parent triples maps (in the join conditions) against the database
    // and apply their iterators. These queries will serve in computing the joins.
    // This "cache" lives just the time of computing this triples map, therefore it is
    // different from the global cache executedQueries that lives along the computing of all triples maps
    // parentResultSets is a map whose key is the query map id (query string + iterator), and the value
    // if a list of result documents.
    val parentResultSets: scala.collection.mutable.Map[String, List[String]] = new scala.collection.mutable.HashMap
    poms.foreach(pom => {
      pom.refObjectMaps.foreach(rom => {
        val parentTM = factory.getMappingDocument.getParentTriplesMap(rom)
        val parentQuery = factory.getUnfolder.unfoldTriplesMap(parentTM)
        val queryMapId = MorphMongoDataSourceReader.makeQueryMapId(parentQuery, parentTM.logicalSource.docIterator, None)
        if (!parentResultSets.contains(queryMapId)) {
          val resultSet = factory.getDataSourceReader.executeQueryAndIterator(parentQuery, parentTM.logicalSource.docIterator, None).asInstanceOf[MorphMongoResultSet].resultSet
          parentResultSets += (queryMapId -> resultSet.toList)
        }
      })
    })

    // Main loop: iterate and process each result document of the result set
    var nbTriples = 0
    var i = 0;
    for (document <- childResultSetWithPushDown) {
      i = i + 1;
      if (logger.isDebugEnabled()) logger.debug("Generating triples for document " + i + "/" + childResultSetWithPushDown.size + ": " + document)

      try {
        // Create the subject resource
        val subjects = this.translateData(sm, document)
        if (subjects == null) { throw new Exception("null value in the subject triple") }
        if (logger.isDebugEnabled()) logger.debug("Document " + i + " - subjects: " + subjects)

        // Create the list of resources representing subject target graphs
        val subjectGraphs = sm.graphMaps.flatMap(sgmElement => {
          this.translateData(sgmElement, document)
        }).toList;
        if (!subjectGraphs.isEmpty)
          if (logger.isTraceEnabled()) logger.trace("Document " + i + " - subject graphs: " + subjectGraphs)

        // Add subject resource to the JENA model with its class (rdf:type) and target graphs
        sm.classURIs.foreach(classURI => {
          val classRes = factory.getMaterializer.model.createResource(classURI);
          if (subjectGraphs == null || subjectGraphs.isEmpty) {
            for (sub <- subjects) {
              factory.getMaterializer.materializeQuad(this.createRDFNode(sub), RDF.`type`, classRes, null)
            }
          } else {
            subjectGraphs.foreach(subjectGraph => {
              for (sub <- subjects)
                factory.getMaterializer.materializeQuad(this.createRDFNode(sub), RDF.`type`, classRes, this.createRDFNode(subjectGraph))
            });
          }
        });

        // Internal loop on each predicate-object map
        poms.foreach(pom => {
          // ----- Make a list of resources for the predicate maps of this predicate-object map
          val predicates = pom.predicateMaps.flatMap(predicateMap => { this.translateData(predicateMap, document) });
          if (!predicates.isEmpty && logger.isDebugEnabled()) logger.debug("Document " + i + " - predicates: " + predicates)

          // ------ Make a list of resources for the object maps of this predicate-object map
          val objects = pom.objectMaps.flatMap(objectMap => { this.translateData(objectMap, document) });
          if (!objects.isEmpty && logger.isDebugEnabled()) logger.debug("Document " + i + " - objects: " + objects)

          // ----- For each RefObjectMap get the IRIs from the subject map of the parent triples map
          val refObjects = pom.refObjectMaps.flatMap(refObjectMap => {

            val parentTM = factory.getMappingDocument.getParentTriplesMap(refObjectMap)

            // Compute a list of subject IRIs for each of the join conditions, that we will intersect later
            val subjectsCandidates: Set[List[RDFTerm]] = for (joinCond <- refObjectMap.joinConditions) yield {

              // Evaluate the child reference on the current document (of the child triples map)
              val childMsp = MixedSyntaxPath(joinCond.childRef, sm.refFormulation)
              val childValues: List[Object] = childMsp.evaluate(document)

              // ---- Evaluate the parent reference on the results of the parent triples map logical source

              // Create the mixed syntax path corresponding to the parent reference
              val parentMsp = MixedSyntaxPath(joinCond.parentRef, parentTM.logicalSource.refFormulation)

              // Get the results of the parent query
              val queryMapId = MorphMongoDataSourceReader.makeQueryMapId(factory.getUnfolder.unfoldTriplesMap(parentTM), parentTM.logicalSource.docIterator, None)
              val parentRes = parentResultSets.get(queryMapId).get

              // Evaluate the mixed syntax path on each parent query result. The result is stored as pairs:
              // 		(JSON document, result of the evaluation of the parent reference on the JSON document)
              val parentValues = parentRes.map(res => (res, parentMsp.evaluate(res)))

              // ---- Make the join between the child values and parent values
              val parentSubjects = parentValues.flatMap(parentVal => {
                // For each document returned by the parent triples map (named parent document),
                // if at least one of the child values is in the current parent document values,
                // then generate an RDF term for the subject of the current parent document.
                if (!childValues.intersect(parentVal._2).isEmpty) // parentVal._2 is the evaluation of the parent ref
                  Some(this.translateData(parentTM.subjectMap, parentVal._1)) // parentVal._1 is the JSON document itself
                else
                // There was no match: return an empty list so that the final intersection of candidate return nothing
                  Some(List())
              }).flatten
              if (logger.isTraceEnabled()) logger.trace("Join parent candidates: " + joinCond.toString + ", result:" + parentSubjects)
              parentSubjects
            }

            // There is a logical AND between several join conditions of the same RefObjectMap
            // => make the intersection between all subjects generated by all join conditions
            val finalParentSubjects = GeneralUtility.intersectMultipleTermSets(subjectsCandidates)
            if (logger.isTraceEnabled()) logger.trace("Join parent subjects after intersection all joinConditions: " + finalParentSubjects)

            // Optionally convert the result to an RDF collection or container
            if (refObjectMap.isR2RMLTermType)
              finalParentSubjects
            else
              MorphBaseDataTranslator.createCollection(refObjectMap.termType.get, finalParentSubjects)
          })
          if (!refObjects.isEmpty && logger.isDebugEnabled()) logger.debug("Document " + i + " - refObjects: " + refObjects)

          // ----- Create the list of resources representing target graphs mentioned in the predicate-object map
          val predicateObjectGraphs = pom.graphMaps.flatMap(pogmElement => {
            val poGraphValue = this.translateData(pogmElement, document)
            poGraphValue
          }).toList
          if (!predicateObjectGraphs.isEmpty)
            if (logger.isTraceEnabled()) logger.trace("Document " + i + " - predicate-object map graphs: " + predicateObjectGraphs)

          // ----- Finally, combine all the terms to generate triples in the target graphs or default graph
          val subNodes = subjects.map(this.createRDFNode)
          val predNodes = predicates.map(this.createRDFNode)
          val objNodes = objects.map(this.createRDFNode)
          val refObjNodes = refObjects.map(this.createRDFNode)
          val graphNodes = (subjectGraphs ++ predicateObjectGraphs).map(this.createRDFNode)
          nbTriples += factory.getMaterializer.materializeQuads(subNodes, predNodes, objNodes, refObjNodes, graphNodes)
        })
      } catch {
        case e: MorphException => {
          logger.error("Error while translating data of document " + i + ": " + e.getMessage);
          e.printStackTrace()
        }
        case e: Exception => {
          logger.error("Unexpected error while translating data of document " + i + ": " + e.getCause() + " - " + e.getMessage);
          e.printStackTrace()
        }
      }
    }
    if (logger.isDebugEnabled()) logger.debug(i + " instances retrieved.");
    if (logger.isInfoEnabled()) logger.info(nbTriples + " triples generated.")
  }

  /**
    * Generate triples in the context of the query rewriting: run the child and optional parent queries,
    * and apply the triples map bound to the child query to create RDF triples.
    *
    * This assumes that triples maps are normalized, i.e. (1) exactly one predicate-object map with exactly one
    * predicate map and one object map, (2) each rr:class property of the subject map was translated into an
    * equivalent normalized triples map.
    *
    * @param query an abstract query in which the targetQuery fields must have been set
    * @throws es.upm.fi.dia.oeg.morph.base.exception.MorphException if one of the atomic abstract queries in this query has no target query
    */
  override def generateRDFTriples(query: AbstractQuery): Unit = {
    if (!query.isTargetQuerySet)
      throw new MorphException("Target queries not set in " + query)

    var start = System.currentTimeMillis()
    val listTriples = query.generateRdfTerms(factory.getDataSourceReader, this)
    for (triple <- listTriples)
      factory.getMaterializer.materializeQuad(this.createRDFNode(triple.subject), this.createRDFNode(triple.predicate), this.createRDFNode(triple.objct), null)

    if (logger.isDebugEnabled) logger.debug("Materialized " + listTriples.size + " triples.")
    logger.warn("Duration of query execution and generation of triples = " + (System.currentTimeMillis - start) + " ms.");
  }

  /**
    * Apply a term map to a JSON document, and generate a list of RDF terms:
    * for each element reference in the term map (reference or template), read values from the document,
    * then translate those values into RDF terms.
    */
  def translateData(termMap: AbstractTermMap, jsonDoc: String): List[RDFTerm] = {
    if (termMap == null) {
      val errorMessage = "TermMap is null";
      logger.error(errorMessage);
      throw new MorphException(errorMessage);
    }

    val result: List[RDFTerm] = termMap.termMapType match {

      // --- Constant-valued term map
      case Constants.MorphTermMapType.ConstantTermMap => {
        this.translateDataWithConstantTermMap(termMap, jsonDoc);
      }

      // --- Reference-valued term map
      case Constants.MorphTermMapType.ReferenceTermMap => {
        this.translateDataWithReferenceTermMap(termMap, jsonDoc);
      }

      // --- Template-valued term map
      case Constants.MorphTermMapType.TemplateTermMap => {
        this.translateDataWithTemplateTermMap(termMap, jsonDoc);
      }

      case _ => { throw new MorphException("Invalid term map type " + termMap.termMapType) }
    }
    result
  }

  def translateDataWithConstantTermMap(termMap: AbstractTermMap, jsonDoc: String): List[RDFTerm] = {

    val (collecTermType: Option[String], datatype: Option[String], languageTag: Option[String], memberTermType: String) =
      termMap.calculateCollecTermType_DataType_LanguageTag_TermType();

    MorphBaseDataTranslator.translateSingleValue(termMap.getConstantValue(), collecTermType, memberTermType
      , datatype, languageTag, encodeUnsafeCharsInUri, encodeUnsafeCharsInDbValues)
  }

  def generatePushDownFields(listPushDown:List[xR2RMLPushDown], jsonNode:JsonNode) : Map[String, Any] = {
    val pushedFields:Map[String, Any] = listPushDown.map(pushDown => {
      val pdReference = pushDown.reference;
      //val pdReferenceIDReplaced = this.replaceIDField(pdReference);
      val pdReferenceKey = pdReference.replaceAllLiterally("$.", "")
      //val pdReferenceKeyIDReplaced = this.replaceIDField(pdReference).replaceAllLiterally("$.", "");

      //val pdReferenceValue:Any = jsonDocAsMap.get(pdReferenceKey).get
      val pdReferenceValue = if(pdReferenceKey.equals("_id")) {
        val idValue = jsonNode.get(pdReferenceKey);
        if(idValue == null) {
          jsonNode.get(pdReferenceKey).get("$oid")
        } else {
          idValue
        }
      } else {
        jsonNode.get(pdReferenceKey)
      }
      logger.info("pdReferenceValue = " + pdReferenceValue)


      val pdReferenceValueReplaced = if(pdReferenceValue.isTextual) {
        pdReferenceValue.toString.replaceAll("\"", "");
      } else {
        pdReferenceValue
      }

      val pdAlias = pushDown.alias
      val pdAliasValue:String = if(pdAlias.isDefined) {
        pdAlias.get
      } else { pdReferenceKey }
      logger.info("pdAliasValue = " + pdAliasValue)

      (pdAliasValue -> pdReferenceValueReplaced)
    }).toMap
    pushedFields
  }

  def generatePushDownFields(listPushDown:List[xR2RMLPushDown], jsonString:String) : Map[String, Any] = {
    // create an ObjectMapper instance.
    val mapper = new ObjectMapper
    // use the ObjectMapper to read the json string and create a tree
    val node = mapper.readTree(jsonString)

    this.generatePushDownFields(listPushDown, node);
  }


  def translateDataWithReferenceTermMap(termMap: AbstractTermMap, jsonDoc: String): List[RDFTerm] = {
    val jsonString = jsonDoc.toString;
    val jsonNode = this.mapper.readTree(jsonString)

    val (collecTermType: Option[String], datatype: Option[String], languageTag: Option[String], memberTermType: String) =
      termMap.calculateCollecTermType_DataType_LanguageTag_TermType();

    val msPath = {
      if (termMap.getReference() == "$._id")
      // The MongoDB "_id" field is an ObjectId: retrieve the $oid subfield to get the id value
        MixedSyntaxPath("$._id.$oid", termMap.getReferenceFormulation())
      else
        termMap.getMixedSyntaxPaths()(0) // '(0)' because in a reference there is only one mixed syntax path
    }

    // Evaluate the value against the mixed syntax path
    val values: List[Object] = msPath.evaluate(jsonDoc);




    // Generate RDF terms from the values resulting from the evaluation
    if (termMap.hasNestedTermMap()) {
      val ntm = termMap.nestedTermMap.get

      if (ntm.isSimpleNestedTermMap)
      // The nested term map just add term type, datatype and/or language tag. Generate the values straight away.
        MorphBaseDataTranslator.translateMultipleValues(values, collecTermType, memberTermType, datatype, languageTag
          , encodeUnsafeCharsInUri, encodeUnsafeCharsInDbValues);
      else {
        val pushedFields:Map[String, Any] = this.generatePushDownFields(ntm.listPushDown, jsonNode);

        val valuesWithPushDown = if(pushedFields != null && pushedFields.size > 0) values.map(value => {
          val valueString = value.toString;
          val valueWithPushDown = this.insertPushedDownFieldsIntoJsonString(valueString, pushedFields);
          valueWithPushDown
        }) else {
          values
        }

        // The nested term map add an iteration within the current document
        val valuesFromNtm = valuesWithPushDown.flatMap {
          value =>  this.translateData(ntm, value.asInstanceOf[String])
        }
        if (collecTermType.isDefined)
        // Create the collection/container with that list of nodes
          MorphBaseDataTranslator.createCollection(collecTermType.get, valuesFromNtm)
        else
          valuesFromNtm
      }
    } else
      MorphBaseDataTranslator.translateMultipleValues(values, collecTermType, memberTermType, datatype, languageTag, encodeUnsafeCharsInUri, encodeUnsafeCharsInDbValues);
  }

  def replaceIDField(tplString:String): String = {
    if (tplString == "$._id") {
      "$._id.$oid"
    } else {
      tplString
    }
  }

  def translateDataWithTemplateTermMap(termMap: AbstractTermMap, jsonDoc: String): List[RDFTerm] = {

    val (collecTermType: Option[String], datatype: Option[String], languageTag: Option[String], memberTermType: String) =
      termMap.calculateCollecTermType_DataType_LanguageTag_TermType();

    // For each capturing group of the template, compute a list of replacement strings
    val msPaths = {
      // Get the list of template strings
      val tplStrings = TemplateUtility.getTemplateGroups(termMap.getTemplateString())

      // For each one, parse it as a mixed syntax path
      tplStrings.map(tplString => {
        if (tplString == "$._id")
        // The MongoDB "_id" field is an ObjectId: retrieve the $oid subfield to get the id value
          //TODO This is not always true, better to put this in the config file as a property.
          MixedSyntaxPath("$._id.$oid", termMap.getReferenceFormulation())
        else
          MixedSyntaxPath(tplString, termMap.getReferenceFormulation())
      })
    }

    val listReplace = msPaths.map { msPath =>
      // Evaluate the raw document against each mixed-syntax path.
      val valuesRaw: List[Object] = msPath.evaluate(jsonDoc)
      valuesRaw.filter(_ != null).map(v =>
        MorphBaseDataTranslator.encodeResvdCharsIfUri(v, memberTermType, encodeUnsafeCharsInDbValues))
    }

    // Check if at least one of the replacements is not null.
    var isEmptyReplacements: Boolean = true
    for (repl <- listReplace)
      if (!repl.isEmpty) isEmptyReplacements = false

    // Replace "{...}" groups in the template string with corresponding values from the db
    if (isEmptyReplacements) {
      if (logger.isTraceEnabled()) logger.trace("Template " + termMap.getTemplateString() + ": no values (or only null values) were read from the DB.")
      List()
    } else {
      // Compute the list of template results by making all possible combinations of the replacement values
      val tplResults = TemplateUtility.replaceTemplateGroups(termMap.getTemplateString(), listReplace);
      val resultAux = MorphBaseDataTranslator.translateMultipleValues(tplResults, collecTermType, memberTermType, datatype, languageTag, encodeUnsafeCharsInUri, encodeUnsafeCharsInDbValues);
      resultAux
    }
  }

  /**
    * Defines the mapping from JSON data types to XSD data types
    */
  private def inferDataType(value: Object): String = {
    value match {
      case _: java.lang.Byte => XSDDatatype.XSDinteger.getURI()
      case _: java.lang.Short => XSDDatatype.XSDinteger.getURI()
      case _: java.lang.Integer => XSDDatatype.XSDinteger.getURI()
      case _: java.lang.Long => XSDDatatype.XSDinteger.getURI()

      case _: java.lang.Double => XSDDatatype.XSDdecimal.getURI()
      case _: java.lang.Float => XSDDatatype.XSDdecimal.getURI()

      case _: java.lang.Boolean => XSDDatatype.XSDboolean.getURI()

      case _: java.lang.Number => XSDDatatype.XSDdecimal.getURI()

      case _ => null
    }
  }

  /**
    * Create a JENA literal resource with optional data type and language tag.
    * This method overrides the base method to enable the mapping between JSON data types
    * and XSD data types
    */
  override protected def createLiteralNode(term: RDFTermLiteral): Literal = {
    if (term.language.isDefined)
      factory.getMaterializer.model.createLiteral(term.value.asInstanceOf[String], term.language.get);
    else if (term.datatype.isDefined)
      factory.getMaterializer.model.createTypedLiteral(term.value, term.datatype.get);
    else {
      val inferedDT = inferDataType(term.value)
      if (inferedDT == null)
        factory.getMaterializer.model.createLiteral(term.value.asInstanceOf[String]);
      else
        factory.getMaterializer.model.createTypedLiteral(term.value, inferedDT);
    }
  }

  def insertPushedDownFieldsIntoJsonString(jsonString:String, pushedFields:Map[String, Any]): String = {
    if(jsonString == null) {
      null
    } else {
      val jsonNode = this.mapper.readTree(jsonString)
      this.insertPushedDownFieldsIntoJsonNode(jsonNode, pushedFields);
      jsonNode.toString;
    }
  }

  def insertPushedDownFieldsIntoListJsonString(listJsonString:List[String], pushedFields:Map[String, Any]): List[String] = {
    if(listJsonString == null) {
      null
    } else {
      listJsonString.map(jsonString => this.insertPushedDownFieldsIntoJsonString(jsonString, pushedFields) )
    }
  }

  def insertPushedDownFieldsIntoJsonNode(jsonNode:JsonNode, pushedFields:Map[String, Any]): Unit = {
    if(jsonNode != null) {
      if(jsonNode.isArray) {
        val arrayNode = jsonNode.asInstanceOf[ArrayNode]
        this.insertPushedDownFieldsIntoArrayNode(arrayNode, pushedFields)
      } else if(jsonNode.isObject) {
        val objectNode = jsonNode.asInstanceOf[ObjectNode];
        this.insertPushedDownFieldsIntoObjectNode(objectNode, pushedFields)
      } else {
        throw new MorphException("Unsupported type of JSON Node : " + jsonNode);
      }
    }
  }

  def insertPushedDownFieldsIntoObjectNode(objectNode:ObjectNode, pushedFields:Map[String, Any]): Unit = {
    if(objectNode != null) {
      for(pushedFieldKey <- pushedFields.keys) {
        val pushedFieldValue = pushedFields(pushedFieldKey);
        objectNode.put(pushedFieldKey, pushedFieldValue.toString.replaceAll("\"", ""));
      }
    }

  }

  def insertPushedDownFieldsIntoArrayNode(arrayNode:ArrayNode, pushedFields:Map[String, Any]): Unit = {
    if(arrayNode != null) {
      val it = arrayNode.iterator();
      while(it.hasNext) {
        val jsonNode = it.next();
        this.insertPushedDownFieldsIntoJsonNode(jsonNode, pushedFields);
      }
    }
  }

}

//object MorphMongoDataTranslator extends java.io.Serializable {
//
//    /**
//     * Apply a term map to a JSON document, and generate a list of RDF terms:
//     * for each element reference in the term map (reference or template), read values from the document,
//     * then translate those values into RDF terms.
//     */
//    def translateData2(termMap: R2RMLTermMap, jsonDoc: String, encodeUnsafeCharsInUri: Boolean, encodeUnsafeCharsInDbValues: Boolean): List[RDFTerm] = {
//        if (termMap == null) {
//            val errorMessage = "TermMap is null";
//            throw new MorphException(errorMessage);
//        }
//
//        var datatype = termMap.datatype
//        var languageTag = termMap.languageTag
//
//        // Term type of the collection/container to generate, or None if this is not the case 
//        var collecTermType: Option[String] = None
//
//        // Term type of the RDF terms to generate from database values
//        var memberTermType: String = Constants.R2RML_LITERAL_URI
//
//        // In case of a collection/container, a nested term map should give the details of term type, datatype and language or the terms 
//        if (R2RMLTermMap.isRdfCollectionTermType(termMap.inferTermType)) {
//            collecTermType = Some(termMap.inferTermType)
//            if (termMap.nestedTermMap.isDefined) { // a nested term type MUST be defined in a term map with collection/container term type
//                memberTermType = termMap.nestedTermMap.get.inferTermType
//                datatype = termMap.nestedTermMap.get.datatype
//                languageTag = termMap.nestedTermMap.get.languageTag
//            }
//        } else {
//            collecTermType = None
//            memberTermType = termMap.inferTermType
//        }
//
//        val result: List[RDFTerm] = termMap.termMapType match {
//
//            // --- Constant-valued term map
//            case Constants.MorphTermMapType.ConstantTermMap => {
//                MorphBaseDataTranslator.translateSingleValue(
//                    termMap.constantValue, collecTermType, memberTermType, datatype, languageTag,
//                    encodeUnsafeCharsInUri, encodeUnsafeCharsInDbValues)
//            }
//
//            // --- Reference-valued term map
//            case Constants.MorphTermMapType.ReferenceTermMap => {
//                val msPath =
//                    if (termMap.reference == "$._id")
//                        // The MongoDB "_id" field is an ObjectId: retrieve the $oid subfield to get the id value
//                        MixedSyntaxPath("$._id.$oid", termMap.refFormulation)
//                    else
//                        termMap.getMixedSyntaxPaths()(0) // '(0)' because in a reference there is only one mixed syntax path
//
//                // Evaluate the value against the mixed syntax path
//                val values: List[Object] = msPath.evaluate(jsonDoc)
//
//                // Generate RDF terms from the values resulting from the evaluation
//                MorphBaseDataTranslator.translateMultipleValues(
//                    values, collecTermType, memberTermType, datatype, languageTag,
//                    encodeUnsafeCharsInUri, encodeUnsafeCharsInDbValues)
//            }
//
//            // --- Template-valued term map
//            case Constants.MorphTermMapType.TemplateTermMap => {
//
//                // For each group of the template, compute a list of replacement strings
//                // CHANGE 2016/06/02: Replaced this line: 
//                //     val msPaths = termMap.getMixedSyntaxPaths()
//                // with the following, in order to deal with the _id field:
//                val msPaths = {
//                    // Get the list of template strings
//                    val tplStrings = TemplateUtility.getTemplateGroups(termMap.templateString)
//
//                    // For each one, parse it as a mixed syntax path
//                    tplStrings.map(tplString => {
//                        if (tplString == "$._id")
//                            // The MongoDB "_id" field is an ObjectId: retrieve the $oid subfield to get the id value
//                            MixedSyntaxPath("$._id.$oid", termMap.refFormulation)
//                        else
//                            MixedSyntaxPath(tplString, termMap.refFormulation)
//                    })
//                }
//
//                val listReplace = for (i <- 0 to (msPaths.length - 1)) yield {
//                    // Evaluate the raw source document against the mixed-syntax path(s).
//                    val valuesRaw: List[Object] = msPaths(i).evaluate(jsonDoc)
//                    valuesRaw.filter(_ != null).map(v => MorphBaseDataTranslator.encodeResvdCharsIfUri(v, memberTermType, encodeUnsafeCharsInDbValues))
//                }
//
//                val replacements: List[List[Object]] = listReplace.toList
//
//                // Check if at least one of the replacements is not null.
//                var isEmptyReplacements: Boolean = true
//                for (repl <- listReplace) {
//                    if (!repl.isEmpty)
//                        isEmptyReplacements = false
//                }
//
//                // Replace "{...}" groups in the template string with corresponding values from the db
//                if (isEmptyReplacements) {
//                    List()
//                } else {
//                    // Compute the list of template results by making all possible combinations of the replacement values
//                    val tplResults = TemplateUtility.replaceTemplateGroups(termMap.templateString, replacements)
//                    MorphBaseDataTranslator.translateMultipleValues(
//                        tplResults, collecTermType, memberTermType, datatype, languageTag,
//                        encodeUnsafeCharsInUri, encodeUnsafeCharsInDbValues)
//                }
//            }
//
//            case _ => { throw new MorphException("Invalid term map type " + termMap.termMapType) }
//        }
//        result
//    }
//}