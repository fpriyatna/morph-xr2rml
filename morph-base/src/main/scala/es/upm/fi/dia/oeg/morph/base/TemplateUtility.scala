package es.upm.fi.dia.oeg.morph.base

import java.util.regex.Pattern

import scala.collection.JavaConversions.seqAsJavaList
import scala.collection.mutable.Queue
import scala.util.matching.Regex

import org.apache.log4j.Logger

import es.upm.fi.dia.oeg.morph.base.path.MixedSyntaxPath

object TemplateUtility {

    val logger = Logger.getLogger(this.getClass().getName())

    val TemplatePattern = Constants.R2RML_TEMPLATE_PATTERN.r
    val TemplatePatternCapGrp = Constants.R2RML_TEMPLATE_PATTERN_WITH_CAPTURING_GRP.r

    val TemplatePatternCapGrpPat = Pattern.compile(Constants.R2RML_TEMPLATE_PATTERN_WITH_CAPTURING_GRP);

    /**
     * CAUTION ### This method was not updated to support Mixed-syntax paths ###
     * Unused (at least in data materialization)
     */
    def getTemplateMatching(inputTemplateString: String, inputURIString: String): Map[String, String] = {

        println("#################### inputTemplateString " + inputTemplateString)
        var newTemplateString = inputTemplateString;
        if (!newTemplateString.startsWith("<"))
            newTemplateString = "<" + newTemplateString;
        if (!newTemplateString.endsWith(">"))
            newTemplateString = newTemplateString + ">";

        var newURIString = inputURIString;
        if (!newURIString.startsWith("<"))
            newURIString = "<" + newURIString;
        if (!newURIString.endsWith(">"))
            newURIString = newURIString + ">";

        val columnsFromTemplate = this.getTemplateColumns(newTemplateString);

        var columnsList = List[String]();
        for (column <- columnsFromTemplate) {
            columnsList = column :: columnsList;
            // Replace each group "{column}" with the regex "(.+?)"
            newTemplateString = newTemplateString.replaceAll("\\{" + column + "\\}", "(.+?)");
        }
        columnsList = columnsList.reverse;

        val pattern = new Regex(newTemplateString);
        val firstMatch = pattern.findFirstMatchIn(newURIString);

        // Create a map of couples (column name, column value)
        val result: Map[String, String] =
            if (firstMatch != None) {
                // Subgroups return values corresponding to the (.+?) groups
                val subgroups = firstMatch.get.subgroups;
                var i = 0;
                columnsList.map(column => {
                    val resultAux = (column -> subgroups(i));
                    i = i + 1;
                    resultAux
                }).toMap
            } else
                Map.empty

        result;
    }

    /**
     * Get the list of capturing groups between '{' and '}' in a template string, without the '{' and '}'.
     * This method applies to R2RML templates as well as xR2RML templates including
     * mixed-syntax paths like: "http://example.org/{Column(NAME)/JSONPath(...)}/...
     *
     * Extracting encapsulating groups at once with a regex is quite complicated due to mixed syntax paths
     * since they can contain '{' and '}'. As a result this method does this in several steps. We examplify this
     * on template string http://example.org/{ID}/{Column(NAME)/JSONPath(...)}
     * (1) Save all mixed-syntax paths from the template string to a list => List("Column(NAME)", "JSONPath(...)").
     * (2) Replace each path expression with a place holder "xR2RML_replacer" => "http://example.org/{ID}/{xR2RML_replacer/xR2RML_replacer}".
     * (3) Extract all template groups between '{' and '}' to a list => List("{ID}", "{xR2RML_replacer/xR2RML_replacer}").
     * (4) Then, in this last list, we replace place holders with original mixed syntax path
     * expressions saved in step 1: List("{ID}", "{"Column(NAME)/JSONPath(...)}").
     * (5) Finally, remove the first '{' and last '}' characters => List("ID", "Column(NAME)/JSONPath(...)")
     */
    def getTemplateGroups(tplStr: String): List[String] = {

        // (1) Save all mixed-syntax path expressions in the template string
        val mixedSntxRegex = Constants.xR2RML_MIXED_SYNTX_PATH_REGEX
        val mixedSntxPaths: Queue[String] = Queue(mixedSntxRegex.findAllMatchIn(tplStr).toList.map(item => item.toString): _*)

        // (2) Replace each path expression with a place holder "xR2RML_replacer"
        val tpl2 = mixedSntxRegex.replaceAllIn(tplStr, "xR2RML_replacer")

        // (3) Make a list of the R2RML template groups between '{' '}'
        val listPattern = TemplatePatternCapGrp.findAllIn(tpl2).toList

        // (4) Restore the path expressions in each of the place holders
        val listReplaced = MixedSyntaxPath.replaceTplPlaceholder(listPattern, mixedSntxPaths)

        // Extract the column references of each template group between '{' and '}'
        val groupsFromTpl = listReplaced.map(group =>
            {
                // For non mixed-syntax paths (like simple column name), there has been no parsing 
                // at all above so they still have the '{' and '}'
                if (group.startsWith("{") && group.endsWith("}"))
                    group.substring(1, group.length() - 1)
                else group
            }
        )
        if (logger.isTraceEnabled()) logger.trace("Extracted groups: " + groupsFromTpl + " from template " + tplStr)
        groupsFromTpl;
    }

    /**
     * Get the list of columns referenced in a template string.
     * This method applies to R2RML templates as well as xR2RML templates including
     * mixed-syntax paths like: "http://example.org/{Column(NAME)/JSONPath(...)}/...
     *
     * Example: on template string "http://example.org/{ID}/{Column(NAME)/JSONPath(...)}"
     * this method returns "List("ID", "NAME")"
     */
    def getTemplateColumns(tplStr: String): List[String] = {

        val groups = getTemplateGroups(tplStr)

        // Extract the column references of each template group between
        val columnsFromTemplate = groups.map(group =>
            MixedSyntaxPath(group, Constants.xR2RML_REFFORMULATION_COLUMN).getReferencedColumn.getOrElse("")
        )
        if (logger.isTraceEnabled()) logger.trace("Extracted columns: " + columnsFromTemplate + " from template " + tplStr)
        columnsFromTemplate;
    }

    /**
     * Replace template tokens ( i.e. capturing groups between { and }) with replacement values.
     * This method applies to R2RML templates as well as xR2RML templates including
     * mixed-syntax paths like: "http://example.org/{Column(NAME)/JSONPath(...)}/...
     *
     * Replacement values come as N lists corresponding to the N groups. Values are produced by doing
     * a Cartesian product, that is by making all the possible combinations between all possible values
     * of each group.
     */
    def replaceTemplateGroups(tplStr: String, replacements: List[List[Object]]): List[String] = {

        if (replacements.isEmpty) { return List(tplStr) }

        if (getTemplateGroups(tplStr).length > replacements.length) {
            logger.error("Unexpected error: there are more template groups than replacement values. Template: " + tplStr + ". Replacements: " + replacements)
            // Return the template as is, not to raise an exception... not sure that's the best behavior
            return List(tplStr)
        }

        // Save all mixed-syntax path expressions in the template string
        val mixedSntxRegex = Constants.xR2RML_MIXED_SYNTX_PATH_REGEX
        val mixedSntxPaths = mixedSntxRegex.findAllMatchIn(tplStr).toList

        // Replace each path expression with a place holder "xR2RML_replacer"
        val tpl2 = mixedSntxRegex.replaceAllIn(tplStr, "xR2RML_replacer")

        // Compute all possible combinations between all values of the groups
        val combinations = cartesianProduct(replacements)
        if (logger.isTraceEnabled()) logger.trace("Template replacement combinations: " + combinations)

        val templateResults = new Queue[String]

        for (combination <- combinations) {
            var buffer: StringBuffer = new StringBuffer()
            var grpIdx = 0

            // Make a list of the template groups between '{' '}'
            val matcher = TemplatePatternCapGrpPat.matcher(tpl2)
            var appendIdx = 0
            while (matcher.find()) {
                val path = matcher.group(1)
                val replacement = combination.get(grpIdx)

                // Copy the part before the match 
                buffer.append(tpl2.substring(appendIdx, matcher.start()))

                // Append the replacement
                buffer.append(replacement.toString)

                grpIdx = grpIdx + 1
                appendIdx = matcher.end()
            }

            // Copy the end part after the last match
            if (appendIdx < tpl2.length() - 1)
                buffer.append(tpl2.substring(appendIdx, tpl2.length()))

            templateResults += buffer.toString
        }

        val result = templateResults.toList
        if (logger.isTraceEnabled()) logger.trace("Generated templates: " + result)
        result
    }

    /**
     * Make the Cartesian product between any number of lists: from a set of lists l1 to lN,
     * compute a new set containing all the combinations of elements of lists l1, l2... lN.
     * This is used in the production of all template strings when one or several groups
     * of the template are multi-valued.
     */
    def cartesianProduct(lists: List[List[Object]]): List[List[Object]] = {

        val combinations = new Queue[List[Object]]
        val nbLists = lists.length

        // Initialize the index to browse each list
        var indexes = Array.fill[Int](nbLists)(0)

        var stillToGo = true
        while (stillToGo) {

            // Build a list (combination) from the current elements of each list 
            val combination = for (j <- 0 to (nbLists - 1)) yield {
                if (lists(j).isEmpty) ""
                else lists(j)(indexes(j))
            }
            combinations += combination.toList

            // Search in which list we can increase the index, starting with the last one, then last but one etc.
            var continue = true
            var lstIdx = nbLists - 1
            while (continue && lstIdx >= 0) {
                if (indexes(lstIdx) < lists(lstIdx).length - 1) {
                    // Found one list to increment the index: increment it and stop for now
                    indexes(lstIdx) = indexes(lstIdx) + 1
                    continue = false
                } else {
                    // Not possible to increment this list anymore, so reset its index
                    // and check if we can decrement the index of another list
                    indexes(lstIdx) = 0
                    lstIdx = lstIdx - 1
                }
            }
            // If we exist the loop without being able to increment the index of any list,
            // that means we have reached the last possible combination => the end
            if (lstIdx == -1)
                stillToGo = false
        }

        combinations.toList
    }

    def main(args: Array[String]) = {
        var template = "Hello {Name} Please find attached {Invoice Number} which is due on {Due Date}";

        val replacements = List(List("Freddy"), List("INV0001"))

        val attributes = TemplateUtility.getTemplateColumns(template);
        System.out.println("attributes = " + attributes);

        val template2 = TemplateUtility.replaceTemplateGroups(template, replacements);
        System.out.println("template2 = " + template2);

        template = """\{\w+\}""";
        val columnsFromTemplate = template.r.findAllIn("http://example.org/{abc}#{def}").toList;
        System.out.println("columnsFromTemplate = " + columnsFromTemplate);

        val date = """(\d\d\d\d)-(\d\d)-(\d\d)""".r
        val dates = "Important dates in history: 2004-01-20, 1958-09-05, 2010-10-06, 2011-07-15"

        val firstDate = date findFirstIn dates getOrElse "No date found."
        System.out.println("firstDate: " + firstDate)

        val firstYear = for (m <- date findAllMatchIn dates) yield m group 1
        System.out.println("firstYear: " + firstYear.toList)
    }

}