package codingagent.utils;

import java.util.Scanner;

import codingagent.models.Cursor;

public class SourceUtils {
	public static String inject(String source, Cursor cursor, String mark) {
		try (Scanner in = new Scanner(source)) {
			StringBuilder buffer = new StringBuilder();
			int offset = 0;
			int currentIndex = 0;
			while (in.hasNext()) {
				String row = in.nextLine();
				if (currentIndex == cursor.row()) {
					int currentRowOffset =  cursor.rowOffset() - offset;
					currentRowOffset = Math.min(row.length()-1, currentRowOffset);
					String left = row.substring(0, currentRowOffset);
					String right =row.substring(currentRowOffset);
										
					
					String currentRow = left + mark + right;
					buffer.append(currentRow).append("\n");
				} else {
					buffer.append(row).append("\n");
				}
				currentIndex++;
				offset += row.length() + "\n".length();
			}
			return buffer.toString();
		}
	}

}
