package j2a.core;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;

/** Case-insensitive index of the JAR resources (assets/j2a/resources.txt). */
final class CaseIndex {
    private static HashMap<String, String> index;

    private CaseIndex() {}

    static synchronized String find(String path) {
        if (index == null) {
            index = new HashMap<String, String>();
            if (Host.activity != null) {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(
                            Host.activity.getAssets().open("j2a/resources.txt"), "UTF-8"));
                    String line;
                    while ((line = r.readLine()) != null) {
                        line = line.trim();
                        if (line.length() > 0) index.put(line.toLowerCase(), line);
                    }
                    r.close();
                } catch (Exception ignored) {}
            }
        }
        return index.get(path.toLowerCase());
    }
}
