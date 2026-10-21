package javax.microedition.util;

import android.content.Intent;

/** Result of startActivityForResult, forwarded by the port activity (compat with J2ME Loader code). */
public interface ActivityResultListener {
	void onActivityResult(int requestCode, int resultCode, Intent data);
}
