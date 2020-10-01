package org.snomed.otf.cd;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/**
 * Supporting class to turn an RF2 File into a collection that can be iterated over, with
 * each line split into an array of strings for further processing.
 */
class ArchiveLines implements Iterable<String[]> {
	
	public static String FIELD_DELIMITER = "\t";
	
	InputStream is;
	BufferedReader br;
	String nextLine;
	
	ArchiveLines (InputStream is) {
		this.is = is;
	}
	@Override
	public Iterator<String[]> iterator() {
		//Not putting this in a try resource block otherwise it will close the stream on completion and we've got more to read!
		br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
		try {
			nextLine = br.readLine();
		} catch (Exception e) {}
		
		return new Iterator<String[]>() {
			@Override
			public boolean hasNext() {
				return nextLine != null;
			}

			@Override
			public String[] next() {
				if (nextLine != null) {
					String thisLine = nextLine;
					try {
						nextLine = br.readLine();
					} catch (IOException e) {
						e.printStackTrace();
						nextLine = null;
						return null;
					}
					return thisLine.split(FIELD_DELIMITER);
				} else {
					return null;
				}
			}
		};
	}
}
