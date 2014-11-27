package es.upm.fi.dia.oeg.morph.r2rml.model

import scala.collection.JavaConversions._
import com.hp.hpl.jena.rdf.model.Resource
import es.upm.fi.dia.oeg.morph.base.Constants
import org.apache.log4j.Logger
import es.upm.fi.dia.oeg.morph.base.sql.MorphDatabaseMetaData
import es.upm.fi.dia.oeg.morph.base.model.MorphBasePropertyMapping

class R2RMLPredicateObjectMap(val predicateMaps: List[R2RMLPredicateMap], val objectMaps: List[R2RMLObjectMap], val refObjectMaps: List[R2RMLRefObjectMap] //, objectMapTypes:List[R2RMLPredicateObjectMap.ObjectMapType.Value]
, val graphMaps: Set[R2RMLGraphMap]) extends MorphBasePropertyMapping {
    val logger = Logger.getLogger(this.getClass().getName());
    var alias: String = null;

    def getMappedPredicateName(index: Int): String = {
        val result = if (this.predicateMaps != null && !this.predicateMaps.isEmpty()) {
            this.predicateMaps.get(index).getOriginalValue();
        } else {
            null;
        }
        result;
    }

    def getObjectMap(index: Int): R2RMLObjectMap = {
        if (this.objectMaps != null && !this.objectMaps.isEmpty()) {
            this.objectMaps.get(index);
        } else { null }
    }

    def getPredicateMap(index: Int): R2RMLPredicateMap = {
        val result = if (this.predicateMaps != null && !this.predicateMaps.isEmpty()) {
            predicateMaps.get(index);
        } else {
            null;
        }
        result;
    }

    def getPropertyMappingID(): String = {
        null;
    }

    def getRangeClassMapping(index: Int): String = {
        val result = if (this.refObjectMaps != null && !this.refObjectMaps.isEmpty()
            && this.refObjectMaps.get(index) != null) {
            this.refObjectMaps.get(index).getParentTripleMapName();
        } else {
            null;
        }
        result;
    }

    def getRefObjectMap(index: Int): R2RMLRefObjectMap = {
        val result = if (this.refObjectMaps != null && !this.refObjectMaps.isEmpty()) {
            this.refObjectMaps.get(index);
        } else {
            null;
        }
        result;
    }

    def getRelationName(): String = {
        // TODO Auto-generated method stub
        logger.warn("TODO: Implement getRelationName");
        null;
    }

    override def toString(): String = {
        val result = "R2RMLPredicateObjectMap [predicateMaps=" + predicateMaps + ", objectMaps=" + objectMaps + ", refObjectMaps=" + refObjectMaps + "]";
        result;
    }

    def getAlias(): String = {
        alias;
    }

    def setAlias(alias: String) = {
        this.alias = alias;
    }

    override def getMappedPredicateNames(): Iterable[String] = {
        val result = this.predicateMaps.map(pm => {
            pm.getOriginalValue();
        });

        result;
    }

    def getAttributeName(): String = {
        // TODO Auto-generated method stub
        logger.warn("TODO: Implement getAttributeName");
        null;
    }
}

object R2RMLPredicateObjectMap {
    object ObjectMapType extends Enumeration {
        type ObjectMapType = Value
        val ObjectMap, RefObjectMap = Value
    }

    def apply(resource: Resource, formatFromLogicalTable: String): R2RMLPredicateObjectMap = {

        val predicateMaps = R2RMLPredicateMap.extractPredicateMaps(resource, formatFromLogicalTable).toList;
        val objectMaps = R2RMLObjectMap.extractObjectMaps(resource, formatFromLogicalTable).toList;
        val refObjectMaps = R2RMLRefObjectMap.extractRefObjectMaps(resource).toList;
        val graphMaps = R2RMLGraphMap.extractGraphMaps(resource, formatFromLogicalTable);

        val pom = new R2RMLPredicateObjectMap(predicateMaps, objectMaps, refObjectMaps, graphMaps);
        pom;
    }

    def extractPredicateObjectMaps(resource: Resource, formatFromLogicalTable: String): Set[R2RMLPredicateObjectMap] = {
        val predicateObjectMapStatements = resource.listProperties(
            Constants.R2RML_PREDICATEOBJECTMAP_PROPERTY);
        val predicateObjectMaps = if (predicateObjectMapStatements != null) {
            predicateObjectMapStatements.toList().map(predicateObjectMapStatement => {
                val predicateObjectMapStatementObjectResource =
                    predicateObjectMapStatement.getObject().asInstanceOf[Resource];
                val predicateObjectMap = R2RMLPredicateObjectMap(predicateObjectMapStatementObjectResource, formatFromLogicalTable);
                predicateObjectMap;
            });
        } else {
            Set.empty;
        }
        predicateObjectMaps.toSet;
    }

}