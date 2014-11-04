package es.upm.fi.dia.oeg.morph.base.model

import es.upm.fi.dia.oeg.morph.base.sql.MorphTableMetaData
import es.upm.fi.dia.oeg.morph.base.sql.MorphDatabaseMetaData
import com.hp.hpl.jena.rdf.model.Resource

/**
 * Root class for defining class mappings, i.e. in particular triples maps.
 * Each class mapping keeps track of the JENA resource it corresponds to (member resource).
 */
abstract class MorphBaseClassMapping(val propertyMappings: Iterable[MorphBasePropertyMapping]) {
  var id: String = null;
  var name: String = null;
  var resource: Resource = null;

  def getConceptName(): String;
  def getPropertyMappings(propertyURI: String): Iterable[MorphBasePropertyMapping];
  def getPropertyMappings(): Iterable[MorphBasePropertyMapping];
  def isPossibleInstance(uri: String): Boolean;
  def getLogicalSource(): MorphBaseLogicalTable;
  def getLogicalTableSize(): Long;
  def getTableMetaData(): Option[MorphTableMetaData];
  def getMappedClassURIs(): Iterable[String];
  def getSubjectReferencedColumns(): List[String];
  def buildMetaData(dbMetaData: Option[MorphDatabaseMetaData]);

  def setId(id: String) = { this.id = id }

}