package fr.unice.i3s.morph.xr2rml.mongo.query

/**
 * MongoDB query of the form:  "p.q": {$exists: true}.
 * Contrary to MongoQueryNodeField, the parameter here may be formed with several field names like "p.0.q".
 */
class MongoQueryNodeExists(val mongoPath: String) extends MongoQueryNode {

    override def toQueryStringNotFirst() = { "'" + mongoPath + "': {$exists: true}" }
}
