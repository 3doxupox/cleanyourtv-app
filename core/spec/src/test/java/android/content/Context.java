package android.content;

import android.content.res.Resources;
import java.io.File;

// Test stub for upstream Config.java: the two Context calls it makes.
public class Context {
    private final File externalFilesDir;
    private final Resources resources;

    public Context(File externalFilesDir, Resources resources) {
        this.externalFilesDir = externalFilesDir;
        this.resources = resources;
    }

    public File getExternalFilesDir(String type) { return externalFilesDir; }

    public Resources getResources() { return resources; }
}
