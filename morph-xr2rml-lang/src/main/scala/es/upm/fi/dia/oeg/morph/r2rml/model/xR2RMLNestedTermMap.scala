package es.upm.fi.dia.oeg.morph.r2rml.model

import org.apache.log4j.Logger

import es.upm.fi.dia.oeg.morph.base.Constants
import com.hp.hpl.jena.rdf.model.RDFNode
import com.hp.hpl.jena.rdf.model.Resource
import es.upm.fi.dia.oeg.morph.base.exception.MorphException

/**
 * @todo This class is only a partial implementation of xR2RML nested term maps: it only support simple nested term maps,
 * i.e. without any xrr:reference, rr:template nor xrr:nestedTermMap property.
 * It can be used only to qualify terms of an RDF collection or container generated by the parent term map.
 * 
 * @author Franck Michel, I3S laboratory
 */
class xR2RMLNestedTermMap(
		/** Type of the root parent term map, used to infer the term type if it is not provided explicitly */
		parentTermMapType: Constants.MorphTermMapType.Value,
		nestedTermMapType: Constants.NestedTermMapType.Value,
		termType: Option[String],
		val datatype: Option[String],
		val languageTag: Option[String],
		nestedTermMap: Option[xR2RMLNestedTermMap]) {

	val logger = Logger.getLogger(this.getClass().getName());
	logger.info("nested term map type = " + nestedTermMapType);
	
	override def toString(): String = {
			"NestedTermMap[termType:" + termType + ", datatype:" + datatype + ", language:" + languageTag + "]";
	}

	/**
	 * Return true if the nested term map has a xrr:reference property
	 */
	def isReferenceValuedNestedTermMap = { this.nestedTermMapType == Constants.NestedTermMapType.ReferenceNestedTermMap }

	/**
	 * Return true if the nested term map has a rr:template property
	 */
	def isTemplateValuedNestedTermMap = { this.nestedTermMapType == Constants.NestedTermMapType.TemplateNestedTermMap }

	/**
	 * Return true if the nested term map has no xrr:reference nor rr:template property
	 */
	def isSimpleNestedTermMap = { !isReferenceValuedNestedTermMap && !isTemplateValuedNestedTermMap }

	/**
	 * Return the term type mentioned by property rr:termType or the default term type otherwise
	 */
	def inferTermType: String = {
			this.termType.getOrElse(this.getDefaultTermType)
	}

	def getDefaultTermType: String = {
			parentTermMapType match {
			case Constants.MorphTermMapType.ColumnTermMap => Constants.R2RML_LITERAL_URI
			case Constants.MorphTermMapType.ReferenceTermMap => Constants.R2RML_LITERAL_URI
			case Constants.MorphTermMapType.TemplateTermMap => Constants.R2RML_IRI_URI
			case _ => Constants.R2RML_LITERAL_URI
			}
			}

			/**
			 * Return true if the term type's term map is one of RDF list, bag, seq, alt
			 */
			def isRdfCollectionTermType: Boolean = {
					if (this.termType.isDefined) {
						val tt = this.termType.get
								(tt == Constants.xR2RML_RDFLIST_URI ||
								tt == Constants.xR2RML_RDFBAG_URI ||
								tt == Constants.xR2RML_RDFSEQ_URI ||
								tt == Constants.xR2RML_RDFALT_URI)
					} else { false }
			}


}

object xR2RMLNestedTermMap {
	val logger = Logger.getLogger(this.getClass().getName());


	/**
	 * Deduce the type of the nested term map (simple, reference, template) based on its properties
	 * @param rdfNode the nested term map node
	 * @throws es.upm.fi.dia.oeg.morph.base.exception.MorphException in case the nested term map type cannot be decided
	 */
	def extractNestedTermMapType(rdfNode: RDFNode) = {
			rdfNode match {
			case resource: Resource => {
				val templateStmt = resource.getProperty(Constants.R2RML_TEMPLATE_PROPERTY);
				val referenceStmt = resource.getProperty(Constants.xR2RML_REFERENCE_PROPERTY);

				if (templateStmt != null && referenceStmt == null) Constants.NestedTermMapType.TemplateNestedTermMap;
				else if (referenceStmt != null && templateStmt == null) Constants.NestedTermMapType.ReferenceNestedTermMap;
				else if (templateStmt == null && referenceStmt == null) { Constants.NestedTermMapType.SimpleNestedTermMap; }
				else {
					val errorMessage = "Invalid nested term map " + resource.getLocalName() + ". Should be either template or reference or simple";
					logger.error(errorMessage);
					throw new MorphException(errorMessage);
				}
			}
			case _ => {
				val errorMessage = "Invalid nested term map. Should be either template or reference or simple";
				logger.error(errorMessage);
				throw new MorphException(errorMessage);
			}
			}    
	}
}
