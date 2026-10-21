package net.drave.atrdiagnostics;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;

/**
 * Restarts Daanvia after the move from the old app. It runs in its own small process (":restart" in the manifest):
 * it ends the app's main process, which had the old, empty settings loaded, and opens the app again, which then loads
 * the moved maps and settings from disk.
 */
public final class RestartActivity extends Activity {
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        int pid = getIntent().getIntExtra("pid", -1);
        if (pid > 0 && pid != Process.myPid()) Process.killProcess(pid);
        startActivity(new Intent(this, HomeActivity.class).putExtra("moved", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
        Runtime.getRuntime().exit(0);
    }
}
