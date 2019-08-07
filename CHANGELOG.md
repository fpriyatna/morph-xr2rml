# Changelog

## [1.1-RC] 2019-07-02: undo last modification + fix quotes management
- Return to last official Jongo version 1.4.0 (https://github.com/frmichel/jongo)
- Escaped single-quotes not supported anymore, instead use double-quotes notation + single quotes inside e.g.
```xrr:query """db.locations.find( {"adminLevel": "Collectivité d'outre-mer"} )""".```
 
## 2019-06-21: add support for MongoDB queries containing values with escaped single quotes
- Compile with patched version of Jongo (https://github.com/frmichel/jongo)
- Fix pre-processing of MongoDB query strings: allow for escaped single-quotes + keep spaces within such escaped strings, e.g.
```xrr:query """db.locations.find( {'adminLevel': 'Collectivité d\\'outre-mer'} )""".```
 - Upgrade to Scala 2.12.3
 
## 2019-06-19: add term map property xrr:languageReference
- The R2RML rr:language property provides a static language tag to assign to literals. The new xrr:languageReference property allows to do that using a language tag that comes from the data source.
- Update Jongo to 1.4.0
 
## 2018-05-31: add run options
Options `--output` and `--mappingFile` can be used to override the output.file.path and mappingdocument.file properties respectively.

Add configuration parameter `literal.trim` set to true to trim the literal values read from the database.

## 2017-10-25: new property xrr:pushDown 
Property xrr:pushDown extends the mapping possibilities when defining iterations within a document (pull request #3 by Freddy Priyatna, to fulfill a need of the [SlideWiki project](https://slidewiki.eu/)). 
When iterating in a sub-part of a document (e.g. a JSON array), that property helps use values of fields that are higher in the document hierarchy, hence not accessible inside this sub-part of the document. See complete description in [2]. Implemented for the MongoDB database.
Example: a property ```xrr:pushDown [ xrr:reference "$.id"; xrr:as "newId"]``` can be defined either in the logical source together with an  rml:iterator, or within a referenced-valued term map that has a nested term map.
  - In a logical source: the xrr:reference "$.id" is evaluated against the current document, then the iterator is applied and in each document that comes out of the iterator, a new field ("newID" in this example) is added.
  - In a reference-valued term map, the xrr:reference "$.id" is evaluated against the document of the current iteration, and a new field ("newID" in this example) is added inside the documents that are passed to the nested term map.

## 2017-09-05: full implementation of the nested term maps
Complex nested term maps (nested term map that embed another nested term map) are now enabled, thus allowing to deal with any level of nested documents (pull request #1 by Freddy Priyatna). Implemented for the MongoDB database.
