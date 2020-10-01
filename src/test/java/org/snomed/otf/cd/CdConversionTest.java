package org.snomed.otf.cd;

import java.util.*;

import junit.framework.*;

public class CdConversionTest extends TestCase
{
	static final String testConcept = "322236009";  //|Product containing precisely paracetamol 500 milligram/1 each conventional release oral tablet (clinical drug)|
	static final String testInput = "EquivalentClasses(:322236009 ObjectIntersectionOf(:763158003 ObjectSomeValuesFrom(:411116001 :421026006) ObjectSomeValuesFrom(:609096000 ObjectIntersectionOf(ObjectSomeValuesFrom(:732943007 :387517004) ObjectSomeValuesFrom(:732944001 :732775002) ObjectSomeValuesFrom(:732945000 :258684004) ObjectSomeValuesFrom(:732946004 :38112003) ObjectSomeValuesFrom(:732947008 :732936001) ObjectSomeValuesFrom(:762949000 :387517004))) ObjectSomeValuesFrom(:763032000 :732936001) ObjectSomeValuesFrom(:766952006 :38112003)))";
	static final String expectedOuput = "EquivalentClasses(:322236009 ObjectIntersectionOf(:763158003 ObjectSomeValuesFrom(:411116001 :421026006) ObjectSomeValuesFrom(:609096000 ObjectIntersectionOf(ObjectSomeValuesFrom(:732943007 :387517004) DataHasValue(:3264475007 \"500\"^^xsd:decimal) ObjectSomeValuesFrom(:732945000 :258684004) DataHasValue(:3264476008 \"1\"^^xsd:decimal) ObjectSomeValuesFrom(:732947008 :732936001) ObjectSomeValuesFrom(:762949000 :387517004))) ObjectSomeValuesFrom(:763032000 :732936001) DataHasValue(:3264479001 \"1\"^^xsd:integer)))";
	/**
	 * Create the test case
	 *
	 * @param testName name of the test case
	 */
	public CdConversionTest( String testName )
	{
		super( testName );
	}

	/**
	 * @return the suite of tests being tested
	 */
	public static Test suite()
	{
		return new TestSuite( CdConversionTest.class );
	}

	/**
	 * Rigourous Test :-)
	 */
	public void testOWLConversion()
	{
		Map<String, String> attributeTypeMap = new HashMap<>();
		attributeTypeMap.put("766952006", "3264479001");  //Ingred Count
		attributeTypeMap.put("732944001", "3264475007");  //Pres Num Val
		attributeTypeMap.put("732946004", "3264476008");  //Pres Demom Val
		
		Map<String, String> concreteTypeMap = new HashMap<>();
		concreteTypeMap.put("3264479001", "integer");
		concreteTypeMap.put("3264475007", "decimal");
		concreteTypeMap.put("3264476008", "decimal");
		
		Map<String, String> conceptNumberMap = new HashMap<>();
		conceptNumberMap.put("732775002", "500");  
		conceptNumberMap.put("3445001", "10");
		conceptNumberMap.put("38112003", "1");
		
		CdConversion conversion = new CdConversion();
		conversion.setConfig(attributeTypeMap, concreteTypeMap, conceptNumberMap);
		String convertedOwl = conversion.modifyOWLIfRequired(testConcept, testInput);
		assertEquals(expectedOuput, convertedOwl);
	}
}
