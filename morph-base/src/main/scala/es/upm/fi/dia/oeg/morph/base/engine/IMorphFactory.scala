package es.upm.fi.dia.oeg.morph.base.engine

import es.upm.fi.dia.oeg.morph.base.GenericConnection
import es.upm.fi.dia.oeg.morph.base.MorphProperties
import es.upm.fi.dia.oeg.morph.base.querytranslator.MorphBaseQueryResultProcessor
import es.upm.fi.dia.oeg.morph.base.querytranslator.MorphBaseQueryTranslator
import es.upm.fi.dia.oeg.morph.r2rml.model.R2RMLMappingDocument
import es.upm.fi.dia.oeg.morph.base.materializer.MorphBaseMaterializer
import es.upm.fi.dia.oeg.morph.base.querytranslator.MorphBaseTriplePatternBinder

trait IMorphFactory {

    def getProperties: MorphProperties

    def getConnection: GenericConnection

    def getMappingDocument: R2RMLMappingDocument

    def getUnfolder: MorphBaseUnfolder

    def getDataSourceReader: MorphBaseDataSourceReader

    def getMaterializer: MorphBaseMaterializer

    def getDataTranslator: MorphBaseDataTranslator

    def getTriplePatternBinder: MorphBaseTriplePatternBinder

    def getQueryTranslator: MorphBaseQueryTranslator

    def getQueryResultProcessor: MorphBaseQueryResultProcessor
}