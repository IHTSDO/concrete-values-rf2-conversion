
# Archive Status Notice
**This repository is no longer maintained or used and has therefore been archived and made read-only. SNOMED International does not recommend using the code in this repository.**


# concrete-values-rf2-conversion

This conversion tool will take a snapshot with an optional extension and/or an optional delta archive and change existing stated concepts-as-number 
relationships (in the stated OWL file) into concrete relationships.  The output is an unzipped delta archive directory structure in a local output folder..

The attributes to be replaced are specified in the config.txt file which can be modified as required.  The default file supplied contains the mapping for the International Edition attributes.

So, for example, in the OWL file ObjectSomeValuesFrom(:732946004 :38112003) will be converted to DataHasValue(:3264476008 \"1\"^^xsd:decimal)

The inferred Relationship file is not modified, neither is a new inferred RelationshipConcreteValues file created. It is expected that the output of this process would be
fed in a concrete-domain capable classifier so that these files would be modified and/or created as required.

## Usage
`java -jar CdConversion -s <snapshot dependency archive> [-e <snapshot extension archive>] [-d <delta archive>] [-c <config mapping file> or config.txt is used]`

## Examples
1.  Convert a published release

```
java -jar target/CdConversion.jar -s ~/code/reporting-engine/script-engine/releases/SnomedCT_InternationalRF2_PRODUCTION_20200731T120000Z.zip
```
  
2.  Convert a published release with a current 'in flight editing cycle' delta

```  
java -jar target/CdConversion.jar -s ~/code/reporting-engine/script-engine/releases/SnomedCT_InternationalRF2_PRODUCTION_20200731T120000Z.zip -d ~/tmp/delta_MAIN_export_20200930.zip
```
Note that a -o flag can be optionally specified to make the process only output modified axioms.  Otherwise the entire delta (including additional changes for concrete values) will be output.

3.  Convert an extension release, based on a previous international release

```
java -jar target/CdConversion.jar -s ~/code/reporting-engine/script-engine/releases/SnomedCT_InternationalRF2_PRODUCTION_20200731T120000Z.zip  
-e ~/code/reporting-engine/script-engine/releases/SnomedCT_ManagedServiceDK_PRODUCTION_DK1000005_20200930T120000Z.zip
```
4.   Convert an 'in flight' extension

```
java -jar target/CdConversion.jar -s ~/code/reporting-engine/script-engine/releases/SnomedCT_InternationalRF2_PRODUCTION_20200731T120000Z.zip  
-e ~/code/reporting-engine/script-engine/releases/SnomedCT_ManagedServiceDK_PRODUCTION_DK1000005_20200930T120000Z.zip  
-d ~/tmp/delta_DK_export_20201002.zip
```
## Sample Output (for example 2 above)

    SNOMED International RF2 Concrete Values Conversion Tool  
    =========================================================  
    First pass through the archives to find all number concepts  
    Processing SnomedCT_InternationalRF2_PRODUCTION_20200731T120000Z.zip  
    Processing delta_MAIN_export_20200930.zip  
    789 <! 260299005 |Number (qualifier value)| detected  
    Second pass through descriptions to determine actual numeric values  
    Processing SnomedCT_InternationalRF2_PRODUCTION_20200731T120000Z.zip  
    Processing delta_MAIN_export_20200930.zip  
    784 numeric values determined  
    Third pass to change concept-as-number attributes to concrete values  
    Processing SnomedCT_InternationalRF2_PRODUCTION_20200731T120000Z.zip  
    Processing delta_MAIN_export_20200930.zip  
    Appending non-superseeded snapshot conversion remainder  
    Processing Complete. Concepts remodelled: 27281  `
