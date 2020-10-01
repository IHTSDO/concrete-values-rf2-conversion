package org.snomed.otf.cd;

import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.*;

/**
 * RF2 Conversion Script to replace existing concepts-as-numbers with concreate values
 */
public class CdConversion
{
	public static String FIELD_DELIMITER = "\t";
	public static String LINE_DELIMITER = "\r\n";
	public static int IDX_ID=0, IDX_EFFECTIVE_TIME=1, IDX_ACTIVE=2, IDX_SOURCE=4, 
			IDX_CONCEPT=4, IDX_TARGET=5, IDX_REFCOMPID=5, 
			IDX_OWL_EXPRESSION=6, IDX_TYPE=7, IDX_TERM=7;
	public static String SCTID_NUMBER = "260299005";  // |Number (qualifier value)|
	public static String SCTID_IS_A = "116680003"; // |Is a (attribute)|
	public static String SNAPSHOT = "Snapshot";
	public static String DELTA = "Delta";
	//Nested regex to pick out the clause, and separate out the two SCTIDS involved
	public static String REGEX = "(ObjectSomeValuesFrom\\(:(\\d{6,18}) :(\\d{6,18})\\))";
	private File dependency;
	private File extension;
	private File delta;
	private File attributeMapConfig;
	private Map<String, String> conceptNumberMap = new HashMap<>();
	private Map<String, String> attributeTypeMap = new HashMap<>();
	private Map<String, String> concreteTypeMap = new HashMap<>();
	private Map<String, String[]> outputOWLMap = new HashMap<>();
	private Map<Path, PrintWriter> printWriterMap = new HashMap<>();
	private Pattern pattern = Pattern.compile(REGEX);
	private int conceptsRemodelled = 0;
	
	private DateTimeFormatter dtFormatter = DateTimeFormatter.ofPattern("yyyyMMdd");
	private String today;
	private Path owlPath;
	
	enum ArchiveType{ SNAPSHOT, EXTENSION, DELTA };
	ArchiveType latestArchiveType;  //What's the most specific archive we've been passed#'
	
	public static void main(String[] args) throws IOException {
	
		info("SNOMED International RF2 Concrete Values Conversion Tool");
		info("=========================================================");
	
		if (args.length < 1) {
			exit("Usage: java -jar CdConversion -s <snapshot dependency archive> [-e <snapshot extension archive>] [-d <delta archive>] [-c <config mapping file> or config.txt is used]");
		}
		
		CdConversion app = new CdConversion();
		for (int x=0; x< args.length; x++) {
			String thisArg = args[x];
			if (thisArg.equals("-s")) {
				app.dependency = validateFile(args[x+1]);
			} else if (thisArg.equals("-e")) {
				app.extension = validateFile(args[x+1]);
			} else if (thisArg.equals("-d")) {
				app.delta = validateFile(args[x+1]);
			} else if (thisArg.equals("-c")) {
				app.attributeMapConfig = validateFile(args[x+1]);
			}
		}
		
		if (app.dependency == null) {
			exit ("A dependency archive must at least be specified using the -s command line parameter");
		} else {
			app.latestArchiveType = ArchiveType.SNAPSHOT;
		}
		
		if (app.extension != null) {
			app.latestArchiveType = ArchiveType.EXTENSION;
		}
		
		if (app.delta != null) {
			app.latestArchiveType = ArchiveType.DELTA;
		}
		
		if (app.attributeMapConfig == null) {
			File defaultConfig = new File("config.txt");
			if (defaultConfig.canRead()) {
				app.attributeMapConfig = defaultConfig;
			} else {
				exit ("An attribute map config file must at least be specified using the -c command line parameter");
			}
		}
		
		app.init();
		app.runConversion();
	}
	
	private void init(){
		today = LocalDate.now().format(dtFormatter);
		//Load in the config map to see what each attribute type is changing to
		try (BufferedReader br = new BufferedReader(new FileReader(attributeMapConfig))) {
			String line;
			while ((line = br.readLine()) != null) {
				String[] types = line.split(FIELD_DELIMITER);
				//Check we have an integer here
				new BigInteger(types[0]);
				new BigInteger(types[1]);
				attributeTypeMap.put(types[0], types[1]);
				concreteTypeMap.put(types[1], types[2]);
			}
		} catch (Exception e) {
			throw new IllegalStateException ("Unable to read three column (old new type), tab delimited attribute config: " + attributeMapConfig, e);
		}
	}
	
	/**
	 * Used only to set values for testing
	 */
	protected void setConfig (Map<String, String> attributeTypeMap, Map<String, String> concreteTypeMap, Map<String, String> conceptNumberMap) {
		this.attributeTypeMap = attributeTypeMap;
		this.concreteTypeMap = concreteTypeMap;
		this.conceptNumberMap = conceptNumberMap;
	}

	private void runConversion() throws IOException {
		info("First pass through the archives to find all number concepts");
		processAllArchiveFiles ( new FileProcessor() {
			public void processFile (ArchiveType archiveType, Path p, InputStream is) {
				if (p.getFileName().toString().startsWith("sct2_Relationship_")) {
					for (String[] fields :  new ArchiveLines(is)) {
						//Is this a type of number?  Remember the SCTID if so
						if (fields[IDX_ACTIVE].contentEquals("1") &&
								fields[IDX_TYPE].equals(SCTID_IS_A) &&
								fields[IDX_TARGET].equals(SCTID_NUMBER)) {
							conceptNumberMap.put(fields[IDX_SOURCE], null);
						}
					}
				}
			}
		});
		info (conceptNumberMap.keySet().size() + " <! 260299005 |Number (qualifier value)| detected");
		
		info("Second pass through descriptions to determine actual numeric values ");
		final Set<String> numberConcepts = conceptNumberMap.keySet();
		processAllArchiveFiles ( new FileProcessor() {
			public void processFile (ArchiveType archiveType, Path p, InputStream is) {
				if (p.getFileName().toString().startsWith("sct2_Description_")) {
					for (String[] fields :  new ArchiveLines(is)) {
						//Is this a type of number?  Remember the SCTID if so
						if (fields[IDX_ACTIVE].contentEquals("1") &&
								numberConcepts.contains(fields[IDX_CONCEPT])) {
							determineNumericValue(fields[IDX_CONCEPT], fields[IDX_TERM]);
						}
					}
				}
			}
		});
		info (new HashSet<>(conceptNumberMap.values()).size() + " numeric values determined");
		
		//Someone is going to ask me why this number doesn't match the number of concepts as numbers!
		//reportFailedNumberLookups(); eg 272065005 |Cardinal number (qualifier value)|
		
		info("Third pass to change concept-as-number attributes to concrete values");
		processAllArchiveFiles ( new FileProcessor() {
			public void processFile (ArchiveType archiveType, Path p, InputStream is) throws IOException {
				String fileName = p.getFileName().toString();
				boolean isDelta = fileName.contains(DELTA);
				boolean isOWL = fileName.startsWith("sct2_sRefset_OWLExpression");
				for (String[] fields :  new ArchiveLines(is)) {
					modifyIfRequired(archiveType, p, fields, isOWL, isDelta);
				}
			}
		});
		
		info("Appending non-superseeded snapshot conversion remainder");
		for (String[] fields : outputOWLMap.values()) {
			conceptsRemodelled++;
			writeRF2(owlPath, fields);
		}
		
		//Now we can close all our open file handles
		finish();
		info("Processing Complete. Concepts remodelled: " + conceptsRemodelled);
	}

	protected void modifyIfRequired(ArchiveType archiveType, Path p, String[] fields, boolean isOWL, boolean isDelta) throws IOException {
		//Grab any header rows - delta or snapshot - if we've not seen them before
		//But only for the latest archive passed, otherwise we'll end up with 3 output structures!
		if (archiveType == latestArchiveType && fields[IDX_ID].contentEquals("id")) {
			if (!fileInitialised(p)) {
				writeRF2(p, fields);
			}
			return;
		}
		
		if (isOWL) {
			//We need to keep a track of the OWL file path to target the data in the snapshot 
			//that's converted and will be added at the end.
			if (owlPath == null && archiveType == latestArchiveType) {
				owlPath = p;
			}
			modifyOWLIfRequired(p, fields, isDelta);
		} else if (isDelta) {
			//We will pass through all non-owl delta files intact
			writeRF2(p, fields);
		}
	}

	/**
	 * Look for OWL with items ObjectSomeValuesFrom(:A :B) where A is one of the
	 * types that's we're expecting to map and B is a concept as number.
	 * 
	 * If this is a delta, output immediately. Otherwise we'll store the modified
	 * row just in case a subsequent snapshot or delta modifies it further.  If not,
	 * any rows remaining will be appended at the end.
	 * @throws IOException 
	 */
	private void modifyOWLIfRequired(Path p, String[] fields, boolean isDelta) throws IOException {
		String owl = fields[IDX_OWL_EXPRESSION];
		
		//If the row is not active, no need to update.  Also if we previously knew about it,
		//it's no longer applicable.
		if (fields[IDX_ACTIVE].contentEquals("0")) {
			outputOWLMap.remove(fields[IDX_ID]);
		} else {
			String modifiedOwl = modifyOWLIfRequired(fields[IDX_REFCOMPID], owl);
			//No need to change the effective time if the OWL is unchanged
			if (!owl.contentEquals(modifiedOwl)) {
				validateBeforeAndAfter(fields[IDX_REFCOMPID], owl, modifiedOwl);
				conceptsRemodelled++;
				fields[IDX_EFFECTIVE_TIME] = "";
				fields[IDX_OWL_EXPRESSION] = modifiedOwl;
				//Are we storing this value to output later if no further updates are received?
				if (!isDelta) {
					outputOWLMap.put(fields[IDX_ID], fields);
				}
			}
			
			//If we're processing a delta, we can output this directly
			if (isDelta) {
				writeRF2(p, fields);
				//If we'd stored this OWL entry previously, we don't need that anymore
				outputOWLMap.remove(fields[IDX_ID]);
			}
		}
	}

	/**
	 * Valiate some invariants
	 * @param string
	 * @param owl
	 * @param modifiedOwl
	 */
	private void validateBeforeAndAfter(String concept, String before, String after) {
		//The number of open brackets should not change
		int beforeOpenB = countChars('(', before);
		int afterOpenB = countChars('(', after);
		
		if (beforeOpenB != afterOpenB) {
			exit("OWL conversion failure, bracket count mismatch at " + concept + "\nBefore: " + before + "\nAfter: " + after);
		}
		//And it should match the number of closing brackets
		int afterCloseB = countChars(')', after);
		if (afterOpenB != afterCloseB) {
			exit("OWL conversion failure, bracket pair mismatch at " + concept + "\nBefore: " + before + "\nAfter: " + after);
		}
		
		//And the number of colons should not changed, because the ones that previously
		//preceeded an SCTID will now appear in ^^xsd:integer
		int beforeColon = countChars(':', before);
		int afterColon = countChars(':', after);
		if (beforeColon != afterColon) {
			exit("OWL conversion failure, argument count mismatch at " + concept + "\nBefore: " + before + "\nAfter: " + after);
		}
	}

	private static void exit(String msg) {
		warn(msg);
		System.exit(-1);
	}

	private int countChars(char c1, String str) {
		int count = 0;
		for (char c2 : str.toCharArray()) {
			if (c1 == c2) {
				count++;
			}
		}
		return count;
	}

	protected String modifyOWLIfRequired(String concept, String owl) {
		Matcher m = pattern.matcher(owl); // Matching groups: (1(:2) (:3))
		//replacing with something like DataHasValue(:3264479001 "1"^^xsd:integer)
		StringBuffer sb = new StringBuffer();
		while (m.find()) {
			String source = m.group(2);
			//Is this one of the attribute types we're going to replace?
			if (attributeTypeMap.containsKey(source)) {
				//Do we have a number value for it?
				String attributeType = attributeTypeMap.get(source);
				String concreteValue = conceptNumberMap.get(m.group(3));
				String concreteType = concreteTypeMap.get(attributeType);
				if (concreteValue == null) {
					warn("Failed to find a numeric for " + m.group(3) + " in " + concept);
					return owl;
				}
				
				//Now we can build up our string for a replacment
				String replacement = "DataHasValue(:" + attributeTypeMap.get(source) + " \"" +
						concreteValue + "\"^^xsd:" + concreteType + ")";
				m.appendReplacement(sb, replacement);
			} else {
				//Otherwise just append what we matched 'as is'
				m.appendReplacement(sb, m.group(0));
			}
		}
		m.appendTail(sb);
		return sb.toString();
	}

	private static File validateFile(String filePath) throws IOException {
		File f = new File(filePath);
		if (!f.canRead() || f.isDirectory() || !filePath.endsWith(".zip")) {
			throw new IOException (filePath + " could not be read an archive file.");
		}
		return f;
	}

	private void processAllArchiveFiles(FileProcessor processor) throws IOException {
		loadArchiveZip(ArchiveType.SNAPSHOT, dependency, processor, SNAPSHOT);
		loadArchiveZip(ArchiveType.EXTENSION, extension, processor, SNAPSHOT);
		loadArchiveZip(ArchiveType.DELTA, delta, processor, DELTA);
	}
	
	private void loadArchiveZip(ArchiveType archiveType, File archive, FileProcessor processor, String filter) throws IOException {
		if (archive == null) {
			return;
		}
		
		info ("Processing " + archive.getName());
		ZipInputStream zis = new ZipInputStream(new FileInputStream(archive));
		ZipEntry ze = zis.getNextEntry();
		try {
			while (ze != null) {
				if (!ze.isDirectory()) {
					Path path = Paths.get(ze.getName());
					if (path.getFileName().toString().contains(filter)) {
						processor.processFile(archiveType, path, zis);
					}
				}
				ze = zis.getNextEntry();
			}
		}  finally {
			try{
				zis.closeEntry();
				zis.close();
			} catch (Exception e){} //Well, we tried.
		}
	}
	

	protected void determineNumericValue(String numberConcept, String term) {
		try {
			Double.parseDouble(term);
			//If we get here, we've purely a number to store
			conceptNumberMap.put(numberConcept, term);
		} catch (Exception e) {}
		
	}
	
	private void writeRF2(Path p, String[] fields) throws IOException {
		PrintWriter pw = getPrintWriter(p);
		StringBuffer line = new StringBuffer();
		for (int x=0; x<fields.length; x++) {
			if (x > 0) {
				line.append(FIELD_DELIMITER);
			}
			line.append(fields[x]==null?"":fields[x]);
		}
		pw.print(line.toString() + LINE_DELIMITER);
	}
	
	/**
	 * @return A printer writer appropriate for the given path, in the output directory 
	 * and with the original effectiveTime replaced with today's date.
	 */
	PrintWriter getPrintWriter(Path p) throws IOException {
		try {
			PrintWriter pw = printWriterMap.get(p);
			if (pw == null) {
				File file = ensureFileExists("output/" + modifyEffectiveDate(p));
				OutputStreamWriter osw = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8);
				BufferedWriter bw = new BufferedWriter(osw);
				pw = new PrintWriter(bw);
				printWriterMap.put(p, pw);
			}
			return pw;
		} catch (Exception e) {
			throw new IOException("Unable to initialise output/" + p.toString() + " due to " + e.getMessage(), e);
		}
	}
	
	private void finish() {
		for (PrintWriter pw : printWriterMap.values()) {
			try {
				pw.flush();
				pw.close();
			} catch (Exception e) {}
		}
	}
	
	private boolean fileInitialised(Path p) {
		return printWriterMap.containsKey(p);
	}
	
	private String modifyEffectiveDate(Path p) {
		//I'm concerned this might replace the module id in the root folder
		String replace = "_" + today + ".txt";
		return p.toString().replaceAll("_\\d{8}[.]txt", replace).replaceAll(SNAPSHOT, DELTA);
	}

	public static File ensureFileExists(String fileName) throws IOException {
		File file = new File(fileName);
		try {
			if (!file.exists()) {
				if (file.getParentFile() != null) {
					file.getParentFile().mkdirs();
				}
				file.createNewFile();
			}
		} catch (IOException e) {
			throw new IOException ("Failed to create file " + fileName, e);
		}
		return file;
	}
	

	/*
	 * Number concepts that did not feature a pure numeric description:
		272065005
		272070003
		278492000
		118586006
		272072006
		272071004
		Total: 6
	 private void reportFailedNumberLookups() {
		info ("Number concepts that did not feature a pure numeric description:");
		int count = 0;
		for (Map.Entry<String, String> entry : conceptNumberMap.entrySet()) {
			if (entry.getValue() == null) {
				info(entry.getKey());
				count++;
			}
		}
		info ("Total: " + count);
		
		info ("Duplicate number concepts: ");
		count = 0;
		Set<String> alreadySeen = new HashSet<>();
		for (Map.Entry<String, String> entry : conceptNumberMap.entrySet()) {
			if (entry.getValue() != null) {
				if (alreadySeen.contains(entry.getValue())) {
					info ("'" + entry.getValue() + "'");
					count++;
				} else {
					alreadySeen.add(entry.getValue());
				}
			}
		}
		info("Total: " + count);
	}*/

	public static void info(String msg) {
		System.out.println(msg);
	}

	public static void warn(String msg) {
		System.err.println(msg);
	}
	
	interface FileProcessor {
		void processFile (ArchiveType archiveType, Path path, InputStream is) throws IOException;
	}
	
}
