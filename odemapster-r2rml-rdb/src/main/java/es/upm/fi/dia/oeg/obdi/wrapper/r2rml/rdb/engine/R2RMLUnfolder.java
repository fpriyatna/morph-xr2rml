package es.upm.fi.dia.oeg.obdi.wrapper.r2rml.rdb.engine;

import java.io.ByteArrayInputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Vector;

import org.apache.log4j.Logger;

import Zql.ZExp;
import Zql.ZExpression;
import Zql.ZQuery;
import Zql.ZSelectItem;
import Zql.ZStatement;
import Zql.ZqlParser;
import es.upm.fi.dia.oeg.morph.base.Constants;
import es.upm.fi.dia.oeg.morph.base.sql.MorphSQLSelectItem;
import es.upm.fi.dia.oeg.obdi.core.engine.AbstractUnfolder;
import es.upm.fi.dia.oeg.obdi.core.model.AbstractConceptMapping;
import es.upm.fi.dia.oeg.obdi.core.model.AbstractMappingDocument;
import es.upm.fi.dia.oeg.obdi.core.sql.SQLFromItem;
import es.upm.fi.dia.oeg.obdi.core.sql.SQLFromItem.LogicalTableType;
import es.upm.fi.dia.oeg.obdi.core.sql.SQLJoinTable;
import es.upm.fi.dia.oeg.obdi.core.sql.SQLLogicalTable;
import es.upm.fi.dia.oeg.obdi.core.sql.SQLQuery;
import es.upm.fi.dia.oeg.obdi.wrapper.r2rml.rdb.model.R2RMLJoinCondition;
import es.upm.fi.dia.oeg.obdi.wrapper.r2rml.rdb.model.R2RMLLogicalTable;
import es.upm.fi.dia.oeg.obdi.wrapper.r2rml.rdb.model.R2RMLMappingDocument;
import es.upm.fi.dia.oeg.obdi.wrapper.r2rml.rdb.model.R2RMLObjectMap;
import es.upm.fi.dia.oeg.obdi.wrapper.r2rml.rdb.model.R2RMLPredicateMap;
import es.upm.fi.dia.oeg.obdi.wrapper.r2rml.rdb.model.R2RMLPredicateObjectMap;
import es.upm.fi.dia.oeg.obdi.wrapper.r2rml.rdb.model.R2RMLRefObjectMap;
import es.upm.fi.dia.oeg.obdi.wrapper.r2rml.rdb.model.R2RMLSQLQuery;
import es.upm.fi.dia.oeg.obdi.wrapper.r2rml.rdb.model.R2RMLSubjectMap;
import es.upm.fi.dia.oeg.obdi.wrapper.r2rml.rdb.model.R2RMLTable;
import es.upm.fi.dia.oeg.obdi.wrapper.r2rml.rdb.model.R2RMLTermMap;
import es.upm.fi.dia.oeg.obdi.wrapper.r2rml.rdb.model.R2RMLTriplesMap;

public class R2RMLUnfolder extends AbstractUnfolder implements R2RMLElementVisitor {
	private Map<Object, Collection<String>> mapTermMapColumnsAliases = new HashMap<Object, Collection<String>>();
	private static Logger logger = Logger.getLogger(R2RMLUnfolder.class);
	private Map<R2RMLRefObjectMap, String> mapRefObjectMapAlias = new HashMap<R2RMLRefObjectMap, String>();
	//private ConfigurationProperties configurationProperties;
	
	public R2RMLUnfolder() {
		super();
	}
	
	public Collection<String> getAliases(Object termMapOrRefObjectMap) {
		return this.mapTermMapColumnsAliases.get(termMapOrRefObjectMap);
	}

	public Map<R2RMLRefObjectMap, String> getMapRefObjectMapAlias() {
		return mapRefObjectMapAlias;
	}

	public SQLLogicalTable unfold(R2RMLLogicalTable logicalTable) {
		SQLLogicalTable result;
		
		Enum<LogicalTableType> logicalTableType = logicalTable.getLogicalTableType();
		if(logicalTableType == LogicalTableType.TABLE_NAME) {
			result = new SQLFromItem(logicalTable.getValue(), LogicalTableType.TABLE_NAME, this.dbType);
		} else if(logicalTableType == LogicalTableType.QUERY_STRING) {
			String sqlString = logicalTable.getValue();
			try {
				String sqlString2 = sqlString;
				if(!sqlString2.endsWith(";")) {
					sqlString2 += ";";
				}
				result = R2RMLUnfolder.toSQLQuery(sqlString2);
			} catch(Exception e) {
				logger.warn("Not able to parse the query, string will be used.");
				result = new SQLFromItem(sqlString, LogicalTableType.QUERY_STRING, this.dbType);
			}
		} else {
			result = null;
			logger.warn("Invalid logical table type");
		}

		return result;
	}

	public SQLQuery unfold(R2RMLLogicalTable logicalTable, R2RMLSubjectMap subjectMap
			, Collection<R2RMLPredicateObjectMap> poms) throws Exception {
		R2RMLTriplesMap triplesMap = subjectMap.getOwner();
		logger.info("unfolding triplesMap : " + triplesMap);

		//SQLQuery result = new SQLQuery();
		
		//Collection<ZSelectItem> resultSelectItems = new HashSet<ZSelectItem>();

		//unfold subjectMap
		//R2RMLLogicalTable logicalTable = triplesMap.getLogicalTable();
		SQLQuery result = this.unfoldSubjectMap(subjectMap, logicalTable);
		String databaseType = triplesMap.getOwner().getConfigurationProperties().databaseType();
		result.setDatabaseType(databaseType);
		
		//logicalTableAlias = subjectMap.getAlias();
		//String logicalTableAlias = triplesMap.getLogicalTable().getAlias();
		String logicalTableAlias = logicalTable.getAlias();
		
		if(poms != null) {
			for(R2RMLPredicateObjectMap predicateObjectMap : poms) {
				//UNFOLD PREDICATEMAP
				Collection<R2RMLPredicateMap> predicateMaps = predicateObjectMap.getPredicateMaps();
				if(predicateMaps != null && !predicateMaps.isEmpty()) {
					R2RMLPredicateMap predicateMap = predicateObjectMap.getPredicateMap(0);
					Collection<String> predicateMapColumnsString = predicateMap.getDatabaseColumnsString();
					//if(predicateMapColumnsString != null && logicalTable instanceof R2RMLTable) {
					if(predicateMapColumnsString != null) {
						
						for(String predicateMapColumnString : predicateMapColumnsString) {
							ZSelectItem selectItem = MorphSQLSelectItem.apply(predicateMapColumnString
									, logicalTableAlias, dbType);
							if(selectItem != null) {
								if(selectItem.getAlias() == null) {
									String alias = selectItem.getTable() + "_" + selectItem.getColumn();
									selectItem.setAlias(alias);
									if(this.mapTermMapColumnsAliases.containsKey(predicateMap)) {
										this.mapTermMapColumnsAliases.get(predicateMap).add(alias);
									} else {
										Collection<String> aliases = new Vector<String>();
										aliases.add(alias);
										this.mapTermMapColumnsAliases.put(predicateMap, aliases);
									}								
								}
								//resultSelectItems.add(selectItem);
								result.addSelect(selectItem);
							}
						}
					}					
				}


				//UNFOLD OBJECTMAP
				Collection<R2RMLObjectMap> objectMaps = predicateObjectMap.getObjectMaps();
				if(objectMaps != null && !objectMaps.isEmpty()) {
					R2RMLObjectMap objectMap = predicateObjectMap.getObjectMap(0);
					if(objectMap != null) {
						//objectMap.setAlias(logicalTableAlias);
						Collection<String> objectMapColumnsString = objectMap.getDatabaseColumnsString();
						//if(objectMapColumnsString != null && logicalTable instanceof R2RMLTable) {
						if(objectMapColumnsString != null) {
							for(String objectMapColumnString : objectMapColumnsString) {
								ZSelectItem selectItem = MorphSQLSelectItem.apply(
										objectMapColumnString, logicalTableAlias, this.dbType);
								if(selectItem != null) {
									if(selectItem.getAlias() == null) {
										String alias = selectItem.getTable() + "_" + selectItem.getColumn();
										selectItem.setAlias(alias);
										if(this.mapTermMapColumnsAliases.containsKey(objectMap)) {
											this.mapTermMapColumnsAliases.get(objectMap).add(alias);
										} else {
											Collection<String> aliases = new Vector<String>();
											aliases.add(alias);
											this.mapTermMapColumnsAliases.put(objectMap, aliases);
										}
									}
									//resultSelectItems.add(selectItem);
									result.addSelect(selectItem);
								}
							}
						}
					}					
				}


				//UNFOLD REFOBJECTMAP
				Collection<R2RMLRefObjectMap> refObjectMaps = predicateObjectMap.getRefObjectMaps();
				if(refObjectMaps != null && !refObjectMaps.isEmpty()) {
					R2RMLRefObjectMap refObjectMap = predicateObjectMap.getRefObjectMap(0);
					if(refObjectMap != null) {
						R2RMLLogicalTable parentLogicalTable = refObjectMap.getParentLogicalTable();
						if(parentLogicalTable == null) {
							String errorMessage = "Parent logical table is not found for RefObjectMap : " 
						+ predicateObjectMap.getMappedPredicateName(0);
							throw new Exception(errorMessage);
						}
						SQLLogicalTable sqlParentLogicalTable = 
								(SQLLogicalTable) parentLogicalTable.accept(this);
						
						SQLJoinTable joinQuery = new SQLJoinTable(sqlParentLogicalTable);
						joinQuery.setJoinType("LEFT");
						String joinQueryAlias = sqlParentLogicalTable.generateAlias();
						sqlParentLogicalTable.setAlias(joinQueryAlias);
						//refObjectMap.setAlias(joinQueryAlias);
						this.mapRefObjectMapAlias.put(refObjectMap, joinQueryAlias);
						predicateObjectMap.setAlias(joinQueryAlias);
						
						Collection<String> refObjectMapColumnsString = 
								refObjectMap.getParentDatabaseColumnsString();
						if(refObjectMapColumnsString != null ) {
							for(String refObjectMapColumnString : refObjectMapColumnsString) {
								ZSelectItem selectItem = MorphSQLSelectItem.apply(
										refObjectMapColumnString, joinQueryAlias, dbType, null);
								if(selectItem.getAlias() == null) {
									String alias = selectItem.getTable() + "_" + selectItem.getColumn();
									selectItem.setAlias(alias);
									if(this.mapTermMapColumnsAliases.containsKey(refObjectMap)) {
										this.mapTermMapColumnsAliases.get(refObjectMap).add(alias);
									} else {
										Collection<String> aliases = new Vector<String>();
										aliases.add(alias);
										this.mapTermMapColumnsAliases.put(refObjectMap, aliases);
									}
								}							
								//resultSelectItems.add(selectItem);
								result.addSelect(selectItem);
							}
						}


						ZExpression onExpression;
						Collection<R2RMLJoinCondition> joinConditions = 
								refObjectMap.getJoinConditions();
						if(joinConditions != null && joinConditions.size() > 0) {
							onExpression = R2RMLJoinCondition.generateJoinCondition(joinConditions
									, logicalTableAlias, joinQueryAlias, dbType);
						} else {
							onExpression = Constants.SQL_EXPRESSION_TRUE();
						}
						joinQuery.setOnExpression(onExpression);
						//result.addJoinQuery(joinQuery);		
						result.addFromItem(joinQuery);
					}					
				}


			}
		}

//		if(resultSelectItems != null) {
//			for(ZSelectItem selectItem : resultSelectItems) {
//				result.addSelect(selectItem);
//			}
//		}
		//logger.info(triplesMap + " unfolded = \n" + result);

		return result;		
	}
	
	public SQLQuery unfold(R2RMLTriplesMap triplesMap, String subjectURI) throws Exception {
		R2RMLLogicalTable logicalTable = triplesMap.getLogicalTable();
		R2RMLSubjectMap subjectMap = triplesMap.getSubjectMap();
		Collection<R2RMLPredicateObjectMap> predicateObjectMaps = 
				triplesMap.getPredicateObjectMaps();
		
		SQLQuery result = this.unfold(logicalTable, subjectMap, predicateObjectMaps);
		if(subjectURI != null) {
			ZExp whereExpression = subjectMap.generateCondForWellDefinedURI(subjectURI, logicalTable.getAlias(), this.dbType);
			if(whereExpression != null) {
				result.addWhere(whereExpression);
			} else {
				result = null;
			}
		}
		return result;		
	}
	
	public SQLQuery unfold(R2RMLTriplesMap triplesMap) throws Exception {
		return this.unfold(triplesMap, null);
	}
	


	@Override
	public SQLQuery unfoldConceptMapping(AbstractConceptMapping cm)
			throws Exception {
		return this.unfold((R2RMLTriplesMap) cm);
	}
	
	@Override
	public SQLQuery unfoldConceptMapping(AbstractConceptMapping cm,
			String subjectURI) throws Exception {
		return this.unfold((R2RMLTriplesMap) cm, subjectURI);
	}

	@Override
	protected Collection<SQLQuery> unfoldMappingDocument(AbstractMappingDocument mappingDocument) {
		Collection<SQLQuery> result = new HashSet<SQLQuery>();

		Collection<AbstractConceptMapping> triplesMaps = mappingDocument.getConceptMappings();
		if(triplesMaps != null) {
			for(AbstractConceptMapping triplesMap : triplesMaps) {
				try {
					SQLQuery triplesMapUnfolded = this.unfoldConceptMapping(triplesMap);
					result.add(triplesMapUnfolded);
				} catch(Exception e) {
					logger.error("error while unfolding triplesMap : " + triplesMap);
					logger.error("error message = " + e.getMessage());
				}
			}
		}
		return result;

	}

	@Override
	public SQLQuery unfoldSubject(AbstractConceptMapping cm)
			throws Exception {
		R2RMLTriplesMap triplesMap = (R2RMLTriplesMap) cm;
		R2RMLLogicalTable logicalTable = triplesMap.getLogicalTable();
		R2RMLSubjectMap subjectMap = triplesMap.getSubjectMap();
		Collection<R2RMLPredicateObjectMap> predicateObjectMaps = 
				triplesMap.getPredicateObjectMaps();
		
		SQLQuery result = this.unfold(logicalTable, subjectMap, null);
		return result;
	}

	private SQLQuery unfoldSubjectMap(R2RMLSubjectMap subjectMap
			, R2RMLLogicalTable logicalTable) {
//		R2RMLLogicalTable logicalTable = triplesMap.getLogicalTable();
//		R2RMLSubjectMap subjectMap = triplesMap.getSubjectMap();
		
		SQLQuery result = new SQLQuery();
		Collection<ZSelectItem> resultSelectItems = new HashSet<ZSelectItem>();
		
		SQLFromItem logicalTableUnfolded = null;
		String logicalTableAlias = null;

		if(logicalTable instanceof R2RMLTable) {
			logicalTableUnfolded = (SQLFromItem) logicalTable.accept(this);
		} else if(logicalTable instanceof R2RMLSQLQuery) {
			Object logicalTableAux = logicalTable.accept(this);
			if(logicalTableAux instanceof SQLQuery) {
				ZQuery zQuery = (ZQuery) logicalTable.accept(this);
				logicalTableUnfolded = new SQLFromItem(zQuery.toString(), LogicalTableType.QUERY_STRING, this.dbType);
			} else if(logicalTableAux instanceof SQLFromItem) {
				logicalTableUnfolded = (SQLFromItem) logicalTableAux;
			}
		}
		logicalTableAlias = logicalTableUnfolded.generateAlias();
		logicalTable.setAlias(logicalTableAlias);
		//result.addFrom(logicalTableUnfolded);
		SQLJoinTable logicalTableUnfoldedJoinTable = new SQLJoinTable(logicalTableUnfolded, null, null); 
		result.addFromItem(logicalTableUnfoldedJoinTable);

		Collection<String> subjectMapColumnsString = subjectMap.getDatabaseColumnsString();
		if(subjectMapColumnsString != null) {
			
			for(String subjectMapColumnString : subjectMapColumnsString) {
				ZSelectItem selectItem = MorphSQLSelectItem.apply(subjectMapColumnString, logicalTableAlias, this.dbType);
				
				if(selectItem != null) {
					if(selectItem.getAlias() == null) {
						String alias = selectItem.getTable() + "_" + selectItem.getColumn();
						if(this.mapTermMapColumnsAliases.containsKey(subjectMap)) {
							this.mapTermMapColumnsAliases.get(subjectMap).add(alias);
						} else {
							Collection<String> aliases = new Vector<String>();
							aliases.add(alias);
							this.mapTermMapColumnsAliases.put(subjectMap, aliases);
						}
						
						selectItem.setAlias(alias);						
					}
					resultSelectItems.add(selectItem);
				}
			}
		}
		
		result.setSelectItems(resultSelectItems);
		return result;
	}

	public SQLLogicalTable visit(R2RMLLogicalTable logicalTable) {
		SQLLogicalTable result = this.unfold(logicalTable);
		return result;
	}

	public Collection<SQLQuery> visit(R2RMLMappingDocument mappingDocument) {
		Collection<SQLQuery> result = this.unfoldMappingDocument(mappingDocument);
		return result;
	}

	public Object visit(R2RMLObjectMap objectMap) {
		// TODO Auto-generated method stub
		return null;
	}

	public Object visit(R2RMLRefObjectMap refObjectMap) {
		// TODO Auto-generated method stub
		return null;
	}

	public Object visit(R2RMLTermMap r2rmlTermMap) {
		// TODO Auto-generated method stub
		return null;
	}

	public SQLQuery visit(R2RMLTriplesMap triplesMap) throws Exception {
		SQLQuery result = this.unfold(triplesMap);
		return result;
	}


	
	public static SQLQuery toSQLQuery(String sqlString) throws Exception {
		ZQuery zQuery = R2RMLUnfolder.toZQuery(sqlString);
		SQLQuery sqlQuery = new SQLQuery(zQuery);
		return sqlQuery;
	}
	
	
	public static ZQuery toZQuery(String sqlString) throws Exception {
		try {
			//sqlString = sqlString.replaceAll(".date ", ".date2");
			ByteArrayInputStream bs = new ByteArrayInputStream(sqlString.getBytes());
			ZqlParser parser = new ZqlParser(bs);
			ZStatement statement = parser.readStatement();
			ZQuery zQuery = (ZQuery) statement;
			
			return zQuery;
		} catch(Exception e) {
			String errorMessage = "error parsing query string : \n" + sqlString; 
			//e.printStackTrace();
			logger.error(errorMessage);
			logger.error("error message = " + e.getMessage());
			throw e;
		} catch(Error e) {
			String errorMessage = "error parsing query string : \n" + sqlString;
			logger.error(errorMessage);
			throw new Exception(errorMessage);
		}
	}	
}
