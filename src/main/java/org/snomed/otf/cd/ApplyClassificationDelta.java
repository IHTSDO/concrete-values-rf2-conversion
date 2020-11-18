package org.snomed.otf.cd;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Scanner;
import java.util.Set;

public class ApplyClassificationDelta {
	
	File classificationOutput;
	File relationshipDelta;
	File relationshipDeltaTmp;
	Set<String> existingIds;

	public static void main(String[] args) throws IOException {
		if (args.length != 2) {
			exit ("Usage: ApplyClassificationDelta <classification output file> <existing relationship delta file>");
		}
		new ApplyClassificationDelta().process(args[0], args[1]);
	}

	private void process(String file1Str, String file2Str) throws IOException {
		classificationOutput = checkFile(file1Str);
		relationshipDelta = checkFile(file2Str);
		
		//Read through the classification results to find existing ids
		recoverExistingIds();
		
		//Move the relationship delta to a temp location so we can recreate it
		String origPath = relationshipDelta.getPath();
		relationshipDeltaTmp = new File ( origPath + ".tmp");
		relationshipDelta.renameTo(relationshipDeltaTmp);
		relationshipDelta = new File (origPath);
		
		//Now read through the existing delta and suppress any with ids returned from the classifier
		recreateDeltaWithIdsSuppressed();
		
		//And finally add the entire contents of the classifications
		appendAllClassificationResults();
		relationshipDeltaTmp.delete();
	}

	private void recoverExistingIds() throws IOException {
		existingIds = new HashSet<>();
		FileInputStream is = null;
		Scanner sc = null;
		try {
			is = new FileInputStream(classificationOutput);
			sc = new Scanner(is, "UTF-8");
			while (sc.hasNextLine()) {
				String[] items = sc.nextLine().split("\t");
				if (items.length > 1 && items[0].length() > 5) {
					existingIds.add(items[0]);
				}
			}
			// note that Scanner suppresses exceptions
			if (sc.ioException() != null) {
				throw sc.ioException();
			}
		} finally {
			if (is != null) {
				is.close();
			}
			if (sc != null) {
				sc.close();
			}
		}
		out("Read " + existingIds.size() + " exisiting ids from " + classificationOutput);
	}
	
	private void recreateDeltaWithIdsSuppressed() throws FileNotFoundException {
		Scanner sc = new Scanner(relationshipDeltaTmp);
		PrintWriter pw = new PrintWriter(relationshipDelta);
		int suppressedCount = 0;
		try {
			while (sc.hasNextLine()) {
				String s = sc.nextLine();
				String[] items = s.split("\t");
				//If we DON'T have this line coming back from the classifier,
				//write it to the file
				if (!existingIds.contains(items[0])) {
					pw.write(s + "\r\n");
				} else {
					suppressedCount++;
				}
			}
			pw.flush();
		} finally {
			if (pw != null) {
				pw.close();
			}
			if (sc != null) {
				sc.close();
			}
		}
		out("Suppressed " + suppressedCount + " rows from " + relationshipDelta);
	}
	
	private void appendAllClassificationResults() throws FileNotFoundException {
		Scanner sc = new Scanner(classificationOutput);
		PrintWriter pw = new PrintWriter(new FileOutputStream(relationshipDelta, true)); //Append
		int rowsAppended = 0;
		try {
			boolean isFirstLine = true;
			while (sc.hasNextLine()) {
				String s = sc.nextLine();
				if (isFirstLine) {
					isFirstLine = false;
				} else {
					pw.write(s + "\r\n");
					rowsAppended++;
				}
			}
			pw.flush();
		} finally {
			if (pw != null) {
				pw.close();
			}
			if (sc != null) {
				sc.close();
			}
		}
		out("Appended " + rowsAppended + " rows ");

	}

	private File checkFile(String fileStr) {
		File file = new File(fileStr);
		if (!file.isFile() || !file.canRead()) {
			exit ("Cannot read from " + fileStr);
		}
		return file;
	}

	public static void exit(String msg) {
		out(msg);
		System.exit(-1);
	}
	
	public static void out(String msg) {
		System.out.println(msg);
	}

}
